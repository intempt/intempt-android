@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Optional, opt-in TLS certificate pinning for the ingestion endpoint (delivered through the
 * vendored queue substrate in `com.intempt.core.queue` -- see `QueueConfig.getSSLSocketFactory()`
 * and `HttpService`, which null-checks it and falls back to platform-default TLS when absent).
 *
 * The SDK talks to a fixed, first-party host (configurable via `apiUrl`, defaults to
 * `api.intempt.com`), so pinning is a hardening option a host app may enable -- not a
 * requirement. It activates only when the host app supplies pins via `certificatePins` in
 * `assets/intempt-config.json`; with none configured (the default), behaviour is exactly what
 * it was before: platform-default TLS trust validation, no pinning.
 *
 * Pins are SHA-256 hashes of the certificate's SubjectPublicKeyInfo, in the "sha256/BASE64"
 * format used by OkHttp's `CertificatePinner` -- a format many host apps and cert-pinning
 * tooling already produce.
 */
internal class CertificatePinningTrustManager(
    private val pins: Set<String>,
    private val delegate: X509TrustManager = defaultTrustManager(),
) : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        // Standard platform chain/trust validation first.
        delegate.checkServerTrusted(chain, authType)

        // Then require at least one certificate in the presented chain to match a configured pin.
        val matchesPin = chain.any { pinFor(it) in pins }
        if (!matchesPin) {
            throw CertificateException(
                "Certificate pinning failure: no configured pin matched the presented certificate chain",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    companion object {
        /** Computes the "sha256/BASE64" SPKI pin for a certificate (OkHttp CertificatePinner format). */
        fun pinFor(certificate: X509Certificate): String {
            val spki = certificate.publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(spki)
            // android.util.Base64 (API 1), not java.util.Base64 (API 26) -- minSdk here is 23,
            // same reasoning as ConfigManagerService.token(). NO_WRAP: no embedded newlines.
            return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
        }

        fun defaultTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
        }
    }
}

/** Builds the (optional) pinned [SSLSocketFactory] wired into `QueueConfig` for delivery requests. */
internal object CertificatePinning {
    /**
     * Returns `null` -- meaning "no pinning, platform-default TLS" -- when [pins] is empty.
     * Otherwise returns an [SSLSocketFactory] that additionally requires the presented chain to
     * match one of [pins].
     */
    fun sslSocketFactoryFor(pins: List<String>): SSLSocketFactory? {
        if (pins.isEmpty()) return null
        val trustManager = CertificatePinningTrustManager(pins.toSet())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return sslContext.socketFactory
    }
}
