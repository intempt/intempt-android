package com.intempt.core

import com.intempt.core.services.CertificatePinning
import com.intempt.core.services.IsrgRootTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
class IsrgRootTrustTest {
    private val isrgRootX1Sha256 = "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6"

    private fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

    private fun emptyTrustManager(): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    @Test
    fun bundledCertificateIsIsrgRootX1() {
        assertEquals(isrgRootX1Sha256, sha256(IsrgRootTrust.isrgRootX1))
    }

    @Test
    fun withIsrgRootX1AddsTheRootToTheGivenAnchors() {
        val augmented = IsrgRootTrust.withIsrgRootX1(emptyTrustManager())
        assertEquals(listOf(isrgRootX1Sha256), augmented.acceptedIssuers.map(::sha256))
    }

    @Test
    @Config(sdk = [23])
    fun beforeApi25DeliveryTrustsIsrgRootX1() {
        assertTrue(IsrgRootTrust.isNeeded)
        assertNotNull(IsrgRootTrust.sslSocketFactory())
        assertNotNull(CertificatePinning.sslSocketFactoryFor(emptyList()))
        assertTrue(IsrgRootTrust.trustManager().acceptedIssuers.any { sha256(it) == isrgRootX1Sha256 })
    }

    @Test
    @Config(sdk = [25])
    fun fromApi25DeliveryKeepsPlatformDefaultTls() {
        assertFalse(IsrgRootTrust.isNeeded)
        assertNull(IsrgRootTrust.sslSocketFactory())
        assertNull(CertificatePinning.sslSocketFactoryFor(emptyList()))
    }
}
