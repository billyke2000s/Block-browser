/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Block Browser video finder, background part.
 *
 * Watches the responses each tab receives and remembers the ones that are videos: HLS playlists (.m3u8), DASH
 * manifests (.mpd) and plain video files played by a <video> element. Nothing is fetched or changed; the list is
 * only handed to the tab's content script, which passes it to the app. The list is cleared when the tab loads a new
 * page.
 */

const MAX_ITEMS = 40;
const HLS_TYPES = new Set([
  "application/vnd.apple.mpegurl",
  "application/x-mpegurl",
  "audio/mpegurl",
  "audio/x-mpegurl",
]);
const DASH_TYPES = new Set(["application/dash+xml"]);
const FILE_EXTENSIONS = [".mp4", ".m4v", ".webm", ".mov", ".mkv", ".ogv"];
// Pieces of a stream, never offered on their own.
const SEGMENT_EXTENSIONS = [".ts", ".m4s", ".m4a", ".aac", ".cmfv", ".cmfa", ".vtt"];

/** tabId -> Map(url -> item), in the order the page loaded them. */
const found = new Map();

function header(details, name) {
  for (const h of details.responseHeaders || []) {
    if (h.name.toLowerCase() === name) {
      return h.value || "";
    }
  }
  return "";
}

/** "hls", "dash", "file" or null. */
function classify(details) {
  let url;
  try {
    url = new URL(details.url);
  } catch (e) {
    return null;
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    return null;
  }
  if (details.statusCode >= 400) {
    return null;
  }
  const path = url.pathname.toLowerCase();
  const type = header(details, "content-type").toLowerCase().split(";")[0].trim();

  if (path.endsWith(".m3u8") || HLS_TYPES.has(type)) {
    return "hls";
  }
  if (path.endsWith(".mpd") || DASH_TYPES.has(type)) {
    return "dash";
  }
  if (SEGMENT_EXTENSIONS.some(ext => path.endsWith(ext)) || type === "video/mp2t" || type.includes("segment")) {
    return null;
  }
  // A <video> element playing a file directly. Players that feed the video in pieces (MediaSource) fetch them as
  // xmlhttprequest and are covered by their playlist or manifest above.
  if (details.type === "media" && (type.startsWith("video/") || FILE_EXTENSIONS.some(ext => path.endsWith(ext)))) {
    return "file";
  }
  return null;
}

function notify(tabId) {
  const items = list(tabId);
  browser.tabs.sendMessage(tabId, { type: "media", items }, { frameId: 0 }).catch(() => {
    // The content script isn't there yet; it asks for the list when it starts.
  });
}

function list(tabId) {
  const map = found.get(tabId);
  return map ? Array.from(map.values()) : [];
}

browser.webRequest.onBeforeRequest.addListener(
  details => {
    if (details.tabId >= 0 && found.delete(details.tabId)) {
      notify(details.tabId);
    }
  },
  { urls: ["<all_urls>"], types: ["main_frame"] }
);

browser.webRequest.onHeadersReceived.addListener(
  details => {
    if (details.tabId < 0) {
      return;
    }
    const kind = classify(details);
    if (!kind) {
      return;
    }
    let map = found.get(details.tabId);
    if (!map) {
      map = new Map();
      found.set(details.tabId, map);
    }
    if (map.has(details.url)) {
      return;
    }
    if (map.size >= MAX_ITEMS) {
      map.delete(map.keys().next().value);
    }
    const length = parseInt(header(details, "content-length"), 10);
    map.set(details.url, {
      url: details.url,
      kind,
      mime: header(details, "content-type").split(";")[0].trim(),
      // For a file played with range requests this is only the first part's size, so it's sent only for 200s.
      size: details.statusCode === 200 && length > 0 ? length : -1,
      inFrame: details.frameId !== 0,
    });
    notify(details.tabId);
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

browser.tabs.onRemoved.addListener(tabId => found.delete(tabId));

browser.runtime.onMessage.addListener((message, sender) => {
  if (message && message.type === "get" && sender.tab) {
    return Promise.resolve({ items: list(sender.tab.id) });
  }
  return undefined;
});
