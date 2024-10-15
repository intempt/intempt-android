package com.intempt.intempt_android.autocapture.screenTracker

import android.app.Activity
import com.intempt.intempt_android.BaseIntemptEvent
import com.intempt.intempt_android.StorageHandler

class FragmentTransitionEvent(activity:Activity): BaseIntemptEvent(activity) {

    private val visibleFragment = StorageHandler.getFragmentName(activity,"visibleFragment") ?: ""
    private val addedFragment = StorageHandler.getFragmentName(activity,"addedFragment") ?: ""
    private val removedFragment = StorageHandler.getFragmentName(activity,"removedFragment") ?: ""

    override fun toString(): String {

        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    visibleFragment: ${visibleFragment},
                    addedFragment: ${addedFragment},
                    removedFragment: ${removedFragment},
                },
            }
        """
        return output.trimIndent()
    }
}