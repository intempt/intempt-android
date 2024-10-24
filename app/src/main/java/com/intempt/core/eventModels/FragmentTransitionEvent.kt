package com.intempt.core.eventModels


internal class FragmentTransitionEvent: BaseIntemptEvent() {
    private val visibleFragment = storage.getFragmentName("visibleFragment") ?: ""
    private val addedFragment = storage.getFragmentName("addedFragment") ?: ""
    private val removedFragment = storage.getFragmentName("removedFragment") ?: ""

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