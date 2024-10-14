package com.intempt.intempt_android.autocapture.touchTracker

import android.app.Activity
import android.view.MotionEvent
import android.view.Window
import androidx.fragment.app.Fragment
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger

class TouchTracker {

    fun registerTouchEventsForActivity(activity: Activity) {
        activity.window.callback = object : Window.Callback by activity.window.callback {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                Logger.log("AutoCapture | TouchEvents: ${activity.localClassName}")

                EventBus.dispatchEvent(
                    DispatchEventProps(
                        eventName = "TouchEvent",
                        type = "touch",
                        event = null,
                        context = activity
                    )
                )

                return activity.dispatchTouchEvent(event)
               // return@dispatchTouchEvent true
            }
        }
    }

    fun registerTouchEventsForFragment(fragment: Fragment) {
        fragment.view?.setOnTouchListener { view, event ->
            Logger.log("AutoCapture | TouchEvents in Fragment: ${fragment::class.java.simpleName}")

            EventBus.dispatchEvent(
                DispatchEventProps(
                    eventName = "TouchEvent",
                    type = "touch",
                    event = null,
                    context = fragment.requireActivity()
                )
            )

            if (event?.action == MotionEvent.ACTION_UP) {
                // Call performClick for accessibility
                view.performClick()
            }

            view.onTouchEvent(event)
            return@setOnTouchListener true
        }
    }


}