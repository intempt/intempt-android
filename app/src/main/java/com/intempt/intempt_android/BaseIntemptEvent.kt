package com.intempt.intempt_android

import android.content.Context



open class BaseIntemptEvent() {
    protected val eventId: String = generateId("ev");
    protected val sessionId: String = StorageHandler.sessionIdGet().toString();
    protected val pageId: String = StorageHandler.pageIdGet().toString()
    protected val profileId: String = StorageHandler.profileIdGet().toString();
    protected val timestamp: Long = System.currentTimeMillis();


    fun getEventTime(): Long {
        return timestamp;
    }


}