package com.intempt.intempt_android

import android.content.Context



open class BaseIntemptEvent(context: Context) {



    protected val eventId: String = generateId("ev");
    protected val sessionId: String = context.let { StorageHandler.sessionIdGet(it).toString() };
    protected val pageId: String = context.let { StorageHandler.pageIdGet(it).toString() }
    protected val profileId: String = context.let { StorageHandler.profileIdGet(it).toString() };
    protected val timestamp: Long = System.currentTimeMillis();


    fun getEventTime(): Long {
        return timestamp;
    }


}