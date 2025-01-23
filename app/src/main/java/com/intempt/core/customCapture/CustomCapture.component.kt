package com.intempt.core.customCapture

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
import com.intempt.core.eventModels.IntemptEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.DefaultConfigs
import com.intempt.core.types.EventType
import com.intempt.core.types.Product
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureComponent @Inject constructor(
    private val srv: CustomCaptureService,
    private val config: ConfigManagerService,
    private val eventPool: EventPoolManagerService,
    private val intemptEvent: IntemptEventManagerService,
    private val utils: UtilsService
) {

    fun isLoggingEnabled(): Boolean {
        return utils.withTryCatch("isLoggingEnabled fails"){
            config.isLoggingEnabled
        } ?: DefaultConfigs.IsLoggingEnabled.value
    }

    fun isTrackingEnabled(): Boolean {
        return utils.withTryCatch("isTrackingEnabled fails"){
            config.isUserOptIn
        } ?: DefaultConfigs.IsUserOptIn.value
    }

    fun enableLogging(){
        utils.withTryCatch("disableLogging fails"){
            srv.logger.log("Invoke enableLogging")
            config.isLoggingEnabled = true
        }
    }

    fun disableLogging(){
        utils.withTryCatch("disableLogging fails"){
            srv.logger.log("Invoke disableLogging")
            config.isLoggingEnabled = false
        }
    }

    fun optIn(){
        utils.withTryCatch("optIn fails"){
            srv.logger.log("Invoke optIn")
            config.isUserOptIn = true
            srv.logger.log("isTrackingEnabled ${isTrackingEnabled()}")
        }
    }

    fun optOut(){
        utils.withTryCatch("optOut fails"){
            srv.logger.log("Invoke optOut")
            config.isUserOptIn = false
            srv.logger.log("isTrackingEnabled ${isTrackingEnabled()}")
        }
    }

    fun doNotCaptureText(view: View){
        utils.withTryCatch("doNotCaptureText fails"){
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
    }


    fun identify(
         userId: String,
         eventTitle: String? = null,
         userAttributes: Map<String, String>? = null,
         data: Map<String, String>? = null,
    ) {
        utils.withTryCatch("identify fails"){
            if (!config.isUserOptIn) return@withTryCatch
            if (!srv.isIdentifyValid(userId,eventTitle,userAttributes)) return@withTryCatch

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
    }

    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null
    ) {
        utils.withTryCatch("group fails"){
            if (!config.isUserOptIn) return@withTryCatch
            if (!srv.isGroupValid(accountId,eventTitle,accountAttributes)) return@withTryCatch

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
    }

    fun track(eventTitle: String,data: Map<String, String>){
        utils.withTryCatch("track fails"){
            if (!config.isUserOptIn) return@withTryCatch
            if (!srv.isTrackValid(eventTitle)) return@withTryCatch

            srv.logger.log("Invoke track")

            val newEvent = IntemptEvent(
                name = eventTitle,
                type = EventType.Track.value,
                payload = intemptEvent.generateTrackEventPayload(data)
            )

            eventPool.emitEvent(newEvent)
        }

    }

    fun record(
        eventTitle: String,
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ){
        utils.withTryCatch("record fails"){
            if (!config.isUserOptIn) return@withTryCatch
            if (!srv.isTrackValid(eventTitle)) return@withTryCatch

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

    }

    fun alias(userId: String, anotherUserId: String){
        utils.withTryCatch("alias fails"){
            if (!config.isUserOptIn) return@withTryCatch

            srv.logger.log("Invoke alias")

            val newEvent = IntemptEvent(
                name = "Identify",
                type = EventType.Alias.value,
                payload = intemptEvent.generateAliasEventPayload(userId, anotherUserId)
            )

            eventPool.emitEvent(newEvent)
        }

    }

    fun consent(
        action: String,
        validUntil: Long,
        email: String ? = null,
        message: String ? = null,
        category: String ? = null,
    ){
        utils.withTryCatch("consent fails"){
            if (!config.isUserOptIn) return@withTryCatch
            if (!srv.isConsentValid(action)) return@withTryCatch

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

    }


    fun logOut(){
        utils.withTryCatch("logOut fails"){
            if (!config.isUserOptIn) return@withTryCatch
            srv.logger.log("Invoke logOut")
            srv.logoutHandler()
        }
    }

    fun productAdd(productId:String, quantity:Int){
        utils.withTryCatch("productAdd fails"){
            if (!config.isUserOptIn) return@withTryCatch
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
    }

    fun productOrdered(products: List<Map<String, Any>>){
        utils.withTryCatch("productOrdered fails"){
            if (!config.isUserOptIn) return@withTryCatch
            if(!srv.isProductListValid(products)) {
                srv.logger.error("Each element should have productId and quantity")
                return@withTryCatch
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
    }

    fun productView(productId:String){
        utils.withTryCatch("productView fails"){
            if (!config.isUserOptIn) return@withTryCatch
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

    suspend fun recommendation(id:String, quantity:Int, fields:List<String>, productId:String?): JsonObject? {
        return eventPool.getFeedData(id, quantity, fields, productId)
    }
}





