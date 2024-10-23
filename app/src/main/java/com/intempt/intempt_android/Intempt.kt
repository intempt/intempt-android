package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.autocapture.AutoCaptureComponent
import com.intempt.intempt_android.autocapture.sessiontracker.SessionTracker
import com.intempt.intempt_android.configManager.ConfigManagerService
import com.intempt.intempt_android.eventModels.SessionUserAttributes
import com.intempt.sdk.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject


class Intempt private constructor()  {
    @Inject
    lateinit var configManager: ConfigManagerService

    @Inject
    lateinit var autoCapture: AutoCaptureComponent


    companion object {
        @Volatile
        private var instance: Intempt? = null


        fun initialize(context: Context): Intempt = instance ?: synchronized(this) {
            val intemptComponent = DaggerIntemptComponent.factory()
                .create(IntemptModule(context))



            instance ?: Intempt().also {
                intemptComponent.inject(it)
                it.init(context)
            }
        }
    }



    private fun init(context: Context) {
        val job = Job()
        val coroutineScope = CoroutineScope(Dispatchers.Main + job)

        coroutineScope.launch {
            val locationDeferred = async { SessionUserAttributes.getLocationInfo() }
            val sessionDeferred = async {
                SessionTracker.start(null, context);
                StorageHandler.profileIdSet()

            }

            locationDeferred.await()
            sessionDeferred.await()





            Logger.log("Intempt SDK initialized")
            Logger.log("VERSION: ${BuildConfig.sdkVersion}")
        }
    }
}