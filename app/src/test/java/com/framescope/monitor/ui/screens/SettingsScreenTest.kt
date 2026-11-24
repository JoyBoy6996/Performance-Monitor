package com.framescope.monitor.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name of an app that has none of its own.
 *
 * A system plugin - the wallet of the system bars, for example - has no label
 * a person would recognise, and the raw package name was the title of the row.
 */
class SettingsScreenTest {

    @Test
    fun `a system plugin is named after its package`() {
        assertEquals(
            "Globalactions wallet",
            readableAppName("com.android.systemui.plugin.globalactions.wallet", "")
        )
    }

    @Test
    fun `a label that the app gave is kept`() {
        assertEquals(
            "PhonePe",
            readableAppName("com.phonepe.app", "PhonePe")
        )
    }

    @Test
    fun `a label that is the package itself is replaced`() {
        assertEquals(
            "Systemui plugin",
            readableAppName("com.android.systemui.plugin", "com.android.systemui.plugin")
        )
    }

    @Test
    fun `a package without a dot keeps its name`() {
        assertEquals("platform", readableAppName("platform", ""))
    }
}
