package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.autocapture.AutoCapture
import com.intempt.intempt_android.autocapture.sessiontracker.SessionTracker
import com.intempt.intempt_android.autocapture.eventModels.SessionUserAttributes
import com.intempt.intempt_android.types.IntemptInitProps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject


class Intempt private constructor()  {
    @Inject
    lateinit var autoCapture: AutoCapture


    companion object {
        @Volatile
        private var instance: Intempt? = null


        fun initialize(context: Context): Intempt = instance ?: synchronized(this) {
            val intemptComponent = DaggerIntemptComponent.builder()
                .intemptModule(IntemptModule(context))
                .build()

            instance ?: Intempt().also {
                    intemptComponent.inject(it)
                    it.init(
                        IntemptInitProps(
                            context = context,
                            config = ConfigManager(context)
                        )
                    )
                }
            }
    }



    private fun init(props: IntemptInitProps) {
        val (context, config) = props
        Logger.log("config: $config")

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
        }
    }
}