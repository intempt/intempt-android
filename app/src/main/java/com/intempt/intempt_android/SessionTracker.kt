package com.intempt.intempt_android



class SessionTracker {
    private var _id: String = generateId("ses");

    var id: String
        get() = _id
        set(value) {
            _id = value
        }



    fun start(){

        EventBus.postEvent("Session started")
    }

    fun end(){

        EventBus.postEvent("Session end")

    }

}