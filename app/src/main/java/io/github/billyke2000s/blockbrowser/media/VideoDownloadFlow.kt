/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media

import android.app.Activity
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import java.io.IOException
import java.util.Locale

/**
 * What happens after "Download video" in the menu: works out which of the videos the page loaded can be saved,
 * lets the user pick one (and a quality), then hands it to [StreamDownloadService] (streams) or to the browser's own
 * download manager (plain files).
 */
object VideoDownloadFlow {
    private val logger = Logger("VideoDownloadFlow")

    private sealed class Candidate(val label: String) {
        class File(val item: MediaFinder.Item, label: String) : Candidate(label)
        class HlsMaster(val url: String, val playlist: HlsPlaylist, label: String) : Candidate(label)
        class HlsMedia(val url: String, label: String) : Candidate(label)
        class Dash(val url: String, val manifest: DashManifest, label: String) : Candidate(label)
    }

    /** Starts the flow for the selected tab, or the given custom tab. Call on the main thread. */
    fun start(activity: Activity, customTabId: String? = null) {
        val components = activity.components
        val tab = components.core.store.state.findCustomTabOrSelectedTab(customTabId)
        val found = tab?.let { MediaFinder.foundFor(it.id, it.content.url) }
        if (tab == null || found == null || found.items.isEmpty()) {
            message(activity, R.string.bb_video_none_title, activity.getString(R.string.bb_video_none_message))
            return
        }
        val checking =
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.bb_video_checking)
                .setCancelable(true)
                .show()
        val fetcher = GeckoFetcher(components.core.client, found.pageUrl, tab.content.private)
        Thread({
            val problems = mutableListOf<String>()
            val candidates =
                try {
                    resolve(activity, found.items, fetcher, problems)
                } catch (e: Exception) {
                    logger.warn("Checking videos failed", e)
                    problems.add(e.message ?: e.javaClass.simpleName)
                    emptyList()
                }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !checking.isShowing) return@runOnUiThread
                checking.dismiss()
                choose(activity, tab, found, candidates, problems)
            }
        }, "video-check").start()
    }

    /** Loads each stream once to drop duplicates (a playlist's own quality playlists) and what can't be saved. */
    private fun resolve(
        activity: Activity,
        items: List<MediaFinder.Item>,
        fetcher: StreamDownload.Fetcher,
        problems: MutableList<String>,
    ): List<Candidate> {
        val streams = mutableListOf<Candidate>()
        val files = mutableListOf<Candidate>()
        val hlsMedia = mutableListOf<Pair<String, HlsPlaylist>>()
        val referenced = mutableSetOf<String>()
        for (item in items.take(MAX_CHECKED)) {
            try {
                when (item.kind) {
                    MediaFinder.Kind.HLS -> {
                        val p = StreamDownload.loadHls(fetcher, item.url)
                        if (p.isMaster) {
                            if (p.encryption != null) throw StreamDownload.Refused(COPY_PROTECTED)
                            p.variants.forEach { referenced.add(it.uri) }
                            p.renditions.forEach { r -> r.uri?.let { referenced.add(it) } }
                            val best = p.qualities().firstOrNull()?.label().orEmpty()
                            streams.add(Candidate.HlsMaster(item.url, p, label(activity, true, best, item.url)))
                        } else {
                            hlsMedia.add(item.url to p)
                        }
                    }

                    MediaFinder.Kind.DASH -> {
                        val m = StreamDownload.loadDash(fetcher, item.url)
                        StreamDownload.checkDash(m)
                        val best = (m.videoQualities().firstOrNull { !it.isProtected } ?: m.audios.firstOrNull())
                        streams.add(Candidate.Dash(item.url, m, label(activity, true, best?.label().orEmpty(), item.url)))
                    }

                    MediaFinder.Kind.FILE -> {
                        val size = if (item.size > 0) Formatter.formatShortFileSize(activity, item.size) else ""
                        files.add(Candidate.File(item, label(activity, false, size, item.url)))
                    }
                }
            } catch (e: StreamDownload.Refused) {
                problems.add(e.message.orEmpty())
            } catch (e: IOException) {
                logger.warn("Couldn't check ${item.kind}", e)
                problems.add(e.message.orEmpty())
            }
        }
        // Playlists of single qualities are offered only when no master playlist already covers them.
        for ((url, p) in hlsMedia) {
            if (url in referenced) continue
            try {
                StreamDownload.checkHls(p)
                streams.add(Candidate.HlsMedia(url, label(activity, true, duration(p.duration()), url)))
            } catch (e: IOException) {
                problems.add(e.message.orEmpty())
            }
        }
        return streams + files
    }

    private fun choose(
        activity: Activity,
        tab: SessionState,
        found: MediaFinder.Found,
        candidates: List<Candidate>,
        problems: List<String>,
    ) {
        if (candidates.isEmpty()) {
            val reason = problems.filter { it.isNotBlank() }.distinct().joinToString("\n")
            message(
                activity,
                R.string.bb_video_cant_title,
                reason.ifEmpty { activity.getString(R.string.bb_video_none_message) },
            )
            return
        }
        if (candidates.size == 1) {
            pickQuality(activity, tab, found, candidates[0])
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bb_video_choose)
            .setItems(candidates.map { it.label }.toTypedArray()) { _, which ->
                pickQuality(activity, tab, found, candidates[which])
            }
            .show()
    }

    private fun pickQuality(activity: Activity, tab: SessionState, found: MediaFinder.Found, c: Candidate) {
        val title = found.title.ifBlank { host(found.pageUrl).ifBlank { "video" } }
        val private = tab.content.private
        when (c) {
            is Candidate.File -> {
                activity.components.core.store.dispatch(
                    ContentAction.UpdateDownloadAction(
                        tab.id,
                        DownloadState(
                            url = c.item.url,
                            contentType = c.item.mimeType.ifBlank { null },
                            referrerUrl = found.pageUrl,
                            private = private,
                            sessionId = tab.id,
                        ),
                    )
                )
            }

            is Candidate.HlsMedia -> {
                StreamDownloadService.startHls(activity, c.url, null, title, found.pageUrl, private)
                started(activity)
            }

            is Candidate.HlsMaster -> {
                val qualities = c.playlist.qualities()
                val go = { v: HlsPlaylist.Variant ->
                    val audio = c.playlist.audioFor(v)?.uri
                    StreamDownloadService.startHls(activity, v.uri, audio, title, found.pageUrl, private)
                    started(activity)
                }
                if (qualities.size == 1) {
                    go(qualities[0])
                } else {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.bb_video_choose_quality)
                        .setItems(qualities.map { it.label() }.toTypedArray()) { _, which -> go(qualities[which]) }
                        .show()
                }
            }

            is Candidate.Dash -> {
                val videos = c.manifest.videoQualities().filter { !it.isProtected }
                val go = { v: DashManifest.Representation ->
                    val audio = if (v.isAudio) null else c.manifest.audioFor(v)?.takeIf { !it.isProtected }
                    StreamDownloadService.startDash(activity, c.url, v.id, audio?.id, title, found.pageUrl, private)
                    started(activity)
                }
                when {
                    videos.isEmpty() -> c.manifest.audios.firstOrNull { !it.isProtected }?.let(go)
                    videos.size == 1 -> go(videos[0])
                    else ->
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.bb_video_choose_quality)
                            .setItems(videos.map { it.label() }.toTypedArray()) { _, which -> go(videos[which]) }
                            .show()
                }
            }
        }
    }

    private fun started(activity: Activity) {
        Toast.makeText(activity, R.string.bb_video_started, Toast.LENGTH_LONG).show()
    }

    private fun message(activity: Activity, title: Int, text: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** "Video stream · 1080p · 5.2 Mbps · cdn.example.com" or "Video file · clip.mp4 · 12 MB". */
    private fun label(activity: Activity, stream: Boolean, detail: String, url: String): String {
        val kind = activity.getString(if (stream) R.string.bb_video_stream else R.string.bb_video_file)
        val name =
            if (stream) {
                host(url)
            } else {
                Uri.parse(url).lastPathSegment?.take(MAX_NAME).orEmpty()
            }
        return listOf(kind, detail, name).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun host(url: String) = Uri.parse(url).host.orEmpty().removePrefix("www.")

    private fun duration(seconds: Double): String {
        val s = seconds.toLong()
        if (s <= 0) return ""
        return if (s >= HOUR) {
            String.format(Locale.ROOT, "%d:%02d:%02d", s / HOUR, s % HOUR / MINUTE, s % MINUTE)
        } else {
            String.format(Locale.ROOT, "%d:%02d", s / MINUTE, s % MINUTE)
        }
    }

    private const val MAX_CHECKED = 12
    private const val MAX_NAME = 40
    private const val HOUR = 3600L
    private const val MINUTE = 60L
    private const val COPY_PROTECTED = "This video is copy-protected, so it can't be downloaded"
}
