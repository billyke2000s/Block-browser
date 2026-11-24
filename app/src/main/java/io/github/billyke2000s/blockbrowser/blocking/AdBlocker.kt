/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.blocking

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Block Browser's own ad blocker: a built-in extension (assets/extensions/blockbrowser-blocker) cancels requests to
 * the ~215,000 domains in its blocklist. This object installs it, keeps the list of sites the user turned blocking
 * off for (and sends it to the extension), and keeps the blocking statistics shown on the home page.
 */
object AdBlocker {
    private const val EXTENSION_ID = "blocker@blockbrowser.billyke2000s.github.io"
    private const val EXTENSION_URL = "resource://android/assets/extensions/blockbrowser-blocker/"
    private const val MESSAGE_ID = "blockbrowserBlocker"
    private const val PREFS = "blockbrowser_blocking"
    private const val KEY_ALLOWED = "allowed_sites"
    private const val KEY_TOTAL = "total"
    private const val KEY_DAYS = "days"
    private const val KEY_DOMAINS = "domains"
    private const val KEY_LIST_SIZE = "list_size"
    private const val KEEP_DAYS = 30L
    private const val KEEP_DOMAINS = 200
    private const val DEFAULT_LIST_SIZE = 215_000
    private val logger = Logger("AdBlocker")

    /** What the home page shows. */
    data class Stats(
        val today: Long = 0,
        val session: Long = 0,
        val total: Long = 0,
        /** Oldest first, the last entry is today. */
        val lastSevenDays: List<Pair<LocalDate, Long>> = emptyList(),
        /** Most blocked domains, most first (normal tabs only). */
        val topDomains: List<Pair<String, Long>> = emptyList(),
        val listSize: Int = DEFAULT_LIST_SIZE,
    )

    private lateinit var prefs: SharedPreferences
    private var port: Port? = null
    private var session = 0L
    private val allowed = mutableSetOf<String>()
    private val days = mutableMapOf<LocalDate, Long>()
    private val domains = mutableMapOf<String, Long>()
    private var total = 0L
    private var listSize = DEFAULT_LIST_SIZE
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** Installs the blocker. Call once at startup, on the main thread. */
    fun install(context: Context, engine: Engine) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { extension ->
                if (!extension.isAllowedInPrivateBrowsing()) {
                    engine.setAllowedInPrivateBrowsing(
                        extension,
                        true,
                        onError = { e -> logger.warn("Ad blocker not enabled in private tabs", e) },
                    )
                }
                extension.registerBackgroundMessageHandler(MESSAGE_ID, Handler)
            },
            onError = { e -> logger.error("Could not install the ad blocker", e) },
        )
    }

    /** Whether ads are blocked on [host] (false when the user turned blocking off for it or a parent domain). */
    fun isBlockingOn(host: String?): Boolean {
        if (host.isNullOrEmpty()) return true
        var h = host.lowercase()
        while (h.isNotEmpty()) {
            if (h in allowed) return false
            h = h.substringAfter('.', "")
        }
        return true
    }

    /** Turns ad blocking on or off for [host]. Turning it on also clears any parent domain's exception. */
    fun setBlockingOn(host: String, on: Boolean) {
        val h = host.lowercase()
        if (on) {
            var p = h
            while (p.isNotEmpty()) {
                allowed.remove(p)
                p = p.substringAfter('.', "")
            }
        } else {
            allowed.add(h)
        }
        saveAllowed()
        sendAllowed()
    }

    private object Handler : MessageHandler {
        override fun onPortConnected(port: Port) {
            AdBlocker.port = port
            sendAllowed()
        }

        override fun onPortDisconnected(port: Port) {
            if (AdBlocker.port === port) AdBlocker.port = null
        }

        override fun onPortMessage(message: Any, port: Port) {
            val json = message as? JSONObject ?: return
            when (json.optString("type")) {
                "ready" -> {
                    listSize = json.optInt("domains", listSize)
                    prefs.edit { putInt(KEY_LIST_SIZE, listSize) }
                    publish()
                }

                "blocked" -> record(json)

                "allow" -> {
                    val host = json.optString("host")
                    if (host.isNotEmpty()) {
                        allowed.add(host.lowercase())
                        saveAllowed()
                    }
                }
            }
        }
    }

    private fun record(json: JSONObject) {
        var added = json.optLong("private", 0)
        json.optJSONObject("hosts")?.let { hosts ->
            for (key in hosts.keys()) {
                val n = hosts.optLong(key, 0)
                added += n
                domains[key] = (domains[key] ?: 0) + n
            }
        }
        if (added <= 0) return
        session += added
        total += added
        val today = LocalDate.now()
        days[today] = (days[today] ?: 0) + added
        trim(today)
        save()
        publish()
    }

    private fun trim(today: LocalDate) {
        days.keys.filter { it.isBefore(today.minusDays(KEEP_DAYS)) }.forEach { days.remove(it) }
        if (domains.size > KEEP_DOMAINS * 2) {
            val keep = domains.entries.sortedByDescending { it.value }.take(KEEP_DOMAINS).map { it.key }.toSet()
            domains.keys.retainAll(keep)
        }
    }

    private fun publish() {
        val today = LocalDate.now()
        _stats.value =
            Stats(
                today = days[today] ?: 0,
                session = session,
                total = total,
                lastSevenDays = (6L downTo 0L).map { today.minusDays(it) }.map { it to (days[it] ?: 0) },
                topDomains = domains.entries.sortedByDescending { it.value }.take(TOP_SHOWN).map { it.key to it.value },
                listSize = listSize,
            )
    }

    private const val TOP_SHOWN = 5

    private fun sendAllowed() {
        val p = port ?: return
        try {
            p.postMessage(JSONObject().put("type", "allowed").put("hosts", JSONArray(allowed.toList())))
        } catch (e: IllegalStateException) {
            logger.warn("Could not send the allowed sites to the ad blocker", e)
        }
    }

    private fun load() {
        allowed.clear()
        allowed.addAll(prefs.getStringSet(KEY_ALLOWED, emptySet()).orEmpty())
        total = prefs.getLong(KEY_TOTAL, 0)
        listSize = prefs.getInt(KEY_LIST_SIZE, DEFAULT_LIST_SIZE)
        days.clear()
        runCatching {
            val o = JSONObject(prefs.getString(KEY_DAYS, "{}").orEmpty())
            for (k in o.keys()) days[LocalDate.parse(k)] = o.optLong(k)
        }
        domains.clear()
        runCatching {
            val o = JSONObject(prefs.getString(KEY_DOMAINS, "{}").orEmpty())
            for (k in o.keys()) domains[k] = o.optLong(k)
        }
        publish()
    }

    private fun save() {
        val d = JSONObject()
        days.forEach { (k, v) -> d.put(k.toString(), v) }
        val top = JSONObject()
        domains.forEach { (k, v) -> top.put(k, v) }
        prefs.edit {
            putLong(KEY_TOTAL, total)
            putString(KEY_DAYS, d.toString())
            putString(KEY_DOMAINS, top.toString())
        }
    }

    private fun saveAllowed() {
        prefs.edit { putStringSet(KEY_ALLOWED, allowed.toSet()) }
    }
}
