package io.github.lvlahti.pochi

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Browser-only receiver bound to the selected local interface; access requires an unguessable URL token. */
class StreamServer(
    private val context: Context,
    private val address: Inet4Address,
    private val settings: StreamSettings,
    private val port: Int = 8822
) : AutoCloseable {
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val url: String = "http://${address.hostAddress}:$port/?token=$token"
    private val running = AtomicBoolean(false)
    private val server = ServerSocket()
    private val websocketClients = CopyOnWriteArrayList<WebsocketClient>()
    private val mjpegClients = CopyOnWriteArrayList<MjpegClient>()
    private val audioClients = CopyOnWriteArrayList<AudioClient>()
    @Volatile private var initialization: ByteArray? = null
    @Volatile private var mime = ""
    @Volatile private var audioAvailable = false

    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, port), 8)
        running.set(true)
        Thread({ acceptLoop() }, "Pochi-HTTP").start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                Thread({ handle(socket) }, "Pochi-Client").start()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept failed", e)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: return
            val method = requestLine.split(' ').firstOrNull()
            val headers = HashMap<String, String>()
            for (i in 0 until 48) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val expectedHost = "${address.hostAddress}:$port"
            if (method != "GET" || headers["host"] != expectedHost) {
                respond(output, 403, "text/plain", "Forbidden".toByteArray()); return
            }
            val resource = path.substringBefore('?')
            val requestToken = path.substringAfter("?token=", "").substringBefore('&')
            val authorized = requestToken == token
            if (!authorized && resource != "/app.js" && resource != "/style.css") {
                respond(output, 403, "text/plain", "Invalid Pochi viewer link".toByteArray()); return
            }
            when (resource) {
                "/" -> serveAsset(output, "index.html", "text/html; charset=utf-8")
                "/app.js" -> serveAsset(output, "app.js", "text/javascript; charset=utf-8")
                "/style.css" -> serveAsset(output, "style.css", "text/css; charset=utf-8")
                "/status" -> respond(output, 200, "application/json", JSONObject().apply {
                    put("mode", settings.mode)
                    put("transport", settings.transport)
                    put("audio", audioAvailable)
                    put("fps", settings.fps)
                    put("maxEdge", settings.maxEdge)
                }.toString().toByteArray())
                "/ws" -> {
                    if (settings.mode != StreamSettings.MODE_H264 ||
                        headers["origin"] != "http://$expectedHost" ||
                        headers["upgrade"]?.lowercase() != "websocket" ||
                        headers["sec-websocket-version"] != "13") {
                        respond(output, 403, "text/plain", "Forbidden".toByteArray()); return
                    }
                    val key = headers["sec-websocket-key"] ?: return
                    if (!handshake(output, key)) return
                    socket.soTimeout = 0
                    val client = WebsocketClient(socket, output)
                    websocketClients.add(client)
                    initialization?.let { client.sendInit(mime, it) }
                    client.start()
                    // Read and discard client frames. The sender thread owns the output stream.
                    while (running.get() && input.read() != -1) { /* wait for disconnect */ }
                    websocketClients.remove(client)
                    client.close()
                }
                "/audio" -> {
                    if (!audioAvailable || headers["origin"] != "http://$expectedHost" ||
                        headers["upgrade"]?.lowercase() != "websocket" ||
                        headers["sec-websocket-version"] != "13") {
                        respond(output, 403, "text/plain", "Forbidden".toByteArray()); return
                    }
                    val key = headers["sec-websocket-key"] ?: return
                    if (!handshake(output, key)) return
                    socket.soTimeout = 0
                    val client = AudioClient(socket, output)
                    audioClients.add(client)
                    client.start()
                    while (running.get() && input.read() != -1) { /* wait for disconnect */ }
                    audioClients.remove(client)
                    client.close()
                }
                "/mjpeg" -> {
                    if (settings.mode != StreamSettings.MODE_MJPEG) {
                        respond(output, 404, "text/plain", "Not found".toByteArray()); return
                    }
                    output.write(("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=pochi\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "X-Content-Type-Options: nosniff\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.flush()
                    socket.soTimeout = 0
                    val client = MjpegClient(socket, output)
                    mjpegClients.add(client)
                    client.writeLoop(running)
                    mjpegClients.remove(client)
                }
                else -> respond(output, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (_: Exception) {
            // Peer disconnected, browser cancelled, or malformed request.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        for (i in 0 until 4096) {
            val byte = input.read()
            if (byte == -1) return null
            if (byte == 10) return buf.toString().trimEnd('\r')
            if (byte != 13) buf.append(byte.toChar())
        }
        return null // enforce maximum header length
    }

    private fun serveAsset(out: OutputStream, filename: String, type: String) {
        val bytes = context.assets.open(filename).use { it.readBytes() }
        respond(out, 200, type, bytes)
    }

    private fun respond(out: OutputStream, code: Int, type: String, data: ByteArray) {
        val status = if (code == 200) "OK" else if (code == 403) "Forbidden" else "Not Found"
        out.write(("HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${data.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; " +
            "connect-src 'self' ws://${address.hostAddress}:$port; img-src 'self'; media-src 'self' blob:\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.flush()
    }

    private fun handshake(out: OutputStream, key: String): Boolean {
        if (key.length > 128 || runCatching { Base64.getDecoder().decode(key).size }.getOrDefault(0) != 16) return false
        val hash = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        val accept = Base64.getEncoder().encodeToString(hash)
        out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
            .toByteArray(Charsets.US_ASCII))
        out.flush()
        return true
    }

    /** Call when the MediaCodec output format changes and SPS/PPS become available. */
    fun setH264Init(mimeType: String, initSegment: ByteArray) {
        mime = mimeType
        initialization = initSegment
        websocketClients.forEach { it.sendInit(mimeType, initSegment) }
    }

    fun publishFragment(fragment: H264Mp4Muxer.Fragment) {
        websocketClients.forEach { it.offerFragment(fragment) }
    }

    /** A new video geometry needs a fresh MSE init segment on every connected browser. */
    fun resetVideo() {
        initialization = null
        mime = ""
        websocketClients.forEach { it.close() }
        websocketClients.clear()
    }

    /** Send bounded low-latency mono 48 kHz PCM16 chunks (no microphone capture). */
    fun setAudioAvailable(available: Boolean) { audioAvailable = available }

    fun publishAudio(pcm: ByteArray) {
        audioClients.forEach { it.offer(pcm) }
    }

    fun publishJpeg(jpeg: ByteArray) {
        mjpegClients.forEach { it.offer(jpeg) }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        websocketClients.forEach { it.close() }
        mjpegClients.forEach { it.close() }
        audioClients.forEach { it.close() }
        websocketClients.clear()
        mjpegClients.clear()
        audioClients.clear()
    }

    private class WebsocketClient(val socket: Socket, val output: OutputStream) : AutoCloseable {
        private data class Packet(val type: Int, val bytes: ByteArray)
        private val queue = LinkedBlockingDeque<Packet>(48)
        private val alive = AtomicBoolean(true)
        private var awaitingKeyframe = true

        @Synchronized fun sendInit(mime: String, init: ByteArray) {
            queue.clear()
            awaitingKeyframe = true
            val config = JSONObject().put("type", "init").put("mime", mime).toString()
            queue.offer(Packet(1, config.toByteArray(Charsets.UTF_8)))
            queue.offer(Packet(2, init))
        }

        @Synchronized fun offerFragment(fragment: H264Mp4Muxer.Fragment) {
            if (awaitingKeyframe && !fragment.startsWithKeyframe) return
            if (fragment.startsWithKeyframe) awaitingKeyframe = false
            if (!queue.offer(Packet(2, fragment.bytes))) {
                queue.clear() // a slow browser must rejoin on a new IDR
                awaitingKeyframe = true
            }
        }

        fun start() {
            Thread({
                try {
                    while (alive.get()) {
                        val packet = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        val size = packet.bytes.size.toLong()
                        output.write(0x80 or packet.type)
                        when {
                            size < 126 -> output.write(size.toInt())
                            size <= 65535 -> {
                                output.write(126)
                                output.write((size.toInt() ushr 8) and 255)
                                output.write(size.toInt() and 255)
                            }
                            else -> {
                                output.write(127)
                                for (shift in 56 downTo 0 step 8) output.write(((size ushr shift) and 255).toInt())
                            }
                        }
                        output.write(packet.bytes)
                        output.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                }
            }, "Pochi-WS-Writer").start()
        }

        override fun close() {
            alive.set(false)
            runCatching { socket.close() }
        }
    }

    private class AudioClient(private val socket: Socket, private val output: OutputStream) : AutoCloseable {
        private val queue = LinkedBlockingDeque<ByteArray>(8)
        private val alive = AtomicBoolean(true)
        fun offer(bytes: ByteArray) {
            if (!queue.offer(bytes)) {
                queue.clear() // never build a growing audible delay on slow clients
                queue.offer(bytes)
            }
        }
        fun start() {
            Thread({
                try {
                    while (alive.get()) {
                        val chunk = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        val size = chunk.size
                        output.write(0x82)
                        if (size < 126) output.write(size)
                        else if (size <= 65535) {
                            output.write(126)
                            output.write((size ushr 8) and 255)
                            output.write(size and 255)
                        } else {
                            output.write(127)
                            for (shift in 56 downTo 0 step 8) output.write(((size.toLong() ushr shift) and 255).toInt())
                        }
                        output.write(chunk)
                        output.flush()
                    }
                } catch (_: Exception) {
                } finally { close() }
            }, "Pochi-Audio-WS").start()
        }
        override fun close() {
            alive.set(false)
            runCatching { socket.close() }
        }
    }

    private class MjpegClient(val socket: Socket, val output: OutputStream) : AutoCloseable {
        private val queue = LinkedBlockingDeque<ByteArray>(2)
        fun offer(frame: ByteArray) {
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
        }
        fun writeLoop(running: AtomicBoolean) {
            try {
                while (running.get()) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    output.write(("--pochi\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    output.write(frame)
                    output.write("\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }
        override fun close() = runCatching { socket.close() }.let { Unit }
    }

    companion object { private const val TAG = "PochiServer" }
}