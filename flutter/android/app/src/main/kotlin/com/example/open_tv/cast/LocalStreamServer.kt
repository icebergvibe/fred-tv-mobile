package dev.fredol.open_tv.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Serves the HLS output of [StreamPipeline] to the Chromecast over the LAN.
 *
 * The receiver is a browser, so every response carries CORS headers, and the
 * playlist is marked uncacheable because it is rewritten every few seconds.
 * Files are only served from [rootDir], under a random token path so another
 * device on the network can't stumble on the stream.
 */
class LocalStreamServer(
    private val rootDir: File,
    port: Int,
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "LocalStreamServer"
        const val DEFAULT_PORT = 47800

        /** Starts on [DEFAULT_PORT], or an ephemeral port if that one is taken. */
        fun start(rootDir: File): LocalStreamServer {
            return try {
                LocalStreamServer(rootDir, DEFAULT_PORT).apply { start(SOCKET_READ_TIMEOUT, true) }
            } catch (e: Exception) {
                Log.w(TAG, "Port $DEFAULT_PORT unavailable, using a random one", e)
                LocalStreamServer(rootDir, 0).apply { start(SOCKET_READ_TIMEOUT, true) }
            }
        }

        /**
         * The address the Chromecast must use to reach this phone: the Wi-Fi
         * (or Ethernet) IPv4, never a VPN tunnel address.
         */
        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!lan || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    val props = cm.getLinkProperties(network) ?: continue
                    props.linkAddresses
                        .map { it.address }
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.let { return it.hostAddress }
                }
            }
            // Fallback: scan interfaces, preferring the usual Wi-Fi name.
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }

    val token: String = SecureRandom().let { rnd ->
        val bytes = ByteArray(12).also(rnd::nextBytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    fun baseUrl(host: String): String = "http://$host:$listeningPort/$token"

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return cors(newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, ""))
        }
        val prefix = "/$token/"
        val uri = session.uri
        if (!uri.startsWith(prefix)) {
            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, ""))
        }
        val relative = uri.removePrefix(prefix)
        val file = File(rootDir, relative)
        val inside = runCatching {
            file.canonicalPath.startsWith(rootDir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!inside || !file.isFile) {
            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, ""))
        }
        val mime = mimeFor(file.name)
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(Response.Status.OK, mime, "").apply {
                addHeader("Content-Length", file.length().toString())
            }
        } else {
            newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
        }
        if (file.name.endsWith(".m3u8")) {
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        }
        return cors(response)
    }

    private fun cors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "*")
        addHeader("Access-Control-Max-Age", "86400")
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        name.endsWith(".ts") -> "video/mp2t"
        name.endsWith(".m4s") -> "video/iso.segment"
        name.endsWith(".mp4") -> "video/mp4"
        name.endsWith(".vtt") -> "text/vtt"
        else -> "application/octet-stream"
    }
}
