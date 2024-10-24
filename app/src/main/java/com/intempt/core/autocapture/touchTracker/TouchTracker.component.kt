package com.intempt.core.autocapture.touchTracker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.core.autocapture.BaseAutoCaptureComponent
import com.intempt.core.services.debounce
import com.intempt.core.types.Constants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TouchTrackerComponent @Inject constructor(
    private val srv: TouchTrackerService,
): BaseAutoCaptureComponent() {
    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        registerForActivity(activity)
    }

    override fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?
    ) {
        registerForFragment(fragment)
    }



    private val debounceDelay = Constants.DEBOUNCE_DELAY
    private val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)
    private val handler = Handler(Looper.getMainLooper())

    private fun registerForActivity(activity: Activity) {
        srv.setupHandler(handler, runnableWrapper) { handler, runnableWrapper ->
            val originalCallback = activity.window.callback;
            activity.window.callback = object : Window.Callback by originalCallback {
                override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                    if (event?.action == MotionEvent.ACTION_UP) {
                        val touchedView = event.let { ev ->
                            val rootView = activity.window.decorView
                            rootView.findViewAtLocation(ev.rawX, ev.rawY)
                        }

                        runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                            srv.logAndDispatch(touchedView, activity, "Activity")
                        }
                    }

                    return originalCallback.dispatchTouchEvent(event)
                }
            }
        }
    }

    private fun registerForFragment(fragment: Fragment) {
        srv.setupHandler(handler, runnableWrapper) { handler, runnableWrapper ->
            fragment.view?.setOnTouchListener { view, event ->
                runnableWrapper[0] = debounce(handler, debounceDelay, runnableWrapper[0]) {
                    srv.logAndDispatch(view, fragment.requireActivity(), "Fragment")

                    if (event?.action == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                }
                // Return false to allow normal event processing to continue
                false
            }
        }
    }

    private fun View.findViewAtLocation(rawX: Float, rawY: Float): View? {
        val location = IntArray(2)
        this.getLocationOnScreen(location)

        val x = rawX.toInt()
        val y = rawY.toInt()

        return if (
                x >= location[0] && x <= location[0] + this.width &&
                y >= location[1] && y <= location[1] + this.height
            ) { this }
            else {
                (this as? ViewGroup)?.children
                    ?.mapNotNull { it.findViewAtLocation(rawX, rawY) }
                    ?.firstOrNull()
            }
    }


    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {}

    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {}

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {}


}