package com.ovpn.autodelay

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

class OpenVpnSplitRelay(
    private val service: VpnService,
    private val hosts: List<String>,
    private val port: Int,
) {
    init { require(hosts.isNotEmpty()) }

    private val resolvedIps: List<String> by lazy {
        val list = mutableListOf<String>()
        for (h in hosts) {
            if (h.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                list.add(h)
            } else {
                val all = runCatching { InetAddress.getAllByName(h).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
                if (all.isNotEmpty()) list.addAll(all) else list.add(h)
            }
        }
        list.distinct()
    }

    @Volatile private var next = 0
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = server.localPort
    @Volatile private var closed = false
    private val open = mutableListOf<Socket>()

    fun start() {
        thread(name = "ovpn-relay-accept", isDaemon = true) {
            while (!closed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "ovpn-relay-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        synchronized(open) { open += client }
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val (host, up) = connect()
            upstream = up
            val t0 = System.currentTimeMillis()
            val clamped = clamp(up, HANDSHAKE_WINDOW)
            val trace = Trace(t0)
            if (clamped) thread(name = "ovpn-relay-unclamp", isDaemon = true) {
                try {
                    while (!closed && trace.downBytes < UNCLAMP_AFTER_BYTES &&
                        System.currentTimeMillis() - t0 < UNCLAMP_AFTER_MS) Thread.sleep(100)
                    clamp(up, OPEN_WINDOW)
                } catch (_: Exception) { }
            }
            thread(name = "ovpn-relay-down", isDaemon = true) {
                try {
                    pump(up.getInputStream(), client.getOutputStream(), trace)
                    if (trace.downBytes < HANDSHAKE_BYTES) skip(host)
                } catch (_: Exception) {
                } finally {
                    runCatching { client.shutdownOutput() }
                }
            }
            upSplit(client.getInputStream(), up.getOutputStream(), trace)
            if (trace.downBytes < HANDSHAKE_BYTES) skip(host)
            runCatching { up.shutdownOutput() }
        } catch (e: Exception) {
            Log.w(TAG, "relay: ${e.message}")
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun connect(): Pair<String, Socket> {
        val targets = resolvedIps
        var last: Exception? = null
        val start = synchronized(this) {
            val s = next % targets.size
            next = (s + 1) % targets.size
            s
        }
        for (i in targets.indices) {
            if (closed) break
            val at = (start + i) % targets.size
            val socket = Socket()
            synchronized(open) { open += socket }
            try {
                socket.tcpNoDelay = true
                service.protect(socket)
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(targets[at]), port),
                    if (targets.size > 1) FAILOVER_CONNECT_MS else CONNECT_MS
                )
                return targets[at] to socket
            } catch (e: java.io.IOException) {
                last = e
                runCatching { socket.close() }
                synchronized(open) { open -= socket }
            }
        }
        throw last ?: java.io.IOException("relay closed")
    }

    private fun skip(host: String) {
        val targets = resolvedIps
        if (targets.size > 1 && targets[next % targets.size] == host) {
            next = (next + 1) % targets.size
        }
    }

    private fun upSplit(input: InputStream, output: OutputStream, trace: Trace) {
        val rng = SecureRandom()
        val buf = ByteArray(16 * 1024)
        var budget = SPLIT_BYTES
        var first = true
        var resetLeft = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            trace.up(n)
            var at = 0
            if (resetLeft != 0) {
                if (resetLeft < 0) {
                    if (n < 2) { output.write(buf, 0, n); output.flush(); resetLeft = 0; continue }
                    resetLeft = 2 + (((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff))
                }
                val whole = minOf(resetLeft, n)
                output.write(buf, 0, whole)
                output.flush()
                resetLeft -= whole
                at = whole
                if (at >= n) continue
            }
            if (budget <= 0) { output.write(buf, at, n - at); output.flush(); continue }
            while (at < n) {
                val size = if (first) 1 else MIN_PIECE + rng.nextInt(MAX_PIECE - MIN_PIECE + 1)
                first = false
                val end = minOf(n, at + size)
                output.write(buf, at, end - at)
                output.flush()
                budget -= end - at
                at = end
                if (budget <= 0) { if (at < n) { output.write(buf, at, n - at); output.flush() }; break }
                if (at < n) Thread.sleep(PAUSE_MIN_MS + rng.nextInt(PAUSE_SPREAD_MS).toLong())
            }
        }
    }

    private fun clamp(socket: Socket, bytes: Int): Boolean = try {
        android.os.ParcelFileDescriptor.fromSocket(socket).use { pfd ->
            android.system.Os.setsockoptInt(pfd.fileDescriptor, IPPROTO_TCP, TCP_WINDOW_CLAMP, bytes)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun pump(input: InputStream, output: OutputStream, trace: Trace) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) return
                trace.down(n)
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) { }
    }

    private class Trace(private val t0: Long) {
        @Volatile var upBytes = 0L
        @Volatile var downBytes = 0L
        fun up(n: Int) { upBytes += n }
        fun down(n: Int) { downBytes += n }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } }; open.clear() }
    }

    companion object {
        private const val TAG = "OpenVpnRelay"
        private const val CONNECT_MS = 3_000
        private const val FAILOVER_CONNECT_MS = 1_500
        private const val HANDSHAKE_BYTES = 2048L
        private const val IPPROTO_TCP = 6
        private const val TCP_WINDOW_CLAMP = 10
        private const val HANDSHAKE_WINDOW = 1024
        private const val OPEN_WINDOW = 16 * 1024 * 1024
        private const val UNCLAMP_AFTER_BYTES = 12 * 1024L
        private const val UNCLAMP_AFTER_MS = 20_000L
        const val SPLIT_BYTES = 6 * 1024
        private const val MIN_PIECE = 3
        private const val MAX_PIECE = 29
        private const val PAUSE_MIN_MS = 2
        private const val PAUSE_SPREAD_MS = 6
    }
}
