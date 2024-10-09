package com.intempt.intempt_android

import android.content.Context


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
        fun initialize(context: Context): Intempt =
            instance ?: synchronized(this) {
                instance ?: Intempt().also { it.init(context) }
            }
    }


    /**
     * Performs the actual initialization logic.
     *
     * @param context The application context.
     */
    private fun init(context: Context) {
        val session = SessionTracker();
        session.start()

        Logger.log("Intempt SDK initialized")
    }
}