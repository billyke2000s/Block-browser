/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class StreamDownloadTest {

  /** In-memory server: address -> body; ranges are cut from the body. */
  private static final class FakeFetcher implements StreamDownload.Fetcher {
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

    FakeFetcher put(String url, String body) {
      files.put(url, body.getBytes(StandardCharsets.UTF_8));
      return this;
    }

    @Override public StreamDownload.Response get(String url, long offset, long length, int maxBytes)
        throws IOException {
      AtomicInteger fail = failuresLeft.get(url);
      if (fail != null && fail.getAndDecrement() > 0) throw new StreamDownload.HttpError(503);
      byte[] b = files.get(url);
      if (b == null) throw new StreamDownload.HttpError(404);
      if (offset >= 0) {
        int end = length < 0 ? b.length : (int) (offset + length);
        byte[] part = new byte[end - (int) offset];
        System.arraycopy(b, (int) offset, part, 0, part.length);
        b = part;
      }
      return new StreamDownload.Response(b, url);
    }
  }

  private static File tempDir() throws IOException {
    File d = Files.createTempDirectory("stream-test").toFile();
    d.deleteOnExit();
    return d;
  }

  private static String read(File f) throws IOException {
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
  }

  @Test public void hlsPiecesJoinedInOrderWithRetry() throws Exception {
    String base = "https://s.example/v/";
    StringBuilder pl = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=\"init.mp4\"\n");
    FakeFetcher f = new FakeFetcher().put(base + "init.mp4", "[init]");
    StringBuilder expected = new StringBuilder("[init]");
    for (int i = 0; i < 20; i++) {
      pl.append("#EXTINF:2.0,\nseg").append(i).append(".m4s\n");
      f.put(base + "seg" + i + ".m4s", "<" + i + ">");
      expected.append("<").append(i).append(">");
    }
    pl.append("#EXT-X-ENDLIST\n");
    f.put(base + "media.m3u8", pl.toString());
    f.failuresLeft.put(base + "seg7.m4s", new AtomicInteger(1));

    HlsPlaylist media = StreamDownload.loadHls(f, base + "media.m3u8");
    StreamDownload.checkHls(media);
    StreamTrack track = StreamTrack.fromHls(media);
    assertEquals(40.0, track.duration, 1e-9);
    int[] last = new int[2];
    StreamDownload.Result r = StreamDownload.download(f, track, null, tempDir(), new StreamDownload.Listener() {
      @Override public void progress(int done, int total, long bytes) { last[0] = done; last[1] = total; }
      @Override public boolean cancelled() { return false; }
    });
    assertEquals(expected.toString(), read(r.video));
    assertEquals(21, last[0]);
    assertEquals(21, last[1]);
  }

  @Test public void dashVideoAndAudioTracks() throws Exception {
    String base = "https://d.example/m/";
    String mpd = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT6S\"><Period>"
        + "<AdaptationSet mimeType=\"video/mp4\"><SegmentTemplate timescale=\"1\" duration=\"2\" startNumber=\"1\""
        + " initialization=\"$RepresentationID$-init.m4s\" media=\"$RepresentationID$-$Number$.m4s\"/>"
        + "<Representation id=\"v\" bandwidth=\"1000\" height=\"720\"/></AdaptationSet>"
        + "<AdaptationSet mimeType=\"audio/mp4\"><SegmentTemplate timescale=\"1\" duration=\"3\" startNumber=\"1\""
        + " initialization=\"$RepresentationID$-init.m4s\" media=\"$RepresentationID$-$Number$.m4s\"/>"
        + "<Representation id=\"a\" bandwidth=\"100\"/></AdaptationSet></Period></MPD>";
    FakeFetcher f = new FakeFetcher().put(base + "manifest.mpd", mpd)
        .put(base + "v-init.m4s", "V").put(base + "v-1.m4s", "1").put(base + "v-2.m4s", "2").put(base + "v-3.m4s", "3")
        .put(base + "a-init.m4s", "A").put(base + "a-1.m4s", "x").put(base + "a-2.m4s", "y");
    DashManifest m = StreamDownload.loadDash(f, base + "manifest.mpd");
    StreamDownload.checkDash(m);
    DashManifest.Representation v = m.videoQualities().get(0);
    DashManifest.Representation a = m.audioFor(v);
    StreamDownload.Result r = StreamDownload.download(f, v.track, a.track, tempDir(), null);
    assertEquals("V123", read(r.video));
    assertEquals("Axy", read(r.audio));
  }

  @Test public void missingPieceFailsWithoutEndlessRetries() throws Exception {
    String base = "https://s.example/x/";
    FakeFetcher f = new FakeFetcher().put(base + "m.m3u8", "#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST\n")
        .put(base + "a.ts", "a");
    StreamTrack t = StreamTrack.fromHls(StreamDownload.loadHls(f, base + "m.m3u8"));
    try {
      StreamDownload.download(f, t, null, tempDir(), null);
      fail("404 must fail");
    } catch (StreamDownload.HttpError e) {
      assertEquals(404, e.code);
    }
  }

  @Test public void encryptedAndLiveHlsRefused() throws Exception {
    HlsPlaylist enc = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n", "https://x/");
    HlsPlaylist live = HlsPlaylist.parse("#EXTM3U\n#EXTINF:1,\na.ts\n", "https://x/");
    for (HlsPlaylist p : new HlsPlaylist[]{enc, live}) {
      try {
        StreamDownload.checkHls(p);
        fail("must be refused");
      } catch (StreamDownload.Refused expected) { }
    }
  }

  @Test public void cancelStopsTheDownload() throws Exception {
    String base = "https://s.example/c/";
    StringBuilder pl = new StringBuilder("#EXTM3U\n");
    FakeFetcher f = new FakeFetcher();
    for (int i = 0; i < 50; i++) { pl.append("#EXTINF:1,\n").append(i).append(".ts\n"); f.put(base + i + ".ts", "x"); }
    f.put(base + "m.m3u8", pl.append("#EXT-X-ENDLIST\n").toString());
    StreamTrack t = StreamTrack.fromHls(StreamDownload.loadHls(f, base + "m.m3u8"));
    try {
      StreamDownload.download(f, t, null, tempDir(), new StreamDownload.Listener() {
        int calls;
        @Override public void progress(int done, int total, long bytes) { }
        @Override public boolean cancelled() { return ++calls > 5; }
      });
      fail("must be cancelled");
    } catch (StreamDownload.Cancelled expected) { }
  }

  @Test public void id3TagsAreStripped() {
    byte[] tagged = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 2, 9, 9, 'A', 'A', 'C'};
    assertArrayEquals(new byte[]{'A', 'A', 'C'}, StreamDownload.stripId3(tagged));
    byte[] plain = new byte[]{1, 2, 3};
    assertArrayEquals(plain, StreamDownload.stripId3(plain));
  }

  @Test public void contentRangeChecks() throws Exception {
    StreamDownload.checkContentRange("bytes 100-199/1000", 100, 100);
    StreamDownload.checkContentRange("bytes 100-999/1000", 100, -1);
    for (String bad : new String[]{null, "bytes 0-99/1000", "bytes 100-150/1000", "bytes 100-1000/1000", "bytes 100-x/1"}) {
      try {
        StreamDownload.checkContentRange(bad, 100, 100);
        fail("should reject " + bad);
      } catch (IOException expected) {
        assertTrue(expected.getMessage() != null);
      }
    }
  }
}
