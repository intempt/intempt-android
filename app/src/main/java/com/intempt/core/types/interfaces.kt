package com.intempt.core.types

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

internal interface IntemptEventProvider {
    val eventId: String
    val sessionId: String
    val pageId: String
    val profileId: String
    val timestamp: Long

    fun getEventTime(): Long

    fun toFormated(): Map<String, Any?>
}

// For activity lifecycle events
internal interface ActivityLifecycleListener {
    fun onActivityResumed(activity: Activity)

    fun onActivityPaused(activity: Activity)
}

// For fragment lifecycle events
internal interface FragmentLifecycleListener {
    fun onFragmentViewCreated(
        fm: FragmentManager,
        fragment: Fragment,
        view: View,
        savedInstanceState: Bundle?,
    )

    fun onFragmentResumed(
        fm: FragmentManager,
        fragment: Fragment,
    )

    fun onFragmentAttached(
        fm: FragmentManager,
        fragment: Fragment,
        context: Context,
    )

    fun onFragmentDetached(
        fm: FragmentManager,
        fragment: Fragment,
    )
}
