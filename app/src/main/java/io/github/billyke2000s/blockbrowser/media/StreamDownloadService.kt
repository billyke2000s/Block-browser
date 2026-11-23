/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mozilla.components.ui.icons.R as iconsR

/**
 * Downloads a video stream (HLS or DASH) in the background, with a progress notification and a Cancel button, then
 * converts it to a normal video file (without re-encoding) and saves it to the phone's Downloads folder.
 */
class StreamDownloadService : Service() {
    private val logger = Logger("StreamDownloadService")
    private val executor = Executors.newCachedThreadPool()
    private val cancelled = ConcurrentHashMap<Int, Boolean>()
    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) {
            val id = intent.getIntExtra(EXTRA_JOB, -1)
            if (id >= 0) cancelled[id] = true
            stopIfIdle()
            return START_NOT_STICKY
        }
        val job = Job.from(intent) ?: run {
            stopIfIdle()
            return START_NOT_STICKY
        }
        createChannel(this)
        val notification = progressNotification(job, getString(R.string.bb_video_preparing), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(job.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(job.id, notification)
        }
        running.incrementAndGet()
        cancelled[job.id] = false
        executor.execute { run(job) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled.keys.forEach { cancelled[it] = true }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun run(job: Job) {
        val work = File(cacheDir, "stream-download-${job.id}")
        val manager = getSystemService(NotificationManager::class.java)
        try {
            val fetcher = GeckoFetcher(components.core.client, job.referrer, job.private)
            val (video, audio) = tracks(fetcher, job)
            val listener =
                object : StreamDownload.Listener {
                    override fun progress(piecesDone: Int, piecesTotal: Int, bytes: Long) {
                        val percent = if (piecesTotal > 0) piecesDone * PERCENT / piecesTotal else 0
                        val text =
                            getString(
                                R.string.bb_video_progress,
                                percent,
                                Formatter.formatShortFileSize(this@StreamDownloadService, bytes),
                            )
                        manager.notify(job.id, progressNotification(job, text, percent))
                    }

                    override fun cancelled() = cancelled[job.id] == true
                }
            val result = StreamDownload.download(fetcher, video, audio, work, listener)
            if (listener.cancelled()) throw StreamDownload.Cancelled()
            manager.notify(job.id, progressNotification(job, getString(R.string.bb_video_converting), -1))
            val extension = StreamRemuxer.outputExtension(result.video)
            val out = File(work, "video.$extension")
            StreamRemuxer.remux(result.video, result.audio, out, result.duration)
            val uri = save(out, fileName(job.title, extension), mimeType(extension))
            finish(job, done(job, uri, mimeType(extension)))
        } catch (e: StreamDownload.Cancelled) {
            finish(job, null)
        } catch (e: StreamDownload.Refused) {
            finish(job, failed(job, e.message ?: getString(R.string.bb_video_failed)))
        } catch (e: Exception) {
            logger.warn("Stream download failed", e)
            finish(job, failed(job, e.message ?: e.javaClass.simpleName))
        } finally {
            work.deleteRecursively()
        }
    }

    /** The video track and, when the stream keeps it separate, the audio track. */
    private fun tracks(fetcher: StreamDownload.Fetcher, job: Job): Pair<StreamTrack, StreamTrack?> =
        when (job.kind) {
            KIND_HLS -> {
                val video = StreamDownload.loadHls(fetcher, job.url)
                StreamDownload.checkHls(video)
                val audio = job.audioUrl?.let { StreamDownload.loadHls(fetcher, it) }
                audio?.let { StreamDownload.checkHls(it) }
                StreamTrack.fromHls(video) to audio?.let { StreamTrack.fromHls(it) }
            }

            KIND_DASH -> {
                val manifest = StreamDownload.loadDash(fetcher, job.url)
                StreamDownload.checkDash(manifest)
                val all = manifest.videos + manifest.audios
                val video = all.firstOrNull { it.id == job.videoId } ?: throw IOException("the chosen quality is gone")
                val audio = job.audioId?.let { id -> manifest.audios.firstOrNull { it.id == id } }
                StreamDownload.checkDash(video)
                audio?.let { StreamDownload.checkDash(it) }
                video.track to audio?.track
            }

            else -> throw IOException("unknown stream type")
        }

    /** Copies the finished file into Downloads and returns its address for opening it. */
    private fun save(file: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val resolver = contentResolver
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("couldn't create the file in Downloads")
            try {
                resolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                    ?: throw IOException("couldn't write to Downloads")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) throw StreamDownload.Refused(getString(R.string.bb_video_storage_permission))
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("couldn't open Downloads")
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')).also { n++ }
        file.copyTo(target)
        // Ask Android to index it, which also gives a content:// address other apps may open.
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(this, arrayOf(target.path), arrayOf(mime)) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(target)
    }

    private fun finish(job: Job, notification: Notification?) {
        val manager = getSystemService(NotificationManager::class.java)
        cancelled.remove(job.id)
        if (running.decrementAndGet() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        manager.cancel(job.id)
        // A separate id, so the result stays after the foreground notification is removed.
        notification?.let { manager.notify(job.id + RESULT_ID_OFFSET, it) }
    }

    private fun stopIfIdle() {
        if (running.get() <= 0) stopSelf()
    }

    private fun progressNotification(job: Job, text: String, percent: Int): Notification {
        val cancel =
            PendingIntent.getService(
                this,
                job.id,
                Intent(this, StreamDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB, job.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconsR.drawable.mozac_ic_download_24)
            .setContentTitle(job.title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT, percent.coerceAtLeast(0), percent < 0)
            .addAction(0, getString(R.string.bb_video_cancel), cancel)
            .build()
    }

    private fun done(job: Job, uri: Uri, mime: String): Notification {
        val open =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending =
            PendingIntent.getActivity(
                this,
                job.id,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconsR.drawable.mozac_ic_download_24)
            .setContentTitle(job.title)
            .setContentText(getString(R.string.bb_video_done))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    }

    private fun failed(job: Job, reason: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconsR.drawable.mozac_ic_download_24)
            .setContentTitle(getString(R.string.bb_video_failed) + ": " + job.title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .build()

    /** What to download, as passed in the intent that starts the service. */
    internal data class Job(
        val id: Int,
        val kind: String,
        val url: String,
        val audioUrl: String?,
        val videoId: String?,
        val audioId: String?,
        val title: String,
        val referrer: String?,
        val private: Boolean,
    ) {
        companion object {
            fun from(intent: Intent): Job? {
                val url = intent.getStringExtra(EXTRA_URL) ?: return null
                return Job(
                    id = intent.getIntExtra(EXTRA_JOB, -1).takeIf { it >= 0 } ?: return null,
                    kind = intent.getStringExtra(EXTRA_KIND) ?: return null,
                    url = url,
                    audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL),
                    videoId = intent.getStringExtra(EXTRA_VIDEO_ID),
                    audioId = intent.getStringExtra(EXTRA_AUDIO_ID),
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "Video",
                    referrer = intent.getStringExtra(EXTRA_REFERRER),
                    private = intent.getBooleanExtra(EXTRA_PRIVATE, false),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "blockbrowser_video_downloads"
        private const val ACTION_CANCEL = "io.github.billyke2000s.blockbrowser.CANCEL_VIDEO_DOWNLOAD"
        private const val EXTRA_JOB = "job"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO_URL = "audioUrl"
        private const val EXTRA_VIDEO_ID = "videoId"
        private const val EXTRA_AUDIO_ID = "audioId"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REFERRER = "referrer"
        private const val EXTRA_PRIVATE = "private"
        private const val PERCENT = 100
        private const val RESULT_ID_OFFSET = 1_000_000
        private const val SCAN_TIMEOUT_S = 10L
        private const val MAX_NAME = 80
        const val KIND_HLS = "hls"
        const val KIND_DASH = "dash"
        private val nextId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 50_000)

        /** HLS: [mediaPlaylist] for the video (with its audio, if muxed) and an optional separate [audioPlaylist]. */
        fun startHls(
            context: Context,
            mediaPlaylist: String,
            audioPlaylist: String?,
            title: String,
            referrer: String?,
            private: Boolean,
        ) = start(context, KIND_HLS, mediaPlaylist, audioPlaylist, null, null, title, referrer, private)

        /** DASH: the manifest, and the ids of the chosen video and audio representations. */
        fun startDash(
            context: Context,
            manifest: String,
            videoId: String,
            audioId: String?,
            title: String,
            referrer: String?,
            private: Boolean,
        ) = start(context, KIND_DASH, manifest, null, videoId, audioId, title, referrer, private)

        @Suppress("LongParameterList")
        private fun start(
            context: Context,
            kind: String,
            url: String,
            audioUrl: String?,
            videoId: String?,
            audioId: String?,
            title: String,
            referrer: String?,
            private: Boolean,
        ) {
            val intent =
                Intent(context, StreamDownloadService::class.java)
                    .putExtra(EXTRA_JOB, nextId.incrementAndGet())
                    .putExtra(EXTRA_KIND, kind)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_AUDIO_URL, audioUrl)
                    .putExtra(EXTRA_VIDEO_ID, videoId)
                    .putExtra(EXTRA_AUDIO_ID, audioId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_REFERRER, referrer)
                    .putExtra(EXTRA_PRIVATE, private)
            ContextCompat.startForegroundService(context, intent)
        }

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.bb_video_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }

        /** A file name from the page title: no characters Android or other apps reject. */
        fun fileName(title: String, extension: String): String {
            val clean =
                title
                    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .trim('.')
                    .take(MAX_NAME)
                    .ifEmpty { "video" }
            return "$clean.$extension"
        }

        fun mimeType(extension: String) = if (extension == "webm") "video/webm" else "video/mp4"
    }
}
