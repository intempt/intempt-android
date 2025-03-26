package com.intempt.core

import android.content.Context
import android.view.View
import com.google.firebase.FirebaseApp
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.types.ModificationProvider
import kotlinx.serialization.json.JsonObject


object Intempt  {
    private lateinit var component: IntemptCoreComponent
    private lateinit var intemptCore: IntemptCoreService

    lateinit var experiment: ModificationProvider
    lateinit var personalization: ModificationProvider

    fun initialize(context: Context) {
        try{
            component = DaggerIntemptCoreComponent.factory()
                .create(IntemptCoreModule(context));

            component.inject(this);

            intemptCore = component.initService()

            experiment = intemptCore.modification.experimentHandler
            personalization = intemptCore.modification.personalizationHandler

            FirebaseApp.initializeApp(context);
        }
        catch (e:Exception){
            println("Intempt initialization failed")
        }
    }



    fun identify(
        userId: String,
        eventTitle: String? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>?= null,
    ) {
        intemptCore.capture.identify(userId, eventTitle, userAttributes, data)
    }

    fun group(
        accountId: String,
        eventTitle: String? = null,
        accountAttributes: Map<String, String>? = null
    ) {
        intemptCore.capture.group(accountId, eventTitle, accountAttributes)
    }

    fun track( eventTitle: String, data: Map<String, String>) {
        intemptCore.capture.track( eventTitle, data)
    }

    fun record(
        eventTitle: String,
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null
    ) {
        intemptCore.capture.record(
            eventTitle,
            accountId,
            userId,
            accountAttributes,
            userAttributes,
            data
        )
    }

    fun alias(userId: String, anotherUserId: String) {
        intemptCore.capture.alias(userId, anotherUserId)
    }

    fun consent(
        action: String,
        validUntil: Long,
        email: String? = null,
        message: String? = null,
        category: String? = null
    ) {
        intemptCore.capture.consent(
            action,
            validUntil,
            email,
            message,
            category
        )
    }

    fun productAdd(productId:String, quantity:Int){
        intemptCore.capture.productAdd(productId,quantity)
    }

    fun productOrdered(products: List<Map<String, Any>>){
        intemptCore.capture.productOrdered(products)
    }

    fun productView(productId:String){
        intemptCore.capture.productView(productId)
    }

    suspend fun recommendation(id:String, quantity:Int, fields:List<String>, productId:String?): JsonObject? {
        return intemptCore.capture.recommendation(id, quantity, fields, productId)
    }

    fun logOut() {
        intemptCore.capture.logOut()
    }

    fun doNotCaptureText(view: View){
        intemptCore.capture.doNotCaptureText(view)
    }

    object Logging {
        fun start(){
            intemptCore.capture.enableLogging()
        }
        fun stop(){
            intemptCore.capture.disableLogging()

        }
        fun isLoggingEnabled(): Boolean{
            return intemptCore.capture.isLoggingEnabled()
        }

    }

    object Tracking {
        fun start(){
            intemptCore.capture.optIn()
        }
        fun stop() {
            intemptCore.capture.optOut()
        }
        fun isTrackingEnabled(): Boolean{
            return intemptCore.capture.isTrackingEnabled()
        }

    }

}