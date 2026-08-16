package com.intempt.core.internal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presence-gated reflection bridge into the optional `:push` module.
 *
 * `:app` never declares a compile-time dependency on `:push` — the dependency direction is
 * `:push` -> `:app` (a host app opts into push by adding `:push`/`intempt-push`, not the other
 * way around). These two call sites ([initializeIfPresent], [registerTokenIfPresent]) are the
 * only places `:app` needs to reach into it, and both do so via `Class.forName` so that when
 * `:push` is absent from the classpath the call is a silent, deliberate no-op rather than an
 * uncaught `ClassNotFoundException` taking the host app down.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal object PushBridge {
    private const val ENTRY_POINT = "com.intempt.push.PushModuleEntryPoint"

    fun initializeIfPresent(context: Context) {
        try {
            val clazz = Class.forName(ENTRY_POINT)
            val instance = clazz.getDeclaredField("INSTANCE").get(null)
            clazz.getMethod("initialize", Context::class.java).invoke(instance, context)
        } catch (e: ClassNotFoundException) {
            // :push is not on the classpath — push notifications disabled. Nothing to log: this
            // is the expected, common case for a host app that hasn't added :push.
        } catch (e: Exception) {
            // Deliberately broad: this reflective call can fail in ways specific to no particular
            // exception type (NoSuchMethodException, InvocationTargetException wrapping anything
            // :push's own init throws, IllegalAccessException, ...) and none of them should ever
            // reach the host app — push is optional, so any failure here degrades to "push is
            // off," logged for diagnosis rather than rethrown.
            Log.i("FCM", "Push module present but failed to initialize", e)
        }
    }

    /**
     * Returns the FCM token, or null if `:push` is absent or anything about the reflective call
     * fails. Never throws.
     *
     * `PushModuleEntryPoint.registerTokenBlocking()` is an ordinary blocking method (not a real
     * Kotlin suspend fun), specifically so it can be invoked through plain
     * `java.lang.reflect.Method.invoke` without constructing a `Continuation` by hand. That
     * blocking call is wrapped here in `withContext(Dispatchers.IO)` so it doesn't block
     * whichever dispatcher the calling coroutine happens to be running on.
     */
    suspend fun registerTokenIfPresent(): String? =
        withContext(Dispatchers.IO) {
            try {
                val clazz = Class.forName(ENTRY_POINT)
                val instance = clazz.getDeclaredField("INSTANCE").get(null)
                clazz.getMethod("registerTokenBlocking").invoke(instance) as? String
            } catch (e: ClassNotFoundException) {
                // :push is not on the classpath — no token to register.
                null
            } catch (e: Exception) {
                Log.i("FCM", "Push module present but token registration failed", e)
                null
            }
        }
}
