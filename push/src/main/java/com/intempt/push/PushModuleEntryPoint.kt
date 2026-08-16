package com.intempt.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.runBlocking

/**
 * The single entry point `:app`'s [com.intempt.core.internal.PushBridge] reaches via reflection.
 *
 * `:app` cannot declare a compile-time dependency on `:push` (the dependency direction is
 * `:push` -> `:app`, since push is the optional add-on), so this object's shape is dictated by
 * what plain Java reflection can call: no suspend functions directly (a Kotlin suspend fun
 * compiles to an extra synthetic `Continuation` parameter, which is invokable via reflection but
 * unnecessarily fragile across compiler versions). [registerTokenBlocking] is therefore an
 * ordinary blocking method that runs the real suspend call with `runBlocking`; the suspend-shaped
 * public surface for anything inside `:push` itself is still [registerToken].
 *
 * Kept as a plain `object` (not a class) because `PushBridge` looks it up via the `INSTANCE`
 * static field Kotlin generates for singletons.
 */
object PushModuleEntryPoint {
    /**
     * Moved verbatim from `Intempt.kt`'s previous inline Firebase bootstrap. Push notifications
     * are optional and only work when the host app has configured Firebase (google-services
     * plugin + google-services.json); if it hasn't, this fails silently rather than taking down
     * core analytics init.
     */
    fun initialize(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
        } catch (e: Exception) {
            Log.i("FCM", "Firebase not configured; push notifications are disabled.")
        }
    }

    /** Suspend-shaped entry point for callers already inside `:push` (i.e. this module's own tests). */
    suspend fun registerToken(): String = FirebaseService().initializeToken()

    /**
     * Blocking wrapper reflection can call directly, since invoking a genuine Kotlin suspend
     * function via `java.lang.reflect.Method` requires constructing and passing a `Continuation`
     * object by hand. `PushBridge.registerTokenIfPresent` wraps this call in
     * `withContext(Dispatchers.IO)` on the `:app` side so blocking here doesn't block the calling
     * coroutine's own dispatcher.
     */
    @JvmStatic
    fun registerTokenBlocking(): String = runBlocking { registerToken() }
}
