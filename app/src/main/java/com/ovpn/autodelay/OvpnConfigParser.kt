package com.ovpn.autodelay

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class OvpnProfileItem(
    val name: String,
    val displayName: String,
    val flag: String,
    val rawConfig: String,
    val host: String,
    val port: Int,
    @Volatile var bestIp: String? = null,
    @Volatile var tcpPingMs: Long = Long.MAX_VALUE,
    @Volatile var realDelayMs: Long = Long.MAX_VALUE,
    @Volatile var isTesting: Boolean = false
)

object OvpnConfigParser {
    private const val SAVED_ZIP_NAME = "saved_servers.zip"

    fun saveZip(context: Context, input: InputStream): List<OvpnProfileItem> {
        val outFile = File(context.filesDir, SAVED_ZIP_NAME)
        outFile.outputStream().use { out -> input.copyTo(out) }
        return outFile.inputStream().use { parseFromZip(it) }
    }

    fun loadPersistedOrBundled(context: Context): List<OvpnProfileItem> {
        val saved = File(context.filesDir, SAVED_ZIP_NAME)
        if (saved.exists() && saved.length() > 100) {
            runCatching { saved.inputStream().use { return parseFromZip(it) } }
        }
        runCatching {
            context.assets.open("servers.zip").use { return parseFromZip(it) }
        }
        return emptyList()
    }

    fun parseFromZip(zipStream: InputStream): List<OvpnProfileItem> {
        val certs = mutableMapOf<String, String>()
        val rawOvpnFiles = mutableListOf<Pair<String, String>>()

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast("/")
                    when {
                        fileName.endsWith(".crt", ignoreCase = true) ||
                        fileName.endsWith(".pem", ignoreCase = true) -> {
                            certs[fileName.lowercase()] = zis.bufferedReader().readText().trim()
                        }
                        fileName.endsWith(".ovpn", ignoreCase = true) -> {
                            rawOvpnFiles.add(fileName to zis.bufferedReader().readText())
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val profiles = mutableListOf<OvpnProfileItem>()
        val seenHosts = mutableSetOf<String>()
        val remoteRegex = Regex("^remote\\s+(\\S+)\\s+(\\d+)", RegexOption.MULTILINE)
        val caRegex = Regex("^ca\\s+(\\S+)", RegexOption.MULTILINE)

        for ((fileName, rawContent) in rawOvpnFiles) {
            var content = rawContent
            val caMatch = caRegex.find(content)
            if (caMatch != null) {
                val caFile = caMatch.groupValues[1].trim().substringAfterLast("/").lowercase()
                val certBody = certs[caFile] ?: certs.values.firstOrNull()
                if (certBody != null) {
                    content = content.replace(caRegex, "")
                    content += "\n<ca>\n$certBody\n</ca>\n"
                }
            }

            val remoteMatch = remoteRegex.find(content)
            if (remoteMatch != null) {
                val host = remoteMatch.groupValues[1]
                val port = remoteMatch.groupValues[2].toIntOrNull() ?: 443
                val profileName = fileName.removeSuffix(".ovpn")
                val isTb = host.endsWith("lazerpenguin.com", ignoreCase = true)
                val key = if (isTb) host.lowercase() else "$host:$port:$profileName"
                if (seenHosts.add(key)) {
                    val clean = CountryFlags.cleanName(profileName)
                    profiles.add(
                        OvpnProfileItem(
                            name = profileName,
                            displayName = clean,
                            flag = CountryFlags.flagFor(host, clean),
                            rawConfig = content,
                            host = host,
                            port = if (isTb) 7011 else port
                        )
                    )
                }
            }
        }
        return profiles.sortedBy { it.displayName }
    }

    fun rewriteForLocalRelay(original: String, localPort: Int): String {
        val lines = original.lines().filterNot { line ->
            val t = line.trim().lowercase()
            t.startsWith("remote ") || t.startsWith("proto ") || t.startsWith("dev ")
        }
        val header = listOf(
            "dev tun",
            "proto tcp-client",
            "remote 127.0.0.1 $localPort"
        )
        var result = (header + lines).joinToString("\n")
        if (!result.contains("data-ciphers-fallback")) {
            result += "\ndata-ciphers-fallback AES-256-CBC\n"
        }
        return result
    }
}
