package com.intempt.core.customCapture

import android.view.View
import com.intempt.core.R
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CustomCaptureService
    @Inject
    constructor(
        private val storage: StorageManagerService,
        val logger: LoggerManagerService,
    ) : BaseComponent(logger) {
        private val forbiddenEventNames: Array<String> =
            arrayOf(
                "auto-track",
                "view page",
                "leave page",
                "change on",
                "click on",
                "submit on",
                "identify",
                "consent",
            )

        /**
         * Case-insensitive on purpose. The list above is lowercase and the check used
         * `Array.contains`, which is exact — so "identify", "Consent" and "Click On" were
         * reserved in name only: any capitalisation walked straight past the guard and shipped
         * an event under a name the platform reserves for its own.
         */
        private fun isForbidden(eventTitle: String): Boolean = forbiddenEventNames.any { it.equals(eventTitle, ignoreCase = true) }

        fun setDoNotCaptureTag(view: View) {
            view.setTag(R.id.intemptDoNotCapture, true)
        }

        fun logoutHandler() {
            storage.clearAllStorage()
        }

        fun isIdentifyValid(
            userId: String,
            eventTitle: String?,
            userAttributes: Map<String, String>?,
        ): Boolean {
            if (userId.isEmpty()) {
                logger.error("Identify parameters are invalid: set 'userId' to use 'identify'.")
                return false
            }

            // The rule that used to live here rejected identify() whenever userAttributes was
            // passed without an eventTitle, which is the most natural way to call it:
            //
            //     Intempt.identify(userId = "u1", userAttributes = mapOf("plan" to "free"))
            //
            // It guarded nothing. IdentifyEvent has no title field at all, and the caller in
            // CustomCaptureComponent already writes `name = eventTitle ?: "Identify"` — the null
            // case was handled two statements later. So the check only turned a supported call
            // into a silent no-op: it logged an error, returned false, and identify() returned
            // normally with nothing queued.
            //
            // Caught by calling it the obvious way in the sample app and finding no identify row
            // in the on-device queue.

            if (eventTitle != null && isForbidden(eventTitle)) {
                logger.error("The '$eventTitle' event title is forbidden")
                return false
            }

            return true
        }

        fun isGroupValid(
            accountId: String,
            eventTitle: String?,
            accountAttributes: Map<String, String>?,
        ): Boolean {
            if (accountId.isEmpty()) {
                logger.error("Group parameters are invalid: 'accountId' is required.")
                return false
            }

            if (eventTitle != null && isForbidden(eventTitle)) {
                logger.error("The '$eventTitle' event title is forbidden")
                return false
            }

            // Same dead rule as isIdentifyValid had, for the same reason: GroupEvent carries no
            // title, and the caller already writes `name = eventTitle ?: "Group"`. Requiring a
            // title here turned group(accountId, accountAttributes) into a silent no-op.

            return true
        }

        fun isTrackValid(eventTitle: String?): Boolean {
            if (eventTitle.isNullOrEmpty()) {
                logger.error("Track parameters are invalid: eventTitle is required.")
                return false
            }

            if (isForbidden(eventTitle)) {
                logger.error("The '$eventTitle' event title is forbidden")
                return false
            }
            return true
        }

        /**
         * `!=`, not `!==`. The original used reference identity on Strings, so it only worked
         * for interned literals — a value read from JSON, a network response or string
         * concatenation compares unequal even when it reads "accept", and the consent was
         * rejected. Kotlin's `!==` is identity; `!=` is equals. Found by an adversarial review,
         * not by a test: all four consent tests asserted nothing that could see it.
         */
        fun isConsentValid(action: String): Boolean {
            if (action.isNotEmpty() && action != "accept" && action != "reject") {
                logger.error("Consent parameters are invalid: action should be either 'reject' or 'accept'.")
                return false
            }
            return true
        }

        fun isProductListValid(products: List<Map<String, Any>>): Boolean {
            return products.all { product ->
                val productId = product["productId"]
                val quantity = product["quantity"]
                productId is String && productId.isNotBlank() && quantity is Int && quantity > 0
            }
        }

        fun onUiEventReceive(props: UiEventProps): DispatchEventProps {
            logger.log("AutoCapture | Is UiEventProps")
            val (activity, view, listenerType) = props

            val eventName =
                when (listenerType) {
                    "change" -> Constants.CHANGE.EVENT_NAME
                    else -> Constants.TOUCH.EVENT_NAME
                }

            val entityName =
                when (listenerType) {
                    "change" -> Constants.CHANGE.ENTITY_NAME
                    else -> Constants.TOUCH.ENTITY_NAME
                }

            return DispatchEventProps(
                eventName = eventName,
                entityName = entityName,
                type = listenerType,
                event = null,
                context = activity,
                view = view,
            )
        }

        fun onScreenEventReceive(props: ScreenEventProps): DispatchEventProps {
            val (activity, eventName, entityName, eventType) = props
            return DispatchEventProps(
                eventName = eventName,
                entityName = entityName,
                type = eventType,
                event = null,
                context = activity,
            )
        }
    }
