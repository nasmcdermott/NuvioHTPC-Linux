package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keyboard/gamepad hand-off between page content and the desktop sidebar.
 *
 * Content keeps its own focus model (arrow keys move app-level indices, not Compose focus), so
 * the sidebar cannot be reached by focus traversal. Content asks for the sidebar with
 * [enterSidebar] and the sidebar gives focus back with [leaveSidebar]. Gamepad input arrives as
 * ordinary key events, so nothing here is gamepad-specific.
 */
object RootNavFocus {
    private val sidebarOwners = mutableSetOf<Any>()
    @Volatile
    private var sidebarManuallyAvailable = false

    /** True while any sidebar instance is drawn; routes can temporarily draw overlapping sidebars. */
    var sidebarAvailable: Boolean
        get() = sidebarManuallyAvailable || sidebarOwners.isNotEmpty()
        set(value) { sidebarManuallyAvailable = value }

    internal fun registerSidebar(owner: Any) {
        sidebarOwners += owner
    }

    internal fun unregisterSidebar(owner: Any) {
        sidebarOwners -= owner
    }

    /**
     * The sidebar's counterpart above the page: a bar drawn across the top of the screen with its row
     * of tabs. Up at the top edge of a page reaches it, Left and Right walk it, Down, Back or Dismiss
     * give the keys back.
     *
     * Two flags rather than one, because Back means different things at the two bars. This one is the
     * floating bar, drawn over every tab but Settings: there Back belongs to it once the page has
     * nothing left to close. The Settings screen's own row sets [settingsTopBarAvailable] instead:
     * Back there goes up a level and then back to the tab Settings was opened from, so the row must
     * not take it ([enterFloatingTopBar]). Up is the same at both ([enterTopBar]).
     */
    private val topBarOwners = mutableSetOf<Any>()
    @Volatile
    private var topBarManuallyAvailable = false

    /** True while any floating top bar is drawn. */
    var topBarAvailable: Boolean
        get() = topBarManuallyAvailable || topBarOwners.isNotEmpty()
        set(value) { topBarManuallyAvailable = value }

    internal fun registerTopBar(owner: Any) {
        topBarOwners += owner
    }

    internal fun unregisterTopBar(owner: Any) {
        topBarOwners -= owner
    }

    private val settingsTopBarOwners = mutableSetOf<Any>()
    @Volatile
    private var settingsTopBarManuallyAvailable = false

    /** The Settings screen's own row of tab icons; see [topBarAvailable]. */
    var settingsTopBarAvailable: Boolean
        get() = settingsTopBarManuallyAvailable || settingsTopBarOwners.isNotEmpty()
        set(value) { settingsTopBarManuallyAvailable = value }

    internal fun registerSettingsTopBar(owner: Any) {
        settingsTopBarOwners += owner
    }

    internal fun unregisterSettingsTopBar(owner: Any) {
        settingsTopBarOwners -= owner
    }

    /** Whether either bar across the top is drawn, which is what decides if Up has anywhere to go. */
    val anyTopBarDrawn: Boolean
        get() = topBarAvailable || settingsTopBarAvailable

    /**
     * A page that draws its own controls on the floating bar's line (the Sports page's chips and
     * pills, YouTube's section tabs) registers how to take the keys when the bar leaves sideways.
     * [left] focuses the page's control nearest the bar's left end, [right] the one nearest its right
     * end; each says whether it took the keys. The page's own edge controls ask for the bar with
     * [enterTopBar], so the two sides hand the keys to each other instead of stepping over the bar.
     *
     * The page unregisters while covered, so a page kept alive behind another never answers for it.
     */
    class BarSideHandoff(
        internal val left: () -> Boolean,
        internal val right: () -> Boolean,
    )

    private val barSideHandoffOwners = mutableMapOf<Any, BarSideHandoff>()

    internal fun registerBarSideHandoff(owner: Any, left: () -> Boolean, right: () -> Boolean) {
        barSideHandoffOwners[owner] = BarSideHandoff(left, right)
    }

    internal fun unregisterBarSideHandoff(owner: Any) {
        barSideHandoffOwners.remove(owner)
    }

    /**
     * A page's own way in, registered while it is the page on screen: what [BarPageEntry] adds.
     *
     * [restoreContentFocus] alone cannot promise the keys end up in the page — the control the page
     * had may be gone (a reload, a row that went away) or the bar may have been reached by pointer —
     * and the spatial fallback can then find nothing below the bar and drop focus altogether. The
     * page's own entry is what makes leaving the bar always land in the page.
     */
    private val pageEntries = mutableMapOf<Any, () -> Boolean>()

    internal fun registerPageEntry(owner: Any, entry: () -> Boolean) {
        pageEntries[owner] = entry
    }

    internal fun unregisterPageEntry(owner: Any) {
        pageEntries.remove(owner)
    }

    /**
     * A page's own row of controls across the top — the Sports header's chips on the left and its
     * pills on the right — answering by position, so a control below can reach the one drawn over it
     * (Up in the Sports calendar asks the row first, then the bar).
     *
     * Each picker gets the asking control's window x and returns whether it took the keys; one that
     * has no control there declines, and the next page (or the bar) is asked.
     */
    private val horizontalEntries = mutableMapOf<Any, (Float) -> Boolean>()

    internal fun registerHorizontalEntry(owner: Any, pick: (Float) -> Boolean) {
        horizontalEntries[owner] = pick
    }

    internal fun unregisterHorizontalEntry(owner: Any) {
        horizontalEntries.remove(owner)
    }

    fun enterHorizontalEntry(windowX: Float): Boolean {
        for (pick in horizontalEntries.values.toList().asReversed()) {
            if (runCatching { pick(windowX) }.getOrDefault(false)) return true
        }
        return false
    }

    /**
     * Asks the page on screen to take the keys at its own entry control. The newest entry is tried
     * first, for a page composed over another; one that declines (or throws) lets the next answer.
     */
    fun enterPageContent(): Boolean {
        for (entry in pageEntries.values.toList().asReversed()) {
            if (runCatching { entry() }.getOrDefault(false)) return true
        }
        return false
    }

    /**
     * Steps the keys out of the bar's [left] or right end into the page's controls beside it.
     *
     * The newest handoff is tried first, but a screen that is still composed behind another (the
     * YouTube mini player, a dialog's page) can leave a stale owner registered. A handoff that
     * cannot take the keys therefore declines, and the next one is asked, instead of blocking the
     * visible page behind it. A request that throws is a request that declined.
     */
    fun focusBarSide(left: Boolean): Boolean {
        // Snapshot: a handoff may focus a control and cause another screen to register/unregister.
        for (handoff in barSideHandoffOwners.values.toList().asReversed()) {
            if (runCatching { if (left) handoff.left() else handoff.right() }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    /**
     * The other half of [focusBarSide]: Left at the page control the bar steps into hands the keys
     * back to that same end of the bar, so the two sides pass them to each other instead of the key
     * being lost or the focus jumping over the bar into something unrelated.
     *
     * [left] is the bar's left end, the first icon drawn (Home); otherwise its right end, the
     * Settings gear, which is the last slot whatever the bar holds.
     */
    fun enterTopBarEnd(left: Boolean): Boolean =
        enterTopBarAt(if (left) 0 else Int.MAX_VALUE)

    /**
     * Set by a screen that closes itself as it asks for the bar (game mode is drawn over the bar), so
     * the app's focus restore after that close stands aside instead of taking the bar's focus back.
     * Cleared by whoever reads it.
     */
    @Volatile
    var handoffPending: Boolean = false

    private val enter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val enterProfile = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val enterPosition = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    private val enterTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val enterTopAt = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val enterTopNear = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    private val leave = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    val enterRequests: SharedFlow<Unit> = enter.asSharedFlow()

    /** Like [enterRequests], but lands on the profile row rather than the current tab's row. */
    val profileEnterRequests: SharedFlow<Unit> = enterProfile.asSharedFlow()

    /** A sidebar entry request carrying the window Y whose nearest nav row should take focus. */
    val positionEnterRequests: SharedFlow<Float> = enterPosition.asSharedFlow()

    /** The top bar's counterpart of [enterRequests]. */
    val topBarEnterRequests: SharedFlow<Unit> = enterTop.asSharedFlow()

    /** Like [topBarEnterRequests], but lands on the requested bar slot. */
    val topBarEnterAtRequests: SharedFlow<Int> = enterTopAt.asSharedFlow()

    /** Like [topBarEnterRequests], but lands on the icon nearest the given window x. */
    val positionTopBarEnterRequests: SharedFlow<Float> = enterTopNear.asSharedFlow()

    /**
     * One emission per [leaveSidebar]: whether the page should get the keys back. False when the
     * caller has something else ready to take them (the Games row opens game mode over the bar).
     */
    val leaveRequests: SharedFlow<Boolean> = leave.asSharedFlow()

    private var pendingContentFocusY: Float? = null

    /** Remembers where the focused content element is, for the sidebar's nearest-icon hand-off. */
    fun rememberContentFocusY(windowY: Float) {
        pendingContentFocusY = windowY
    }

    /**
     * Returns true when a sidebar is shown and took the request. A content element that registered
     * its position is entered at the nav icon nearest that height; otherwise the current tab's row
     * is used, as before.
     */
    fun enterSidebar(): Boolean {
        val y = pendingContentFocusY
        pendingContentFocusY = null
        return if (y != null) enterSidebarNear(y) else sidebarAvailable && enter.tryEmit(Unit)
    }

    /** Enters the sidebar on its profile row, for the Settings top bar's update icon. */
    fun enterSidebarAtProfile(): Boolean = sidebarAvailable && enterProfile.tryEmit(Unit)

    /** Enters the sidebar on the row vertically nearest [windowY], used by the Settings category rail. */
    fun enterSidebarNear(windowY: Float): Boolean = sidebarAvailable && enterPosition.tryEmit(windowY)

    /** Up reaches whichever bar across the top is drawn, so this asks for that one. */
    fun enterTopBar(): Boolean = anyTopBarDrawn && enterTop.tryEmit(Unit)

    /** Asks for the bar at [slot], for a page control that sits beside a particular icon. */
    fun enterTopBarAt(slot: Int): Boolean = anyTopBarDrawn && enterTopAt.tryEmit(slot)

    /**
     * Asks for the bar at the icon nearest [windowX] in window space, for a page control that asks
     * the bar by where it is rather than by which end it is near — the Sports calendar's Up, the
     * way the Settings rail asks the sidebar for its nearest row by height.
     */
    fun enterTopBarNear(windowX: Float): Boolean = anyTopBarDrawn && enterTopNear.tryEmit(windowX)

    /**
     * Back and Escape reach only the floating bar. On Settings they belong to the page's own ladder —
     * up a level, then back to the tab Settings was opened from — so the row drawn there must not take
     * them.
     */
    fun enterFloatingTopBar(): Boolean = topBarAvailable && enterTop.tryEmit(Unit)

    /**
     * [enterSidebar] for a screen drawn over the bar that closes itself as it asks (game mode). The
     * close restores the content's focus a moment later; [handoffPending] tells that restore to stand
     * aside so it does not take the bar's focus back.
     */
    fun enterSidebarAfterClosing(): Boolean {
        if (!sidebarAvailable) return false
        handoffPending = true
        if (enterSidebar()) return true
        handoffPending = false
        return false
    }

    /**
     * The bar is done with the keys and gives them back to the page it sits beside, which is what Back,
     * Right, Escape and Dismiss on the bar do, and what selecting the row of the page already open
     * does: a step out of that page rather than a dead end in the bar.
     *
     * [restoreContent] false for the one row that has something else ready to take the keys — Games
     * opens game mode over the bar — so the page is not focused first and then immediately left again.
     */
    fun leaveSidebar(restoreContent: Boolean = true) {
        leave.tryEmit(restoreContent)
    }

    /** The top bar's counterpart of [leaveSidebar]: Down, Back, Dismiss and Right on the bar. */
    fun leaveTopBar(restoreContent: Boolean = true) {
        leave.tryEmit(restoreContent)
    }

    /** The page whose control held the keys when the bar took them, and that control. */
    private var contentOwner: Any? = null
    private var contentControl: FocusTargetModifierNode? = null

    /**
     * Remembers the control the page had as the bar takes the keys, so [leaveSidebar] can put the
     * keyboard back exactly where the person left it rather than on the page's first control.
     *
     * [owner] names the page the control belongs to (the bar passes the selected tab). A control from a
     * page the person has since navigated away from is never restored: the page it belonged to can
     * still be composed behind the one now showing. Must run before the bar focuses itself, while the
     * page still holds the keys.
     */
    fun rememberContentFocus(owner: Any?, manager: FocusManager) {
        contentOwner = owner
        contentControl = manager.focusedControl()
    }

    fun forgetContentFocus() {
        contentOwner = null
        contentControl = null
    }

    /**
     * Gives the keys back to the control remembered for [owner]'s page. False when there is nothing
     * to give them back to — the bar was entered with nothing focused, the control has left the screen,
     * or the person selected another page while in the bar — which leaves the caller to find the page
     * itself. Always clears what was remembered.
     */
    fun restoreContentFocus(owner: Any?): Boolean {
        val control = contentControl
        val sameOwner = contentOwner == owner
        forgetContentFocus()
        if (!sameOwner || control == null || !control.node.isAttached) return false
        return runCatching { control.requestFocus() }.getOrDefault(false)
    }
}

/**
 * The page's own way back in from the bar, for the bar to compose while it is drawn: the keys go back
 * to the control the page had when the bar took them (remembered by [RootNavFocus.rememberContentFocus]),
 * so a person who stepped out of a row lands back on that row instead of at the top of the page.
 *
 * When that control has since left the screen — the page reloaded, the row it was on went away, the bar
 * was reached by pointer rather than from the page — the bar is drawn left of the page, so the page's
 * own controls to the right take the keys. Leaving the bar therefore always ends up in the page beside
 * it, which is what Back, Dismiss, Right and the row of the page already open are for.
 *
 * [sidebarHasFocus] keeps a leave that arrives after the page has already taken the keys from moving
 * focus on again, and [pageOwner] names the page the keyboard was remembered for.
 */
@Composable
internal fun SidebarLeaveRestoresContent(
    sidebarHasFocus: () -> Boolean,
    pageOwner: () -> Any?,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusManager) {
        RootNavFocus.leaveRequests.collect { restoreContent ->
            if (!restoreContent || !sidebarHasFocus()) return@collect
            if (RootNavFocus.restoreContentFocus(pageOwner())) return@collect
            focusManager.moveFocus(FocusDirection.Right)
        }
    }
}

/**
 * The top bar's counterpart of [SidebarLeaveRestoresContent]. The bar is drawn above the page, so with
 * nothing remembered the keys go down into the page rather than to the right.
 */
@Composable
internal fun TopBarLeaveRestoresContent(
    topBarHasFocus: () -> Boolean,
    pageOwner: () -> Any?,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusManager) {
        RootNavFocus.leaveRequests.collect { restoreContent ->
            if (!restoreContent || !topBarHasFocus()) return@collect
            if (RootNavFocus.restoreContentFocus(pageOwner())) return@collect
            // Nothing was remembered (the bar was reached by pointer) or the remembered control is
            // gone: ask the page itself, so leaving the bar always ends up in the page rather than
            // in whatever the spatial search finds — which on Home was nothing at all.
            if (RootNavFocus.enterPageContent()) return@collect
            focusManager.moveFocus(FocusDirection.Down)
        }
    }
}

/**
 * Registers the currently drawn page's controls beside the floating bar, and unregisters them when
 * the page is covered or leaves. Pages otherwise repeat the same [DisposableEffect],
 * [LocalScreenCovered][com.nuvio.app.core.ui.LocalScreenCovered] check and cleanup; keeping it here
 * makes a new page's bar handoff one call.
 *
 * [left] and [right] return whether they took the keys. A side left null declines, so the focus
 * system's normal spatial traversal (or another registered page) can answer instead. [enabled]
 * lets a page that has not drawn its controls yet stay out of the registry.
 */
@Composable
fun BarSideFocusHandoff(
    left: (() -> Boolean)? = null,
    right: (() -> Boolean)? = null,
    enabled: Boolean = true,
) {
    val owner = remember { Any() }
    val covered = LocalScreenCovered.current
    val currentLeft by rememberUpdatedState(left)
    val currentRight by rememberUpdatedState(right)
    DisposableEffect(owner, covered, enabled) {
        if (!covered && enabled) {
            RootNavFocus.registerBarSideHandoff(
                owner,
                left = { currentLeft?.invoke() ?: false },
                right = { currentRight?.invoke() ?: false },
            )
        }
        onDispose { RootNavFocus.unregisterBarSideHandoff(owner) }
    }
}

/**
 * Registers the current page's way in from a bar (see [RootNavFocus.enterPageContent]): the control
 * keys should land on when the bar is left with nothing to restore. Call inside the page, next to
 * its focus root; [entry] returns whether it took the keys.
 *
 * Dropped while the page is [covered][LocalScreenCovered], so a page kept alive behind another never
 * answers for it.
 */
@Composable
fun BarPageEntry(entry: () -> Boolean) {
    val owner = remember { Any() }
    val covered = LocalScreenCovered.current
    val currentEntry by rememberUpdatedState(entry)
    DisposableEffect(owner, covered) {
        if (!covered) RootNavFocus.registerPageEntry(owner) { currentEntry() }
        onDispose { RootNavFocus.unregisterPageEntry(owner) }
    }
}

/**
 * Registers the current page's row of controls across the top [pick]er (see
 * [RootNavFocus.enterHorizontalEntry]). Dropped while the page is [covered][LocalScreenCovered], so a
 * page kept alive behind another never answers for it.
 */
@Composable
fun HorizontalEntryHandoff(pick: (Float) -> Boolean) {
    val owner = remember { Any() }
    val covered = LocalScreenCovered.current
    val currentPick by rememberUpdatedState(pick)
    DisposableEffect(owner, covered) {
        if (!covered) RootNavFocus.registerHorizontalEntry(owner) { currentPick(it) }
        onDispose { RootNavFocus.unregisterHorizontalEntry(owner) }
    }
}

/** What a key does while a bar drawn across the top holds the focus. */
internal sealed interface TopBarKeyAction {
    data class Move(val index: Int) : TopBarKeyAction
    data object Activate : TopBarKeyAction
    data object Leave : TopBarKeyAction
}

/**
 * [sidebarKeyAction] turned sideways: Left and Right walk the bar's entries, Select activates, and
 * Down, Back or Dismiss hand the keys back to the page under it. Up is not an action here: there is
 * nothing above the bar, so [topBarKeyNavigation] keeps it on the bar.
 */
internal fun topBarKeyAction(key: Key, index: Int, count: Int): TopBarKeyAction? {
    if (count <= 0) return null
    return when (navActionForKey(key)) {
        NavAction.Right -> TopBarKeyAction.Move((index + 1).coerceAtMost(count - 1))
        NavAction.Left -> TopBarKeyAction.Move((index - 1).coerceAtLeast(0))
        NavAction.Select -> TopBarKeyAction.Activate
        NavAction.Down, NavAction.Dismiss, NavAction.Back -> TopBarKeyAction.Leave
        else -> null
    }
}

/**
 * Handles the top bar's keys, the sidebar's list turned sideways. Movement and leave act on key-down
 * and are always consumed, so an arrow at either end of the bar stays on the bar instead of
 * wandering into the page. Activation acts on key-up, as the sidebar's does: a held Select fires
 * once, and its release cannot land on the page that just took the keys.
 *
 * Up is consumed as well: there is nothing above a bar across the top of the screen, so it must not
 * travel back to the page's hand-off, which would only ask for the bar again. Left at the first
 * entry and Right at the last are released instead, so the bar's own arrow navigation can step out
 * to the page controls drawn beside it. If there is nothing there, the focus system keeps the key
 * where it is.
 */
internal fun Modifier.topBarKeyNavigation(
    index: () -> Int,
    count: () -> Int,
    onMove: (Int) -> Unit,
    onActivate: () -> Unit,
    onLeave: () -> Unit,
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    val key = event.navigationKey()
    // Nothing is drawn above the bar: Up stays on it rather than asking for it a second time.
    if (key == Key.DirectionUp) return@onPreviewKeyEvent true
    // Nothing further left inside the bar: let the bar's outer arrow navigation reach the utility
    // icons beside it, rather than pinning the focus to the first tab. The same at the right edge
    // reaches the search field drawn beyond the last tab.
    if (key == Key.DirectionLeft && index() <= 0) return@onPreviewKeyEvent false
    if (key == Key.DirectionRight && index() >= count() - 1) return@onPreviewKeyEvent false
    val action = topBarKeyAction(key, index(), count())
        ?: return@onPreviewKeyEvent false
    when (action) {
        is TopBarKeyAction.Move -> if (event.type == KeyEventType.KeyDown) onMove(action.index)
        TopBarKeyAction.Leave -> if (event.type == KeyEventType.KeyDown) onLeave()
        TopBarKeyAction.Activate -> if (event.type == KeyEventType.KeyUp) onActivate()
    }
    true
}

/**
 * Escape/Back on Home enters the sidebar only when nothing else on the page consumed it. With
 * "Escape goes windowed first" on, the player's Escape rule owns Escape, so the handoff stays off.
 */
internal fun shouldEnterSidebarOnDismiss(
    isHomeSelected: Boolean,
    dismissHandled: Boolean,
    sidebarAvailable: Boolean,
    escWindowedFirst: Boolean,
): Boolean = isHomeSelected && !dismissHandled && sidebarAvailable && !escWindowedFirst

/** What a key does while the sidebar holds focus. */
internal sealed interface SidebarKeyAction {
    data class Move(val index: Int) : SidebarKeyAction
    data object Activate : SidebarKeyAction
    data object Leave : SidebarKeyAction
}

internal fun sidebarKeyAction(key: Key, index: Int, count: Int, minIndex: Int = 0): SidebarKeyAction? {
    if (count <= 0) return null
    return when (navActionForKey(key)) {
        NavAction.Down -> SidebarKeyAction.Move((index + 1).coerceAtMost(count - 1))
        NavAction.Up -> SidebarKeyAction.Move((index - 1).coerceAtLeast(minIndex))
        NavAction.Select -> SidebarKeyAction.Activate
        NavAction.Right, NavAction.Dismiss, NavAction.Back -> SidebarKeyAction.Leave
        else -> null
    }
}

/**
 * At the first card of a row, Left leaves the row for the sidebar; anywhere else it steps back
 * within the row. Callers must offer the sidebar first: the content's moveItem clamps at zero, so a
 * bare Left at the first card would otherwise be swallowed and the sidebar never reached.
 */
internal fun leftLeavesRowForSidebar(itemIndex: Int): Boolean = itemIndex <= 0

/**
 * The sidebar's rows below the profile trigger: one per shown navigation entry, then Settings, then
 * Calendar while the sidebar can open it, then the voice row while voice control is on with a
 * model. The draw loop and the arrow keys both walk this count, so a row that is drawn always has an
 * index and no index exists without a row.
 */
internal fun sidebarRowCount(
    entryCount: Int,
    voiceRowShown: Boolean,
    musicRowShown: Boolean = false,
    calendarRowShown: Boolean = false,
): Int = entryCount + 1 +
    (if (calendarRowShown) 1 else 0) +
    (if (voiceRowShown) 1 else 0) +
    (if (musicRowShown) 1 else 0)

/** Calendar's row, just below Settings, or -1 when the sidebar has no way to open it. */
internal fun sidebarCalendarRowIndex(entryCount: Int, calendarRowShown: Boolean): Int =
    if (calendarRowShown) entryCount + 1 else -1

/** The row the voice item takes, or -1 when it is not drawn. Settings takes [entryCount]. */
internal fun sidebarVoiceRowIndex(
    entryCount: Int,
    voiceRowShown: Boolean,
    calendarRowShown: Boolean = false,
): Int = if (voiceRowShown) entryCount + 1 + (if (calendarRowShown) 1 else 0) else -1

/** The album-cover row, after Settings and the optional calendar and voice rows, or -1. */
internal fun sidebarMusicRowIndex(
    entryCount: Int,
    voiceRowShown: Boolean,
    musicRowShown: Boolean,
    calendarRowShown: Boolean = false,
): Int = if (musicRowShown) {
    entryCount + 1 +
        (if (calendarRowShown) 1 else 0) +
        (if (voiceRowShown) 1 else 0)
} else {
    -1
}

/** Keeps the focused row on a row that still exists after the shown set changes under it. */
internal fun clampSidebarIndex(index: Int, rowCount: Int, minIndex: Int): Int =
    if (rowCount <= 0) minIndex else index.coerceIn(minIndex, rowCount - 1)

/** What a key a tab screen has no use for does: close a page it opened, or reach the navigation. */
internal enum class TabHandoffKey { Back, Escape, Left, Up }

/**
 * Which of the keys a tab screen has no use for hands off to the app's navigation bar. [key] is a
 * [navigationKey] result, so a rebound Back or Dismiss key arrives here as Backspace or Escape.
 *
 * [homeSelected] keeps Escape on Home, which owns it: the hero's trailer closes on it and the
 * "Escape goes windowed first" setting decides whether it may also reach the bar. Handling it here
 * as well would fire twice and defeat that setting.
 *
 * None of the three needs a per-screen flag. Every screen/element that closes a level of its own
 * (Live TV's regions popup, the Music pages' selection, a tab's ladder) runs in the preview phase
 * and answers false when it has nothing left to close, so those levels are spent before this is
 * reached and only the last press arrives here. Left is the same: a page of ordinary focusable
 * controls moves focus with it and releases it when it moved nowhere (see
 * [arrowKeyFocusNavigation]), and a page that runs its own selection declines it at the first item,
 * so a Left that arrives here has nothing further left in the page.
 */
internal fun tabHandoffKey(
    key: Key,
    homeSelected: Boolean,
    topBarAvailable: Boolean = false,
): TabHandoffKey? = when (navActionForKey(key)) {
    NavAction.Dismiss -> if (homeSelected) null else TabHandoffKey.Escape
    NavAction.Back -> TabHandoffKey.Back
    NavAction.Left -> TabHandoffKey.Left
    // Up reaches a bar drawn across the top of the screen, the way Left reaches the sidebar. A page
    // that still had somewhere to go with it kept the key, so one that arrives here is at its top.
    NavAction.Up -> if (topBarAvailable) TabHandoffKey.Up else null
    else -> null
}

/**
 * Dismiss pressed in a focused text field that has nothing left to clear. [tabSidebarHandoff] stands
 * aside for every text field (a field is where Back and Escape delete and clear first), so the field
 * itself has to ask for the bar. [fallback] runs when there is no bar on screen, which is the old
 * behaviour: let go of the field. Returns whether the bar took it.
 */
internal fun dismissToSidebarOr(fallback: () -> Unit): Boolean {
    val took = RootNavFocus.enterSidebar()
    if (!took) fallback()
    return took
}

/**
 * [dismissToSidebarOr] for a window whose navigation is the floating bar across the top rather than
 * the sidebar. Clearing a text field's focus used to be the fallback there, which left the window
 * with no focused control at all: the next Back/Escape is then delivered nowhere and the bar can
 * never be reached with the keyboard or a pad. Ask either bar first, and only clear focus when no
 * bar is drawn (a window with no navigation chrome at all keeps the old behaviour).
 */
internal fun dismissToBarOr(fallback: () -> Unit): Boolean {
    val took = RootNavFocus.enterSidebar() || RootNavFocus.enterFloatingTopBar()
    if (!took) fallback()
    return took
}

/**
 * Marks a focusable content element as the place a sidebar hand-off should come from. When it holds
 * the keys, its window-space centre is recorded; [RootNavFocus.enterSidebar] then picks the nav icon
 * nearest that height instead of always returning to the current tab's row.
 */
fun Modifier.trackSidebarEntryFocusY(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var centerY by remember { mutableStateOf(0f) }
    LaunchedEffect(focused, centerY) {
        if (focused) RootNavFocus.rememberContentFocusY(centerY)
    }
    this
        .onFocusChanged { focused = it.isFocused }
        .onGloballyPositioned { centerY = it.boundsInWindow().center.y }
}

/**
 * Hands the keys an ordinary tab screen has no use for to the navigation bar, as Home's own handlers
 * do at its first card and on an empty Dismiss. Bubble phase, so a context menu's popup, a tab's own
 * page ladder or a text field that is typing all get the key first; a text field keeps every key.
 *
 * Left included, for every tab: a page that moves focus itself releases a Left that moved it nowhere
 * (see [arrowKeyFocusNavigation]) and a page that runs its own selection declines it at the first
 * item, so a Left that gets here is one with nothing further left on the page. Up is the same for a
 * bar drawn across the top, which [topBarAvailable] reports: a page that still had a row above it
 * moved into it and kept the key.
 */
internal fun Modifier.tabSidebarHandoff(
    homeSelected: () -> Boolean,
    sidebarAvailable: () -> Boolean,
    onBack: () -> Boolean,
    topBarAvailable: () -> Boolean = { RootNavFocus.anyTopBarDrawn },
): Modifier = onKeyEvent { event: KeyEvent ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    // Typing fields keep every Back/Escape: they delete and clear first (see navigationKey).
    if (TextInputFocusTracker.active.value) return@onKeyEvent false
    when (tabHandoffKey(event.navigationKey(), homeSelected(), topBarAvailable())) {
        // Back and Escape close what the tab opened over itself before a bar takes the focus. A
        // window with no bar on it keeps the key, as it always has. Only the floating bar takes them:
        // on Settings the page's own ladder owns Back, up a level and then out of the tab.
        TabHandoffKey.Back, TabHandoffKey.Escape ->
            if (onBack()) true else sidebarAvailable() && RootNavFocus.enterSidebar() ||
                RootNavFocus.enterFloatingTopBar()
        // Left at the page's own left edge steps out of it: to the sidebar when one is drawn, and
        // otherwise to the bar across the top, which is the only navigation the window has.
        TabHandoffKey.Left ->
            (sidebarAvailable() && RootNavFocus.enterSidebar()) || RootNavFocus.enterFloatingTopBar()
        TabHandoffKey.Up -> RootNavFocus.enterTopBar()
        null -> false
    }
}

/**
 * Handles the sidebar's keys. Movement and leave act on key-down; activation acts on key-up so a
 * held Enter fires once and its release cannot land on the content that just took focus.
 */
internal fun Modifier.sidebarKeyNavigation(
    index: () -> Int,
    count: () -> Int,
    onMove: (Int) -> Unit,
    onActivate: () -> Unit,
    onLeave: () -> Unit,
    minIndex: Int = 0,
    enabled: Boolean = true,
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    if (!enabled) return@onPreviewKeyEvent false
    val action = sidebarKeyAction(event.navigationKey(), index(), count(), minIndex) ?: return@onPreviewKeyEvent false
    val isActivate = action == SidebarKeyAction.Activate
    when (event.type) {
        KeyEventType.KeyDown -> if (!isActivate) when (action) {
            is SidebarKeyAction.Move -> onMove(action.index)
            SidebarKeyAction.Leave -> onLeave()
            SidebarKeyAction.Activate -> Unit
        }
        KeyEventType.KeyUp -> if (isActivate) onActivate()
        else -> Unit
    }
    true
}
