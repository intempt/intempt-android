package com.intempt.core.services

import android.content.Context
import android.content.SharedPreferences
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.Constants
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.InstanceId
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One worker for every storage write in the process, so the durable order is the call order.
 * See the `dispatcher` parameter below for what went wrong without it.
 *
 * `limitedParallelism(1)` rather than a single-thread executor: it gives the same serialisation
 * by borrowing a thread from the shared IO pool only while work is queued. The executor version
 * created a non-daemon thread that was never shut down — in an SDK that is a thread leaked for
 * the host app's whole lifetime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val SERIAL_STORAGE_DISPATCHER = Dispatchers.IO.limitedParallelism(1)

/** Keys that belong to the install, not the user, so a logout wipe keeps them. */
private val DEVICE_STATE_KEYS = setOf(StorageKeys.PreviousVersionCode.key)

@Singleton
internal class StorageManagerService
    @Inject
    constructor(
        private val context: Context,
        private val utils: UtilsService,
        // Single-threaded ON PURPOSE (INT-3911). Dispatchers.IO is a pool, so two writes to
        // the SAME key issued back-to-back could complete in either order: `optOut(); optIn()`
        // was able to persist `false` last and leave a consenting user opted out on the next
        // launch. A single worker makes the durable order the call order, for every key, and
        // keeps the write off the caller's thread. `localStore` never had the problem — it is
        // written on the caller's thread, which is why only the persisted copy could disagree.
        private val dispatcher: CoroutineDispatcher = SERIAL_STORAGE_DISPATCHER,
        /**
         * Which instance's storage this is.
         *
         * Every `SharedPreferences` name goes through [InstanceId.scope]. Without it two named
         * instances share one set of prefs, so the second inherits the first's `profileId` — the
         * identity leak named instances exist to prevent, reproduced one layer down.
         */
        private val instance: InstanceId = InstanceId.Default,
    ) {
        private val coroutineScope = CoroutineScope(dispatcher)

        /**
         * Guards [localStore] and [generation], and orders every persisted write against
         * [clearAllStorage] (INT-3924).
         *
         * A lock rather than a ConcurrentHashMap: the map holds nulls, `clearAllStorage` has to
         * wipe prefs, drop the cache and bump the generation as one step, and a queued write has to
         * check the generation and apply in one step too. A concurrent map makes each access safe
         * and none of those compound steps atomic. Every critical section is a few map operations
         * or an in-memory `SharedPreferences` apply, so contention is not a concern.
         */
        private val lock = Any()

        /**
         * Read and written from the caller's thread, the IO capture services and the serial
         * dispatcher, so only ever under [lock]. A plain LinkedHashMap here let a `put` from one
         * thread race `retainAll` in [clearAllStorage] on another.
         */
        private val localStore = mutableMapOf<String, Any?>()

        /**
         * Bumped by every [clearAllStorage]. A persisted write records the generation it was issued
         * in and is dropped if a clear happened in between, so a session or page id queued just
         * before logout cannot reach disk after the wipe and come back on the next cold read.
         */
        private var generation = 0L

        /** The instance-scoped name for a `SharedPreferences` file. */
        private fun scopedPrefs(base: String): String = instance.scope(base)

        fun setLocalProp(
            key: String,
            value: Any,
        ) {
            synchronized(lock) { localStore[key] = value }
        }

        fun <T> setStorageItem(
            prefs: String,
            key: String,
            value: T,
            applyToPrefs: SharedPreferences.Editor.(String, T) -> Unit,
        ) {
            // The cache write happens on the caller's thread, BEFORE this returns. It used to
            // happen inside the coroutine, which made every write invisible to an immediate
            // read-back — getSessionId() right after the session tracker stored one, or
            // getProfileId() right after the mint. Persistence is the only slow part, and
            // SharedPreferences.apply() already has exactly these semantics one level down:
            // visible now, durable eventually.
            val issuedIn =
                synchronized(lock) {
                    localStore[key] = value
                    generation
                }
            coroutineScope.launch {
                val sharedPreferences = context.getSharedPreferences(scopedPrefs(prefs), Context.MODE_PRIVATE)
                // The edit is staged outside the lock (nothing is visible until apply) and applied
                // inside it, so the generation check and the apply are one step against a clear.
                val editor = sharedPreferences.edit()
                editor.applyToPrefs(key, value)
                synchronized(lock) {
                    // Install-scoped keys survive a clear on purpose, so their writes do too.
                    if (issuedIn == generation || key in DEVICE_STATE_KEYS) editor.apply()
                }
            }
        }

        fun <T> getStorageItem(
            prefs: String,
            key: String,
            fallBack: T? = null,
            fetchFromPrefs: SharedPreferences.(String, T?) -> T?,
        ): T? {
            // Under the lock end to end, so a prefs read that started before a clear cannot
            // repopulate the cache with the old value after it.
            synchronized(lock) {
                @Suppress("UNCHECKED_CAST")
                val cached = localStore[key] as T?
                if (cached != null) return cached

                // Cache miss: read through to SharedPreferences and remember the answer. Every
                // caller has always passed this lambda, but it was never invoked — the cache was
                // the only source of truth, so a process restart (or a read that beat the async
                // population job) returned the fallback even though the value sat on disk.
                val sharedPreferences = context.getSharedPreferences(scopedPrefs(prefs), Context.MODE_PRIVATE)
                val fetched = sharedPreferences.fetchFromPrefs(key, fallBack)
                if (fetched != null) localStore[key] = fetched
                return fetched
            }
        }

        private val sessionLock = Any()

        /**
         * Emits "Session start" for a rotation. Installed by the session tracker only when the
         * sessions switch is on; the id itself is minted either way (INT-7446).
         */
        @Volatile
        var onSessionStart: (() -> Unit)? = null

        /**
         * Mints or rotates the session id on the caller's thread and records activity. True when a
         * new session began.
         *
         * Every event build calls this, so the decision is made before the event reads its
         * sessionId: the old async start let the first event after init carry "" or the previous
         * session's id, and a second minting path in the event subscriber produced two Session
         * starts for one window (INT-7446, CONTRACT-R2 S5). [onSessionStart] runs inside the lock,
         * so a concurrent event waits until the Session start is enqueued ahead of it; the lock is
         * reentrant because that event's own build calls back in here.
         */
        fun ensureSession(now: Long = System.currentTimeMillis()): Boolean =
            synchronized(sessionLock) {
                val lastActivity =
                    getStorageItem(
                        prefs = StorageKeys.SessionPrefs.key,
                        key = StorageKeys.SessionTimestamp.key,
                    ) { key, fallBack -> getLong(key, fallBack ?: 0L) } ?: 0L
                val rotate = getSessionId().isEmpty() || now - lastActivity > Constants.SESSION.SESSION_TIMEOUT
                if (rotate) {
                    setStorageItem(
                        prefs = StorageKeys.SessionPrefs.key,
                        key = StorageKeys.SessionId.key,
                        value = utils.generateId(IdTypeKeys.SessionId.key),
                    ) { key, value -> putString(key, value) }
                }
                setStorageItem(
                    prefs = StorageKeys.SessionPrefs.key,
                    key = StorageKeys.SessionTimestamp.key,
                    value = now,
                ) { key, value -> putLong(key, value) }
                if (rotate) onSessionStart?.invoke()
                rotate
            }

        fun getSessionId(): String {
            val fallback = ""
            return getStorageItem(
                prefs = StorageKeys.SessionPrefs.key,
                key = StorageKeys.SessionId.key,
            ) { key, fallBack ->
                getString(key, fallBack ?: fallback)
            } ?: fallback
        }

        fun getPageId(): String {
            val fallback = ""
            return getStorageItem(
                prefs = StorageKeys.SessionPrefs.key,
                key = StorageKeys.PageId.key,
            ) { key, fallBack ->
                getString(key, fallBack ?: fallback)
            } ?: fallback
        }

        fun getProfileId(): String {
            val fallback = ""
            return getStorageItem(
                prefs = StorageKeys.UserPrefs.key,
                key = StorageKeys.ProfileId.key,
            ) { key, fallBack ->
                getString(key, fallBack ?: fallback)
            } ?: fallback
        }

        fun getStoredBuildType(): String {
            val fallback = ""
            return getStorageItem(
                prefs = StorageKeys.AppPrefs.key,
                key = StorageKeys.PreviousBuildType.key,
            ) { key, fallBack ->
                getString(key, fallBack ?: fallback)
            } ?: fallback
        }

        fun getAppVisibilityState(): AppVisibilityState {
            val fallback = AppVisibilityState.Background.key
            val storedState =
                getStorageItem(
                    prefs = StorageKeys.AppPrefs.key,
                    key = StorageKeys.AppVisibilityState.key,
                ) { key, fallBack ->
                    getString(key, fallBack ?: fallback)
                } ?: fallback
            return AppVisibilityState.fromString(storedState)
        }

        fun getFragmentName(keyType: String): String {
            val fallback = ""
            return getStorageItem(
                prefs = StorageKeys.FragmentPrefs.key,
                key = keyType,
            ) { key, fallBack ->
                getString(key, fallBack ?: fallback)
            } ?: fallback
        }

        fun getPageTime(): Long {
            val fallback = -1L
            return getStorageItem(
                prefs = StorageKeys.SessionPrefs.key,
                key = StorageKeys.PageTimestamp.key,
            ) { key, fallBack ->
                getLong(key, fallBack ?: fallback)
            } ?: fallback
        }

        fun getStoredVersionCode(): Long {
            val fallbackVersion = -1L
            val versionCode =
                getStorageItem(
                    prefs = StorageKeys.AppPrefs.key,
                    key = StorageKeys.PreviousVersionCode.key,
                ) { key, fallBack ->
                    getLong(key, fallBack ?: fallbackVersion)
                } ?: fallbackVersion

            return versionCode
        }

        fun clearAllStorage() {
            // One step under the lock (INT-3924): no cache write, cache fill or queued prefs write
            // can interleave with the wipe, and bumping the generation first makes every write
            // issued before this point a no-op when it finally runs.
            synchronized(lock) {
                generation++
                // Deliberately NOT launched on a coroutine: reset()/logOut() promise that the very
                // next getProfileId() already sees the rotated identity. The previous version ran
                // this whole block fire-and-forget, so a caller reading immediately after reset()
                // raced the wipe and still saw the old profile id. The work here is cheap —
                // SharedPreferences are in-memory once loaded and apply() persists asynchronously.

                // Clear all entries in SessionPrefs
                val sessionPrefs =
                    context.getSharedPreferences(scopedPrefs(StorageKeys.SessionPrefs.key), Context.MODE_PRIVATE)
                sessionPrefs.edit().clear().apply()

                // Clear AppPrefs, except what describes the install rather than the user (INT-5964).
                // The stored version code went with everything else, so with versionChanges on the
                // launch after any logout reported an "App Install" on a device that had it all along.
                val appPrefs = context.getSharedPreferences(scopedPrefs(StorageKeys.AppPrefs.key), Context.MODE_PRIVATE)
                val appEditor = appPrefs.edit()
                appPrefs.all.keys.filterNot { it in DEVICE_STATE_KEYS }.forEach { appEditor.remove(it) }
                appEditor.apply()

                // Clear all entries in FragmentPrefs
                val fragmentPrefs =
                    context.getSharedPreferences(scopedPrefs(StorageKeys.FragmentPrefs.key), Context.MODE_PRIVATE)
                fragmentPrefs.edit().clear().apply()

                val userPrefs =
                    context.getSharedPreferences(scopedPrefs(StorageKeys.UserPrefs.key), Context.MODE_PRIVATE)
                userPrefs.edit().clear().apply()

                // The cache too: a version code stored a moment ago may still be on its way to disk.
                localStore.keys.retainAll(DEVICE_STATE_KEYS)

                // Issue a FRESH anonymous profileId rather than restoring the previous one.
                //
                // This previously read getProfileId() before clearing and wrote the same value
                // back, so logOut() did not actually separate identities: the next user of a
                // shared device inherited the previous user's profile, and their events were
                // attributed to it. Rotating here is the whole point of logging out.
                //
                // Written inline rather than through setStorageItem(): that helper launches its own
                // coroutine, which both delays the localStore update past this method's return and
                // could interleave with the prefs wipe above.
                val freshProfileId = utils.generateId(IdTypeKeys.ProfileId.key)
                userPrefs.edit().putString(StorageKeys.ProfileId.key, freshProfileId).apply()
                localStore[StorageKeys.ProfileId.key] = freshProfileId
            }
        }

        /**
         * Loads the profile id into the cache, minting one if this install has none — all on
         * the caller's thread, so `getProfileId()` answers correctly the moment this returns.
         *
         * This must run before `initialize()` resolves. Its predecessor (`validateProfileId`,
         * a suspend fun launched fire-and-forget from `startAutomaticEvents`) raced every
         * immediate read: on a warm device the mint usually won; on a cold one the caller read
         * an empty id — observed intermittently through the React Native bridge's e2e probe.
         */
        fun ensureProfileId() {
            // Through the read-through getter, not raw prefs: an id minted a moment ago is in
            // the cache while its persist is still in flight, and reading prefs directly here
            // would miss it and rotate an identity that already exists.
            if (getProfileId().isNotEmpty()) return

            setStorageItem(
                prefs = StorageKeys.UserPrefs.key,
                key = StorageKeys.ProfileId.key,
                value = utils.generateId(IdTypeKeys.ProfileId.key),
            ) { key, value ->
                putString(key, value)
            }
        }
    }
