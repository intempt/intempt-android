package com.intempt.core.types

object Constants {
    object CHANGE  {
        val EVENT_NAME = "Change Event"
        val ENTITY_NAME = "changeEvent"
        val EVENT_TYPE = "change"
    }

    object TOUCH {
        val EVENT_NAME = "Touch Event"
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
            val VIEW_EVENT_NAME = "Screen view"
            val LEAVE_EVENT_NAME = "Screen leave"
            val VIEW_ENTITY_NAME = "screenView"
            val LEAVE_ENTITY_NAME = "screenLeave"
            val EVENT_TYPE = "screen"
            val VIEW_TYPE = "Activity"
        }


    }

    object INSTALL_UPGRADE {
        val EVENT_NAME = "App install/upgrade"
        val ENTITY_NAME = "installUpgrade"
        val EVENT_TYPE = "installOrUpgrade"

    }

    val DEBOUNCE_DELAY = 320L

}