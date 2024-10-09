package com.intempt.intempt_android

object EventBus {

    fun postEvent(eventName:String) {
        // Notify all listeners

        Logger.log("Received Event: $eventName")
    }

}