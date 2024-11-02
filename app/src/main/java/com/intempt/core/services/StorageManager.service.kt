package com.intempt.core.services

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.Fragment
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class StorageManagerService @Inject constructor(
    private val context: Context,
    private val logger: LoggerManagerService
):BaseComponent(logger){

    init{
        profileIdSet()
    }

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
        logger.log("Get page Id");
        return getStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.PageId.key
        ){ key, fallBack ->
            getString(key,fallBack ?: "")
        } ?: ""
    }

    fun getProfileId():String {
        logger.log("Get profile Id");
        return getStorageItem(
            prefs = StorageKeys.UserPrefs.key,
            key = StorageKeys.ProfileId.key
        ){key, fallBack ->
            getString(key,fallBack ?: "")
        } ?: ""
    }


    fun getPreviousBuildType():String? {
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


    private fun profileIdSet() {
        val prefKey = StorageKeys.UserPrefs.key;
        val idKey = StorageKeys.ProfileId.key;
        val idType = IdTypeKeys.ProfileId.key

        val sharedPreferences = context.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
        val profileId = sharedPreferences.getString(idKey, null)

        if (profileId == null) {
//            Logger.log("Set profile Id");
           // setId(idType, prefKey, idKey)
        }
    }







    fun getId(prefs:String, keyType:String): String?{
        val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE);
        val id = sharedPreferences.getString(keyType, null)

       // Logger.log("GetId: prefs=$prefs, keyType=$keyType, id=$id")
        return id
    }



    fun getFragmentName( key: String): String? {
        return context
            .getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    fun saveFragmentName(key: String, fragment: Fragment) {
        return context
            .getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
            .edit()
            .putString(key, fragment.javaClass.simpleName)
            .apply()
    }





    fun getPageTime(): Long {
        val prefKey = StorageKeys.SessionPrefs.key;
        val timeKey = StorageKeys.PageTimestamp.key;


        val sharedPreferences = context.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
        val timestamp = sharedPreferences.getLong(timeKey, 0L)
        //Logger.log("GetSessionTime: prefs=$prefKey, key=$timeKey, timestamp=$timestamp")
        return timestamp
    }









}