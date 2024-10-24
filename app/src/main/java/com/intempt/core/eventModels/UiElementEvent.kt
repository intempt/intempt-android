package com.intempt.core.eventModels
import android.view.View
import android.widget.TextView
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.Logger
import com.intempt.core.services.StorageService
import com.intempt.core.services.withTryCatch
import javax.inject.Inject

internal data class UiElementEvent(
    private val view: View
): BaseIntemptEvent() {
    @Inject
    lateinit var config: ConfigManagerService



    private val targetElement = view.javaClass.simpleName
    private val hierarchy = getViewHierarchy()
    private val targetText = getText()
    private val targetClass = view.javaClass.name
//    private val targetId = view.resources.getResourceName(view.id)
//    private val fullTargetId = view.resources.getResourceEntryName(view.id)

    private fun getViewHierarchy(): String {
        try{
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
        catch(e: Exception){
            Logger.error("Error getting view hierarchy: ${e.message}");
            return ""
        }
    }

    private fun getText():String {
        try{
            val text = (view as? TextView)?.text?.toString();
            val disabledText = "****";


            return if (!config.isTextCaptureEnabled && !text.isNullOrEmpty()) {
                disabledText
            } else {
                text ?: ""
            }
        }
        catch (e: Exception){

            Logger.error("Error getting text from view: ${e.message}")
            return ""
        }

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