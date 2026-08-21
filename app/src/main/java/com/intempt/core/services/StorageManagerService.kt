package com.intempt.core.services

import android.content.Context
import android.content.SharedPreferences
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.InstanceId
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class StorageManagerService
    @Inject
    constructor(
        private val context: Context,
        private val utils: UtilsService,
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        private val localStore = mutableMapOf<String, Any?>()

        /** The instance-scoped name for a `SharedPreferences` file. */
        private fun scopedPrefs(base: String): String = instance.scope(base)

        fun setLocalProp(
            key: String,
            value: Any,
        ) {
            localStore[key] = value
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
            localStore[key] = value
            coroutineScope.launch {
                val sharedPreferences = context.getSharedPreferences(scopedPrefs(prefs), Context.MODE_PRIVATE)
                val editor = sharedPreferences.edit()
                editor.applyToPrefs(key, value)
                editor.apply()
            }
        }

        fun <T> getStorageItem(
            prefs: String,
            key: String,
            fallBack: T? = null,
            fetchFromPrefs: SharedPreferences.(String, T?) -> T?,
        ): T? {
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
            // Deliberately NOT launched on a coroutine: reset()/logOut() promise that the very
            // next getProfileId() already sees the rotated identity. The previous version ran
            // this whole block fire-and-forget, so a caller reading immediately after reset()
            // raced the wipe and still saw the old profile id. The work here is cheap —
            // SharedPreferences are in-memory once loaded and apply() persists asynchronously.

            // Clear all entries in SessionPrefs
            val sessionPrefs =
                context.getSharedPreferences(scopedPrefs(StorageKeys.SessionPrefs.key), Context.MODE_PRIVATE)
            sessionPrefs.edit().clear().apply()

            // Clear all entries in AppPrefs
            val appPrefs = context.getSharedPreferences(scopedPrefs(StorageKeys.AppPrefs.key), Context.MODE_PRIVATE)
            appPrefs.edit().clear().apply()

            // Clear all entries in FragmentPrefs
            val fragmentPrefs =
                context.getSharedPreferences(scopedPrefs(StorageKeys.FragmentPrefs.key), Context.MODE_PRIVATE)
            fragmentPrefs.edit().clear().apply()

            val userPrefs =
                context.getSharedPreferences(scopedPrefs(StorageKeys.UserPrefs.key), Context.MODE_PRIVATE)
            userPrefs.edit().clear().apply()

            localStore.clear()

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
