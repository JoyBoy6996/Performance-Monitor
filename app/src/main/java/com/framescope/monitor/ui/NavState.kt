package com.framescope.monitor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.framescope.monitor.core.SessionRecord

/**
 * Where the app is: the tab, and the page that is open inside it.
 *
 * The rule is simple and it is the whole point of this class: a tab is always
 * stronger than a page. Choosing another tab closes the page that was open,
 * so a report of a run can never stay on top of the screen the user just
 * asked for. The back key closes the page and leaves the tab where it was.
 */
class NavState(initialTab: AppTab = AppTab.MONITOR) {

    /** The place the user is in. */
    var tab: AppTab by mutableStateOf(initialTab)
        private set

    /** The page that is open inside the tab, if there is one. */
    var openPage: OpenPage? by mutableStateOf(null)
        private set

    /** True when a page is open on top of the tab. */
    val hasOpenPage: Boolean get() = openPage != null

    /**
     * Chooses a tab.
     *
     * The page inside the old tab is closed, whatever it was. Tapping the tab
     * the user is already in closes the page too: the bar is a place, and a
     * place is always above the page that was opened from it.
     */
    fun selectTab(selected: AppTab) {
        tab = selected
        openPage = null
    }

    /** Opens a page inside the current tab. */
    fun open(page: OpenPage) {
        openPage = page
    }

    /** Closes the page that is open and leaves the tab where it was. */
    fun closePage() {
        openPage = null
    }

    /** Opens the report of one recorded run. */
    fun openSession(session: SessionRecord) {
        open(OpenPage.Session(session))
    }

    /** Opens the comparison of two recorded runs. */
    fun openCompare() {
        open(OpenPage.Compare)
    }

    /** Opens the tools inside the settings. */
    fun openTools() {
        open(OpenPage.Tools)
    }

    companion object {
        /**
         * Keeps the tab across a rotation.
         *
         * Only the tab is kept: the page that was open is a step inside the
         * tab and is closed when the screen is built again.
         */
        val Saver: Saver<NavState, String> = Saver(
            save = { it.tab.name },
            restore = { NavState(AppTab.valueOf(it)) }
        )
    }
}
