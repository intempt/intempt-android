package com.intempt.core.services
import android.util.Log
object  Logger {
    fun log(message: String) {
        Log.i("Intempt", message)
    }

    fun error(message: String) {
       // if (_isTesting) return println(message)
        Log.e("Intempt", message)
    }

}