package com.intempt.core.services
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoggerManagerService  @Inject constructor(
    private val config: ConfigManagerService
){
    fun log(message: String) {
        if(!config.isLoggingEnabled) return
        Log.i("Intempt", message)
    }

    fun error(message: String) {
        if(!config.isLoggingEnabled) return
        Log.e("Intempt", message)
    }

}