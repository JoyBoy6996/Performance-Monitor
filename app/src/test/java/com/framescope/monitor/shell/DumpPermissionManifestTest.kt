package com.framescope.monitor.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission the app reads the frame timings with.
 *
 * Without it the app can only ask Shizuku, and the answer comes back through a
 * binder that cuts it short. A game owns a dozen surfaces and its dump is
 * bigger than what a binder call may carry, so the layers that were drawing
 * went missing and the game showed no frame rate at all.
 *
 * The permission has to be declared, or `pm grant` refuses to hand it over.
 */
class DumpPermissionManifestTest {

    @Test
    fun `the dump permission is declared`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("the manifest was not found at ${manifest.absolutePath}", manifest.exists())

        val text = manifest.readText()
        assertTrue(
            "android.permission.DUMP is not declared",
            text.contains("android.permission.DUMP")
        )
        assertTrue(
            "the usage stats permission is not declared",
            text.contains("android.permission.PACKAGE_USAGE_STATS")
        )
    }
}
