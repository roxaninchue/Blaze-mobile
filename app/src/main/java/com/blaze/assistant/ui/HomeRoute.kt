package com.blaze.assistant.ui

/**
 * RC2 navigation (pure, HomeRouteTest): with the redesigned Home on, the new Home (grid + chat bar) is the app's root.
 *  - Navigation is always Swipe (Home under the chat sheet); the old Classic tabs' Home tab never shows.
 *  - Back from any view, page, sheet or the plain chat sheet lands on Home with the sheet down and views cleared; Back on
 *    Home itself is Home's own (closes a focused card / chat over Home / the board, then leaves Blaze).
 *  - Back out of Blaze's own activities (Music, Video, Browser, Terminal, Blaze Phone) brings MainActivity forward with
 *    [EXTRA_HOME], which lands on Home too.
 *  - Asks from Home (Quick actions, board buttons) open the chat over Home, not the full chat.
 * "All widgets" stays the explicit way to the old board; the full chat stays reachable by swiping the bar up or "Open the
 * full chat", and Blaze still raises it for a question / approval it needs; Back from it goes Home.
 * A private chat keeps its own Back (it asks before leaving).
 */
object HomeRoute {
    const val EXTRA_HOME = "blaze_home"

    fun swipe(setting: Boolean, redesign: Boolean): Boolean = setting || redesign

    fun homeRoot(swipe: Boolean, redesign: Boolean, privateChat: Boolean): Boolean = swipe && redesign && !privateChat

    fun askOverHome(redesign: Boolean): Boolean = redesign

    /** Where the sheet settles after Back / a return: 0 = Home, 1 = the chat sheet; null = leave it. */
    fun sheetAfterBack(swipe: Boolean, homeRoot: Boolean, sheet: Float): Float? = when {
        !swipe -> null
        homeRoot -> 0f
        sheet < 1f -> 1f
        else -> null
    }
}
