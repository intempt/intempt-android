package com.intempt.core.customCapture

import android.view.View
import android.view.ViewGroup
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
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.AutoCaptureParam
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.EventType
import com.intempt.core.types.Product
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureComponent @Inject constructor(
    private val srv: CustomCaptureService,
    private val config: ConfigManagerService,
    private val eventPool: EventPoolManagerService,
    private val intemptEvent: IntemptEventManagerService
) {

    fun isLoggingEnabled(): Boolean {
        return config.isLoggingEnabled
    }

    fun enableLogging(){
        srv.logger.log("Invoke enableLogging")
        config.isLoggingEnabled = true
    }

    fun disableLogging(){
        srv.logger.log("Invoke disableLogging")
        config.isLoggingEnabled = false
    }

    fun optIn(){
        srv.logger.log("Invoke optIn")
        config.isUserOptIn = true
    }

    fun optOut(){
        srv.logger.log("Invoke optOut")
        config.isUserOptIn = false
    }

    fun doNotCaptureText(view: View){
        val canUse = view is EditText
                || view is Spinner
                || view is ToggleButton
                || view is CheckBox
                || view is RadioButton
                || view is CompoundButton
                || view is TextView
                || view is SeekBar
                || view is RatingBar
                || view is TimePicker
                || view is DatePicker
                || view is ListView

        if (canUse) {
            srv.setDoNotCaptureTag(view)
        }
        else{
            srv.logger.error("Can't accept view of type ${view.javaClass.name}. Supported types are: EditText, Spinner, ToggleButton, CheckBox, RadioButton, CompoundButton, TextView, SeekBar, RatingBar, TimePicker, DatePicker, ListView.")
        }
    }


    fun identify(
         userId: String,
         eventTitle: String? = null,
         userAttributes: Map<String, String>? = null,
         data: Map<String, String>? = null,
    ) {
        if (!config.isUserOptIn) return
        if (!srv.isIdentifyValid(userId,eventTitle,userAttributes)) return

        srv.logger.log("Invoke identify")

         val newEvent = IntemptEvent(
             name = eventTitle ?: "Identify",
             type = EventType.Identify.value,
             payload = intemptEvent.generateIdentifyEventPayload(
                 userId,
                 userAttributes,
                 data
             )
         )

         eventPool.emitEvent(newEvent)
    }

    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null
    ) {
        if (!config.isUserOptIn) return
        if (!srv.isGroupValid(accountId,eventTitle,accountAttributes)) return

        srv.logger.log("Invoke group")

        val newEvent = IntemptEvent(
            name = eventTitle ?: "Identify",
            type = EventType.Group.value,
            payload = intemptEvent.generateGroupEventPayload(
                accountId,
                accountAttributes
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun track(eventTitle: String,data: Map<String, String>){
        if (!config.isUserOptIn) return
        if (!srv.isTrackValid(eventTitle)) return

        srv.logger.log("Invoke track")

        val newEvent = IntemptEvent(
            name = eventTitle,
            type = EventType.Track.value,
            payload = intemptEvent.generateTrackEventPayload(data)
        )

        eventPool.emitEvent(newEvent)
    }

    fun record(
        eventTitle: String,
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ){
        if (!config.isUserOptIn) return
        if (!srv.isTrackValid(eventTitle)) return

        srv.logger.log("Invoke record")

        val newEvent = IntemptEvent(
            name = eventTitle,
            type = EventType.Record.value,
            payload = intemptEvent.generateRecordEventPayload(
                accountId,
                userId,
                accountAttributes,
                userAttributes,
                data
            )
        )

        eventPool.emitEvent(newEvent)
    }

    fun alias(userId: String, anotherUserId: String){
        if (!config.isUserOptIn) return

        srv.logger.log("Invoke alias")

        val newEvent = IntemptEvent(
            name = "Identify",
            type = EventType.Alias.value,
            payload = intemptEvent.generateAliasEventPayload(userId, anotherUserId)
        )

        eventPool.emitEvent(newEvent)
    }

    fun consent(
        action: String,
        validUntil: Long,
        email: String ? = null,
        message: String ? = null,
        category: String ? = null,
    ){
        if (!config.isUserOptIn) return
        if (!srv.isConsentValid(action)) return

        srv.logger.log("Invoke consent")

        val newEvent = IntemptEvent(
            name = "Consent",
            type = EventType.Consent.value,
            payload = intemptEvent.generateConsentEventPayload(
                action,
                email,
                message,
                category,
                validUntil,
                config.sourceId
            )
        )

        eventPool.emitEvent(newEvent)
    }


    fun logOut(){
        if (!config.isUserOptIn) return
        srv.logger.log("Invoke logOut")
        srv.logoutHandler()
    }

    fun productAdd(productId:String, quantity:Int){
        if (!config.isUserOptIn) return
        srv.logger.log("Invoke productAdd")
        val newEvent = IntemptEvent(
            name = "Added to cart",
            type = EventType.Product.value,
            payload = intemptEvent.generateProductEventPayload(
                listOf(
                    Product(productId, quantity)
                )
            )
        )
        eventPool.emitEvent(newEvent)
    }

    fun productOrdered(products: List<Map<String, Any>>){
        if (!config.isUserOptIn) return
        if(!srv.isProductListValid(products)) {
            srv.logger.error("Each element should have productId and quantity")
            return
        }

        val checkedProduct = products.map { product ->
             Product(
                productId = product["productId"] as String,
                quantity = product["quantity"] as Int
            )
        }


        srv.logger.log("Invoke productOrdered")
        val newEvent = IntemptEvent(
            name = "Product ordered",
            type = EventType.Product.value,
            payload = intemptEvent.generateProductEventPayload(
                checkedProduct
            )
        )
        eventPool.emitEvent(newEvent)
    }

    fun productView(productId:String){
        if (!config.isUserOptIn) return
        srv.logger.log("Invoke productView")
        val newEvent = IntemptEvent(
            name = "Product viewed",
            type = EventType.Product.value,
            payload = intemptEvent.generateProductEventPayload(
                listOf(Product(productId))
            )
        )
        eventPool.emitEvent(newEvent)
    }
}





