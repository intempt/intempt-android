@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.customCapture

import android.view.View
import com.intempt.core.R
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IntemptError
import com.intempt.core.types.Product
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
        private val errors: ErrorReporter,
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

        // Guard clauses. A single exit point would nest these checks three deep, which is the
        // shape they were written to avoid.
        @Suppress("ReturnCount")
        fun isIdentifyValid(
            userId: String,
            eventTitle: String?,
        ): Boolean {
            if (userId.isEmpty()) {
                errors.report(IntemptError.MissingIdentity("identify requires a non-blank userId"))
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
                errors.report(IntemptError.ForbiddenEventName(eventTitle))
                return false
            }

            return true
        }

        @Suppress("ReturnCount")
        fun isGroupValid(
            accountId: String,
            eventTitle: String?,
        ): Boolean {
            if (accountId.isEmpty()) {
                errors.report(IntemptError.MissingIdentity("group requires a non-blank accountId"))
                return false
            }

            if (eventTitle != null && isForbidden(eventTitle)) {
                errors.report(IntemptError.ForbiddenEventName(eventTitle))
                return false
            }

            // Same dead rule as isIdentifyValid had, for the same reason: GroupEvent carries no
            // title, and the caller already writes `name = eventTitle ?: "Group"`. Requiring a
            // title here turned group(accountId, accountAttributes) into a silent no-op.

            return true
        }

        fun isTrackValid(eventTitle: String?): Boolean {
            if (eventTitle.isNullOrEmpty()) {
                errors.report(IntemptError.MissingIdentity("an event requires a non-blank eventTitle"))
                return false
            }

            if (isForbidden(eventTitle)) {
                errors.report(IntemptError.ForbiddenEventName(eventTitle))
                return false
            }
            return true
        }

        /**
         * Validates a commerce line list.
         *
         * The predecessor took `List<Map<String, Any>>` and checked for a `"productId"` String and
         * a positive `"quantity"` Int. Every way of getting that wrong — a misspelled key, a
         * quantity arriving as a String from a bridge — produced a silent no-op on the most
         * valuable event an ecommerce app sends. [Product] makes both fields unmissable at the
         * call site, so what is left to check is only their values.
         */
        fun isProductListValid(products: List<Product>): Boolean {
            if (products.isEmpty()) {
                errors.report(IntemptError.MissingIdentity("productOrdered requires at least one product"))
                return false
            }

            val problems =
                products.flatMapIndexed { index, product ->
                    product.problems().map { "products[$index]: $it" }
                }
            problems.forEach { errors.report(IntemptError.InvalidPropertyValue(it)) }
            return problems.isEmpty()
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
