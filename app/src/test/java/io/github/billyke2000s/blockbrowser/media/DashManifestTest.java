/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

public class DashManifestTest {
  private static final String BASE = "https://cdn.example.com/videos/clip/manifest.mpd";

  private static String mpd(String attrs, String body) {
    return "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " + attrs + ">" + body + "</MPD>";
  }

  @Test public void numberedTemplateWithPaddingAndInit() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT10S\"",
        "<Period><AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">"
            + "<SegmentTemplate timescale=\"1000\" duration=\"4000\" startNumber=\"1\""
            + " initialization=\"$RepresentationID$/init.mp4\" media=\"$RepresentationID$/seg-$Number%05d$.m4s\"/>"
            + "<Representation id=\"720p\" bandwidth=\"2500000\" width=\"1280\" height=\"720\" codecs=\"avc1.64001f\"/>"
            + "</AdaptationSet></Period>");
    DashManifest m = DashManifest.parse(xml, BASE);
    assertEquals(10.0, m.duration, 0.001);
    assertEquals(1, m.videos.size());
    StreamTrack t = m.videos.get(0).track;
    assertEquals("https://cdn.example.com/videos/clip/720p/init.mp4", t.init.uri);
    assertEquals(3, t.pieces.size()); // 10 s in 4 s pieces
    assertEquals("https://cdn.example.com/videos/clip/720p/seg-00001.m4s", t.pieces.get(0).uri);
    assertEquals("https://cdn.example.com/videos/clip/720p/seg-00003.m4s", t.pieces.get(2).uri);
    assertEquals("720p · 2.5 Mbps", m.videos.get(0).label());
  }

  @Test public void timelineWithRepeatsAndTime() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT0H0M12S\"",
        "<Period><AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\">"
            + "<Representation id=\"a1\" bandwidth=\"128000\" codecs=\"mp4a.40.2\">"
            + "<SegmentTemplate timescale=\"10\" media=\"a/$Time$.m4s\" initialization=\"a/init.m4s\">"
            + "<SegmentTimeline><S t=\"0\" d=\"40\" r=\"1\"/><S d=\"40\"/></SegmentTimeline>"
            + "</SegmentTemplate></Representation></AdaptationSet></Period>");
    DashManifest m = DashManifest.parse(xml, BASE);
    assertEquals(1, m.audios.size());
    List<StreamTrack.Piece> p = m.audios.get(0).track.pieces;
    assertEquals(3, p.size());
    assertEquals("https://cdn.example.com/videos/clip/a/0.m4s", p.get(0).uri);
    assertEquals("https://cdn.example.com/videos/clip/a/40.m4s", p.get(1).uri);
    assertEquals("https://cdn.example.com/videos/clip/a/80.m4s", p.get(2).uri);
  }

  @Test public void timelineRepeatUntilEndOfPeriod() throws IOException {
    String xml = mpd("type=\"static\"",
        "<Period duration=\"PT9S\"><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
            + "<SegmentTemplate timescale=\"1\" media=\"v_$Number$.mp4\" startNumber=\"5\">"
            + "<SegmentTimeline><S t=\"0\" d=\"2\" r=\"-1\"/></SegmentTimeline></SegmentTemplate>"
            + "<Representation id=\"v\" bandwidth=\"1\" height=\"360\"/></AdaptationSet></Period>");
    List<StreamTrack.Piece> p = DashManifest.parse(xml, BASE).videos.get(0).track.pieces;
    assertEquals(5, p.size()); // 0,2,4,6,8 < 9
    assertEquals("https://cdn.example.com/videos/clip/v_5.mp4", p.get(0).uri);
    assertEquals("https://cdn.example.com/videos/clip/v_9.mp4", p.get(4).uri);
  }

  @Test public void templateSplitAcrossLevelsIsMerged() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT4S\"",
        "<Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
            + "<SegmentTemplate media=\"$Bandwidth$/$Number$.m4s\" initialization=\"$Bandwidth$/i.m4s\" timescale=\"1\"/>"
            + "<Representation id=\"x\" bandwidth=\"800\" height=\"240\">"
            + "<SegmentTemplate duration=\"2\" startNumber=\"0\"/></Representation>"
            + "</AdaptationSet></Period>");
    StreamTrack t = DashManifest.parse(xml, BASE).videos.get(0).track;
    assertEquals("https://cdn.example.com/videos/clip/800/i.m4s", t.init.uri);
    assertEquals(2, t.pieces.size());
    assertEquals("https://cdn.example.com/videos/clip/800/0.m4s", t.pieces.get(0).uri);
  }

  @Test public void segmentListWithByteRanges() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT4S\"",
        "<Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
            + "<Representation id=\"v\" bandwidth=\"1\" height=\"480\"><BaseURL>file.mp4</BaseURL>"
            + "<SegmentList><Initialization range=\"0-99\"/>"
            + "<SegmentURL mediaRange=\"100-199\"/><SegmentURL mediaRange=\"200-349\"/></SegmentList>"
            + "</Representation></AdaptationSet></Period>");
    StreamTrack t = DashManifest.parse(xml, BASE).videos.get(0).track;
    assertEquals("https://cdn.example.com/videos/clip/file.mp4", t.init.uri);
    assertEquals(0, t.init.offset);
    assertEquals(100, t.init.length);
    assertEquals(2, t.pieces.size());
    assertEquals(200, t.pieces.get(1).offset);
    assertEquals(150, t.pieces.get(1).length);
  }

  @Test public void segmentBaseIsOneWholeFile() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT30S\"",
        "<BaseURL>https://media.example.org/x/</BaseURL><Period>"
            + "<AdaptationSet mimeType=\"video/webm\"><Representation id=\"1\" bandwidth=\"1\" height=\"1080\""
            + " codecs=\"vp9\"><BaseURL>1080.webm</BaseURL><SegmentBase indexRange=\"200-400\"/></Representation>"
            + "</AdaptationSet></Period>");
    DashManifest m = DashManifest.parse(xml, BASE);
    StreamTrack t = m.videos.get(0).track;
    assertNull(t.init);
    assertEquals(1, t.pieces.size());
    assertEquals("https://media.example.org/x/1080.webm", t.pieces.get(0).uri);
    assertEquals(-1, t.pieces.get(0).offset);
    assertEquals("webm", m.videos.get(0).container());
  }

  @Test public void drmProtectedIsRefused() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT4S\"",
        "<Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
            + "<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>"
            + "<SegmentTemplate media=\"$Number$.m4s\" duration=\"2\"/>"
            + "<Representation id=\"v\" bandwidth=\"1\" height=\"720\"/></AdaptationSet></Period>");
    DashManifest m = DashManifest.parse(xml, BASE);
    assertTrue(m.videos.get(0).isProtected);
    try {
      StreamDownload.checkDash(m);
      fail("DRM must be refused");
    } catch (StreamDownload.Refused expected) {
      assertTrue(expected.getMessage().contains("copy-protected"));
    }
  }

  @Test public void liveAndMultiPeriodAreRefused() throws IOException {
    String live = mpd("type=\"dynamic\"", "<Period><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
        + "<Representation id=\"v\" bandwidth=\"1\"><BaseURL>v.mp4</BaseURL></Representation></AdaptationSet></Period>");
    try {
      StreamDownload.checkDash(DashManifest.parse(live, BASE));
      fail("live must be refused");
    } catch (StreamDownload.Refused expected) { }
    String two = mpd("type=\"static\"", "<Period duration=\"PT1S\"><AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
        + "<Representation id=\"v\" bandwidth=\"1\"><BaseURL>v.mp4</BaseURL></Representation></AdaptationSet></Period>"
        + "<Period duration=\"PT1S\"/>");
    try {
      StreamDownload.checkDash(DashManifest.parse(two, BASE));
      fail("multi-period must be refused");
    } catch (StreamDownload.Refused expected) { }
  }

  @Test public void qualitiesBestFirstAndAudioMatchesContainer() throws IOException {
    String xml = mpd("type=\"static\" mediaPresentationDuration=\"PT2S\"",
        "<Period>"
            + "<AdaptationSet mimeType=\"video/webm\" contentType=\"video\">"
            + "<Representation id=\"w1080\" bandwidth=\"4000000\" height=\"1080\" codecs=\"vp9\"><BaseURL>a</BaseURL></Representation>"
            + "<Representation id=\"w480\" bandwidth=\"900000\" height=\"480\" codecs=\"vp9\"><BaseURL>b</BaseURL></Representation>"
            + "</AdaptationSet>"
            + "<AdaptationSet mimeType=\"video/mp4\" contentType=\"video\">"
            + "<Representation id=\"m1080\" bandwidth=\"5000000\" height=\"1080\" codecs=\"avc1\"><BaseURL>c</BaseURL></Representation>"
            + "<Representation id=\"m720\" bandwidth=\"3000000\" height=\"720\" codecs=\"avc1\"><BaseURL>d</BaseURL></Representation>"
            + "</AdaptationSet>"
            + "<AdaptationSet mimeType=\"audio/mp4\"><Representation id=\"aac\" bandwidth=\"128000\" codecs=\"mp4a.40.2\"><BaseURL>e</BaseURL></Representation></AdaptationSet>"
            + "<AdaptationSet mimeType=\"audio/webm\"><Representation id=\"opus\" bandwidth=\"160000\" codecs=\"opus\"><BaseURL>f</BaseURL></Representation></AdaptationSet>"
            + "</Period>");
    DashManifest m = DashManifest.parse(xml, BASE);
    List<DashManifest.Representation> q = m.videoQualities();
    assertEquals(3, q.size());
    assertEquals("m1080", q.get(0).id); // MP4 preferred at the same resolution
    assertEquals("m720", q.get(1).id);
    assertEquals("w480", q.get(2).id);
    assertEquals("aac", m.audioFor(q.get(0)).id);
    assertEquals("opus", m.audioFor(q.get(2)).id);
  }

  @Test public void subtitlesAreIgnoredAndNamespacesHandled() throws IOException {
    String xml = "<dash:MPD xmlns:dash=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT2S\">"
        + "<dash:Period><dash:AdaptationSet contentType=\"text\" mimeType=\"text/vtt\"><dash:Representation id=\"s\" bandwidth=\"1\"><dash:BaseURL>s.vtt</dash:BaseURL></dash:Representation></dash:AdaptationSet>"
        + "<dash:AdaptationSet mimeType=\"video/mp4\"><dash:Representation id=\"v\" bandwidth=\"1\"><dash:BaseURL>v.mp4</dash:BaseURL></dash:Representation></dash:AdaptationSet>"
        + "</dash:Period></dash:MPD>";
    DashManifest m = DashManifest.parse(xml, BASE);
    assertEquals(1, m.videos.size());
    assertTrue(m.audios.isEmpty());
  }

  @Test public void notAManifest() {
    for (String bad : new String[]{"", "#EXTM3U\n", "<html><body>nope</body></html>"}) {
      try {
        DashManifest.parse(bad, BASE);
        fail("should reject: " + bad);
      } catch (IOException expected) { }
    }
  }

  @Test public void externalEntitiesAreNotLoaded() {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE MPD [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
        + "<MPD type=\"static\"><Period><AdaptationSet mimeType=\"video/mp4\"><Representation id=\"&x;\" bandwidth=\"1\">"
        + "<BaseURL>v.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>";
    try {
      DashManifest m = DashManifest.parse(xml, BASE);
      assertFalse(m.videos.get(0).id.contains("root:"));
    } catch (IOException expected) {
      // Rejecting the DOCTYPE outright is fine too.
    }
  }

  @Test public void durations() {
    assertEquals(3723.5, DashManifest.parseDuration("PT1H2M3.5S"), 1e-9);
    assertEquals(86400 + 60, DashManifest.parseDuration("P1DT1M"), 1e-9);
    assertEquals(0, DashManifest.parseDuration("garbage"), 1e-9);
  }

  @Test public void templateFill() {
    DashManifest.Representation r = new DashManifest.Representation();
    r.id = "v1";
    r.bandwidth = 500;
    assertEquals("v1/500/007/90$x", DashManifest.fill("$RepresentationID$/$Bandwidth$/$Number%03d$/$Time$$$x", r, 7, 90));
  }
}
