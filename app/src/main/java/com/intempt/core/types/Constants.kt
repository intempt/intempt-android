package com.intempt.core.types

object Constants {
    object CHANGE {
        val EVENT_NAME = "Change event"
        val ENTITY_NAME = "changeEvent"
        val EVENT_TYPE = "change"
    }

    object TOUCH {
        val EVENT_NAME = "Touch event"
        val ENTITY_NAME = "touchEvent"
        val EVENT_TYPE = "touch"
    }

    object SCREEN {
        object FRAGMENT {
            val EVENT_NAME = "Fragment transition"
            val ENTITY_NAME = "fragmentTransition"
            val EVENT_TYPE = "fragment"
            val VIEW_TYPE = "Fragment"
        }

        object ACTIVITY {
            val VIEW_EVENT_NAME = "View screen"
            val LEAVE_EVENT_NAME = "Leave screen"
            val VIEW_ENTITY_NAME = "screenView"
            val LEAVE_ENTITY_NAME = "screenLeave"
            val EVENT_TYPE = "screen"
            val VIEW_TYPE = "Activity"
        }
    }

    object SESSION {
        val EVENT_NAME = "Session start"
        val ENTITY_NAME = "sessionStart"
        val EVENT_TYPE = "session"
        val MINUTE_STEP = 30
        val SECONDS_PER_MINUTE = 60
        val MILLISECONDS_PER_SECOND = 1000L
        val SESSION_TIMEOUT = MINUTE_STEP * SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND
    }

    object InstallUpgrade {
        val EVENT_NAME = "App install/upgrade"
        val ENTITY_NAME = "installUpgrade"
        val EVENT_TYPE = "installOrUpgrade"
    }

    val DEBOUNCE_DELAY = 320L

    // val API_URL = "https://api.staging.intempt.com/v1"
    val API_URL = "https://api.intempt.com"

    val SUCCESS_CODE = 200
}
