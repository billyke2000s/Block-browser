/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Block Browser video finder, content part: passes the tab's list of videos from the background script to the app.
 * Messages sent from a content script reach the app tagged with the tab they came from, which the background
 * script can't do. The whole list is sent each time, so a message that gets lost is made up for by the next one.
 */

const NATIVE_APP = "blockbrowserMedia";

function forward(items) {
  browser.runtime
    .sendNativeMessage(NATIVE_APP, {
      type: "media",
      page: location.href,
      title: document.title || "",
      items: items || [],
    })
    .catch(() => {});
}

browser.runtime.onMessage.addListener(message => {
  if (message && message.type === "media") {
    forward(message.items);
  }
});

browser.runtime
  .sendMessage({ type: "get" })
  .then(reply => forward(reply && reply.items))
  .catch(() => {});

// The page title usually arrives after document_start; send it along once the page has loaded.
window.addEventListener("load", () => {
  browser.runtime
    .sendMessage({ type: "get" })
    .then(reply => forward(reply && reply.items))
    .catch(() => {});
});
