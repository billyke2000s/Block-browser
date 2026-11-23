/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads a stream (HLS or DASH): fetches each track's pieces and joins them into one file per track (video, and
 * audio when the stream keeps it separate). Turning those into a playable file is done by {@link StreamRemuxer}.
 *
 * No Android dependencies: network access goes through {@link Fetcher}, so this can be tested on the JVM.
 *
 * Refuses copy-protected (DRM or encrypted) and live streams: Block Browser does not get around copy protection.
 */
public final class StreamDownload {
  private StreamDownload() {}

  /** A stream Block Browser won't or can't save; the message is shown to the user as is. */
  public static final class Refused extends IOException {
    public Refused(String message) { super(message); }
  }

  public static final class Cancelled extends IOException {
    public Cancelled() { super("cancelled"); }
  }

  /** An HTTP error status; 408, 429 and 5xx are worth retrying. */
  public static final class HttpError extends IOException {
    public final int code;
    public HttpError(int code) { super("server answered " + code); this.code = code; }
    boolean retryable() { return code == 408 || code == 429 || code >= 500; }
  }

  /** Fetches addresses the way the page would (its cookies, user agent and referrer). */
  public interface Fetcher {
    /**
     * Gets {@code url}, or only {@code length} bytes from {@code offset} when offset >= 0 (length -1: to the end).
     * Must throw {@link HttpError} for error statuses and reject responses with the wrong byte range.
     */
    Response get(String url, long offset, long length, int maxBytes) throws IOException;
  }

  public static final class Response {
    public final byte[] body;
    /** The address after redirects, which relative playlist entries resolve against. */
    public final String finalUrl;
    public Response(byte[] body, String finalUrl) { this.body = body; this.finalUrl = finalUrl; }
  }

  public interface Listener {
    void progress(int piecesDone, int piecesTotal, long bytes);
    boolean cancelled();
  }

  static final int THREADS = 4;
  public static final int MAX_PIECE_BYTES = 256 * 1024 * 1024;
  public static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
  static final String COPY_PROTECTED = "This video is copy-protected, so it can't be downloaded";

  public static String getText(Fetcher http, String url, String[] finalUrl) throws IOException {
    Response r = http.get(url, -1, -1, MAX_MANIFEST_BYTES);
    if (finalUrl != null) finalUrl[0] = r.finalUrl;
    return new String(r.body, StandardCharsets.UTF_8);
  }

  /** Loads and parses an HLS playlist; relative entries resolve against the address after redirects. */
  public static HlsPlaylist loadHls(Fetcher http, String url) throws IOException {
    String[] finalUrl = new String[1];
    String text = getText(http, url, finalUrl);
    return HlsPlaylist.parse(text, finalUrl[0]);
  }

  public static DashManifest loadDash(Fetcher http, String url) throws IOException {
    String[] finalUrl = new String[1];
    String text = getText(http, url, finalUrl);
    return DashManifest.parse(text, finalUrl[0]);
  }

  /** Checks an HLS media playlist is something Block Browser can save. */
  public static void checkHls(HlsPlaylist media) throws IOException {
    if (media.encryption != null) throw new Refused(COPY_PROTECTED);
    if (media.isMaster) throw new IOException("expected a media playlist");
    if (!media.ended) throw new Refused("This is a live stream. Only videos that have finished can be downloaded");
    if (media.segments.isEmpty()) throw new IOException("the stream has no pieces");
  }

  /** Checks a DASH manifest is something Block Browser can save. */
  public static void checkDash(DashManifest m) throws IOException {
    if (m.live) throw new Refused("This is a live stream. Only videos that have finished can be downloaded");
    if (m.periodCount > 1)
      throw new Refused("This video is made of several parts (often around adverts), which can't be downloaded yet");
    boolean anyOpen = false;
    for (DashManifest.Representation r : m.videos) if (!r.isProtected) anyOpen = true;
    if (!anyOpen && !m.videos.isEmpty()) throw new Refused(COPY_PROTECTED);
  }

  /** Checks a chosen DASH representation (video or audio) can be saved. */
  public static void checkDash(DashManifest.Representation r) throws IOException {
    if (r.isProtected) throw new Refused(COPY_PROTECTED);
  }

  public static final class Result {
    public File video, audio;
    public double duration;
    public long bytes;
  }

  /**
   * Downloads the tracks into workDir: video.part and, when given, audio.part. Each part is the track's pieces
   * joined in order, with its initialization header first.
   */
  public static Result download(Fetcher http, StreamTrack video, StreamTrack audio, File workDir, Listener listener)
      throws IOException {
    if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("can't create a temporary folder");
    Result r = new Result();
    r.duration = video.duration;
    Progress p = new Progress(listener, video.pieceCount() + (audio == null ? 0 : audio.pieceCount()));
    r.video = new File(workDir, "video.part");
    writeTrack(http, video, r.video, p);
    if (audio != null) {
      r.audio = new File(workDir, "audio.part");
      writeTrack(http, audio, r.audio, p);
    }
    r.bytes = p.bytes.get();
    return r;
  }

  private static final class Progress {
    final Listener listener;
    final int total;
    int done;
    final AtomicLong bytes = new AtomicLong();
    long lastReport;
    Progress(Listener listener, int total) { this.listener = listener; this.total = total; }

    void piece(int size) throws IOException {
      done++;
      bytes.addAndGet(size);
      if (listener == null) return;
      if (listener.cancelled()) throw new Cancelled();
      long now = System.currentTimeMillis();
      if (now - lastReport > 400 || done == total) { lastReport = now; listener.progress(done, total, bytes.get()); }
    }
  }

  /** Fetches a track's pieces a few at a time and writes them in order. */
  static void writeTrack(Fetcher http, StreamTrack track, File out, Progress p) throws IOException {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
      Thread t = new Thread(r, "stream-download");
      t.setDaemon(true);
      return t;
    });
    try (OutputStream file = new FileOutputStream(out)) {
      if (track.init != null) {
        byte[] init = fetchPiece(http, track.init, p.listener);
        file.write(init);
        p.piece(init.length);
      }
      Deque<Future<byte[]>> window = new ArrayDeque<>();
      int next = 0;
      while (next < track.pieces.size() || !window.isEmpty()) {
        while (next < track.pieces.size() && window.size() < THREADS * 2) {
          StreamTrack.Piece s = track.pieces.get(next++);
          boolean strip = track.stripId3;
          window.add(pool.submit(() -> {
            byte[] b = fetchPiece(http, s, p.listener);
            return strip ? stripId3(b) : b;
          }));
        }
        byte[] data;
        try {
          data = window.poll().get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Cancelled();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof IOException) throw (IOException) cause;
          throw new IOException(cause);
        }
        file.write(data);
        p.piece(data.length);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** One piece, retried a few times on network errors and temporary server errors. */
  static byte[] fetchPiece(Fetcher http, StreamTrack.Piece s, Listener listener) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      if (listener != null && listener.cancelled()) throw new Cancelled();
      try {
        return http.get(s.uri, s.offset, s.length, MAX_PIECE_BYTES).body;
      } catch (HttpError e) {
        if (!e.retryable()) throw e;
        last = e;
      } catch (Cancelled e) {
        throw e;
      } catch (IOException e) {
        last = e;
      }
      try {
        Thread.sleep(1000L << attempt);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Cancelled();
      }
    }
    throw last;
  }

  /**
   * Audio-only HLS pieces (.aac) start with an ID3 tag holding their timestamp. Joined together, those tags would
   * sit in the middle of the audio, which Android's AAC reader can't skip; drop them.
   */
  static byte[] stripId3(byte[] b) {
    int pos = 0;
    while (b.length - pos >= 10 && b[pos] == 'I' && b[pos + 1] == 'D' && b[pos + 2] == '3') {
      int size = ((b[pos + 6] & 0x7f) << 21) | ((b[pos + 7] & 0x7f) << 14) | ((b[pos + 8] & 0x7f) << 7)
          | (b[pos + 9] & 0x7f);
      boolean footer = (b[pos + 5] & 0x10) != 0;
      int end = pos + 10 + size + (footer ? 10 : 0);
      if (end > b.length) break;
      pos = end;
    }
    if (pos == 0) return b;
    byte[] out = new byte[b.length - pos];
    System.arraycopy(b, pos, out, 0, out.length);
    return out;
  }

  /** Reads a response body, skipping {@code skip} bytes first and keeping {@code want} bytes (-1: all). */
  public static byte[] readAll(java.io.InputStream in, long skip, long want, int maxBytes) throws IOException {
    while (skip > 0) {
      long n = in.skip(skip);
      if (n <= 0) {
        if (in.read() < 0) throw new IOException("piece ended early");
        n = 1;
      }
      skip -= n;
    }
    java.io.ByteArrayOutputStream out =
        new java.io.ByteArrayOutputStream(want > 0 && want < maxBytes ? (int) want : 1 << 16);
    byte[] buf = new byte[1 << 16];
    long left = want;
    int n;
    while ((left < 0 || left > 0)
        && (n = in.read(buf, 0, left < 0 ? buf.length : (int) Math.min(buf.length, left))) > 0) {
      out.write(buf, 0, n);
      if (left > 0) left -= n;
      if (out.size() > maxBytes) throw new IOException("piece too large");
    }
    if (left > 0) throw new IOException("piece ended early");
    return out.toByteArray();
  }

  /**
   * Checks a partial (206) response's Content-Range matches the requested bytes, so the wrong bytes never end up
   * in the middle of a track. Throws when it doesn't.
   */
  public static void checkContentRange(String range, long offset, long length) throws IOException {
    if (range == null || !range.startsWith("bytes " + offset + "-"))
      throw new IOException("server returned the wrong byte range");
    int dash = range.indexOf('-', 6);
    int slash = range.indexOf('/', dash + 1);
    if (dash < 0 || slash < 0) throw new IOException("invalid Content-Range header");
    try {
      long end = Long.parseLong(range.substring(dash + 1, slash));
      long total = range.substring(slash + 1).equals("*") ? -1 : Long.parseLong(range.substring(slash + 1));
      if (end < offset || (length >= 0 && end != offset + length - 1) || (total >= 0 && end >= total))
        throw new IOException("server returned an incomplete or incorrect byte range");
    } catch (NumberFormatException e) {
      throw new IOException("invalid Content-Range header", e);
    }
  }
}
