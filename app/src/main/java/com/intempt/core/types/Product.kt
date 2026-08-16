package com.intempt.core.types

/**
 * One line of a commerce event: which product, and how many.
 *
 * Public because [com.intempt.core.Intempt.productOrdered] takes a list of these. It used to take
 * `List<Map<String, Any>>` and pull `"productId"` and `"quantity"` back out with unchecked casts,
 * which admits a map with neither key, the right keys spelled wrong, or a quantity that is a
 * String. The validator rejected those, so the call became a silent no-op — a completed order,
 * the single most valuable event an ecommerce app sends, vanished on a typo in a map key.
 *
 * [quantity] is nullable because a product view has no quantity, and `productAdd`/`productView`
 * build a `Product` internally for the same wire shape.
 */
data class Product
    @JvmOverloads
    constructor(
        val productId: String,
        val quantity: Int? = null,
    ) {
        /**
         * Empty when usable, otherwise what is wrong.
         *
         * Returned rather than thrown for the same reason [IntemptCredentials.problems] is: the
         * capture surface reports refusal through its return value and an analytics SDK does not
         * throw into a checkout flow.
         */
        internal fun problems(): List<String> =
            buildList {
                if (productId.isBlank()) add("productId is blank")
                if (quantity != null && quantity <= 0) add("quantity must be positive, got $quantity")
            }
    }
