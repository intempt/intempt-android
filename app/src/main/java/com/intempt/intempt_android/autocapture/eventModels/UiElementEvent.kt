package com.intempt.intempt_android.autocapture.eventModels
import android.view.View
import android.widget.TextView
import com.intempt.intempt_android.BaseIntemptEvent

data class UiElementEvent(private val view: View): BaseIntemptEvent() {

    private val targetElement = view.javaClass.simpleName
    private val hierarchy = getViewHierarchy()
    private val targetText = (view as? TextView)?.text?.toString() ?: ""
    private val targetClass = view.javaClass.name
//    private val targetId = view.resources.getResourceName(view.id)
//    private val fullTargetId = view.resources.getResourceEntryName(view.id)

    private fun getViewHierarchy(): String {
        val hierarchy = StringBuilder()
        var currentView: View? = view
        while (currentView != null) {
            hierarchy.append(currentView.javaClass.simpleName)
            currentView = currentView.parent as? View
            if (currentView != null) {
                hierarchy.append(" -> ")
            }
        }
        return hierarchy.toString()
    }

    override fun toString(): String {
        val output = """
            {
                sessionId: $sessionId,
                eventId: $eventId,
                pageId: $pageId,
                profileId: $profileId,
                timestamp: $timestamp,
                data: {
                    targetElement: ${targetElement},
                    hierarchy: ${hierarchy},
                    targetText: ${targetText},
                    targetClass: ${targetClass},

                },
            }
        """
        return output.trimIndent()
    }
}

//targetId: ${targetId},
//fullTargetId: ${fullTargetId},