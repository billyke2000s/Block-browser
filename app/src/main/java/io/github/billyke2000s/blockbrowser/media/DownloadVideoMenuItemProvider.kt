/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mozilla.components.compose.base.text.Text
import mozilla.components.compose.menu.data.MenuItem
import mozilla.components.compose.menu.data.MenuItemTitle
import mozilla.components.compose.menu.data.StandardMenuItem
import mozilla.components.compose.menu.store.MenuEvent
import mozilla.components.compose.menu.ui.MenuItemIconRes
import org.mozilla.fenix.R
import org.mozilla.fenix.components.menu.MenuHost
import org.mozilla.fenix.components.menu.MenuItemProvider
import org.mozilla.fenix.components.menu.MenuTarget
import org.mozilla.fenix.components.menu.store.MenuAction
import mozilla.components.ui.icons.R as iconsR

/**
 * Block Browser: "Download video" in the customizable menu (the default menu adds it in MainMenu.kt).
 *
 * @param context Context of the screen showing the menu; its activity shows the follow-up dialogs.
 * @param target What the menu is shown for, to know which tab's videos to offer.
 */
class DownloadVideoMenuItemProvider(
    private val context: Context,
    private val target: MenuTarget,
) : MenuItemProvider {
    override val itemFlow: StateFlow<MenuItem?> =
        MutableStateFlow(
            StandardMenuItem(
                title = MenuItemTitle(Text.Resource(R.string.bb_menu_download_video)),
                icon = MenuItemIconRes(iconsR.drawable.mozac_ic_download_24),
                onClickEvent = MenuAction.DownloadVideo,
            )
        )

    override fun handles(event: MenuEvent) = event == MenuAction.DownloadVideo

    override fun onEvent(event: MenuEvent, menu: MenuHost) {
        menu.dismiss()
        val activity = context.findActivity() ?: return
        VideoDownloadFlow.start(activity, (target as? MenuTarget.CustomTab)?.customTabId)
    }

    private tailrec fun Context.findActivity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
}
