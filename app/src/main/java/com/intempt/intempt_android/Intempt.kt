package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.sessiontracker.SessionEvent



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
        fun initialize(
            context: Context,
            apiKey: String,
            sourceId: String,
            organizationId: String,
            projectId: String
        ): Intempt =
            instance ?: synchronized(this) {
                instance ?: Intempt().also {
                    it.init(
                        context,
                        apiKey,
                        sourceId,
                        organizationId,
                        projectId
                    )
                }
            }
    }


    /**
     * Performs the actual initialization logic.
     *
     * @param context The application context.
     */
    private fun init(
        context: Context,
        apiKey: String,
        sourceId: String,
        organizationId: String,
        projectId: String
    ) {


        StorageHandler.sessionIdSet(context)
        StorageHandler.profileIdSet(context)
        StorageHandler.pageIdSet(context)

        val sessionEvent = SessionEvent(context)


        Logger.log("$sessionEvent")

        Logger.log("Intempt SDK initialized")
        Logger.log("Intempt config props are: apiKey: $apiKey, sourceId: $sourceId, organizationId: $organizationId, projectId: $projectId")
    }
}