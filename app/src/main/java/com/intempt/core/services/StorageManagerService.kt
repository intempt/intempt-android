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
import kotlinx.coroutines.withContext
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
            coroutineScope.launch {
                val sharedPreferences = context.getSharedPreferences(scopedPrefs(prefs), Context.MODE_PRIVATE)
                val editor = sharedPreferences.edit()
                editor.applyToPrefs(key, value)
                editor.apply()

                localStore[key] = value
            }
        }

        fun <T> getStorageItem(
            prefs: String,
            key: String,
            fallBack: T? = null,
            fetchFromPrefs: SharedPreferences.(String, T?) -> T?,
        ): T? {
            @Suppress("UNCHECKED_CAST")
            return localStore[key] as T?
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
            coroutineScope.launch {
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
                setStorageItem(
                    StorageKeys.UserPrefs.key,
                    StorageKeys.ProfileId.key,
                    utils.generateId(IdTypeKeys.ProfileId.key),
                ) { key, value ->
                    putString(key, value)
                }
            }
        }

        suspend fun validateProfileId() =
            withContext(dispatcher) {
                val profKey = StorageKeys.ProfileId.key
                val sharedPreferences =
                    context.getSharedPreferences(scopedPrefs(StorageKeys.UserPrefs.key), Context.MODE_PRIVATE)
                val profId = sharedPreferences.getString(profKey, null)

                if (!profId.isNullOrEmpty()) {
                    localStore[profKey] = profId
                } else {
                    setStorageItem(
                        prefs = StorageKeys.UserPrefs.key,
                        key = StorageKeys.ProfileId.key,
                        value = utils.generateId(IdTypeKeys.ProfileId.key),
                    ) { key, value ->
                        putString(key, value)
                    }
                }
            }
    }
