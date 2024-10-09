package com.intempt.intempt_android.sessiontracker

import com.intempt.intempt_android.EventBus


class SessionTracker {

    companion object{
        fun initializer(){}



    }




    fun start(){
        EventBus.postEvent("Session started")
    }

    fun end(){

        EventBus.postEvent("Session end")

    }

}