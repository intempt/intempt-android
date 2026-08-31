package com.intempt.core.services

import android.content.Context
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
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
}
