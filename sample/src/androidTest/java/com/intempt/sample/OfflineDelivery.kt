package com.intempt.sample

import android.content.Context
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.queue.HttpService
import com.intempt.core.queue.OfflineMode
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Keeps the merge-gating on-device suites off the live ingestion endpoint (CLAUDE.md §14).
 *
 * These suites assert on rows in the durable queue. With real credentials every row was POSTed
 * and deleted as soon as the gateway accepted it, so a row could be written and gone between two
 * polls: API 34 failed on ef7c062 with `View screen` never observed, because a pause-triggered
 * flush delivered it before launchOnce's first sample. Re-emitting and longer waits only made
 * that race rarer. Reporting offline makes the queue keep every row, which is the state the
 * assertions read, and leaves enqueueing, persistence and payload building real.
 *
 * Not installed under -Pintempt.prodTests=true: the opt-in tests exist to exercise real delivery.
 *
 * Reflection, as coreInstance() does: the SDK has no seam for swapping its poster, and a test-only
 * public one would ship to every consumer.
 */
internal object OfflineDelivery {
    private class Offline : HttpService() {
        override fun isOnline(
            context: Context?,
            offlineMode: OfflineMode?,
        ): Boolean = false
    }

    fun installUnlessProdTests() {
        if (BuildConfig.INTEMPT_PROD_TESTS) return
        val deliveries = findAll(com.intempt.core.Intempt)
        if (deliveries.isEmpty()) {
            throw AssertionError("no DeliveryMessages reachable from Intempt; the SDK is not initialized")
        }
        val field = DeliveryMessages::class.java.getDeclaredField("mHttpService")
        field.isAccessible = true
        deliveries.forEach { field.set(it, Offline()) }
    }

    /** Every DeliveryMessages reachable from [root], walking SDK objects and the JDK containers they hold. */
    private fun findAll(root: Any): List<DeliveryMessages> {
        val found = mutableListOf<DeliveryMessages>()
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque<Any>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val obj = queue.poll() ?: continue
            if (seen.put(obj, Unit) != null) continue
            if (obj is DeliveryMessages) {
                found += obj
                continue
            }
            when (obj) {
                is Map<*, *> -> obj.values.filterNotNullTo(queue)
                is Iterable<*> -> obj.filterNotNullTo(queue)
                is Array<*> -> obj.filterNotNullTo(queue)
                is java.util.concurrent.atomic.AtomicReference<*> -> obj.get()?.let(queue::add)
                is Lazy<*> -> if (obj.isInitialized()) obj.value?.let(queue::add)
                else -> if (isWalkable(obj.javaClass)) fieldsOf(obj).forEach(queue::add)
            }
        }
        return found
    }

    // android.*, java.* and kotlin.* objects are not walked field by field: the Application context
    // alone reaches most of the framework, and hidden-API checks reject reflection into it.
    private fun isWalkable(type: Class<*>): Boolean {
        val name = type.name
        return !type.isPrimitive && !type.isEnum &&
            listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "dalvik.", "sun.", "libcore.")
                .none { name.startsWith(it) }
    }

    private fun fieldsOf(obj: Any): List<Any> {
        val out = mutableListOf<Any>()
        var type: Class<*>? = obj.javaClass
        while (type != null && isWalkable(type)) {
            type.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) || type == com.intempt.core.Intempt.javaClass }
                .filter { !it.type.isPrimitive && it.type != String::class.java }
                .forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        f.get(obj)?.let(out::add)
                    }
                }
            type = type.superclass
        }
        return out
    }
}
