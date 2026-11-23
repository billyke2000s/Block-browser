/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Parser for MPEG-DASH manifests (.mpd), enough to download on-demand video: one period, with segments given by
 * SegmentTemplate (numbered, or timed through a SegmentTimeline), SegmentList, SegmentBase or a plain BaseURL.
 *
 * It only reads what the manifest says. Whether a stream may be downloaded (DRM, live) is decided by
 * {@link StreamDownload#checkDash}. Uses only javax.xml / org.w3c.dom, which exist on Android and the JVM, so it can
 * be unit-tested on the JVM.
 */
public final class DashManifest {

  /** One quality of one track. */
  public static final class Representation {
    public String id, mimeType, codecs;
    public long bandwidth;
    public int width, height;
    public boolean isVideo, isAudio;
    /** ContentProtection on the representation or its adaptation set: the content is DRM-protected. */
    public boolean isProtected;
    public String language;
    public StreamTrack track;

    /** "1080p · 5.2 Mbps", "128 kbps" or "Default quality". */
    public String label() {
      String rate = bandwidth >= 1_000_000 ? String.format(Locale.ROOT, "%.1f Mbps", bandwidth / 1e6)
          : bandwidth > 0 ? (bandwidth / 1000) + " kbps" : "";
      String what = height > 0 ? height + "p" : "";
      if (what.isEmpty()) return rate.isEmpty() ? "Default quality" : rate;
      return rate.isEmpty() ? what : what + " · " + rate;
    }

    /** "webm" or "mp4": the container family of the pieces. */
    public String container() {
      String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
      String c = codecs == null ? "" : codecs.toLowerCase(Locale.ROOT);
      if (m.contains("webm") || c.startsWith("vp8") || c.startsWith("vp9") || c.startsWith("vp09")
          || c.startsWith("opus") || c.startsWith("vorbis")) return "webm";
      return "mp4";
    }
  }

  /** type="dynamic": a live stream, whose pieces keep changing. */
  public boolean live;
  /** More than one Period (often adverts spliced in); not supported. */
  public int periodCount;
  /** Seconds, from mediaPresentationDuration or the period's duration; 0 when unknown. */
  public double duration;
  public final List<Representation> videos = new ArrayList<>();
  public final List<Representation> audios = new ArrayList<>();

  /** Video qualities from best to worst, keeping the highest-bitrate one of each resolution and container. */
  public List<Representation> videoQualities() {
    List<Representation> sorted = new ArrayList<>(videos);
    Collections.sort(sorted, (a, b) -> {
      if (a.height != b.height) return Integer.compare(b.height, a.height);
      // At the same resolution prefer MP4 (plays everywhere), then the higher bitrate.
      if (!a.container().equals(b.container())) return a.container().equals("mp4") ? -1 : 1;
      return Long.compare(b.bandwidth, a.bandwidth);
    });
    List<Representation> out = new ArrayList<>();
    for (Representation r : sorted) {
      boolean dup = false;
      for (Representation o : out) if (o.height == r.height) { dup = true; break; }
      if (!dup) out.add(r);
    }
    return out;
  }

  /** The best audio track to combine with the given video: same container if possible, then highest bitrate. */
  public Representation audioFor(Representation video) {
    Representation best = null;
    for (Representation a : audios) {
      if (best == null) { best = a; continue; }
      boolean aMatches = a.container().equals(video.container());
      boolean bestMatches = best.container().equals(video.container());
      if (aMatches != bestMatches) { if (aMatches) best = a; continue; }
      if (a.bandwidth > best.bandwidth) best = a;
    }
    return best;
  }

  public static DashManifest parse(String text, String manifestUrl) throws IOException {
    if (text == null || text.isEmpty()) throw new IOException("empty manifest");
    Document doc;
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      trySetFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
      trySetFeature(f, "http://xml.org/sax/features/external-general-entities", false);
      trySetFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
      try { f.setExpandEntityReferences(false); } catch (Exception ignored) { }
      DocumentBuilder b = f.newDocumentBuilder();
      doc = b.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IOException("not a valid DASH manifest", e);
    }
    Element mpd = doc.getDocumentElement();
    if (mpd == null || !"MPD".equals(name(mpd))) throw new IOException("not a DASH manifest");

    DashManifest m = new DashManifest();
    m.live = "dynamic".equalsIgnoreCase(mpd.getAttribute("type"));
    m.duration = parseDuration(mpd.getAttribute("mediaPresentationDuration"));
    String mpdBase = baseUrl(mpd, manifestUrl);

    List<Element> periods = children(mpd, "Period");
    m.periodCount = periods.size();
    if (periods.isEmpty()) throw new IOException("the manifest lists no video");
    Element period = periods.get(0);
    double periodSeconds = parseDuration(period.getAttribute("duration"));
    if (periodSeconds <= 0) periodSeconds = m.duration;
    if (m.duration <= 0) m.duration = periodSeconds;
    String periodBase = baseUrl(period, mpdBase);

    for (Element set : children(period, "AdaptationSet")) {
      String setBase = baseUrl(set, periodBase);
      boolean setProtected = !children(set, "ContentProtection").isEmpty();
      for (Element rep : children(set, "Representation")) {
        Representation r = new Representation();
        r.id = rep.getAttribute("id");
        r.mimeType = attr(rep, set, "mimeType");
        r.codecs = attr(rep, set, "codecs");
        r.bandwidth = parseLong(rep.getAttribute("bandwidth"), 0);
        r.width = (int) parseLong(attr(rep, set, "width"), 0);
        r.height = (int) parseLong(attr(rep, set, "height"), 0);
        r.language = set.getAttribute("lang");
        r.isProtected = setProtected || !children(rep, "ContentProtection").isEmpty();
        String type = set.getAttribute("contentType");
        String mime = r.mimeType == null ? "" : r.mimeType;
        r.isVideo = "video".equals(type) || mime.startsWith("video/");
        r.isAudio = !r.isVideo && ("audio".equals(type) || mime.startsWith("audio/"));
        if (!r.isVideo && !r.isAudio) continue; // subtitles, thumbnails
        String repBase = baseUrl(rep, setBase);
        r.track = track(rep, set, period, repBase, r, periodSeconds);
        r.track.duration = periodSeconds;
        (r.isVideo ? m.videos : m.audios).add(r);
      }
    }
    if (m.videos.isEmpty() && m.audios.isEmpty()) throw new IOException("the manifest lists no video");
    return m;
  }

  // ---- Segment addressing -------------------------------------------------------------------------------------

  private static StreamTrack track(Element rep, Element set, Element period, String base, Representation r,
                                   double periodSeconds) throws IOException {
    Element template = merged(rep, set, period, "SegmentTemplate");
    if (template != null) return fromTemplate(template, base, r, periodSeconds);
    Element list = first(rep, set, period, "SegmentList");
    if (list != null) return fromList(list, base);
    // SegmentBase (an indexed single file) or a bare BaseURL: the whole file is one piece.
    StreamTrack t = new StreamTrack();
    t.pieces.add(new StreamTrack.Piece(base, -1, -1));
    return t;
  }

  private static StreamTrack fromTemplate(Element tpl, String base, Representation r, double periodSeconds)
      throws IOException {
    StreamTrack t = new StreamTrack();
    String media = tpl.getAttribute("media");
    if (media.isEmpty()) throw new IOException("segment template has no media address");
    long timescale = Math.max(1, parseLong(tpl.getAttribute("timescale"), 1));
    long startNumber = parseLong(tpl.getAttribute("startNumber"), 1);
    String init = tpl.getAttribute("initialization");
    if (!init.isEmpty()) t.init = new StreamTrack.Piece(resolve(base, fill(init, r, -1, -1)), -1, -1);

    List<Element> timelines = children(tpl, "SegmentTimeline");
    long number = startNumber;
    if (!timelines.isEmpty()) {
      long time = 0;
      long end = periodSeconds > 0 ? (long) Math.ceil(periodSeconds * timescale) : Long.MAX_VALUE;
      List<Element> ss = children(timelines.get(0), "S");
      for (int i = 0; i < ss.size(); i++) {
        Element s = ss.get(i);
        if (s.hasAttribute("t")) time = parseLong(s.getAttribute("t"), time);
        long d = parseLong(s.getAttribute("d"), 0);
        if (d <= 0) throw new IOException("invalid segment timeline");
        long repeat = parseLong(s.getAttribute("r"), 0);
        if (repeat < 0) {
          // Repeat until the next S element's start, or the end of the period.
          long until = i + 1 < ss.size() && ss.get(i + 1).hasAttribute("t")
              ? parseLong(ss.get(i + 1).getAttribute("t"), end) : end;
          if (until == Long.MAX_VALUE) throw new IOException("segment timeline has no end");
          repeat = Math.max(0, (until - time + d - 1) / d - 1);
        }
        for (long k = 0; k <= repeat; k++) {
          t.pieces.add(new StreamTrack.Piece(resolve(base, fill(media, r, number, time)), -1, -1));
          number++;
          time += d;
          if (t.pieces.size() > MAX_SEGMENTS) throw new IOException("too many pieces");
        }
      }
    } else {
      long d = parseLong(tpl.getAttribute("duration"), 0);
      if (d <= 0) throw new IOException("segment template has no duration");
      if (periodSeconds <= 0) throw new IOException("the video's length is unknown");
      long count = (long) Math.ceil(periodSeconds * timescale / (double) d - 1e-9);
      if (count > MAX_SEGMENTS) throw new IOException("too many pieces");
      for (long k = 0; k < count; k++) {
        t.pieces.add(new StreamTrack.Piece(resolve(base, fill(media, r, number, (number - startNumber) * d)), -1, -1));
        number++;
      }
    }
    if (t.pieces.isEmpty()) throw new IOException("the stream has no pieces");
    return t;
  }

  private static StreamTrack fromList(Element list, String base) throws IOException {
    StreamTrack t = new StreamTrack();
    for (Element init : children(list, "Initialization")) {
      String src = init.getAttribute("sourceURL");
      long[] range = range(init.getAttribute("range"));
      t.init = new StreamTrack.Piece(src.isEmpty() ? base : resolve(base, src), range[0], range[1]);
    }
    for (Element seg : children(list, "SegmentURL")) {
      String src = seg.getAttribute("media");
      long[] range = range(seg.getAttribute("mediaRange"));
      t.pieces.add(new StreamTrack.Piece(src.isEmpty() ? base : resolve(base, src), range[0], range[1]));
    }
    if (t.pieces.isEmpty()) throw new IOException("the stream has no pieces");
    return t;
  }

  static final int MAX_SEGMENTS = 200_000;

  private static final Pattern IDENTIFIER = Pattern.compile("(RepresentationID|Number|Bandwidth|Time)(%0(\\d+)d)?");

  /** Fills in a SegmentTemplate address ($RepresentationID$, $Number%05d$, $Time$, $Bandwidth$, $$). */
  static String fill(String template, Representation r, long number, long time) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < template.length()) {
      char c = template.charAt(i);
      if (c != '$') { out.append(c); i++; continue; }
      int close = template.indexOf('$', i + 1);
      if (close < 0) { out.append(template, i, template.length()); break; }
      if (close == i + 1) { out.append('$'); i = close + 1; continue; } // "$$" is a literal dollar sign
      Matcher mt = IDENTIFIER.matcher(template.substring(i + 1, close));
      if (!mt.matches()) { out.append(template, i, close + 1); i = close + 1; continue; }
      String value;
      switch (mt.group(1)) {
        case "RepresentationID": value = r.id == null ? "" : r.id; break;
        case "Number": value = Long.toString(number); break;
        case "Bandwidth": value = Long.toString(r.bandwidth); break;
        default: value = Long.toString(time); break;
      }
      if (mt.group(3) != null && !"RepresentationID".equals(mt.group(1))) {
        int width = Integer.parseInt(mt.group(3));
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) padded.insert(0, '0');
        value = padded.toString();
      }
      out.append(value);
      i = close + 1;
    }
    return out.toString();
  }

  // ---- XML helpers ---------------------------------------------------------------------------------------------

  private static void trySetFeature(DocumentBuilderFactory f, String feature, boolean value) {
    try { f.setFeature(feature, value); } catch (Exception ignored) { }
  }

  private static String name(Node n) {
    String local = n.getLocalName();
    return local != null ? local : n.getNodeName();
  }

  static List<Element> children(Element parent, String tag) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(name(n))) out.add((Element) n);
    }
    return out;
  }

  private static String attr(Element rep, Element set, String name) {
    String v = rep.getAttribute(name);
    return v.isEmpty() ? set.getAttribute(name) : v;
  }

  /** The first of rep / set / period that has a child named tag. */
  private static Element first(Element rep, Element set, Element period, String tag) {
    for (Element e : new Element[]{rep, set, period}) {
      List<Element> c = children(e, tag);
      if (!c.isEmpty()) return c.get(0);
    }
    return null;
  }

  /**
   * SegmentTemplate can be split across levels (e.g. the address on the AdaptationSet, the timeline on the
   * Representation). Returns a copy of the most specific one with missing attributes and timeline filled from the
   * levels above, or null when there is none.
   */
  private static Element merged(Element rep, Element set, Element period, String tag) {
    List<Element> levels = new ArrayList<>();
    for (Element e : new Element[]{rep, set, period}) {
      List<Element> c = children(e, tag);
      if (!c.isEmpty()) levels.add(c.get(0));
    }
    if (levels.isEmpty()) return null;
    Element result = (Element) levels.get(0).cloneNode(true);
    for (int i = 1; i < levels.size(); i++) {
      Element upper = levels.get(i);
      for (int a = 0; a < upper.getAttributes().getLength(); a++) {
        Node at = upper.getAttributes().item(a);
        if (!result.hasAttribute(at.getNodeName())) result.setAttribute(at.getNodeName(), at.getNodeValue());
      }
      if (children(result, "SegmentTimeline").isEmpty()) {
        for (Element tl : children(upper, "SegmentTimeline")) {
          result.appendChild(result.getOwnerDocument().importNode(tl, true));
        }
      }
    }
    return result;
  }

  /** The element's BaseURL resolved against the parent's, or the parent's when it has none. */
  private static String baseUrl(Element e, String parentBase) {
    List<Element> bases = children(e, "BaseURL");
    if (bases.isEmpty()) return parentBase;
    String text = bases.get(0).getTextContent();
    return text == null || text.trim().isEmpty() ? parentBase : resolve(parentBase, text.trim());
  }

  static String resolve(String base, String ref) {
    return HlsPlaylist.resolve(base, ref);
  }

  /** "start-end" -> {offset, length}, or {-1, -1}. */
  static long[] range(String v) {
    if (v == null || v.isEmpty()) return new long[]{-1, -1};
    int dash = v.indexOf('-');
    if (dash < 0) return new long[]{-1, -1};
    long start = parseLong(v.substring(0, dash), -1), end = parseLong(v.substring(dash + 1), -1);
    if (start < 0 || end < start) return new long[]{-1, -1};
    return new long[]{start, end - start + 1};
  }

  private static final Pattern DURATION =
      Pattern.compile("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?");

  /** ISO 8601 duration such as "PT1H2M3.5S" in seconds, or 0. */
  static double parseDuration(String v) {
    if (v == null || v.isEmpty()) return 0;
    Matcher m = DURATION.matcher(v.trim());
    if (!m.matches()) return 0;
    double[] unit = {86400, 3600, 60, 1};
    double total = 0;
    for (int i = 0; i < 4; i++) if (m.group(i + 1) != null) total += Double.parseDouble(m.group(i + 1)) * unit[i];
    return total;
  }

  private static long parseLong(String s, long fallback) {
    if (s == null || s.isEmpty()) return fallback;
    try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
  }

}
