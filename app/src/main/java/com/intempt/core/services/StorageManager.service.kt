package com.intempt.core.services

import android.content.Context
import android.content.SharedPreferences
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.StorageKeys
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class StorageManagerService @Inject constructor(
    private val context: Context,
){

    fun <T> setStorageItem(
        prefs: String,
        key: String,
        value: T,
        applyToPrefs: SharedPreferences.Editor.(String, T) -> Unit
    ){
        val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.applyToPrefs(key, value)
        editor.apply()
    }

    fun  <T> getStorageItem(
        prefs: String,
        key: String,
        fallBack: T? = null,
        fetchFromPrefs: SharedPreferences.(String,T?) -> T?
    ): T? {
        val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        return sharedPreferences.fetchFromPrefs(key, fallBack)

    }

    fun getSessionId():String {
        return getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.SessionId.key
        ){ key, fallBack ->
            getString(key,fallBack ?: "")
        } ?: ""
    }

    fun getPageId(): String {
        return getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.PageId.key
        ){ key, fallBack ->
            getString(key,fallBack ?: "")
        } ?: ""
    }

    fun getProfileId():String {
        return getStorageItem(
            prefs = StorageKeys.UserPrefs.key,
            key = StorageKeys.ProfileId.key
        ){key, fallBack ->
            getString(key,fallBack ?: "")
        } ?: ""
    }

    fun getStoredBuildType():String? {
        val prefKey = StorageKeys.AppPrefs.key;
        val buildKey = StorageKeys.PreviousBuildType.key;

        val sharedPreferences = context.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
        return sharedPreferences.getString(buildKey, null)
    }

    fun getAppVisibilityState(): AppVisibilityState {
        val prefKey = StorageKeys.AppPrefs.key;
        val visibilityKey = StorageKeys.AppVisibilityState.key;

        val sharedPreferences = context.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
        val storedState = sharedPreferences.getString(visibilityKey, null)
        return AppVisibilityState.fromString(storedState)
    }

    fun getFragmentName( key: String): String? {
        return context
            .getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    fun getPageTime(): Long {
        val prefKey = StorageKeys.SessionPrefs.key;
        val timeKey = StorageKeys.PageTimestamp.key;


        val sharedPreferences = context.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
        val timestamp = sharedPreferences.getLong(timeKey, 0L)
        //Logger.log("GetSessionTime: prefs=$prefKey, key=$timeKey, timestamp=$timestamp")
        return timestamp
    }

    fun getStoredVersionCode():Int {
        val fallbackVersion = -1
        val versionCode = getStorageItem(
            prefs = StorageKeys.AppPrefs.key,
            key = StorageKeys.PreviousVersionCode.key,
        ){ key, fallBack ->
            getInt(key, fallBack ?: fallbackVersion)
        } ?: fallbackVersion
        return versionCode
    }

    fun clearAllStorage() {
        val profileId = getProfileId()

        // Clear all entries in SessionPrefs
        val sessionPrefs = context.getSharedPreferences(StorageKeys.SessionPrefs.key, Context.MODE_PRIVATE)
        sessionPrefs.edit().clear().apply()

        // Clear all entries in AppPrefs
        val appPrefs = context.getSharedPreferences(StorageKeys.AppPrefs.key, Context.MODE_PRIVATE)
        appPrefs.edit().clear().apply()

        // Clear all entries in FragmentPrefs
        val fragmentPrefs = context.getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
        fragmentPrefs.edit().clear().apply()

        // Restore the profileId in UserPrefs after clearing
        val userPrefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        userPrefs.edit().clear().apply()

        setStorageItem(
            StorageKeys.UserPrefs.key,
            StorageKeys.ProfileId.key,
            profileId
        ) { key, value ->
            putString(key, value)
        }
    }







}