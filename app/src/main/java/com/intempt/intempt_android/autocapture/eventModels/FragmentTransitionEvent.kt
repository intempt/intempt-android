package com.intempt.intempt_android.autocapture.eventModels
import com.intempt.intempt_android.StorageHandler

class FragmentTransitionEvent: BaseIntemptEvent() {

    private val visibleFragment = StorageHandler.getFragmentName("visibleFragment") ?: ""
    private val addedFragment = StorageHandler.getFragmentName("addedFragment") ?: ""
    private val removedFragment = StorageHandler.getFragmentName("removedFragment") ?: ""

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