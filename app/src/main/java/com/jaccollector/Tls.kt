package com.jaccollector

import android.content.Context
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
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
 *
 * Also, like desktop browsers, it completes chains of servers that forget to send their
 * intermediate certificate: the missing one is downloaded from the address written in the
 * server's own certificate (AIA "CA Issuers"). The download only fills a gap in the chain;
 * the whole chain is still verified against the trusted roots, so a wrong or forged file
 * simply fails the check.
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
                    val first = try {
                        check(system, bundled, chain, authType)
                        return
                    } catch (e: Exception) { e }
                    // incomplete chain? fetch the missing intermediates (up to 3 levels, e.g.
                    // Let's Encrypt YR2 -> Root YR cross-signed by X1) and try again each time
                    var full = chain
                    repeat(3) {
                        val issuer = fetchIssuer(full.last()) ?: throw first
                        full = full + issuer
                        try {
                            check(system, bundled, full, authType)
                            return
                        } catch (_: Exception) {}
                    }
                    throw first
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

    private fun check(system: X509TrustManager, bundled: X509TrustManager,
                      chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: Exception) {
            bundled.checkServerTrusted(chain, authType) // throws if not trusted either
        }
    }

    /** Intermediates already downloaded (address -> certificate); a handful at most. */
    private val issuers = java.util.concurrent.ConcurrentHashMap<String, X509Certificate>()
    /** Addresses that failed recently, not retried for a while (address -> time). */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Downloads the issuer of [cert] from its AIA "CA Issuers" address. Only plain http (as CAs
     * publish them; also avoids TLS inside TLS), small files, short timeouts. Returns null when
     * there is nothing usable. The result is NOT trusted by itself, see the class comment.
     */
    private fun fetchIssuer(cert: X509Certificate): X509Certificate? {
        if (cert.subjectX500Principal == cert.issuerX500Principal) return null // a root: nothing above
        val url = caIssuersUrl(cert) ?: return null
        if (!url.startsWith("http://")) return null
        issuers[url]?.let { return it }
        val now = System.currentTimeMillis()
        if (now - (failed[url] ?: 0) < 10 * 60 * 1000L) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            val bytes = try {
                c.connectTimeout = 4000
                c.readTimeout = 4000
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                c.inputStream.use { inp ->
                    val buf = java.io.ByteArrayOutputStream()
                    val b = ByteArray(4096)
                    while (true) {
                        val n = inp.read(b)
                        if (n < 0) break
                        buf.write(b, 0, n)
                        if (buf.size() > 64 * 1024) throw RuntimeException("too big")
                    }
                    buf.toByteArray()
                }
            } finally {
                c.disconnect()
            }
            val issuer = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            // must really be the one that signed the server certificate
            if (issuer.subjectX500Principal != cert.issuerX500Principal) throw RuntimeException("not the issuer")
            cert.verify(issuer.publicKey)
            issuers[url] = issuer
            RequestLog.add("TLS: completed the chain with ${issuer.subjectX500Principal.name.take(60)}")
            issuer
        } catch (e: Exception) {
            failed[url] = now
            null
        }
    }

    /** The "CA Issuers" address from the Authority Information Access extension, if any. */
    private fun caIssuersUrl(cert: X509Certificate): String? {
        val ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return null
        // OID id-ad-caIssuers (1.3.6.1.5.5.7.48.2), then a URI: [6] IA5String
        val oid = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)
        outer@ for (i in 0..ext.size - oid.size - 2) {
            for (j in oid.indices) if (ext[i + j] != oid[j]) continue@outer
            val tag = i + oid.size
            if (ext[tag] != 0x86.toByte()) continue
            val len = ext[tag + 1].toInt() and 0xFF
            if (len >= 128 || tag + 2 + len > ext.size) return null
            return String(ext, tag + 2, len, Charsets.US_ASCII)
        }
        return null
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}
