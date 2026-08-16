package com.intempt.core.types

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

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
