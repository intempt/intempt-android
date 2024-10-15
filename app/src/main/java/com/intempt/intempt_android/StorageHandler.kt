package com.intempt.intempt_android

import android.content.Context
import androidx.fragment.app.Fragment

class StorageHandler {
    companion object{
        fun saveFragmentName(context: Context,key: String, fragment: Fragment) {
            return context
                .getSharedPreferences(FRAGMENT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, fragment.javaClass.simpleName)
                .apply()
        }

        fun getFragmentName(context: Context, key: String): String? {
            return context
                .getSharedPreferences(FRAGMENT_PREFS, Context.MODE_PRIVATE)
                .getString(key, null)
        }

        fun getSessionTime(context: Context): Long {
            val sharedPreferences = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            val timestamp = sharedPreferences.getLong(SESSION_TIMESTAMP_KEY, 0L)
            Logger.log("GetSessionTime: prefs=$SESSION_PREFS, key=$SESSION_TIMESTAMP_KEY, timestamp=$timestamp")
            return timestamp
        }

        fun getPageTime(context: Context): Long {
            val sharedPreferences = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            val timestamp = sharedPreferences.getLong(PAGE_TIMESTAMP_KEY, 0L)
            Logger.log("GetSessionTime: prefs=$SESSION_PREFS, key=$PAGE_TIMESTAMP_KEY, timestamp=$timestamp")
            return timestamp
        }

        fun sessionIdGet(context: Context): String? {
            Logger.log("Get session Id");
            return getId(context, SESSION_PREFS, SESSION_ID_KEY)
        }

        fun sessionIdSet(context: Context) {
            Logger.log("Set session Id");
            setId(context, "ses", SESSION_PREFS, SESSION_ID_KEY);
            setTimestamp(context, SESSION_PREFS, SESSION_TIMESTAMP_KEY)
        }

        fun sessionIdClear(context: Context) {
            Logger.log("Clear session Id");
            clearId(context, SESSION_PREFS,SESSION_ID_KEY);
        }

        fun updateSessionTimestamp(context: Context) {
            Logger.log("Update session last activity timestamp")
            //setTimestamp(context, SESSION_PREFS, LAST_ACTIVITY_TIMESTAMP_KEY)
        }


        fun profileIdSet(context: Context) {
            val sharedPreferences = context.getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE)
            val profileId = sharedPreferences.getString(PROFILE_ID_KEY, null)

            if (profileId == null) {
                Logger.log("Set profile Id");
                setId(context, "prof", USER_PREFS, PROFILE_ID_KEY)
            }
        }

        fun profileIdGet(context: Context): String? {
            Logger.log("Get profile Id");

            return getId(context, USER_PREFS, PROFILE_ID_KEY)
        }

        fun profileIdClear(context: Context) {
            Logger.log("Clear profile Id");
            clearId(context, USER_PREFS,PROFILE_ID_KEY)
        }


        fun pageIdSet(context: Context) {
            Logger.log("Set page Id");
            setId(context, "pag", SESSION_PREFS, PAGE_ID_KEY);
            setTimestamp(context, SESSION_PREFS, PAGE_TIMESTAMP_KEY)
        }

        fun pageIdGet(context: Context): String? {
            Logger.log("Get page Id");
            return getId(context, SESSION_PREFS, PAGE_ID_KEY)
        }

        fun pageIdClear(context: Context) {
            Logger.log("Clear page Id");
            clearId(context, SESSION_PREFS,PAGE_ID_KEY)
        }



        private const val SESSION_PREFS = "session_prefs";
        private const val USER_PREFS = "user_prefs";
        private const val FRAGMENT_PREFS = "fragment_prefs";

        private const val SESSION_ID_KEY = "session_id";
        private const val PROFILE_ID_KEY = "profile_id";
        private const val PAGE_ID_KEY = "page_id";

        private const val SESSION_TIMESTAMP_KEY = "session_time"
        private const val PAGE_TIMESTAMP_KEY = "page_time"

        private fun setTimestamp(context: Context, prefs: String, key: String) {
            val timestamp = System.currentTimeMillis()
            val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putLong(key, timestamp)
            editor.apply()

            Logger.log("SetTimestamp: prefs=$prefs, key=$key, timestamp=$timestamp")
        }

        private fun setId(context: Context, idType:String, prefs:String, keyType:String){
            val id = generateId(idType)
            val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString(keyType, id)
            editor.apply()

            Logger.log("SetId: idType=$idType, prefs=$prefs, keyType=$keyType, id=$id")
        }

        private fun getId(context: Context,prefs:String,keyType:String): String?{
            val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE);
            val id = sharedPreferences.getString(keyType, null)

            Logger.log("GetId: prefs=$prefs, keyType=$keyType, id=$id")
            return id
        }

        private fun clearId(context: Context, prefs:String, keyType:String){
            val sharedPreferences = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.remove(keyType)
            editor.apply()

            Logger.log("ClearId: prefs=$prefs, keyType=$keyType")
        }


    }




}