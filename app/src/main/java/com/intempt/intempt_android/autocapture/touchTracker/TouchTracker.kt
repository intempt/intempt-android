package com.intempt.intempt_android.autocapture.touchTracker

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.children
import androidx.fragment.app.Fragment
import com.intempt.intempt_android.types.DispatchEventProps
import com.intempt.intempt_android.EventPool

import com.intempt.intempt_android.Logger
import javax.inject.Inject


class TouchTracker @Inject constructor(private val eventSrv: EventPool) {
    fun registerTouchEventsForActivity(activity: Activity) {
        activity.window.callback = object : Window.Callback by activity.window.callback {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                Logger.log("AutoCapture | TouchEvents: ${activity.localClassName}")

                val touchedView = event?.let { ev ->
                    val rootView = activity.window.decorView
                    rootView.findViewAtLocation(ev.rawX, ev.rawY)
                }
                Logger.log("AutoCapture | Touched View: ${touchedView?.javaClass?.simpleName}")

                eventSrv.dispatchEvent(
                    DispatchEventProps(
                        eventName = "TouchEvent",
                        entityName="touchEvent",
                        type = "touch",
                        event = null,
                        context = activity,
                        view = touchedView

                    )
                )

                return activity.dispatchTouchEvent(event)

            }
        }
    }

    fun registerTouchEventsForFragment(fragment: Fragment) {
        fragment.view?.setOnTouchListener { view, event ->
            Logger.log("AutoCapture | TouchEvents in Fragment: ${fragment::class.java.simpleName}")

            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = "TouchEvent",
                    entityName="touchEvent",
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

    fun dispatchEvent(props: DispatchEventProps){
        return eventSrv.dispatchEvent(props)
    }

    private fun View.findViewAtLocation(rawX: Float, rawY: Float): View? {
        val location = IntArray(2)
        this.getLocationOnScreen(location)

        val x = rawX.toInt()
        val y = rawY.toInt()

        return if (x >= location[0] && x <= location[0] + this.width &&
            y >= location[1] && y <= location[1] + this.height) {
            this
        } else {
            (this as? ViewGroup)?.children?.mapNotNull {
                it.findViewAtLocation(rawX, rawY)
            }?.firstOrNull()
        }
    }
}