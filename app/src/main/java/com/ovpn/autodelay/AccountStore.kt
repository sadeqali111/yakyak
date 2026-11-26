package com.ovpn.autodelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VpnAccount(
    val id: String,
    val username: String,
    val password: String,
    val active: Boolean
)

object AccountStore {
    private const val PREF = "yekyekshoon_accounts"
    private const val KEY = "accounts_json"

    fun load(context: Context): MutableList<VpnAccount> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val list = mutableListOf<VpnAccount>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VpnAccount(
                        id = o.optString("id", i.toString()),
                        username = o.optString("u", ""),
                        password = o.optString("p", ""),
                        active = o.optBoolean("a", false)
                    )
                )
            }
        }
        return list
    }

    fun save(context: Context, accounts: List<VpnAccount>) {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("u", a.username)
                put("p", a.password)
                put("a", a.active)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun activeAccount(context: Context): VpnAccount? {
        val list = load(context)
        return list.firstOrNull { it.active } ?: list.firstOrNull()
    }
}
