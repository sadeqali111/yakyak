package com.ovpn.autodelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import com.mlmvpn.scanner.openvpn.OpenVpnNative
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

class AutoDelayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mainJob: Job? = null
    @Volatile private var activeSession: NativeSession? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "یک یک شون VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT_FASTEST -> {
                startForeground(NOTIF_ID, buildNotification("Finding fastest server..."))
                mainJob?.cancel()
                mainJob = scope.launch { fastScanAndConnect(connectWinner = true) }
            }
            ACTION_FAST_SCAN_ONLY -> {
                startForeground(NOTIF_ID, buildNotification("Fast scanning servers..."))
                mainJob?.cancel()
                mainJob = scope.launch { fastScanAndConnect(connectWinner = false) }
            }
            ACTION_CONNECT_SINGLE -> {
                val name = intent.getStringExtra(EXTRA_PROFILE_NAME)
                startForeground(NOTIF_ID, buildNotification("Connecting to $name..."))
                mainJob?.cancel()
                mainJob = scope.launch { connectSingleByName(name) }
            }
            ACTION_STOP -> {
                mainJob?.cancel()
                activeSession?.stop()
                activeSession?.forceCleanup()
                activeSession = null
                VpnStateBus.connState.value = ConnState.DISCONNECTED
                VpnStateBus.connectedDelayMs.value = null
                VpnStateBus.statusSubtext.value = "⚡ Tap to connect"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 2-Phase Fast Scan:
     * Phase 1: Parallel TCP-7011 probe across ALL 44-47 countries concurrently (~3-4 seconds!).
     * Phase 2: Real Delay (HTTPS gstatic 204) test ONLY on top 5 live candidates (~20-30 seconds!).
     */
    private suspend fun fastScanAndConnect(connectWinner: Boolean) {
        if (!OpenVpnNative.available()) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.statusSubtext.value = "Error: libmlmopenvpn.so missing"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val profiles = VpnStateBus.loadedProfiles
        val user = VpnStateBus.username
        val pass = VpnStateBus.password
        if (profiles.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.statusSubtext.value = "Import ZIP & add account first"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        activeSession?.stop()
        activeSession?.forceCleanup()
        activeSession = null

        VpnStateBus.connState.value = ConnState.SCANNING
        VpnStateBus.connectedDelayMs.value = null
        VpnStateBus.statusSubtext.value = "Phase 1: Fast probing ${profiles.size} servers..."
        VpnStateBus.scanHint.value = "Probing ${profiles.size} countries in parallel..."

        // Phase 1: Parallel TCP-7011 probe (takes ~3-4 seconds total!)
        val tStart = System.currentTimeMillis()
        val sem = Semaphore(20)
        coroutineScope {
            profiles.map { p ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        p.isTesting = true
                        val (ip, rtt) = probeBestIp(p.host, p.port)
                        p.bestIp = ip
                        p.tcpPingMs = rtt
                        p.isTesting = false
                    }
                }
            }.awaitAll()
        }
        val phase1Sec = ((System.currentTimeMillis() - tStart) / 1000.0)
        val reachable = profiles.filter { it.tcpPingMs < Long.MAX_VALUE }.sortedBy { it.tcpPingMs }
        VpnStateBus.loadedProfiles = reachable + profiles.filter { it.tcpPingMs == Long.MAX_VALUE }
        VpnStateBus.notifyListChanged()

        if (reachable.isEmpty()) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.statusSubtext.value = "No reachable servers found"
            VpnStateBus.scanHint.value = "0 reachable servers"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Phase 2: Real Delay test on Top 5 fastest reachable countries
        val topCandidates = reachable.take(5)
        VpnStateBus.scanHint.value = "Phase 1 done (${"%.1f".format(phase1Sec)}s). Verifying Real Delay on top ${topCandidates.size}..."
        val working = mutableListOf<OvpnProfileItem>()

        for ((idx, p) in topCandidates.withIndex()) {
            if (!scope.isActive) return
            p.isTesting = true
            VpnStateBus.notifyListChanged()
            VpnStateBus.statusSubtext.value = "Testing [${idx + 1}/${topCandidates.size}] ${p.displayName}..."
            updateNotification("Testing ${p.displayName} (${idx + 1}/${topCandidates.size})")

            val realMs = testSingleProfile(p, user, pass, handshakeTimeoutMs = 14_000L)
            p.realDelayMs = realMs
            p.isTesting = false
            if (realMs < Long.MAX_VALUE) {
                working.add(p)
            }
            // Re-sort so verified Real Delay servers sit at the very top
            VpnStateBus.loadedProfiles = VpnStateBus.loadedProfiles.sortedWith(
                compareBy<OvpnProfileItem> { it.realDelayMs }
                    .thenBy { it.tcpPingMs }
                    .thenBy { it.displayName }
            )
            VpnStateBus.notifyListChanged()
        }

        val winner = working.minByOrNull { it.realDelayMs }
        val totalSec = (System.currentTimeMillis() - tStart) / 1000
        VpnStateBus.scanHint.value = "Scan finished in ${totalSec}s (${working.size} verified)"

        if (!connectWinner) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.statusSubtext.value = if (winner != null) "Best: ${winner.displayName} (${winner.realDelayMs}ms)" else "Scan complete"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (winner == null) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.statusSubtext.value = "No working server found"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        connectAndStay(winner, user, pass)
    }

    private suspend fun connectSingleByName(name: String?) {
        val profile = VpnStateBus.loadedProfiles.firstOrNull { it.name == name }
        val user = VpnStateBus.username
        val pass = VpnStateBus.password
        if (profile == null || user.isEmpty() || pass.isEmpty()) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        activeSession?.stop()
        activeSession?.forceCleanup()
        activeSession = null

        VpnStateBus.connState.value = ConnState.CONNECTING
        VpnStateBus.activeProfile.value = profile
        VpnStateBus.statusSubtext.value = "Connecting to ${profile.displayName}..."

        // Quick 1.5s IP pre-check so relay hits a live IP first
        if (profile.bestIp == null) {
            val (ip, rtt) = probeBestIp(profile.host, profile.port)
            profile.bestIp = ip
            profile.tcpPingMs = rtt
        }
        connectAndStay(profile, user, pass)
    }

    private suspend fun connectAndStay(profile: OvpnProfileItem, user: String, pass: String) {
        VpnStateBus.connState.value = ConnState.CONNECTING
        VpnStateBus.activeProfile.value = profile
        VpnStateBus.statusSubtext.value = "Connecting to ${profile.displayName}..."
        updateNotification("Connecting to ${profile.displayName}...")

        val session = NativeSession(profile, user, pass)
        activeSession = session
        val job = scope.launch { session.runBlockingForever() }

        val connected = withTimeoutOrNull(25_000L) {
            while (!session.isConnected && !session.isFinished) delay(200)
            session.isConnected
        } ?: false

        if (!connected) {
            session.stop()
            session.forceCleanup()
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.statusSubtext.value = "Failed to connect to ${profile.displayName}"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        VpnStateBus.connState.value = ConnState.CONNECTED
        VpnStateBus.statusSubtext.value = "Measuring real delay..."
        delay(1_200)
        var realDelay = Long.MAX_VALUE
        for (attempt in 1..3) {
            realDelay = measureRealHttpDelay(6_000)
            if (realDelay < Long.MAX_VALUE) break
            if (attempt < 3) delay(800)
        }
        if (realDelay < Long.MAX_VALUE) {
            profile.realDelayMs = realDelay
            VpnStateBus.connectedDelayMs.value = realDelay
            VpnStateBus.statusSubtext.value = "⚡ ${realDelay} ms"
            updateNotification("Connected: ${profile.displayName} (${realDelay} ms)")
            VpnStateBus.notifyListChanged()
        } else {
            VpnStateBus.statusSubtext.value = "Connected (no ping)"
            updateNotification("Connected: ${profile.displayName}")
        }

        job.join()
        if (VpnStateBus.connState.value != ConnState.DISCONNECTED) {
            VpnStateBus.connState.value = ConnState.DISCONNECTED
            VpnStateBus.connectedDelayMs.value = null
            VpnStateBus.statusSubtext.value = "⚡ Tap to connect"
        }
    }

    private suspend fun probeBestIp(host: String, port: Int): Pair<String?, Long> = withContext(Dispatchers.IO) {
        val ips = runCatching {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct().take(4)
        }.getOrDefault(emptyList())
        if (ips.isEmpty()) return@withContext (null to Long.MAX_VALUE)

        val results = ips.map { ip ->
            async(Dispatchers.IO) {
                val sock = Socket()
                try {
                    protect(sock)
                    val ms = measureTimeMillis {
                        sock.connect(InetSocketAddress(InetAddress.getByName(ip), port), 2_000)
                    }
                    ip to ms
                } catch (_: Exception) {
                    ip to Long.MAX_VALUE
                } finally {
                    runCatching { sock.close() }
                }
            }
        }.awaitAll()

        val best = results.minByOrNull { it.second }
        if (best != null && best.second < Long.MAX_VALUE) best else (null to Long.MAX_VALUE)
    }

    private suspend fun testSingleProfile(
        profile: OvpnProfileItem,
        user: String,
        pass: String,
        handshakeTimeoutMs: Long
    ): Long {
        val session = NativeSession(profile, user, pass)
        activeSession = session
        val job = scope.launch { session.runBlockingForever() }

        val connected = withTimeoutOrNull(handshakeTimeoutMs) {
            while (!session.isConnected && !session.isFinished) delay(200)
            session.isConnected
        } ?: false

        var realDelay = Long.MAX_VALUE
        if (connected) {
            delay(1_200)
            for (attempt in 1..3) {
                realDelay = measureRealHttpDelay(5_000)
                if (realDelay < Long.MAX_VALUE) break
                if (attempt < 3) delay(800)
            }
        }

        session.stop()
        withTimeoutOrNull(3_000L) { job.join() }
        session.forceCleanup()
        activeSession = null
        delay(250)
        return realDelay
    }

    private fun measureRealHttpDelay(timeoutMs: Int): Long {
        return try {
            var code = 0
            val elapsed = measureTimeMillis {
                val conn = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.useCaches = false
                conn.setRequestProperty("Connection", "close")
                code = conn.responseCode
                conn.disconnect()
            }
            if (code == 204 || code == 200) elapsed else Long.MAX_VALUE
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    @Keep
    private inner class NativeSession(
        private val profile: OvpnProfileItem,
        private val username: String,
        private val password: String
    ) {
        private val stopped = AtomicBoolean(false)
        private val handle = AtomicLong(0L)
        private var builder: Builder? = null
        private var retainedTun: ParcelFileDescriptor? = null
        private var relay: OpenVpnSplitRelay? = null

        @Volatile var isConnected = false
        @Volatile var isFinished = false

        fun runBlockingForever() {
            try {
                val targets = buildList {
                    profile.bestIp?.let { add(it) }
                    add(profile.host)
                }
                val r = OpenVpnSplitRelay(this@AutoDelayVpnService, targets, profile.port)
                relay = r
                r.start()
                val cfg = OvpnConfigParser.rewriteForLocalRelay(profile.rawConfig, r.localPort)
                val evalErr = OpenVpnNative.evaluate(cfg)
                if (evalErr.isNotEmpty()) return
                val id = OpenVpnNative.create()
                if (id == 0L) return
                handle.set(id)
                if (stopped.get()) {
                    OpenVpnNative.stop(id)
                    return
                }
                OpenVpnNative.run(id, cfg, username, password, this)
            } catch (_: Exception) {
            } finally {
                isFinished = true
                forceCleanup()
            }
        }

        fun stop() {
            stopped.set(true)
            handle.get().takeIf { it != 0L }?.let { runCatching { OpenVpnNative.stop(it) } }
        }

        fun forceCleanup() {
            stopped.set(true)
            relay?.let { relay = null; runCatching { it.close() } }
            synchronized(this) {
                runCatching { retainedTun?.close() }
                retainedTun = null
            }
            handle.getAndSet(0L).takeIf { it != 0L }?.let { runCatching { OpenVpnNative.release(it) } }
        }

        @Keep fun configureTun(operation: String, value: Int): Boolean = runCatching {
            when (operation) {
                "new" -> { builder = Builder().setSession("YekYekShoonVPN").setBlocking(false); true }
                "mtu" -> { builder?.setMtu(value.coerceIn(576, 9000)); true }
                "protect" -> protect(value)
                else -> false
            }
        }.getOrDefault(false)

        @Keep fun addAddress(address: String, prefix: Int): Boolean = runCatching {
            builder?.addAddress(address, prefix); true
        }.getOrDefault(false)

        @Keep fun addRoute(address: String, prefix: Int): Boolean = runCatching {
            builder?.addRoute(address, prefix); true
        }.getOrDefault(false)

        @Keep fun addDns(address: String, ignored: Int): Boolean = runCatching {
            builder?.addDnsServer(address); true
        }.getOrDefault(false)

        @Keep fun addSearchDomain(domain: String, ignored: Int): Boolean = runCatching {
            builder?.addSearchDomain(domain); true
        }.getOrDefault(false)

        @Keep fun establishTun(): Int {
            if (stopped.get()) return -1
            val tun = builder?.establish() ?: return -1
            retainedTun?.close()
            retainedTun = tun
            return ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd()
        }

        @Keep fun onCoreEvent(name: String, error: Boolean) {
            if (name == "CONNECTED") isConnected = true
            if (name == "AUTH_FAILED") stop()
        }

        @Keep fun onCoreTraffic(bytesOut: Long, bytesIn: Long) {}
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("یک یک شون VPN")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        mainJob?.cancel()
        activeSession?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT_FASTEST = "com.ovpn.autodelay.CONNECT_FASTEST"
        const val ACTION_FAST_SCAN_ONLY = "com.ovpn.autodelay.FAST_SCAN_ONLY"
        const val ACTION_CONNECT_SINGLE = "com.ovpn.autodelay.CONNECT_SINGLE"
        const val ACTION_STOP = "com.ovpn.autodelay.STOP"
        const val EXTRA_PROFILE_NAME = "profile_name"
        private const val CHANNEL_ID = "yekyekshoon_vpn"
        private const val NOTIF_ID = 701
    }
}
