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
 * Block Browser: installs uBlock Origin silently on first start, so ads are blocked from the beginning without
 * asking.
 *
 * The add-on comes from addons.mozilla.org, so it is Mozilla-signed and updates itself like any other add-on. Its
 * permission and "add-on installed" prompts are accepted automatically (see [isSilentInstall], used by
 * WebExtensionPromptFeature), including in private tabs. If the install fails (for example no internet on first
 * start), it is tried again on the next start. Once it has been installed, Block Browser never reinstalls it, so
 * removing it in Settings → Add-ons is respected.
 */
object UBlockOriginOffer {
    const val UBLOCK_ORIGIN_ID = "uBlock0@raymondhill.net"
    private const val PREFS = "blockbrowser"
    private const val KEY_INSTALLED = "ublock_origin_installed"
    private const val UBLOCK_ORIGIN_URL =
        "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private val logger = Logger("UBlockOriginOffer")

    private const val POST_INSTALL_GRACE_MS = 60_000L

    @Volatile
    private var installing = false

    /** Until when uBlock Origin's prompts are accepted without showing them (covers the "installed" prompt). */
    @Volatile
    private var silentUntil = 0L

    /** True while Block Browser's own uBlock Origin install is running (or just finished) and this is that add-on. */
    fun isSilentInstall(extensionId: String) =
        extensionId == UBLOCK_ORIGIN_ID && System.currentTimeMillis() < silentUntil

    /** Call from the main activity once it is resumed. */
    fun maybeOffer(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INSTALLED, false) || installing) return
        installing = true
        silentUntil = Long.MAX_VALUE
        context.components.addonManager.installAddon(
            url = UBLOCK_ORIGIN_URL,
            installationMethod = InstallationMethod.MANAGER,
            onSuccess = {
                prefs.edit { putBoolean(KEY_INSTALLED, true) }
                installing = false
                silentUntil = System.currentTimeMillis() + POST_INSTALL_GRACE_MS
            },
            onError = { e ->
                installing = false
                silentUntil = 0L
                logger.warn("uBlock Origin was not installed; trying again next start", e)
            },
        )
    }
}
