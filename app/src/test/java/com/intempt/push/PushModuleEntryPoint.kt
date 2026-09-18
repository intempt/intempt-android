package com.intempt.push

import android.content.Context

/**
 * A stand-in for the real `:push` entry point, on the `:app` unit-test classpath only.
 *
 * `:app` cannot depend on `:push` — the dependency direction is `:push` -> `:app` — so
 * `com.intempt.core.internal.PushBridge` reaches it by `Class.forName` on this exact fully
 * qualified name. Declaring the same name here is therefore not a mock of the bridge: the bridge
 * runs unchanged, does its real reflection, and finds this. That is deliberate, because the bug
 * these tests exist for lived in the decision of *whether* to call the bridge, and a stubbed
 * bridge would have proved nothing about it.
 *
 * [token] defaults to blank, which is what a host app without Firebase produces, so every test
 * that does not opt in sees the SDK's "no push" behaviour rather than an invented token.
 */
object PushModuleEntryPoint {
    /** What [registerTokenBlocking] answers. Reset it in `@Before`; tests share one classloader. */
    @JvmStatic
    var token: String = ""

    /** Called by `PushBridge.initializeIfPresent`. Firebase setup is not this fake's business. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) = Unit

    /** Matches the real entry point: an ordinary blocking method, invokable by reflection. */
    @JvmStatic
    fun registerTokenBlocking(): String = token
}
