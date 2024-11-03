package com.intempt.core.types

import kotlinx.serialization.json.JsonArray

interface ModificationProvider {
    suspend fun getByGroup(data: List<String>): JsonArray?
    suspend fun getByName(data: List<String>): JsonArray?
}

interface IntemptEventProvider {
    val eventId: String
    val sessionId: String
    val pageId: String
    val profileId: String
    val timestamp: Long

    fun getEventTime(): Long

    fun toFormatted(): Map<String, Any>
}