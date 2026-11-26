package com.mlmvpn.scanner.openvpn

import androidx.annotation.Keep

@Keep
object OpenVpnNative {
    @Volatile
    private var loaded: Boolean? = null

    @Synchronized
    fun available(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("mlmopenvpn")
            apiVersion() == 1
        } catch (_: Throwable) {
            false
        }
        loaded = ok
        return ok
    }

    external fun apiVersion(): Int
    external fun evaluate(config: String): String
    external fun create(): Long
    external fun run(handle: Long, config: String, username: String, password: String, callback: Any): String
    external fun stop(handle: Long)
    external fun release(handle: Long)
}
