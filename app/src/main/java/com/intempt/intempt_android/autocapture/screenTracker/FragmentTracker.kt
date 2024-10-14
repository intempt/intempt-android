package com.intempt.intempt_android.autocapture.screenTracker

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.autocapture.touchTracker.TouchTracker

class FragmentTracker(touchTracker: TouchTracker) : FragmentManager.FragmentLifecycleCallbacks() {

    private var _touchTracker:TouchTracker? = null;

    init {
        _touchTracker = touchTracker
    }

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentResumed(fm, fragment)

        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen view",
                type = "screen",
                event = null,
                context = fragment.requireActivity()
            )
        )

        Logger.log("AutoCapture | Fragment viewed: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentPaused(fm, fragment)

        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Screen leave",
                type = "fragment",
                event = null,
                context = fragment.requireActivity()
            )
        )

        Logger.log("AutoCapture | Fragment leave: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
        _touchTracker?.registerTouchEventsForFragment(fragment)
    }

}