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
data class IntemptRuntimeOptions
    @JvmOverloads
    constructor(
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
        /**
         * The Intempt API base URL, for example `https://api.staging.intempt.com` or a local stub.
         *
         * Null or blank means the asset file's `apiUrl`, or production (`https://api.intempt.com`).
         * Set here, it wins over the asset file. It is used for every request the SDK makes: events,
         * consent, optimization, feeds and the push webhook.
         *
         * Validated at `initialize`: it must be `https://`, or `http://` to a loopback host only
         * (`localhost`, `127.0.0.1`, `[::1]`, or `10.0.2.2` for the Android emulator). Anything else
         * disables the SDK — `initialize` returns null and logs the reason — rather than sending to an
         * unintended host or quietly falling back to production. A trailing `/` is dropped.
         */
        val apiUrl: String? = null,
    )
