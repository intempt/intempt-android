package com.intempt.core.types

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CompletableFuture

/**
 * What `Intempt.experiment` and `Intempt.personalization` hold before a successful
 * `initialize()`, and after a failed one.
 *
 * Both were `lateinit var` of a non-null type, so reading either before initialization —
 * or at any point after initialization threw — raised
 * `UninitializedPropertyAccessException` inside the host app. An analytics SDK that has
 * failed to start should return nothing, not crash its host, and a host app cannot
 * reasonably be asked to guard every read.
 *
 * Returning null is already a valid answer from these methods: it is what a miss looks
 * like. So a caller that never checks whether the SDK started gets the same behaviour as
 * a caller whose experiment simply has no value, which is the safe reading.
 */
internal object DisabledModificationProvider : ModificationProvider {
    override suspend fun getByGroup(data: List<String>): JsonElement? = null

    override suspend fun getByName(data: List<String>): JsonElement? = null

    override fun getByGroupAsync(data: List<String>): CompletableFuture<JsonElement?> =
        CompletableFuture.completedFuture(null)

    override fun getByNameAsync(data: List<String>): CompletableFuture<JsonElement?> =
        CompletableFuture.completedFuture(null)
}
