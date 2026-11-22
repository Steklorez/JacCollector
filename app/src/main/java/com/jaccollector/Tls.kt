package com.jaccollector

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android before 7.1.1 does not know the Let's Encrypt root (ISRG Root X1), and most public
 * JacRed servers (and GitHub) use Let's Encrypt certificates. Without this, on Android 5-7.0
 * every such server fails with a certificate error.
 *
 * The bundled root is trusted IN ADDITION to the system roots; normal certificate and host name
 * checks stay in place.
 */
object Tls {
    @Volatile private var done = false

    @Synchronized
    fun init(ctx: Context) {
        if (done) return
        done = true
        try {
            val system = trustManager(null)
            val cf = CertificateFactory.getInstance("X.509")
            val extra = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                ctx.resources.openRawResource(R.raw.isrg_root_x1).use {
                    setCertificateEntry("isrg_root_x1", cf.generateCertificate(it))
                }
            }
            val bundled = trustManager(extra)
            val combined = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    system.checkClientTrusted(chain, authType)

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        bundled.checkServerTrusted(chain, authType) // throws if not trusted either
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    system.acceptedIssuers + bundled.acceptedIssuers
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(combined), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
        } catch (e: Exception) {
            RequestLog.add("TLS setup failed: ${e.javaClass.simpleName}")
        }
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}
