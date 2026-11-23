/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media;

import java.util.ArrayList;
import java.util.List;

/**
 * One track of a stream (video, audio, or both muxed together) as the list of pieces to fetch and join in order.
 * Built from an HLS media playlist or a DASH representation. No Android dependencies.
 */
public final class StreamTrack {

  /** A piece of the track: a whole address, or a byte range within it. */
  public static final class Piece {
    public final String uri;
    public final long offset, length; // -1 when the whole address is the piece

    public Piece(String uri, long offset, long length) {
      this.uri = uri;
      this.offset = offset;
      this.length = length;
    }

    @Override public String toString() {
      return offset < 0 ? uri : uri + " [" + offset + "+" + length + "]";
    }
  }

  /** Header the other pieces depend on (fragmented MP4 / WebM initialization), or null. */
  public Piece init;
  public final List<Piece> pieces = new ArrayList<>();
  /** Total duration in seconds, or 0 when unknown. */
  public double duration;
  /** HLS audio-only pieces start with an ID3 tag that has to be dropped before joining them. */
  public boolean stripId3;

  public int pieceCount() {
    return pieces.size() + (init != null ? 1 : 0);
  }

  /** Converts an HLS media playlist (already checked with {@link StreamDownload#checkHls}). */
  public static StreamTrack fromHls(HlsPlaylist media) {
    StreamTrack t = new StreamTrack();
    if (media.init != null) t.init = new Piece(media.init.uri, media.init.offset, media.init.length);
    for (HlsPlaylist.Segment s : media.segments) t.pieces.add(new Piece(s.uri, s.offset, s.length));
    t.duration = media.duration();
    t.stripId3 = true;
    return t;
  }
}
