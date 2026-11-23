/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.kotlinx.coroutines.flow.filterChanged
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps track of the videos each tab has loaded, for the "Download video" menu item.
 *
 * The detection is done by a small built-in extension (assets/extensions/blockbrowser-media) that watches the
 * responses a tab receives. Its content script reports the tab's current list here; messages from content scripts
 * arrive tagged with their engine session, which is how each list is matched to its tab.
 */
object MediaFinder {
    private const val EXTENSION_ID = "media@blockbrowser.billyke2000s.github.io"
    private const val EXTENSION_URL = "resource://android/assets/extensions/blockbrowser-media/"
    private const val MESSAGE_ID = "blockbrowserMedia"
    private val logger = Logger("MediaFinder")

    /** What a page loaded that may be downloadable. */
    data class Item(
        val url: String,
        val kind: Kind,
        val mimeType: String,
        val size: Long,
        val inFrame: Boolean,
    )

    enum class Kind { HLS, DASH, FILE }

    /** The videos found for one tab, with the page they were found on. */
    data class Found(val pageUrl: String, val title: String, val items: List<Item>)

    private val byTab = ConcurrentHashMap<String, Found>()

    /** The videos the given tab's current page loaded, or null when nothing has been reported for it. */
    fun foundFor(tabId: String, currentUrl: String?): Found? {
        val found = byTab[tabId] ?: return null
        // Ignore a list reported for a page the tab has since left (the new page's list may not be in yet).
        if (currentUrl != null && stripFragment(found.pageUrl) != stripFragment(currentUrl)) {
            // Same-document navigations (history.pushState) keep the content script, and its list, alive.
            return found.takeIf { sameOrigin(it.pageUrl, currentUrl) }
        }
        return found
    }

    /** Installs the extension and starts listening to every tab. Call once, with the browser's store. */
    fun install(engine: Engine, store: BrowserStore) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { extension ->
                if (!extension.isAllowedInPrivateBrowsing()) {
                    engine.setAllowedInPrivateBrowsing(
                        extension,
                        true,
                        onError = { e -> logger.warn("Video finder not enabled in private tabs", e) },
                    )
                }
                store.flowScoped(dispatcher = Dispatchers.Main) { flow -> registerHandlers(flow, extension) }
            },
            onError = { e -> logger.error("Could not install the video finder", e) },
        )
    }

    private suspend fun registerHandlers(
        flow: kotlinx.coroutines.flow.Flow<mozilla.components.browser.state.state.BrowserState>,
        extension: WebExtension,
    ) {
        flow
            .map { state -> state.tabs + state.customTabs }
            .filterChanged<SessionState, EngineSession?> { it.engineState.engineSession }
            .collect { tab ->
                val session = tab.engineState.engineSession ?: return@collect
                if (!extension.hasContentMessageHandler(session, MESSAGE_ID)) {
                    extension.registerContentMessageHandler(session, MESSAGE_ID, Handler(tab.id))
                }
            }
    }

    private class Handler(private val tabId: String) : MessageHandler {
        override fun onMessage(message: Any, source: EngineSession?): Any? {
            val json = message as? JSONObject ?: return null
            if (json.optString("type") != "media") return null
            val items = mutableListOf<Item>()
            val array = json.optJSONArray("items")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                    val kind =
                        when (o.optString("kind")) {
                            "hls" -> Kind.HLS
                            "dash" -> Kind.DASH
                            "file" -> Kind.FILE
                            else -> continue
                        }
                    items.add(
                        Item(
                            url = url,
                            kind = kind,
                            mimeType = o.optString("mime"),
                            size = o.optLong("size", -1),
                            inFrame = o.optBoolean("inFrame"),
                        )
                    )
                }
            }
            byTab[tabId] = Found(json.optString("page"), json.optString("title"), items)
            return null
        }
    }

    private fun stripFragment(url: String) = url.substringBefore('#')

    private fun sameOrigin(a: String, b: String): Boolean {
        fun origin(u: String) = u.substringAfter("://", "").substringBefore('/').lowercase()
        return origin(a).isNotEmpty() && origin(a) == origin(b)
    }
}
