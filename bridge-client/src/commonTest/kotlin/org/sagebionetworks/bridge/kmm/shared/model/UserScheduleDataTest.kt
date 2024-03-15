package org.sagebionetworks.bridge.kmm.shared.model

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.models.ScheduledSessionStart
import org.sagebionetworks.bridge.kmm.shared.models.TimestampedValue
import org.sagebionetworks.bridge.kmm.shared.models.UserAvailabilityWindow
import org.sagebionetworks.bridge.kmm.shared.models.UserScheduleData
import org.sagebionetworks.bridge.kmm.shared.models.isNullOrEmpty
import org.sagebionetworks.bridge.kmm.shared.repo.ClientDataHelper
import org.sagebionetworks.bridge.kmm.shared.repo.UserClientDataJsonResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserScheduleDataTest  : BaseTest() {

    @Test
    fun testArcScheduleData_Decode() {
        val userScheduleData = ClientDataHelper.jsonCoder.decodeFromString<UserScheduleData>(UserClientDataJsonResource.exampleArcClientDataJson)
        assertFalse(userScheduleData.isNullOrEmpty())
        assertNotNull(userScheduleData)
        assertNotNull(userScheduleData.availability)
        assertNotNull(userScheduleData.sessionStartTimes)
        assertFalse(userScheduleData.isEmpty())
        assertEquals(LocalTime(10,30), userScheduleData.availability!!.wakeTime)
        assertEquals(LocalTime(18,30), userScheduleData.availability!!.bedTime)
    }

    @Test
    fun testUserScheduleData_UpdateAvailability() {
        val userScheduleData = ClientDataHelper.jsonCoder.decodeFromString<UserScheduleData>(UserClientDataJsonResource.exampleArcClientDataJson)
        val newAvailabilityWindow = UserAvailabilityWindow(
            wakeTime = LocalTime(7, 30),
            bedTime = LocalTime(11, 45)
        )
        userScheduleData.availability = newAvailabilityWindow

        val updatedAvailability = userScheduleData.timestampedAvailability
        assertNotNull(updatedAvailability.timestamp)
        assertEquals(newAvailabilityWindow, updatedAvailability.value)
        assertEquals(updatedAvailability.timestamp, userScheduleData.availabilityUpdatedOn)

        val encodedObject = ClientDataHelper.jsonCoder.encodeToJsonElement(userScheduleData).jsonObject
        assertNotNull(encodedObject)
        assertNotNull(encodedObject["availability"])
        assertNotNull(encodedObject["sessionStartLocalTimes"])
        assertNotNull(encodedObject["availabilityUpdatedOn"])
        assertNull(encodedObject["startTimesCalculatedOn"])
    }

    @Test
    fun testUserScheduleData_UpdateStartTimes() {
        val userScheduleData = ClientDataHelper.jsonCoder.decodeFromString<UserScheduleData>(UserClientDataJsonResource.exampleArcClientDataJson)
        val newStartTimes = listOf(
            ScheduledSessionStart("not-real-guid", LocalTime(9,30))
        )
        userScheduleData.sessionStartTimes = newStartTimes

        val updatedStartTimes = userScheduleData.timestampedSessionStartTimes
        assertNotNull(updatedStartTimes.timestamp)
        assertEquals(newStartTimes, updatedStartTimes.value)
        assertEquals(updatedStartTimes.timestamp, userScheduleData.startTimesCalculatedOn)

        val encodedObject = ClientDataHelper.jsonCoder.encodeToJsonElement(userScheduleData).jsonObject
        assertNotNull(encodedObject)
        assertNotNull(encodedObject["availability"])
        assertNotNull(encodedObject["sessionStartLocalTimes"])
        assertNotNull(encodedObject["startTimesCalculatedOn"])
        assertNull(encodedObject["availabilityUpdatedOn"])
    }

    @Test
    fun testTimestampedValue_MostRecent() {
        val now = Clock.System.now()

        val valueA = TimestampedValue("a", null)
        val valueB = TimestampedValue("b", now.minus(20, DateTimeUnit.SECOND))
        val valueC = TimestampedValue("c", now)
        val valueD = TimestampedValue("d", null)

        assertEquals("b", valueA.mostRecent(valueB).value)
        assertEquals("b", valueB.mostRecent(valueA).value)
        assertEquals("c", valueC.mostRecent(valueB).value)
        assertEquals("c", valueB.mostRecent(valueC).value)
        assertEquals("a", valueA.mostRecent(valueD).value)
        assertEquals("d", valueD.mostRecent(valueA).value)
    }

    @Test
    fun testUserScheduleData_Union_NullToNow() {

        val now = Clock.System.now()

        val availabilityA = UserAvailabilityWindow(
            wakeTime = LocalTime(7, 30),
            bedTime = LocalTime(11, 45)
        )
        val availabilityB = UserAvailabilityWindow(
            wakeTime = LocalTime(9, 30),
            bedTime = LocalTime(10, 30)
        )
        val startTimesA = listOf(
            ScheduledSessionStart("not-real-guid", LocalTime(9,30))
        )
        val startTimesB = listOf(
            ScheduledSessionStart("not-real-guid", LocalTime(6,15))
        )

        val scheduleDataA = UserScheduleData(availabilityA, startTimesA, null, now)
        val scheduleDataB = UserScheduleData(availabilityB, startTimesB, now, null)

        val newScheduleData = UserScheduleData.union(scheduleDataA, scheduleDataB)
        assertNotNull(newScheduleData)
        assertEquals(availabilityB, newScheduleData.availability)
        assertEquals(startTimesA, newScheduleData.sessionStartTimes)
        assertEquals(now, newScheduleData.availabilityUpdatedOn)
        assertEquals(now, newScheduleData.startTimesCalculatedOn)

        val reversed = UserScheduleData.union(scheduleDataB, scheduleDataA)
        assertEquals(newScheduleData, reversed)
    }

    fun testUserScheduleData_Union_PastToNow() {

        val now = Clock.System.now()
        val beforeNow = now.minus(60, DateTimeUnit.SECOND)

        val availabilityA = UserAvailabilityWindow(
            wakeTime = LocalTime(7, 30),
            bedTime = LocalTime(11, 45)
        )
        val availabilityB = UserAvailabilityWindow(
            wakeTime = LocalTime(9, 30),
            bedTime = LocalTime(10, 30)
        )
        val startTimesA = listOf(
            ScheduledSessionStart("not-real-guid", LocalTime(9,30))
        )
        val startTimesB = listOf(
            ScheduledSessionStart("not-real-guid", LocalTime(6,15))
        )

        val scheduleDataA = UserScheduleData(availabilityA, startTimesA, beforeNow, now)
        val scheduleDataB = UserScheduleData(availabilityB, startTimesB, now, beforeNow)

        val newScheduleData = UserScheduleData.union(scheduleDataA, scheduleDataB)
        assertNotNull(newScheduleData)
        assertEquals(availabilityB, newScheduleData.availability)
        assertEquals(startTimesA, newScheduleData.sessionStartTimes)

        val reversed = UserScheduleData.union(scheduleDataB, scheduleDataA)
        assertEquals(newScheduleData, reversed)
    }

    @Test
    fun testUserScheduleData_Union_Null() {

        val availabilityA = UserAvailabilityWindow(
            wakeTime = LocalTime(7, 30),
            bedTime = LocalTime(11, 45)
        )
        val startTimesA = listOf(
            ScheduledSessionStart("not-real-guid", LocalTime(9,30))
        )

        val scheduleDataA = UserScheduleData(availabilityA, startTimesA, null, null)

        assertEquals(scheduleDataA, UserScheduleData.union(scheduleDataA, null))
        assertEquals(scheduleDataA, UserScheduleData.union(null, scheduleDataA))
    }
}