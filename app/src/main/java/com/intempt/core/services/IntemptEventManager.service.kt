package com.intempt.core.services

import android.annotation.SuppressLint
import androidx.core.content.pm.PackageInfoCompat
import android.text.InputType
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
import com.intempt.core.R
import android.content.res.Resources

import com.intempt.core.eventModels.AliasEvent
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.FragmentTransitionEvent
import com.intempt.core.eventModels.GroupEvent
import com.intempt.core.eventModels.IdentifyEvent
import com.intempt.core.eventModels.InstallOrUpgradeEvent
import com.intempt.core.eventModels.ProductEvent
import com.intempt.core.eventModels.RecordEvent
import com.intempt.core.eventModels.ScreenViewEvent
import com.intempt.core.eventModels.SessionEvent
import com.intempt.core.eventModels.SessionUserAttributes
import com.intempt.core.eventModels.TrackEvent
import com.intempt.core.eventModels.UiElementEvent
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.IntemptEventProvider
import com.intempt.core.types.Product
import com.intempt.core.types.RecommendationBody
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal open class IntemptEventManagerService @Inject constructor(
    private val context: Context,
    private val storage: StorageManagerService,
    private val utils: UtilsService,
    private val config: ConfigManagerService
) {
    @SuppressLint("HardwareIds")
    fun generateSessionEventPayload(
        sessionStartEventName: String,
        ipAddress: String,
        city: String,
        region: String,
        country: String,
    ):Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appName = context.applicationInfo?.loadLabel(context.packageManager).toString()
        val appVersion = context.packageManager?.getPackageInfo(context.packageName, 0)?.versionName.toString()
        val appIdentifier = context.packageName
        val androidId = Settings.Secure.getString((context.contentResolver ?: "") as ContentResolver?, Settings.Secure.ANDROID_ID)

        return arrayOf(
            SessionEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                sessionStartEventName = sessionStartEventName,
                deviceName = deviceName,
                appName = appName,
                appVersion = appVersion,
                appIdentifier = appIdentifier,
                androidId = androidId,
                userAttributes = SessionUserAttributes(
                    ipAddress = ipAddress,
                    city = city,
                    region = region,
                    country = country,
                    deviceType = getDeviceType(),
                    carrier = getDeviceCarrier(),
                    platform = getDevicePlatform(),
                )
            )
        )
    }

    fun generateProductEventPayload(products: List<Product>):Array<IntemptEventProvider>{
        return products.map { product ->
            val eventProps = getBaseEventProps()
            ProductEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                productId = product.productId,
                quantity = product.quantity
            )
        }.toTypedArray()
    }


    fun generateFragmentTransitionEventPayload():Array<IntemptEventProvider>?{
        return utils.withTryCatch("Error during generating Fragment Transition payload"){
            val eventProps = getBaseEventProps()

            val visibleFragment = storage.getFragmentName("visibleFragment") ?: ""
            val addedFragment = storage.getFragmentName("addedFragment") ?: ""
            val removedFragment = storage.getFragmentName("removedFragment") ?: ""

            arrayOf(
                FragmentTransitionEvent(
                    eventId = eventProps.eventId,
                    sessionId = eventProps.sessionId,
                    pageId = eventProps.pageId,
                    profileId = eventProps.profileId,
                    visibleFragment = visibleFragment,
                    addedFragment = addedFragment,
                    removedFragment = removedFragment
                )
            )
        }
    }

    fun generateScreenViewEventPayload(activity: Activity, entityName:String):Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()

        val activityName = activity.localClassName;
        val fullActivity = activity.javaClass.name ?: "";
        val screenName = activity.javaClass.name ?: "";
        var timeOnScreen:Long? = null;

        if (entityName == "screenLeave") {
            timeOnScreen = (System.currentTimeMillis() - storage.getPageTime()) / 1000
        }
        return arrayOf(
            ScreenViewEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                activity = activityName,
                fullActivity = fullActivity,
                screenName = screenName,
                timeOnScreen = timeOnScreen
            )
        )
    }

    open fun generateUiElementEventPayload(view: View?): Array<IntemptEventProvider>? {
        return utils.withTryCatch("Error during generating UI payload"){
            val eventProps = getBaseEventProps()
            val targetElement = view?.javaClass?.simpleName ?: ""
            val hierarchy = getViewHierarchy(view)
            val targetText = getViewTextValue(view)
            val targetValue = getViewValue(view)
            val targetClass = view?.javaClass?.name ?: ""
            val targetId = if (view?.id != View.NO_ID) {
                try {
                    view?.resources?.getResourceEntryName(view.id)
                        ?: ""
                } catch (e: Resources.NotFoundException) {
                    "unknown"
                }
            } else {
                "unknown"
            }

            val fullTargetId = if (view?.id != View.NO_ID) {
                try {
                    view?.resources?.getResourceName(view.id)
                        ?: ""
                } catch (e: Resources.NotFoundException) {
                    "unknown"
                }
            } else { "unknown" }


            arrayOf(
                UiElementEvent(
                    eventId = eventProps.eventId,
                    sessionId = eventProps.sessionId,
                    pageId = eventProps.pageId,
                    profileId = eventProps.profileId,
                    targetElement = targetElement,
                    hierarchy = hierarchy ?: "",
                    targetText = targetText ?: "",
                    targetValue = targetValue ?: "",
                    targetClass = targetClass,
                    targetId = targetId,
                    fullTargetId = fullTargetId
                )
            )
        }


    }

    fun generateInstallUpgradeEventPayload(token: String):Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        val currentVersionCode: Long = getCurrentVersionCode();
        val previousVersionCode: Long = storage.getStoredVersionCode();
        val previousBuildType: String = storage.getStoredBuildType() ?: "";
        val currentBuildType: String = getConsumerAppBuildType() ?: "";
        val appVisibilityState: AppVisibilityState = storage.getAppVisibilityState();
        val isUpgrade = currentVersionCode != previousVersionCode || currentBuildType != previousBuildType

        return arrayOf(
            InstallOrUpgradeEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                currentVersionCode = currentVersionCode,
                previousVersionCode = previousVersionCode,
                previousBuildType = previousBuildType,
                currentBuildType = currentBuildType,
                appVisibilityState = appVisibilityState,
                isUpgrade = isUpgrade,
                token = token,
                config = config
            )
        )
    }

    fun generateIdentifyEventPayload(
        userId: String,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ): Array<IntemptEventProvider> {

        val eventProps = getBaseEventProps()

        return arrayOf(
            IdentifyEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                userId = userId,
                userAttributes = userAttributes,
                data = data
            )
        )
    }

    fun generateGroupEventPayload(
        accountId: String,
        accountAttributes: Map<String, String>? = null
    ): Array<IntemptEventProvider> {

        val eventProps = getBaseEventProps()

        return arrayOf(
            GroupEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                accountId = accountId,
                accountAttributes = accountAttributes,
            )
        )
    }

    fun generateRecommendationBody(quantity:Int, fields:List<String>,productId:String?): Map<String, Any?>{
        val map = mutableMapOf<String, Any>();

        map["id"] = storage.getProfileId();
        map["type"] = "profile";
        map["sourceId"] = config.sourceId;
        map["limit"] = quantity;
        map["fields"] = fields;
        productId?.let {
            map["productId"] = it
        }

        return map;
    }

    fun generateTrackEventPayload(
        data: Map<String, String>
    ): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        return arrayOf(
            TrackEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                data = data
            )
        )
    }

    fun generateRecordEventPayload(
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        return arrayOf(
            RecordEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                accountId = accountId,
                userId = userId,
                accountAttributes = accountAttributes,
                userAttributes = userAttributes,
                data = data
            )
        )
    }

    fun generateAliasEventPayload(
        userId: String,
        anotherUserId: String
    ): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()
        return arrayOf(
            AliasEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                userId = userId,
                anotherUserId = anotherUserId
            )
        )
    }

    fun generateConsentEventPayload(
        action: String,
        email: String ? = null,
        message: String ? = null,
        category: String ? = null,
        validUntil: Long,
        sourceId: String,
    ): Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()
        return  arrayOf(
            ConsentEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                action = action,
                email = email,
                message = message,
                category = category,
                sourceId = sourceId,
                validUntil = validUntil,
            )
        )
    }


    private fun getDeviceType(): String {
        return when (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
            Configuration.SCREENLAYOUT_SIZE_SMALL, Configuration.SCREENLAYOUT_SIZE_NORMAL -> "Phone"
            Configuration.SCREENLAYOUT_SIZE_LARGE, Configuration.SCREENLAYOUT_SIZE_XLARGE -> "Tablet"
            else -> "Unknown"
        }
    }

    private fun getDeviceCarrier(): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.networkOperatorName?.toString() ?: ""
    }

    private fun getDevicePlatform(): String {
        return "Android ${android.os.Build.VERSION.RELEASE}"
    }

    private fun getBaseEventProps(): BaseIntemptEvent {
        return BaseIntemptEvent(
            eventId = utils.generateId(IdTypeKeys.EventId.key),
            sessionId = storage.getSessionId(),
            pageId = storage.getPageId(),
            profileId = storage.getProfileId()
        )
    }

    private fun getViewHierarchy(view: View?): String? {
        if(view == null) return ""

        return utils.withTryCatch("Error getting view hierarchy"){
            val hierarchyList = mutableListOf<String>()
            var currentView: View? = view
            while (currentView != null) {
                hierarchyList.add(currentView.javaClass.simpleName)
                currentView = currentView.parent as? View
            }
            hierarchyList.reversed().joinToString(" -> ")
        }
    }

    /**
     * True for password inputs, whose contents must never leave the device.
     *
     * Mirrors ChangeTrackerService.isSensitiveInput. Both sites need it: ChangeTracker
     * decides what to compare for change detection, this decides what to send.
     */
    private fun isSensitiveInput(view: View): Boolean {
        val edit = view as? EditText ?: return false
        val variation = edit.inputType and InputType.TYPE_MASK_VARIATION
        val cls = edit.inputType and InputType.TYPE_MASK_CLASS
        return when {
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_PASSWORD -> true
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> true
            cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }

    private fun getViewTextValue(view: View?):String? {
        if(view == null) return ""
        return utils.withTryCatch("Error getting text from view"){
            val text = (view as? TextView)?.text?.toString()
            val disabledText = "*****"
            val isTextCaptureDisabled = view.getTag(R.id.intemptDoNotCapture) as? Boolean == true

            // A password field is masked unconditionally — not merely when the host app
            // remembered to tag it, and not merely when text capture is switched off.
            // This is the site that builds the event payload, so without the check here a
            // credential still reaches the wire even with ChangeTracker masking in place.
            if (isTextCaptureDisabled || !config.isTextCaptureEnabled || isSensitiveInput(view)) {
                disabledText
            } else {
                text ?: ""
            }
        }
    }

    private fun getViewValue(view: View?): String? {
        if(view == null) return ""
        val disabledText = "*****"
        val isTextCaptureDisabled = view.getTag(R.id.intemptDoNotCapture) as? Boolean == true
        return if (isTextCaptureDisabled || !config.isTextCaptureEnabled) {
            disabledText
        } else {
             utils.withTryCatch("Error getting value from view"){
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
                    // getHour/getMinute are API 23, which is now minSdk, so no guard is needed.
                    is TimePicker -> String.format(Locale.US, "%02d:%02d", view.hour, view.minute)
                    is ListView -> view.selectedItem?.toString() ?: ""
                    else -> ""
                }
            }
        }
    }

    private fun getCurrentVersionCode(): Long {
        // PackageInfoCompat, not .longVersionCode: the latter is API 28.
        return PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }

    private fun getConsumerAppBuildType(): String? {
        return try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val buildTypeField = buildConfigClass.getField("BUILD_TYPE")
            buildTypeField.get(null) as String
        } catch (e: Exception) {
            null
        }
    }

}