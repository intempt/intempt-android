package com.intempt.core.services

import android.content.Context
import android.content.SharedPreferences
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class StorageManagerService @Inject constructor(
    private val context: Context,
    private val utils: UtilsService,
){
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val localStore = mutableMapOf<String, Any?>()


    fun setLocalProp(key:String, value:Any){
        localStore[key] = value
    }

    fun <T> setStorageItem(
        prefs: String,
        key: String,
        value: T,
        applyToPrefs: SharedPreferences.Editor.(String, T) -> Unit
    ){
        coroutineScope.launch {
            val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.applyToPrefs(key, value)
            editor.apply()

            localStore[key] = value
        }

    }

    fun  <T> getStorageItem(
        prefs: String,
        key: String,
        fallBack: T? = null,
        fetchFromPrefs: SharedPreferences.(String,T?) -> T?
    ): T? {
        @Suppress("UNCHECKED_CAST")
        return localStore[key] as T?
    }

    fun getSessionId():String {
        val fallback = ""
        return getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionId.key
        ){ key, fallBack ->
            getString(key,fallBack ?: fallback)
        } ?: fallback
    }

    fun getPageId(): String {
        val fallback = ""
        return getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.PageId.key
        ){ key, fallBack ->
            getString(key,fallBack ?: fallback)
        } ?: fallback
    }

    fun getProfileId():String {
        val fallback = ""
        return getStorageItem(
            prefs = StorageKeys.UserPrefs.key,
            key = StorageKeys.ProfileId.key
        ){key, fallBack ->
            getString(key,fallBack ?: fallback)
        } ?: fallback
    }

    fun getStoredBuildType():String {
        val fallback = ""
        return getStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousBuildType.key
        ){key, fallBack ->
            getString(key,fallBack ?: fallback)
        } ?: fallback
    }

    fun getAppVisibilityState(): AppVisibilityState {
        val fallback = AppVisibilityState.Background.key
        val storedState = getStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.AppVisibilityState.key
        ){key, fallBack ->
            getString(key,fallBack ?: fallback)
        } ?: fallback
        return AppVisibilityState.fromString(storedState)
    }

    fun getFragmentName( keyType: String): String {
        val fallback = ""
        return getStorageItem(
            prefs = StorageKeys.FragmentPrefs.key,
            key = keyType
        ){ key, fallBack ->
            getString(key,fallBack ?: fallback)
        } ?: fallback
    }

    fun getPageTime(): Long {
        val fallback = -1L
        return getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.PageTimestamp.key
        ){ key, fallBack ->
            getLong(key,fallBack ?: fallback)
        } ?: fallback
    }

    fun getStoredVersionCode():Long {
        val fallbackVersion = -1L
        val versionCode = getStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousVersionCode.key,
        ){ key, fallBack ->
            getLong(key, fallBack ?: fallbackVersion)
        } ?: fallbackVersion

        return versionCode
    }

    fun clearAllStorage() {
        coroutineScope.launch {

            // Clear all entries in SessionPrefs
            val sessionPrefs = context.getSharedPreferences(StorageKeys.SessionPrefs.key, Context.MODE_PRIVATE)
            sessionPrefs.edit().clear().apply()

            // Clear all entries in AppPrefs
            val appPrefs = context.getSharedPreferences(StorageKeys.AppPrefs.key, Context.MODE_PRIVATE)
            appPrefs.edit().clear().apply()

            // Clear all entries in FragmentPrefs
            val fragmentPrefs = context.getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
            fragmentPrefs.edit().clear().apply()

            val userPrefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
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
                utils.generateId(IdTypeKeys.ProfileId.key)
            ) { key, value ->
                putString(key, value)
            }
        }
    }

    suspend fun validateProfileId() = withContext(Dispatchers.IO) {
        val profKey = StorageKeys.ProfileId.key
        val sharedPreferences = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        val profId = sharedPreferences.getString(profKey, null)

        if(!profId.isNullOrEmpty()){
            localStore[profKey] = profId
        }
        else{
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