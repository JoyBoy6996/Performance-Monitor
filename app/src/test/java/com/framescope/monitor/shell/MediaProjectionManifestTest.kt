package com.framescope.monitor.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission the app captures the screen with.
 *
 * Android 14 and later refuse a foreground service that captures the screen when
 * the app does not hold this permission. The service then never promotes itself,
 * the system takes it away, and the app dies with it - and with it the frame
 * rate the user came for.
 */
class MediaProjectionManifestTest {

    @Test
    fun `the media projection permission is declared`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("the manifest was not found at ${manifest.absolutePath}", manifest.exists())

        val text = manifest.readText()
        assertTrue(
            "android.permission.PROJECT_MEDIA is not declared",
            text.contains("android.permission.PROJECT_MEDIA")
        )
        assertTrue(
            "the media projection foreground service permission is not declared",
            text.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
        )
        assertTrue(
            "the recorder is not a media projection service",
            text.contains("android:foregroundServiceType=\"mediaProjection\"")
        )
    }
}
