package com.intempt.intempt_android

import android.content.Context
import androidx.fragment.app.Fragment
import com.intempt.intempt_android.types.AppVisibilityState
import com.intempt.intempt_android.types.IdTypeKeys
import com.intempt.intempt_android.types.StorageKeys


class StorageHandler{
    companion object{
        fun register(context: Context) {
            appContext = context.applicationContext
        }

        fun saveFragmentName(key: String, fragment: Fragment) {
            return appContext
                .getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
                .edit()
                .putString(key, fragment.javaClass.simpleName)
                .apply()
        }

        fun getFragmentName( key: String): String? {
            return appContext
                .getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
                .getString(key, null)
        }

        fun getSessionTime(): Long {
            val prefKey = StorageKeys.SessionPrefs.key;
            val timesKey = StorageKeys.SessionTimestamp.key;

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            val timestamp = sharedPreferences.getLong(timesKey, 0L)
            Logger.log("GetSessionTime: prefs=$prefKey, key=$timesKey, timestamp=$timestamp")
            return timestamp
        }

        fun getPageTime(): Long {
            val prefKey = StorageKeys.SessionPrefs.key;
            val timeKey = StorageKeys.PageTimestamp.key;


            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            val timestamp = sharedPreferences.getLong(timeKey, 0L)
            Logger.log("GetSessionTime: prefs=$prefKey, key=$timeKey, timestamp=$timestamp")
            return timestamp
        }

        fun sessionIdGet(): String? {
            Logger.log("Get session Id");
            val prefKey = StorageKeys.SessionPrefs.key;
            val idKey = StorageKeys.SessionId.key;
            return getId( prefKey, idKey)
        }

        fun sessionIdSet() {
            Logger.log("Set session Id");
            val prefKey = StorageKeys.SessionPrefs.key;
            val idKey = StorageKeys.SessionId.key;
            val timesKey = StorageKeys.SessionTimestamp.key;
            val idType = IdTypeKeys.SessionId.key

            setId(idType, prefKey, idKey);
            setTimestamp(prefKey, timesKey)
        }

        fun sessionIdClear() {
            val prefKey = StorageKeys.SessionPrefs.key;
            val idKey = StorageKeys.SessionId.key;


            Logger.log("Clear session Id");
            clearId( prefKey,idKey);
        }

        fun updateSessionTimestamp() {
            Logger.log("Update session last activity timestamp")
//            setTimestamp(SESSION_PREFS, LAST_ACTIVITY_TIMESTAMP_KEY)
        }


        fun profileIdSet() {
            val prefKey = StorageKeys.UserPrefs.key;
            val idKey = StorageKeys.ProfileId.key;
            val idType = IdTypeKeys.ProfileId.key

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            val profileId = sharedPreferences.getString(idKey, null)

            if (profileId == null) {
                Logger.log("Set profile Id");
                setId(idType, prefKey, idKey)
            }
        }

        fun profileIdGet(): String? {
            Logger.log("Get profile Id");
            val prefKey = StorageKeys.UserPrefs.key;
            val idKey = StorageKeys.ProfileId.key;
            return getId(prefKey, idKey)
        }

        fun profileIdClear() {
            Logger.log("Clear profile Id");
            val prefKey = StorageKeys.UserPrefs.key;
            val idKey = StorageKeys.ProfileId.key;
            clearId(prefKey,idKey)
        }

        fun pageIdSet() {
            Logger.log("Set page Id");

            val prefKey = StorageKeys.SessionPrefs.key;
            val idKey = StorageKeys.PageId.key;
            val timesKey = StorageKeys.PageTimestamp.key;
            val idType = IdTypeKeys.PageId.key
            setId(idType, prefKey, idKey);
            setTimestamp(prefKey, timesKey)
        }

        fun pageIdGet(): String? {
            Logger.log("Get page Id");
            val prefKey = StorageKeys.SessionPrefs.key;
            val idKey = StorageKeys.PageId.key;
            return getId(prefKey, idKey)
        }

        fun pageIdClear() {
            Logger.log("Clear page Id");
            val prefKey = StorageKeys.SessionPrefs.key;
            val idKey = StorageKeys.PageId.key;

            clearId(prefKey,idKey)
        }

        fun getPreviousVersionCode(): Int {
            val prefKey = StorageKeys.AppPrefs.key;
            val versionKey = StorageKeys.PreviousVersionCode.key;

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            return sharedPreferences.getInt(versionKey, -1)
        }

        fun setVersionCode(versionCode: Int) {
            val prefKey = StorageKeys.AppPrefs.key;
            val versionKey = StorageKeys.PreviousVersionCode.key;

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putInt(versionKey, versionCode)
            editor.apply()
        }

        fun getPreviousBuildType():String? {
            val prefKey = StorageKeys.AppPrefs.key;
            val buildKey = StorageKeys.PreviousBuildType.key;

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            return sharedPreferences.getString(buildKey, null)
        }

        fun getAppVisibilityState(): AppVisibilityState {
            val prefKey = StorageKeys.AppPrefs.key;
            val visibilityKey = StorageKeys.AppVisibilityState.key;

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            val storedState = sharedPreferences.getString(visibilityKey, null)
            return AppVisibilityState.fromString(storedState)
        }

        fun setAppVisibilityState(visibility: AppVisibilityState) {
            val prefKey = StorageKeys.AppPrefs.key;
            val visibilityKey = StorageKeys.AppVisibilityState.key;

            val sharedPreferences = appContext.getSharedPreferences(prefKey, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString(visibilityKey, visibility.state)
            editor.apply()
        }



        private lateinit var appContext: Context

        private fun setTimestamp(prefs: String, key: String) {
            val timestamp = System.currentTimeMillis()
            val sharedPreferences = appContext.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putLong(key, timestamp)
            editor.apply()

            Logger.log("SetTimestamp: prefs=$prefs, key=$key, timestamp=$timestamp")
        }

        private fun setId(idType:String, prefs:String, keyType:String){
            val id = generateId(idType)
            val sharedPreferences = appContext.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString(keyType, id)
            editor.apply()

            Logger.log("SetId: idType=$idType, prefs=$prefs, keyType=$keyType, id=$id")
        }

        private fun getId(prefs:String,keyType:String): String?{
            val sharedPreferences = appContext.getSharedPreferences(prefs, Context.MODE_PRIVATE);
            val id = sharedPreferences.getString(keyType, null)

            Logger.log("GetId: prefs=$prefs, keyType=$keyType, id=$id")
            return id
        }

        private fun clearId(prefs:String, keyType:String){
            val sharedPreferences = appContext.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.remove(keyType)
            editor.apply()

            Logger.log("ClearId: prefs=$prefs, keyType=$keyType")
        }
    }

}