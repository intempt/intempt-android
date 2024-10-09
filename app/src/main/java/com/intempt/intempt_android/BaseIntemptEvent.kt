package com.intempt.intempt_android

import android.content.Context

open class BaseIntemptEvent(context:Context) {
    protected val eventId: String = generateId("ev");
    protected val sessionId: String = StorageHandler.sessionIdGet(context).toString();
    protected val pageId: String = StorageHandler.pageIdGet(context).toString()
    protected val profileId: String = StorageHandler.profileIdGet(context).toString();
    protected val timestamp: Long = System.currentTimeMillis();
}