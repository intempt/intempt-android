# Usage recipes

The main [README](../README.md) documents each `Intempt` call on its own. These recipes show
them combined into flows a real app actually has. See `sample/` for a runnable host app that
exercises the raw API calls this builds on.

## 1. Consent-gated onboarding

Don't track anything until the user has made a choice, and record the choice itself as an event
so you have an audit trail of who opted in/out and when.

```kotlin
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Intempt.initialize(applicationContext)) {
            // SDK didn't start (e.g. missing intempt-config.json). Every call below is
            // already a safe no-op, so the consent UI can render unconditionally.
            Log.w("Onboarding", "Analytics disabled — continuing without it")
        }
    }

    private fun onUserAcceptsTracking(userEmail: String) {
        Intempt.consent(
            action = "grant",
            validUntil = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000,
            email = userEmail,
            message = "Accepted analytics & marketing v3",
            category = "analytics",
        )
        // Consent is a record, not a switch — it doesn't itself enable/disable tracking.
        Intempt.Tracking.start()
        Intempt.identify(userId = userEmail, eventTitle = "onboarding_completed")
    }

    private fun onUserDeclinesTracking() {
        Intempt.consent(
            action = "deny",
            validUntil = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000,
            category = "analytics",
        )
        Intempt.Tracking.stop()
    }
}
```

## 2. A full ecommerce funnel

Product events plus `record` to attach order-level context in one call, so revenue reporting
doesn't have to stitch three separate events back together.

```kotlin
class ProductDetailActivity : AppCompatActivity() {

    private fun onProductScreenShown(sku: String) {
        Intempt.productView(productId = sku)
    }

    private fun onAddToCart(sku: String, quantity: Int) {
        Intempt.productAdd(productId = sku, quantity = quantity)
    }

    private fun onCheckoutComplete(
        userId: String,
        orderId: String,
        items: List<CartItem>,
        totalCents: Long,
    ) {
        Intempt.productOrdered(
            items.map { item ->
                mapOf(
                    "productId" to item.sku,
                    "quantity" to item.quantity,
                    "price" to item.priceUsd,
                )
            },
        )

        // record() ties the purchase to the user and adds order-level fields productOrdered
        // doesn't carry — useful for revenue dashboards keyed on eventTitle rather than the
        // three separate calls above.
        Intempt.record(
            eventTitle = "order_completed",
            userId = userId,
            data = mapOf(
                "order_id" to orderId,
                "total_cents" to totalCents.toString(),
                "item_count" to items.size.toString(),
            ),
        )
    }
}

data class CartItem(val sku: String, val quantity: Int, val priceUsd: Double)
```

## 3. Rendering a recommendation feed

`recommendation()` is a `suspend` function that returns a nullable `JsonObject` — null on
failure (network error, bad config, no matching feed), not an exception. Handle both branches;
an unhandled null here is the most common way this call breaks a screen.

```kotlin
class HomeFragment : Fragment() {

    private fun loadRecommendations() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = Intempt.recommendation(
                id = "homepage-recs",
                quantity = 5,
                fields = listOf("name", "price", "imageUrl"),
                productId = null,
            )

            if (result == null) {
                // Feed unavailable — degrade the UI rather than leave a spinner forever.
                showRecommendationsEmptyState()
                return@launch
            }

            val items = result["items"]?.jsonArray.orEmpty()
            if (items.isEmpty()) {
                showRecommendationsEmptyState()
            } else {
                renderRecommendationCarousel(items)
            }
        }
    }
}
```
