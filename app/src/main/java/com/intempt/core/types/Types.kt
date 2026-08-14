package com.intempt.core.types

import android.app.Activity
import android.content.Context
import android.view.View

internal data class DispatchEventProps(
    val eventName: String,
    val entityName: String,
    val event: Array<IntemptEventProvider>? = null,
    val type: String,
    val context: Context,
    val view: View? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DispatchEventProps

        if (eventName != other.eventName) return false
        if (entityName != other.entityName) return false
        if (!event.contentEquals(other.event)) return false
        if (type != other.type) return false
        if (context != other.context) return false
        if (view != other.view) return false

        return true
    }

    override fun hashCode(): Int {
        var result = eventName.hashCode()
        result = 31 * result + entityName.hashCode()
        result = 31 * result + event.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + context.hashCode()
        result = 31 * result + (view?.hashCode() ?: 0)
        return result
    }
}

internal data class HandleEventTypeProps(val type: String, val entityName: String, val context: Context, val view: View? = null)

internal data class ScreenViewProps(val activity: Activity, val entityName: String)

internal data class IntemptConfigs(
    val apiKey: String,
    val sourceId: String,
    val organizationId: String,
    val projectId: String,
)

internal data class IntemptOptions(
    val isLoggingEnabled: Boolean,
    val isTouchEnabled: Boolean,
    val isTextCaptureEnabled: Boolean,
    val isQueueEnabled: Boolean,
    val useIpAddressForGeolocation: Boolean,
    val isAutoCaptureEnabled: Boolean,
    val itemsInQueue: Int,
    val timeBuffer: Long,
    // Null means "use the production endpoint". Present so the SDK can be pointed at
    // staging or at a local stub; without it the endpoint was a compile-time constant and
    // the delivery leg could not be tested at all without a real project and key.
    val apiUrl: String?,
    // Opt-in TLS certificate pinning for the ingestion endpoint. Empty (the default) means
    // unchanged, platform-default TLS trust validation -- pinning activates only when the host
    // app supplies pins.
    val certificatePins: List<String> = emptyList(),
)

internal data class ConfigResult(
    val configs: IntemptConfigs?,
    val options: IntemptOptions?,
)

internal data class ModificationBodyParam(
    val optimizationType: String,
    val data: List<String>,
    val paramType: String,
)

internal data class ModificationGetParam(
    val optimizationType: String,
    val data: List<String>,
    val isNameType: Boolean,
)

internal data class RecommendationBody(
    val profileId: String,
    val sourceId: String,
    val limit: Int,
    val fields: List<String>,
    val productId: String?,
)

internal sealed class AutoCaptureParam

internal data class UiEventProps(val activity: Activity, val view: View, val listenerType: String) : AutoCaptureParam()

internal data class ScreenEventProps(
    val activity: Activity,
    val eventName: String,
    val entityName: String,
    val eventType: String,
) : AutoCaptureParam()

internal data class Product(
    val productId: String,
    val quantity: Int? = null,
)
