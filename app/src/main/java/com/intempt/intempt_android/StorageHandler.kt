package com.intempt.intempt_android

import android.content.Context

class StorageHandler {
    companion object{
        private const val SESSION_PREFS = "session_prefs";
        private const val USER_PREFS = "user_prefs";

        private const val SESSION_ID_KEY = "session_id";
        private const val PROFILE_ID_KEY = "profile_id";
        private const val PAGE_ID_KEY = "page_id";


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


        fun sessionIdGet(context: Context): String? {
            Logger.log("Get session Id");
            return getId(context, SESSION_PREFS, SESSION_ID_KEY)
        }

        fun sessionIdSet(context: Context) {
            Logger.log("Set session Id");
            setId(context, "ses", SESSION_PREFS, SESSION_ID_KEY);
        }

        fun sessionIdClear(context: Context) {
            Logger.log("Clear session Id");
            clearId(context, SESSION_PREFS,SESSION_ID_KEY);
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
            setId(context, "pag", SESSION_PREFS, PAGE_ID_KEY)
        }

        fun pageIdGet(context: Context): String? {
            Logger.log("Get page Id");
            return getId(context, SESSION_PREFS, PAGE_ID_KEY)
        }

        fun pageIdClear(context: Context) {
            Logger.log("Clear page Id");
            clearId(context, SESSION_PREFS,PAGE_ID_KEY)
        }

    }




}