package com.intempt.core.eventModels
import com.intempt.core.services.StorageService
import com.intempt.core.services.generateId
import javax.inject.Inject


internal open class BaseIntemptEvent {
    @Inject
    lateinit var storage: StorageService


    protected val eventId: String = generateId("ev");
    protected val sessionId: String = storage.sessionIdGet().toString();
    protected val pageId: String = storage.pageIdGet().toString()
    protected val profileId: String = storage.profileIdGet().toString();
    protected val timestamp: Long = System.currentTimeMillis();


    fun getEventTime(): Long {
        return timestamp;
    }


}