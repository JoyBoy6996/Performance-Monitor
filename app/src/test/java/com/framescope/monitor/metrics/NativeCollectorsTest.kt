package com.framescope.monitor.metrics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.core.MetricAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The metrics that do not need a privileged shell.
 *
 * The point of these collectors is that a phone without Shizuku still knows how much
 * battery it has and how fast its screen runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeCollectorsTest {

    private lateinit var context: Context
    private lateinit var collectors: NativeCollectors

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        collectors = NativeCollectors(context)
    }

    /** A battery broadcast the way the system sends it. */
    private fun batteryIntent(
        level: Int = 80,
        scale: Int = 100,
        temperatureTenths: Int = 315,
        voltageMilliVolts: Int = 4_100
    ) = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
        putExtra(BatteryManager.EXTRA_LEVEL, level)
        putExtra(BatteryManager.EXTRA_SCALE, scale)
        putExtra(BatteryManager.EXTRA_TEMPERATURE, temperatureTenths)
        putExtra(BatteryManager.EXTRA_VOLTAGE, voltageMilliVolts)
        putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
    }

    @Test
    fun theBatteryLevelIsReadWithoutAnyShell() {
        val battery = NativeCollectors.parseBattery(batteryIntent(level = 73))

        assertTrue(battery.isAvailable)
        assertEquals(73, battery.value?.levelPercent)
        assertEquals("BatteryManager", battery.source)
    }

    @Test
    fun theBatteryTemperatureIsConvertedFromTenthsOfADegree() {
        val battery = NativeCollectors.parseBattery(batteryIntent(temperatureTenths = 315))

        assertNotNull(battery.value)
        assertEquals(31.5f, battery.value!!.temperatureC!!, 0.05f)
    }

    @Test
    fun aMissingBatteryIsReportedAsSuch() {
        val battery = NativeCollectors.parseBattery(intent = null)

        assertEquals(MetricAvailability.UNSUPPORTED, battery.availability)
        assertTrue(battery.error!!.isNotBlank())
    }

    @Test
    fun theLevelComesFromTheBatteryManagerWhenItKnowsIt() {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(manager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 55)

        val battery = collectors.battery()

        assertTrue(battery.isAvailable)
        assertEquals(55, battery.value?.levelPercent)
    }

    @Test
    fun thePowerIsCalculatedFromTheCurrentAndTheVoltage() {
        // 1.2 A at 4.1 V is 4.92 W. The sign of the current says it is discharging.
        val reading = NativeCollectors.BatteryReading(
            levelPercent = 50,
            temperatureC = 30f,
            currentMicroAmps = -1_200_000,
            voltageMilliVolts = 4_100,
            charging = false
        )
        assertEquals(4.92f, reading.watts!!, 0.02f)
    }

    @Test
    fun thePowerNeedsBothTheCurrentAndTheVoltage() {
        val withoutCurrent = NativeCollectors.BatteryReading(
            levelPercent = 50,
            temperatureC = 30f,
            currentMicroAmps = null,
            voltageMilliVolts = 4_100,
            charging = false
        )
        val withoutVoltage = NativeCollectors.BatteryReading(
            levelPercent = 50,
            temperatureC = 30f,
            currentMicroAmps = -1_200_000,
            voltageMilliVolts = null,
            charging = false
        )
        assertEquals(null, withoutCurrent.watts)
        assertEquals(null, withoutVoltage.watts)
    }

    @Test
    fun theMemoryIsReadWithoutAnyShell() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = 8L * 1024 * 1024 * 1024
                availMem = 3L * 1024 * 1024 * 1024
                lowMemory = false
            }
        )

        val memory = collectors.memory()

        assertTrue(memory.isAvailable)
        assertNotNull(memory.value)
        assertEquals(8L * 1024 * 1024 * 1024, memory.value!!.totalBytes)
        assertEquals(3L * 1024 * 1024 * 1024, memory.value!!.availableBytes)
        assertEquals("ActivityManager", memory.source)
    }

    @Test
    fun aSystemThatReportsNoMemoryIsSaidSo() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo())

        val memory = collectors.memory()

        assertEquals(MetricAvailability.UNSUPPORTED, memory.availability)
        assertTrue(memory.error!!.isNotBlank())
    }

    @Test
    fun theRefreshRateIsReadFromTheCurrentDisplayMode() {
        val refresh = collectors.refreshRate()

        // The point is that it answers at all: a display rate needs no shell.
        assertTrue(
            "availability was ${refresh.availability}",
            refresh.availability == MetricAvailability.AVAILABLE ||
                refresh.availability == MetricAvailability.UNSUPPORTED
        )
        if (refresh.isAvailable) {
            assertTrue(refresh.value!! > 0f)
        }
    }
}
