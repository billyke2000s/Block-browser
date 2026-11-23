/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media;

import java.util.Locale;

/** Small address helpers shared by the stream parsers. No Android dependencies. */
final class StreamUrls {
  private StreamUrls() {}

  static boolean isWebUrl(String url) {
    if (url == null) return false;
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("https://") || lower.startsWith("http://");
  }
}
