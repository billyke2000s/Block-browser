/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

const params = new URLSearchParams(location.search);
const host = params.get("host") || "";
const url = params.get("url") || "";
document.getElementById("host").textContent = host || "This site";

document.getElementById("back").addEventListener("click", () => {
  if (history.length > 1) {
    history.back();
  } else {
    window.close();
  }
});

document.getElementById("allow").addEventListener("click", async () => {
  if (!host || !/^https?:\/\//i.test(url)) {
    return;
  }
  await browser.runtime.sendMessage({ type: "allow", host });
  location.replace(url);
});
