package com.framescope.monitor.metrics

import com.framescope.monitor.shell.ShellHub
import com.framescope.monitor.shell.ShizukuRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UnavailableMetricsTest {

    @Test
    fun noShizukuResultDoesNotProduceBlankSystemSnapshot() = runBlocking {
        val sampler = SystemSampler(ShellHub(ShizukuRunner(context = null)))

        assertNull(sampler.sample())
    }
}
