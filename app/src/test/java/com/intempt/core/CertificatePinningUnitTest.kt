package com.intempt.core

import com.intempt.core.services.CertificatePinning
import com.intempt.core.services.CertificatePinningTrustManager
import junit.framework.TestCase.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for the ingestion endpoint (wired into QueueConfig -- see
 * PureJvmQueueTest for the QueueConfig-level "opt-in, off by default" assertions) is opt-in:
 * with no pins configured, [CertificatePinning.sslSocketFactoryFor] returns null and delivery
 * keeps using platform-default TLS trust validation unchanged. When pins are configured, the
 * resulting trust manager must reject a chain that does not match any of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CertificatePinningUnitTest {
    // A real self-signed certificate (CN=test.local), used to exercise real X509Certificate
    // parsing/hashing without any network access.
    private val certificatePem =
        """
        -----BEGIN CERTIFICATE-----
        MIIDCzCCAfOgAwIBAgIUUtusUHaEJY5C9faLUjScrhKSi8AwDQYJKoZIhvcNAQEL
        BQAwFTETMBEGA1UEAwwKdGVzdC5sb2NhbDAeFw0yNjA4MTQxNDUyMzVaFw0zNjA4
        MTExNDUyMzVaMBUxEzARBgNVBAMMCnRlc3QubG9jYWwwggEiMA0GCSqGSIb3DQEB
        AQUAA4IBDwAwggEKAoIBAQDUsQnn9mr9dORdclR3LcTuRzauwZlh/CXHodVP23TM
        sk9AM5jMYDaOPZ3t+5In1TXJ2HwDalePgHmXpBzYIQOUlTFuhbYXSr8kpT6Bi79R
        hqBLddU8Zqfmc7IFP607uiKapH6sYrzjdY039C9DORqj0T/uPi/Hc0dSkYc/KuIB
        Gmhs2hIvDck9U1J6DvE4Z+boQijC+h1wislsxEWCOObeUJUxS5/2EIxE9+a8PvXX
        mmOKa4qhr8D1hiGm1U8cL676gA3e2CfabgKd1aKV/VrnpJNkrDKICL6kyDwchC0x
        ZQZE9TZVQW7Nss+Jm7ZJL7hzE1FdN8+sYjIeU4bgaNSNAgMBAAGjUzBRMB0GA1Ud
        DgQWBBTqUAQNenhQYiUhCsfauf7Yh7COozAfBgNVHSMEGDAWgBTqUAQNenhQYiUh
        Csfauf7Yh7COozAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAu
        Xt6Y1lnIhWGXZ8olEYAeeNAcLMxiKHsA13k0Fxw+85iEsUXOifGiiJQI45ZHQ27k
        BUaywDySXt3UaYBAxso1nWMy1LDYB00zC5YSwRzadW/pscRyayRgyyj3wbjjIimL
        hgWRN4QAXshMhwKJ7t4CIvx8GvudM1aC+Bo78rP3FUP+msMuae5EaED7XyKj6xu2
        Q4LtT15NJVtHKZZW6OFmcFApAXBy9gpZqZiysDevaV9hOEGxRrBnp4Sp0jbIQr5g
        C5+QiUBXGXH1MxSW4PyKwfIHi922vjPlTDtZNJAAuaSiNpX1bvKDJDhGpuSZk0cR
        BsgGouCZi6BN1Mt0QHiY
        -----END CERTIFICATE-----
        """.trimIndent()

    // SHA-256 SPKI pin of the certificate above, computed independently with
    // `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
    private val matchingPin = "sha256/niKJ2Asq5j3XOD7JmJ5+mZAiq+IOjp06vpgFT9nJ0yk="
    private val otherPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun parseCertificate(): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(
            ByteArrayInputStream(certificatePem.toByteArray()),
        ) as X509Certificate
    }

    // A trust manager stand-in for the platform default: it never fails chain validation on its
    // own, so failures observed below are solely attributable to the pinning check.
    private val noOpDelegate =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {}

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    @Test
    fun `pinning is off by default when no pins are configured`() {
        val factory: SSLSocketFactory? = CertificatePinning.sslSocketFactoryFor(emptyList())
        assertNull(factory)
    }

    @Test
    fun `pinning is applied when pins are configured`() {
        val factory = CertificatePinning.sslSocketFactoryFor(listOf(matchingPin))
        assertNotNull(factory)
    }

    @Test
    fun `pinFor computes the expected sha256 SPKI pin for a certificate`() {
        val cert = parseCertificate()
        assertEquals(matchingPin, CertificatePinningTrustManager.pinFor(cert))
    }

    @Test
    fun `checkServerTrusted succeeds when the chain matches a configured pin`() {
        val cert = parseCertificate()
        val trustManager = CertificatePinningTrustManager(setOf(matchingPin), noOpDelegate)

        trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        // No exception thrown => success.
    }

    @Test
    fun `checkServerTrusted throws when the chain does not match any configured pin`() {
        val cert = parseCertificate()
        val trustManager = CertificatePinningTrustManager(setOf(otherPin), noOpDelegate)

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }
}
