/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media

import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [StreamDownload.Fetcher] that goes through the browser's own network stack (Gecko), so stream pieces are fetched
 * with the same cookies, user agent and certificate checks as the page, and from a private tab's own cookie jar.
 */
class GeckoFetcher(
    private val client: Client,
    private val referrer: String?,
    private val private: Boolean,
) : StreamDownload.Fetcher {

    override fun get(url: String, offset: Long, length: Long, maxBytes: Int): StreamDownload.Response {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            throw IOException("unsupported address")
        }
        val headers = MutableHeaders()
        if (offset >= 0) {
            val end = if (length >= 0) (offset + length - 1).toString() else ""
            headers.set("Range", "bytes=$offset-$end")
        }
        val request =
            Request(
                url = url,
                headers = headers,
                connectTimeout = CONNECT_TIMEOUT_S to TimeUnit.SECONDS,
                readTimeout = READ_TIMEOUT_S to TimeUnit.SECONDS,
                cookiePolicy = Request.CookiePolicy.INCLUDE,
                private = private,
                referrerUrl = referrer,
            )
        client.fetch(request).use { response ->
            val status = response.status
            if (status >= HTTP_ERROR) throw StreamDownload.HttpError(status)
            if (offset >= 0 && status == HTTP_PARTIAL) {
                StreamDownload.checkContentRange(response.headers["Content-Range"], offset, length)
            } else if (offset >= 0 && status != HTTP_OK) {
                throw IOException("server did not honour the media byte range (HTTP $status)")
            }
            // A server that ignores Range sends the whole file (200); keep only the requested part.
            val skip = if (offset >= 0 && status == HTTP_OK) offset else 0L
            val want = if (offset >= 0 && length >= 0) length else -1L
            val body = response.body.useStream { StreamDownload.readAll(it, skip, want, maxBytes) }
            return StreamDownload.Response(body, response.url)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 20L
        const val READ_TIMEOUT_S = 30L
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_ERROR = 400
    }
}
