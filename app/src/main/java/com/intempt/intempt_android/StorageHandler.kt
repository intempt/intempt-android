package com.intempt.intempt_android

import android.content.Context
import androidx.fragment.app.Fragment

class StorageHandler {
    companion object{
        fun init(context: Context) {
            appContext = context.applicationContext
        }


        fun saveFragmentName(key: String, fragment: Fragment) {
            return appContext
                .getSharedPreferences(FRAGMENT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, fragment.javaClass.simpleName)
                .apply()
        }

        fun getFragmentName( key: String): String? {
            return appContext
                .getSharedPreferences(FRAGMENT_PREFS, Context.MODE_PRIVATE)
                .getString(key, null)
        }

        fun getSessionTime(): Long {
            val sharedPreferences = appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            val timestamp = sharedPreferences.getLong(SESSION_TIMESTAMP_KEY, 0L)
            Logger.log("GetSessionTime: prefs=$SESSION_PREFS, key=$SESSION_TIMESTAMP_KEY, timestamp=$timestamp")
            return timestamp
        }

        fun getPageTime(): Long {
            val sharedPreferences = appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            val timestamp = sharedPreferences.getLong(PAGE_TIMESTAMP_KEY, 0L)
            Logger.log("GetSessionTime: prefs=$SESSION_PREFS, key=$PAGE_TIMESTAMP_KEY, timestamp=$timestamp")
            return timestamp
        }

        fun sessionIdGet(): String? {
            Logger.log("Get session Id");
            return getId( SESSION_PREFS, SESSION_ID_KEY)
        }

        fun sessionIdSet() {
            Logger.log("Set session Id");
            setId("ses", SESSION_PREFS, SESSION_ID_KEY);
            setTimestamp(SESSION_PREFS, SESSION_TIMESTAMP_KEY)
        }

        fun sessionIdClear() {
            Logger.log("Clear session Id");
            clearId( SESSION_PREFS,SESSION_ID_KEY);
        }

        fun updateSessionTimestamp() {
            Logger.log("Update session last activity timestamp")
//            setTimestamp(SESSION_PREFS, LAST_ACTIVITY_TIMESTAMP_KEY)
        }


        fun profileIdSet() {
            val sharedPreferences = appContext.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
            val profileId = sharedPreferences.getString(PROFILE_ID_KEY, null)

            if (profileId == null) {
                Logger.log("Set profile Id");
                setId("prof", USER_PREFS, PROFILE_ID_KEY)
            }
        }

        fun profileIdGet(): String? {
            Logger.log("Get profile Id");

            return getId(USER_PREFS, PROFILE_ID_KEY)
        }

        fun profileIdClear() {
            Logger.log("Clear profile Id");
            clearId(USER_PREFS,PROFILE_ID_KEY)
        }

        fun pageIdSet() {
            Logger.log("Set page Id");
            setId("pag", SESSION_PREFS, PAGE_ID_KEY);
            setTimestamp(SESSION_PREFS, PAGE_TIMESTAMP_KEY)
        }

        fun pageIdGet(): String? {
            Logger.log("Get page Id");
            return getId(SESSION_PREFS, PAGE_ID_KEY)
        }

        fun pageIdClear() {
            Logger.log("Clear page Id");
            clearId(SESSION_PREFS,PAGE_ID_KEY)
        }



        private const val SESSION_PREFS = "session_prefs";
        private const val USER_PREFS = "user_prefs";
        private const val FRAGMENT_PREFS = "fragment_prefs";

        private const val SESSION_ID_KEY = "session_id";
        private const val PROFILE_ID_KEY = "profile_id";
        private const val PAGE_ID_KEY = "page_id";

        private const val SESSION_TIMESTAMP_KEY = "session_time"
        private const val PAGE_TIMESTAMP_KEY = "page_time"

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