/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser

import android.content.Context
import androidx.core.content.edit
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.ext.components

/**
 * Block Browser: offers uBlock Origin once, on the first start.
 *
 * The add-on is installed from addons.mozilla.org, so it is Mozilla-signed and updates itself like any other add-on.
 * Firefox's normal "Add uBlock Origin?" permission prompt is shown; if the user declines, they are not asked again
 * and can still install it later from the Add-ons screen.
 */
object UBlockOriginOffer {
    private const val PREFS = "blockbrowser"
    private const val KEY_OFFERED = "ublock_origin_offered"
    private const val UBLOCK_ORIGIN_URL =
        "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private val logger = Logger("UBlockOriginOffer")

    /** Call from the main activity once it is resumed, so the install prompt has a screen to appear on. */
    fun maybeOffer(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OFFERED, false)) return
        // Mark first: a crash or a declined prompt must not turn this into a prompt on every start.
        prefs.edit { putBoolean(KEY_OFFERED, true) }
        context.components.addonManager.installAddon(
            url = UBLOCK_ORIGIN_URL,
            installationMethod = InstallationMethod.MANAGER,
            onError = { e -> logger.warn("uBlock Origin was not installed", e) },
        )
    }
}
