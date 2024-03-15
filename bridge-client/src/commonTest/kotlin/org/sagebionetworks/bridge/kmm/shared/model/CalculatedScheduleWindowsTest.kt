package org.sagebionetworks.bridge.kmm.shared.model

import kotlinx.datetime.LocalTime
import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.models.CalculatedScheduleWindows
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalStdlibApi::class)
class CalculatedScheduleWindowsTest : BaseTest() {

    @Test
    fun testRandomize() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val randomizedTimes = calculatedScheduleWindows.randomize()
        assertEquals(randomizedTimes.size, 4)
        assertContains(LocalTime(9,0)..<LocalTime(10,30), randomizedTimes[0])
        assertContains(LocalTime(12,0)..<LocalTime(13,30), randomizedTimes[1])
        assertContains(LocalTime(15,0)..<LocalTime(16,30), randomizedTimes[2])
        assertContains(LocalTime(18,0)..<LocalTime(19,30), randomizedTimes[3])
    }

    @Test
    fun testRandomize_AcrossMidnight() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 30,
            startTimes = listOf(
                LocalTime(23, 0),   // 11:00 PM to 11:30 PM
                LocalTime(2, 0),    // 2:00 AM to 2:30 AM
                LocalTime(5, 0),    // 5:00 AM to 5:30 AM
                LocalTime(8, 0)     // 8:00 AM to 8:30 AM
            )
        )
        val randomizedTimes = calculatedScheduleWindows.randomize()
        assertEquals(randomizedTimes.size, 4)
        assertContains(LocalTime(2,0)..<LocalTime(2,30), randomizedTimes[0])
        assertContains(LocalTime(5,0)..<LocalTime(5,30), randomizedTimes[1])
        assertContains(LocalTime(8,0)..<LocalTime(8,30), randomizedTimes[2])
        assertContains(LocalTime(23,0)..<LocalTime(23,30), randomizedTimes[3])
    }

    @Test
    fun testIfValidElseRandomize_Valid() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val previousTimes = listOf(
            LocalTime(9, 12),   // valid
            LocalTime(13, 15),  // valid
            LocalTime(15, 25),  // invalid
            LocalTime(19, 2)    // invalid
        )
        val randomizedTimes = calculatedScheduleWindows.ifValidElseRandomize(previousTimes)
        assertEquals(previousTimes, randomizedTimes)
    }

    @Test
    fun testIfValidElseRandomize_NotValid() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val previousTimes = listOf(
            LocalTime(9, 12),   // valid
            LocalTime(13, 15),  // valid
            LocalTime(18, 25),  // invalid
            LocalTime(21, 2)    // invalid
        )
        val randomizedTimes = calculatedScheduleWindows.ifValidElseRandomize(previousTimes)
        assertEquals(randomizedTimes.size, 4)
        assertNotEquals(previousTimes, randomizedTimes)
        assertContains(LocalTime(9,0)..<LocalTime(10,30), randomizedTimes[0])
        assertContains(LocalTime(12,0)..<LocalTime(13,30), randomizedTimes[1])
        assertContains(LocalTime(15,0)..<LocalTime(16,30), randomizedTimes[2])
        assertContains(LocalTime(18,0)..<LocalTime(19,30), randomizedTimes[3])
    }

    @Test
    fun testIfValidElseRandomize_Valid_AcrossMidnight_StartAfter() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(23, 0),   // 11:00 PM to 12:30 AM
                LocalTime(2, 0),    // 2:00 AM to 3:30 AM
                LocalTime(5, 0),    // 5:00 AM to 6:30 AM
                LocalTime(8, 0)     // 8:00 AM to 9:30 AM
            )
        )
        val previousTimes = listOf(
            LocalTime(0, 12),
            LocalTime(2, 15),
            LocalTime(6, 25),
            LocalTime(8, 32)
        )

        val isTimesValid = calculatedScheduleWindows.areTimesValid(previousTimes)
        assertTrue(isTimesValid)

        val randomizedTimes = calculatedScheduleWindows.ifValidElseRandomize(previousTimes)
        assertEquals(previousTimes, randomizedTimes)
    }

    @Test
    fun testIfValidElseRandomize_Valid_AcrossMidnight_StartBefore() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(23, 0),   // 11:00 PM to 12:30 AM
                LocalTime(2, 0),    // 2:00 AM to 3:30 AM
                LocalTime(5, 0),    // 5:00 AM to 6:30 AM
                LocalTime(8, 0)     // 8:00 AM to 9:30 AM
            )
        )
        val previousTimes = listOf(
            LocalTime(2, 15),
            LocalTime(6, 25),
            LocalTime(8, 32),
            LocalTime(23, 45)
        )

        val isTimesValid = calculatedScheduleWindows.areTimesValid(previousTimes)
        assertTrue(isTimesValid)

        val randomizedTimes = calculatedScheduleWindows.ifValidElseRandomize(previousTimes)
        assertEquals(previousTimes, randomizedTimes)
    }
}