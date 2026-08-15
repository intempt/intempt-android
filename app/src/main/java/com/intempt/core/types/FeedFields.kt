package com.intempt.core.types

/**
 * The field set `products()` requests when the caller does not name one.
 *
 * **An unfielded feed request returns every catalog column**, including raw ML embedding vectors.
 * Measured at 222,919 bytes against 503 for the same 10 products — 443x — so a defaulted-to-all
 * request is a mobile data bill, not a convenience. The cross-SDK contract states it as a rule: a
 * platform MUST NOT default this to "all fields". Widen deliberately, never by omission.
 *
 * `id` is deliberately the whole default rather than a plausible-looking set like
 * `id, name, price, imageUrl`. Those column names are a guess at a customer's catalog schema, and
 * a default that names a column a given catalog does not have fails the request for everyone who
 * never asked for it. `id` is the only field verified to answer against a real feed
 * (`/feeds/{id}/data` on linea_shop), so it is the only one safe to assume.
 *
 * Pass the columns you actually want:
 *
 * ```
 * Intempt.products(feedId, count = 10, fields = listOf("id", "title", "price"))
 * ```
 */
object FeedFields {
    /** @see FeedFields */
    @JvmField
    val DEFAULT: List<String> = listOf("id")
}
