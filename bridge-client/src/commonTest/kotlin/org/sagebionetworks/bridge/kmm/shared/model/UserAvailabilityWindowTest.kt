package org.sagebionetworks.bridge.kmm.shared.model

import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalTime
import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.models.UserAvailabilityWindow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalStdlibApi::class)
class UserAvailabilityWindowTest : BaseTest() {

    @Test
    fun testRandomizedTimes_MinWindow_SingleDay() {
        val availabilityWindow = UserAvailabilityWindow(
            wakeTime = LocalTime(9, 0),
            bedTime = LocalTime(18, 0)
        )

        assertEquals(9 * 60, availabilityWindow.availabilityInMinutes())

        val startTimes = availabilityWindow.randomSessionTimes(6, DateTimePeriod(hours = 1, minutes = 30))
        assertNotNull(startTimes)
        assertEquals(6, startTimes.size)
        assertEquals(LocalTime(9,0), startTimes[0])
        assertEquals(LocalTime(10,30), startTimes[1])
        assertEquals(LocalTime(12,0), startTimes[2])
        assertEquals(LocalTime(13,30), startTimes[3])
        assertEquals(LocalTime(15,0), startTimes[4])
        assertEquals(LocalTime(16,30), startTimes[5])
    }

    @Test
    fun testRandomizedTimes_MinWindow_AcrossMidnight() {
        val availabilityWindow = UserAvailabilityWindow(
            wakeTime = LocalTime(21, 0),
            bedTime = LocalTime(6, 0)
        )

        assertEquals(9 * 60, availabilityWindow.availabilityInMinutes())

        val startTimes = availabilityWindow.randomSessionTimes(6, DateTimePeriod(hours = 1, minutes = 30))
        assertNotNull(startTimes)
        assertEquals(6, startTimes.size)

        assertEquals(LocalTime(0,0), startTimes[0])
        assertEquals(LocalTime(1,30), startTimes[1])
        assertEquals(LocalTime(3,0), startTimes[2])
        assertEquals(LocalTime(4,30), startTimes[3])
        assertEquals(LocalTime(21,0), startTimes[4])
        assertEquals(LocalTime(22,30), startTimes[5])
    }

    @Test
    fun testRandomizedTimes_SingleDay() {
        val availabilityWindow = UserAvailabilityWindow(
            wakeTime = LocalTime(9, 0),
            bedTime = LocalTime(18, 0)
        )

        assertEquals(9 * 60, availabilityWindow.availabilityInMinutes())

        val startTimes = availabilityWindow.randomSessionTimes(6, DateTimePeriod(hours = 1, minutes = 15))
        assertNotNull(startTimes)
        assertEquals(6, startTimes.size)

        assertContains(LocalTime(9,0)..<LocalTime(9,15), startTimes[0])
        assertContains(LocalTime(10,30)..<LocalTime(10,45), startTimes[1])
        assertContains(LocalTime(12,0)..<LocalTime(12,15), startTimes[2])
        assertContains(LocalTime(13,30)..<LocalTime(13,45), startTimes[3])
        assertContains(LocalTime(15,0)..<LocalTime(15,15), startTimes[4])
        assertContains(LocalTime(16,30)..<LocalTime(16,45), startTimes[5])

        val secondRun = availabilityWindow.randomSessionTimes(6, DateTimePeriod(hours = 1, minutes = 15))
        assertNotNull(secondRun)
        assertEquals(6, secondRun.size)
        assertNotEquals(startTimes, secondRun)
    }

    @Test
    fun testRandomizedTimes_AcrossMidnight() {
        val availabilityWindow = UserAvailabilityWindow(
            wakeTime = LocalTime(21, 0),
            bedTime = LocalTime(6, 0)
        )

        assertEquals(9 * 60, availabilityWindow.availabilityInMinutes())

        val startTimes = availabilityWindow.randomSessionTimes(6, DateTimePeriod(hours = 1, minutes = 15))
        assertNotNull(startTimes)
        assertEquals(6, startTimes.size)

        assertContains(LocalTime(0,0)..<LocalTime(0,15), startTimes[0])
        assertContains(LocalTime(1,30)..<LocalTime(1,45), startTimes[1])
        assertContains(LocalTime(3,0)..<LocalTime(3,15), startTimes[2])
        assertContains(LocalTime(4,30)..<LocalTime(4,45), startTimes[3])
        assertContains(LocalTime(21,0)..<LocalTime(21,15), startTimes[4])
        assertContains(LocalTime(22,30)..<LocalTime(22,45), startTimes[5])

        val secondRun = availabilityWindow.randomSessionTimes(6, DateTimePeriod(hours = 1, minutes = 15))
        assertNotNull(secondRun)
        assertEquals(6, secondRun.size)
        assertNotEquals(startTimes, secondRun)
    }
}