package com.intempt.intempt_android
import android.util.Log
class Logger {
    companion object {
        fun log(message: String) {
            Log.i("Intempt", message)
        }

        fun error(message: String) {
            Log.e("Intempt", message)
        }
    }
}