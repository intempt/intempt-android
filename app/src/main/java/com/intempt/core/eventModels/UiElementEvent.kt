package com.intempt.core.eventModels
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TimePicker
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.LoggerManagerService
import java.util.Locale


internal data class UiElementEvent(
    private val view: View,
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
): BaseIntemptEvent() {
    private val targetElement = view.javaClass.simpleName
    private val hierarchy = getViewHierarchy()
    private val targetText = getText()
    private val targetValue = getViewValue()
    private val targetClass = view.javaClass.name
    private val targetId = view.resources.getResourceEntryName(view.id)
    private val fullTargetId  = view.resources.getResourceName(view.id)



    private fun getViewHierarchy(): String {
        try{
            val hierarchyList = mutableListOf<String>()
            var currentView: View? = view
            while (currentView != null) {
                hierarchyList.add(currentView.javaClass.simpleName)
                currentView = currentView.parent as? View
            }
            return hierarchyList.reversed().joinToString(" -> ")
        }
        catch(e: Exception){
            logger.error("Error getting view hierarchy: ${e.message}")
            return ""
        }
    }

    private fun getText():String {
        try{
            val text = (view as? TextView)?.text?.toString();
            val disabledText = "*****";

            return if (!config.isTextCaptureEnabled) {
                disabledText
            } else {
                text ?: ""
            }
        }
        catch (e: Exception){

            logger.error("Error getting text from view: ${e.message}")
            return ""
        }

    }

    private fun getViewValue(): String {
        val disabledText = "*****";
        return if (!config.isTextCaptureEnabled) {
            disabledText
        } else {
            try {
                when (view) {
                    is CheckBox,
                    is RadioButton,
                    is ToggleButton,
                    is CompoundButton -> (view as CompoundButton).isChecked.toString()
                    is SeekBar -> view.progress.toString()
                    is Spinner -> view.selectedItem?.toString() ?: ""
                    is EditText -> view.text.toString()
                    is DatePicker -> "${view.month}-${view.dayOfMonth}-${view.year}"
                    is RatingBar -> view.rating.toString()
                    is TimePicker -> String.format(Locale("en", "US"),"%02d:%02d", view.hour, view.minute)
                    is ListView -> view.selectedItem?.toString() ?: ""
                    else -> ""
                }
            } catch (e: Exception) {
                logger.error("Error getting value from view: ${e.message}")
                ""
            }
        }
    }


    override fun toFormatted(): Map<String, Any> {
        val baseFormatted = super.toFormatted()


        return baseFormatted + mapOf(
            "data" to mapOf(
                "targetElement" to targetElement,
                "hierarchy" to hierarchy,
                "targetValue" to targetValue,
                "targetText" to targetText,
                "targetClass" to targetClass,
                "targetId" to targetId,
                "fullTargetId" to fullTargetId,
            )
        )
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
                    targetValue: ${targetValue},
                    targetId: ${targetId},
                    fullTargetId: ${fullTargetId},
                },
            }
        """
        return output.trimIndent()
    }
}