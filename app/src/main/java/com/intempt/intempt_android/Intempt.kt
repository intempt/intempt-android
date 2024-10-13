package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.sessiontracker.SessionEvent
import com.intempt.intempt_android.sessiontracker.SessionUserAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


class Intempt private constructor()  {
    companion object {
        @Volatile
        private var instance: Intempt? = null

        /**
         * Initializes the Intempt SDK.
         *
         * @param context The application context.
         * @return The singleton instance of Intempt.
         */
        fun initialize(context: Context): Intempt = instance ?: synchronized(this) {
            val config = ConfigManager(context)
            instance ?: Intempt().also {
                    it.init(
                        context,
                        config
                    )
                }
            }
    }


    /**
     * Performs the actual initialization logic.
     *
     * @param context The application context.
     */
    private fun init(context: Context, config: ConfigManager) {

        val job = Job()
        val coroutineScope = CoroutineScope(Dispatchers.Main + job)

        coroutineScope.launch {
            val locationDeferred = async { SessionUserAttributes.getLocationInfo() }
            val sessionDeferred = async {
                StorageHandler.sessionIdSet(context)
                StorageHandler.profileIdSet(context)
                StorageHandler.pageIdSet(context)
            }

            locationDeferred.await()
            sessionDeferred.await()

            val sessionEvent = SessionEvent(context);

            Logger.log("config: $config")
            Logger.log("SessionEvent: $sessionEvent")
            Logger.log("Intempt SDK initialized")
        }
    }
}