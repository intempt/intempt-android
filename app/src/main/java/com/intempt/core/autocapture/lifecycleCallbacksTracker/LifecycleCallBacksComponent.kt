package com.intempt.core.autocapture.lifecycleCallbacksTracker
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.intempt.core.queue.DeliveryMessages
import javax.inject.Singleton

@Singleton
internal class LifecycleCallBacksComponent(
    private val srv: LifecycleCallbackService,
    private val delivery: DeliveryMessages,
) : Application.ActivityLifecycleCallbacks,
    FragmentManager.FragmentLifecycleCallbacks() {
    override fun onActivityResumed(activity: Activity) {
        srv.handleScreenView(activity)
        srv.registerTouchEventListener(activity)
        srv.registerChangeEventListener(activity)

        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        srv.handleScreenLeave(activity)
        if (activity is AppCompatActivity) {
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(this)
        }

        // Flush when the app goes to the background.
        //
        // Nothing called DeliveryMessages.flush() anywhere in the SDK, so the only triggers
        // were the 60-second timer and the 40-event bulk limit. A short session therefore
        // kept its last events on disk until the NEXT launch, and if the user never came back
        // — or uninstalled — they were never sent at all. Durable is not the same as
        // delivered. Mixpanel flushes on background for exactly this reason.
        //
        // flush() only posts a message to the worker, so this does not block the UI thread.
        delivery.flush()
    }

    override fun onActivityDestroyed(activity: Activity) {}

    override fun onFragmentResumed(
        fm: FragmentManager,
        fragment: Fragment,
    ) {
        srv.handleFragmentVisibility(fragment)
        srv.registerChangeEventListener(fragment.requireActivity())
        srv.registerTouchEventListener(fragment.requireActivity())
    }

    override fun onFragmentAttached(
        fm: FragmentManager,
        fragment: Fragment,
        context: Context,
    ) {
        srv.handleFragmentAdd(fragment)
    }

    override fun onFragmentDetached(
        fm: FragmentManager,
        fragment: Fragment,
    ) {
        srv.handleFragmentRemove(fragment)
    }

    override fun onFragmentPaused(
        fm: FragmentManager,
        fragment: Fragment,
    ) {
        srv.handleFragmentRemove(fragment)
    }

    override fun onFragmentStopped(
        fm: FragmentManager,
        fragment: Fragment,
    ) {
        srv.handleFragmentRemove(fragment)
    }

    override fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?,
    ) {
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}
}
