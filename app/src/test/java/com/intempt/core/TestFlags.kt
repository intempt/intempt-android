@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import com.intempt.core.flags.FlagsService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

/**
 * A [FlagsService] on the real ktor transport, pointed at [baseUrl]: each route posts to
 * `$baseUrl/<route>`, so a stub server sees the real path, headers and body.
 */
internal fun flagsServiceAt(
    baseUrl: String,
    logger: LoggerManagerService = mock(LoggerManagerService::class.java),
    sourceId: String = "src-1",
): FlagsService {
    val config = mock(ConfigManagerService::class.java)
    `when`(config.token()).thenReturn("c2VjcmV0LWtleQ==")
    `when`(config.certificatePins).thenReturn(emptyList())
    `when`(config.sourceId).thenReturn(sourceId)
    `when`(config.flagsUrl(any())).thenAnswer { "$baseUrl/${it.arguments[0]}" }
    val storage = mock(StorageManagerService::class.java)
    `when`(storage.getProfileId()).thenReturn("prof-1")
    `when`(storage.getSessionId()).thenReturn("sess-1")
    return FlagsService(config, HttpManagerService(config, logger), storage, logger)
}
