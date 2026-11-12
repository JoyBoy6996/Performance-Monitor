package com.framescope.monitor.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupPersistenceTest {

    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("framescope", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun verifiedSetupStateSurvivesSettingsRecreation() {
        AppSettings(context).setupCompleted.value = true

        assertTrue(AppSettings(context).setupCompleted.value)
    }
}
