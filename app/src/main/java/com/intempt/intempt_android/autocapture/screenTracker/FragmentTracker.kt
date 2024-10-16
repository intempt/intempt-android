package com.intempt.intempt_android.autocapture.screenTracker

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.intempt_android.DispatchEventProps
import com.intempt.intempt_android.EventBus
import com.intempt.intempt_android.Logger
import com.intempt.intempt_android.StorageHandler
import com.intempt.intempt_android.autocapture.touchTracker.TouchTracker

class FragmentTracker(private val touchTracker: TouchTracker) : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentAttached(fm: FragmentManager, fragment: Fragment, context: Context) {
        super.onFragmentAttached(fm, fragment, context);

        val key = "addedFragment"
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )
    }

    override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentResumed(fm, fragment);

        val key = "visibleFragment"
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )

        EventBus.dispatchEvent(
            DispatchEventProps(
                eventName = "Fragment transition",
                entityName="fragmentTransition",
                type = "fragment",
                event = null,
                context = fragment.requireActivity()
            )
        )

        Logger.log("AutoCapture | onFragmentResumed: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentDetached(fm, fragment);

        val key = "removedFragment";
        StorageHandler.saveFragmentName(
            key,
            fragment,
        )
        Logger.log("AutoCapture | onFragmentDetached: ${fragment::class.java.simpleName}")
    }



    override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
        super.onFragmentPaused(fm, fragment)

        Logger.log("AutoCapture | onFragmentPaused: ${fragment::class.java.simpleName}")
    }

    override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?) {
        touchTracker.registerTouchEventsForFragment(fragment)
    }


}