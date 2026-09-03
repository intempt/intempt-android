package com.intempt.core.types

/**
 * Options supplied to `Intempt.initialize(...)` at runtime, overriding `intempt-config.json`.
 *
 * Distinct from [IntemptOptions], which models the asset file's own `options` block.
 *
 * Every field is nullable and null means "whatever the asset file says, or the default". The
 * override is **per field**, not wholesale, exactly as [IntemptCredentials] already works — an app
 * that sets one option here keeps every other value from its asset file.
 *
 * The asset file stays the documented setup for a plain Android app. This exists for the callers
 * that have no asset file to edit: React Native, Flutter and any other bridge, where the host app
 * configures the SDK in JavaScript and cannot reach into the native bundle's assets.
 */
data class IntemptRuntimeOptions(
    /**
     * Whether Intempt may derive country, region and city from the address the request already
     * arrives on.
     *
     * Null means the asset file's `useIpAddressForGeolocation`, or true. The device never reads or
     * sends its own address either way: the SDK sends `?ip=1` or `?ip=0` and the platform resolves
     * the connection address against a local database, then discards it before storing anything.
     *
     * Leaving it on means the app collects coarse location, because the derived country/region/
     * city is stored. Declare it in your Play Data safety form, or set this to false.
     */
    val useIpAddressForGeolocation: Boolean? = null,
)
