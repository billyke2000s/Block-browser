/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Block Browser's ad blocker.
 *
 * Every request a page makes is checked against blocklist.txt (StevenBlack hosts + HaGeZi Multi PRO, one domain per
 * line; a listed domain also blocks all its subdomains). Matching requests are cancelled; a blocked page (the whole
 * tab navigating to a listed domain) shows blocked.html instead. Sites the user turned ad blocking off for are left
 * alone. Blocked requests are reported to the app in batches for the statistics on the home page; for private tabs
 * only the count is reported, not the domain.
 *
 * The app owns the list of sites with blocking turned off and sends it over the native port.
 */

const NATIVE_APP = "blockbrowserBlocker";
const FLUSH_MS = 2000;

let domains = null;
let allowed = new Set();
let port = null;
let retryMs = 1000;
const pendingHosts = new Map(); // host -> count, normal tabs
let pendingPrivate = 0;
let flushTimer = null;
const topHost = new Map(); // tabId -> host of the page shown in it

const loading = fetch(browser.runtime.getURL("blocklist.txt"))
  .then(r => r.text())
  .then(text => {
    const set = new Set();
    for (const raw of text.split("\n")) {
      const line = raw.trim();
      if (line && line[0] !== "#") {
        set.add(line);
      }
    }
    domains = set;
    send({ type: "ready", domains: set.size });
  })
  .catch(e => {
    console.error("Block Browser: could not load the blocklist", e);
    domains = new Set();
  });

function hostOf(url) {
  try {
    const u = new URL(url);
    return u.protocol === "http:" || u.protocol === "https:" ? u.hostname.toLowerCase() : "";
  } catch (e) {
    return "";
  }
}

/** The listed domain that blocks host (host itself or a parent), or null. */
function listed(host) {
  let h = host;
  for (;;) {
    if (domains.has(h)) {
      return h;
    }
    const dot = h.indexOf(".");
    if (dot < 0) {
      return null;
    }
    h = h.slice(dot + 1);
  }
}

/** Whether the user turned blocking off for this page's site (the host or any parent domain). */
function isAllowed(pageHost) {
  let h = pageHost;
  while (h) {
    if (allowed.has(h)) {
      return true;
    }
    const dot = h.indexOf(".");
    h = dot < 0 ? "" : h.slice(dot + 1);
  }
  return false;
}

function count(host, incognito) {
  if (incognito) {
    pendingPrivate++;
  } else {
    pendingHosts.set(host, (pendingHosts.get(host) || 0) + 1);
  }
  if (!flushTimer) {
    flushTimer = setTimeout(flush, FLUSH_MS);
  }
}

function flush() {
  flushTimer = null;
  if (!pendingHosts.size && !pendingPrivate) {
    return;
  }
  const hosts = {};
  for (const [h, n] of pendingHosts) {
    hosts[h] = n;
  }
  if (send({ type: "blocked", hosts, private: pendingPrivate })) {
    pendingHosts.clear();
    pendingPrivate = 0;
  } else {
    flushTimer = setTimeout(flush, FLUSH_MS);
  }
}

function send(message) {
  if (!port) {
    return false;
  }
  try {
    port.postMessage(message);
    return true;
  } catch (e) {
    return false;
  }
}

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    port = null;
    setTimeout(connect, retryMs);
    retryMs = Math.min(retryMs * 2, 30000);
    return;
  }
  port.onMessage.addListener(message => {
    retryMs = 1000;
    if (message && message.type === "allowed" && Array.isArray(message.hosts)) {
      allowed = new Set(message.hosts.map(h => String(h).toLowerCase()));
    }
  });
  port.onDisconnect.addListener(() => {
    port = null;
    setTimeout(connect, retryMs);
    retryMs = Math.min(retryMs * 2, 30000);
  });
  if (domains) {
    send({ type: "ready", domains: domains.size });
  }
}
connect();

async function onBeforeRequest(details) {
  if (!domains) {
    await loading;
  }
  const host = hostOf(details.url);
  if (!host) {
    return {};
  }
  if (details.type === "main_frame") {
    if (details.tabId >= 0) {
      topHost.set(details.tabId, host);
    }
    const hit = listed(host);
    if (!hit || isAllowed(host)) {
      return {};
    }
    count(hit, details.incognito);
    return {
      redirectUrl:
        browser.runtime.getURL("blocked.html") +
        "?host=" + encodeURIComponent(host) + "&url=" + encodeURIComponent(details.url),
    };
  }
  const page =
    (details.tabId >= 0 && topHost.get(details.tabId)) ||
    hostOf((details.frameAncestors && details.frameAncestors.length &&
      details.frameAncestors[details.frameAncestors.length - 1].url) || details.documentUrl || "");
  if (page && isAllowed(page)) {
    return {};
  }
  const hit = listed(host);
  if (!hit) {
    return {};
  }
  count(hit, details.incognito);
  return { cancel: true };
}

browser.webRequest.onBeforeRequest.addListener(onBeforeRequest, { urls: ["<all_urls>"] }, ["blocking"]);
browser.tabs.onRemoved.addListener(tabId => topHost.delete(tabId));

// The blocked page's "Allow this site" button.
browser.runtime.onMessage.addListener(message => {
  if (message && message.type === "allow" && typeof message.host === "string") {
    const host = message.host.toLowerCase();
    allowed.add(host);
    send({ type: "allow", host });
    return Promise.resolve({ ok: true });
  }
  return undefined;
});
