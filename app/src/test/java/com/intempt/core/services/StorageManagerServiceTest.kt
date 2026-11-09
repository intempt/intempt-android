package com.intempt.core.services

import android.content.Context
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StorageManagerServiceTest {
    private lateinit var context: Context
    private lateinit var utils: UtilsService
    private lateinit var storage: StorageManagerService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        utils = mock(UtilsService::class.java)
        var counter = 0
        `when`(utils.generateId(anyString())).thenAnswer { "prof_fresh_${counter++}" }
        // Unconfined so the persist-to-prefs coroutine runs inline: these tests assert
        // read-back guarantees, not scheduler behaviour.
        storage = StorageManagerService(context, utils, Dispatchers.Unconfined)
    }

    /**
     * reset()/logOut() promise that the very NEXT getProfileId() sees the rotated
     * identity. The pre-3.0.3 implementation ran the wipe-and-remint on a
     * fire-and-forget coroutine, so a caller reading immediately after reset()
     * raced it and still saw the old profile id — observed end-to-end from the
     * React Native bridge, where reset() resolved and getProfileId() returned
     * the previous user's id. No scheduler-advancing here, on purpose: the
     * rotation must be visible without yielding.
     */
    @Test
    fun `clearAllStorage rotates the profileId synchronously`() {
        storage.setLocalProp(StorageKeys.ProfileId.key, "prof_old")
        assertEquals("prof_old", storage.getProfileId())

        storage.clearAllStorage()

        val rotated = storage.getProfileId()
        assertNotEquals("the old identity must not survive a reset", "prof_old", rotated)
        assertTrue("a fresh id must already be minted, not left blank", rotated.startsWith("prof_fresh_"))
    }

    /**
     * INT-5166's exact assertion: three reads across two rotations must be three DIFFERENT ids.
     *
     * The reported symptom was that `before`, `afterLogout` and `afterReset` all came back equal,
     * because v3.0.2 rotated through a coroutine-launching helper inside a fire-and-forget block:
     * the fresh id landed after the caller had already read. (An even earlier version had the
     * different bug of writing the old id back, which is what the note in `clearAllStorage` is
     * about.) The existing test above rotates ONCE, which a
     * "remint only when absent" regression would still pass — an id that survives the second wipe
     * looks identical to a correctly-kept one. Rotating twice is what distinguishes them.
     */
    @Test
    fun `successive rotations each yield a new profileId (INT-5166)`() {
        storage.setLocalProp(StorageKeys.ProfileId.key, "prof_old")

        val before = storage.getProfileId()
        storage.clearAllStorage()
        val afterLogout = storage.getProfileId()
        storage.clearAllStorage()
        val afterReset = storage.getProfileId()

        assertNotEquals("logOut must not leave the previous identity in place", before, afterLogout)
        assertNotEquals("reset must rotate again, not reuse the id logOut minted", afterLogout, afterReset)
        assertNotEquals(before, afterReset)
        assertTrue("a rotated id must be minted, never blank", afterLogout.isNotEmpty() && afterReset.isNotEmpty())
    }

    /**
     * INT-5964. The stored app version code describes the install, not the user, but it lives in
     * AppPrefs and the wipe cleared it with everything else. With `versionChanges` on, the next
     * launch after any logout found no stored code and reported an "App Install" that never
     * happened. Checked through a second instance too, so the kept value is on disk and not only
     * in this instance's cache.
     */
    @Test
    fun `clearAllStorage keeps the stored app version code`() {
        storage.setStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousVersionCode.key,
            value = 7L,
        ) { key, value -> putLong(key, value) }

        storage.clearAllStorage()

        assertEquals(7L, storage.getStoredVersionCode())
        assertEquals(7L, StorageManagerService(context, utils, Dispatchers.Unconfined).getStoredVersionCode())
    }

    /** The rest of AppPrefs is still the user's and still goes: the push-token dedupe among it. */
    @Test
    fun `clearAllStorage still wipes the rest of AppPrefs`() {
        storage.setStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.LastRegisteredPushToken.key,
            value = "fcm-token-aaa",
        ) { key, value -> putString(key, value) }

        storage.clearAllStorage()

        val fresh = StorageManagerService(context, utils, Dispatchers.Unconfined)
        val token =
            fresh.getStorageItem<String>(
                prefs = StorageKeys.AppPrefs.key,
                key = StorageKeys.LastRegisteredPushToken.key,
            ) { key, fallBack -> getString(key, fallBack) }
        assertEquals(null, token)
    }

    /**
     * Pre-3.0.4, getProfileId() read ONLY the in-memory cache, which a fire-and-forget
     * coroutine populated some time after initialize() returned. On a cold device the read
     * won the race and returned "" even though the id sat in SharedPreferences — observed
     * intermittently through the React Native bridge's e2e probe. ensureProfileId() is the
     * synchronous replacement; no scheduler-advancing in these tests, on purpose.
     */
    @Test
    fun `ensureProfileId mints synchronously on a fresh install`() {
        storage.ensureProfileId()
        val minted = storage.getProfileId()
        assertTrue("a fresh install must have an id the moment ensure returns", minted.startsWith("prof_fresh_"))
    }

    @Test
    fun `ensureProfileId keeps an existing identity rather than rotating it`() {
        storage.ensureProfileId()
        val first = storage.getProfileId()
        storage.ensureProfileId()
        assertEquals("ensure must be idempotent — only reset() rotates", first, storage.getProfileId())
    }

    @Test
    fun `reads fall back to SharedPreferences when the cache is cold`() {
        storage.ensureProfileId()
        val minted = storage.getProfileId()

        // A second service over the same context simulates a process restart: empty cache,
        // same prefs. The read must come back from disk, not return the blank fallback.
        val restarted = StorageManagerService(context, utils, Dispatchers.Unconfined)
        assertEquals(minted, restarted.getProfileId())
    }

    @Test
    fun `writes are visible to an immediate read-back`() {
        storage.setStorageItem(
            StorageKeys.SessionPrefs.key,
            StorageKeys.SessionId.key,
            "sess_now",
        ) { key, value -> putString(key, value) }
        assertEquals("sess_now", storage.getSessionId())
    }

    @Test
    fun `clearAllStorage persists the fresh profileId, not just caches it`() {
        storage.clearAllStorage()
        val minted = storage.getProfileId()

        // A second service over the same prefs must see the same identity —
        // i.e. the rotation reached SharedPreferences, not only localStore.
        val prefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        assertEquals(minted, prefs.getString(StorageKeys.ProfileId.key, null))
    }

    /**
     * INT-3911 — two writes to the SAME key must land on disk in CALL order.
     *
     * The dispatcher defaulted to `Dispatchers.IO`, a thread POOL: `setStorageItem` launches and
     * returns, so two back-to-back writes ran on two threads and could complete in either order.
     * `optOut(); optIn()` was therefore able to persist `false` last and leave a consenting user
     * opted out on the next launch. The default is now a single worker.
     *
     * Built on the DEFAULT dispatcher on purpose — a dispatcher the test supplies would prove
     * only that a serial dispatcher is serial. The first write is held on a latch so the second
     * is definitely issued while the first is still in flight: on a pool the second overtakes it
     * and `false` ends up durable. A third write acts as the drain barrier; on one worker it
     * cannot run until both of the others have fully applied.
     */
    @Test
    fun `two writes to the same key persist in call order`() {
        val serial = StorageManagerService(context, utils)
        val key = StorageKeys.IsUserOptIn.key
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val drained = CountDownLatch(1)

        serial.setStorageItem(StorageKeys.UserPrefs.key, key, false) { k, v ->
            firstWriteStarted.countDown()
            releaseFirstWrite.await(WAIT_SECONDS, TimeUnit.SECONDS)
            putBoolean(k, v)
        }
        assertTrue(
            "the first write must be in flight before the second is issued",
            firstWriteStarted.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        serial.setStorageItem(StorageKeys.UserPrefs.key, key, true) { k, v -> putBoolean(k, v) }
        releaseFirstWrite.countDown()

        serial.setStorageItem(StorageKeys.UserPrefs.key, "order_barrier", true) { k, v ->
            putBoolean(k, v)
            drained.countDown()
        }
        assertTrue("both writes must have drained", drained.await(WAIT_SECONDS, TimeUnit.SECONDS))

        val prefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        assertTrue("the last call must be the durable value", prefs.getBoolean(key, false))
    }

    /**
     * INT-3924, residual 2. `setStorageItem` writes the cache now and queues the prefs write on the
     * serial dispatcher; `clearAllStorage` runs on the caller's thread. A session id stored just
     * before logout could therefore reach disk AFTER the wipe, and the next cold read put the
     * previous user's session back. A dispatcher that only queues makes the interleaving exact:
     * the write is issued, the clear runs, and only then does the write get to execute.
     */
    @Test
    fun `a prefs write queued before clearAllStorage does not land after it (INT-3924)`() {
        val queued = QueueingDispatcher()
        val serial = StorageManagerService(context, utils, queued)

        serial.setStorageItem(StorageKeys.SessionPrefs.key, StorageKeys.SessionId.key, "sess_old") { k, v ->
            putString(k, v)
        }
        serial.setStorageItem(StorageKeys.SessionPrefs.key, StorageKeys.PageId.key, "page_old") { k, v ->
            putString(k, v)
        }
        serial.clearAllStorage()
        queued.drain()

        val prefs = context.getSharedPreferences(StorageKeys.SessionPrefs.key, Context.MODE_PRIVATE)
        assertFalse("the old session id must not reach disk after the wipe", prefs.contains(StorageKeys.SessionId.key))
        assertFalse("the old page id must not reach disk after the wipe", prefs.contains(StorageKeys.PageId.key))
        val cold = StorageManagerService(context, utils, Dispatchers.Unconfined)
        assertEquals("a cold read must not resurrect the old session", "", cold.getSessionId())
        assertEquals("a cold read must not resurrect the old page", "", cold.getPageId())
    }

    /**
     * The generation check that drops stale writes must not drop the one key the clear keeps on
     * purpose: a version code on its way to disk at logout still has to arrive, or the next launch
     * reports an install that never happened (INT-5964).
     */
    @Test
    fun `a queued version code write still lands after clearAllStorage`() {
        val queued = QueueingDispatcher()
        val serial = StorageManagerService(context, utils, queued)

        serial.setStorageItem(StorageKeys.AppPrefs.key, StorageKeys.PreviousVersionCode.key, 9L) { k, v ->
            putLong(k, v)
        }
        serial.clearAllStorage()
        queued.drain()

        val prefs = context.getSharedPreferences(StorageKeys.AppPrefs.key, Context.MODE_PRIVATE)
        assertEquals(9L, prefs.getLong(StorageKeys.PreviousVersionCode.key, -1L))
    }

    /**
     * INT-3924, residual 1. `localStore` was a plain LinkedHashMap written from the host app's
     * thread and the IO capture services while `clearAllStorage` ran `retainAll` over it. Fresh
     * keys on every write so each one is a structural insert, which is what trips the iterator.
     * After the writers stop, one last clear must leave nothing behind, in the cache or on disk.
     */
    @Test
    fun `concurrent writes during clearAllStorage neither throw nor survive the final clear (INT-3924)`() {
        val executor = Executors.newSingleThreadExecutor()
        val serial = StorageManagerService(context, utils, executor.asCoroutineDispatcher())
        val writers = 4
        val writesPerThread = 300
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers + 1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val writing = AtomicBoolean(true)

        repeat(writers) { t ->
            thread(name = "writer-$t") {
                try {
                    start.await()
                    repeat(writesPerThread) { i ->
                        serial.setStorageItem(StorageKeys.FragmentPrefs.key, "k_${t}_$i", "v") { k, v ->
                            putString(k, v)
                        }
                        serial.getFragmentName("k_${t}_${i / 2}")
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        thread(name = "clearer") {
            try {
                start.await()
                while (writing.get()) serial.clearAllStorage()
            } catch (e: Throwable) {
                failures.add(e)
            } finally {
                done.countDown()
            }
        }

        start.countDown()
        // Writers finish on their own; the clearer loops until told to stop.
        while (done.count > 1) Thread.sleep(5)
        writing.set(false)
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue("concurrent access threw: ${failures.firstOrNull()}", failures.isEmpty())

        serial.clearAllStorage()
        executor.submit {}.get(DRAIN_SECONDS, TimeUnit.SECONDS)
        executor.shutdown()

        val prefs = context.getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
        assertTrue("no pre-clear write may survive on disk: ${prefs.all.keys.take(5)}", prefs.all.isEmpty())
        assertEquals("no pre-clear write may survive in the cache", "", serial.getFragmentName("k_0_0"))
    }

    /** Queues every dispatched block and runs them only when told to. */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
        }

        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val DRAIN_SECONDS = 60L
    }
}
