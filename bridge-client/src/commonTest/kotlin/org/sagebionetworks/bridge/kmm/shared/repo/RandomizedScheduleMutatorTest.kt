package org.sagebionetworks.bridge.kmm.shared.repo

import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.models.CalculatedScheduleWindows
import org.sagebionetworks.bridge.kmm.shared.models.PerformanceOrder
import org.sagebionetworks.bridge.kmm.shared.models.RepeatTimeWindow
import org.sagebionetworks.bridge.kmm.shared.models.ScheduledSession
import org.sagebionetworks.bridge.kmm.shared.models.ScheduledSessionStart
import org.sagebionetworks.bridge.kmm.shared.models.SessionInfo
import org.sagebionetworks.bridge.kmm.shared.models.SessionScheduleType
import org.sagebionetworks.bridge.kmm.shared.models.StudyScheduleConfig
import org.sagebionetworks.bridge.kmm.shared.models.UserAvailabilityConfig
import org.sagebionetworks.bridge.kmm.shared.models.UserAvailabilityWindow
import org.sagebionetworks.bridge.kmm.shared.models.UserScheduleData
import org.sagebionetworks.bridge.kmm.shared.models.plusMinutes
import org.sagebionetworks.bridge.kmm.shared.models.totalMinutes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalStdlibApi::class)
class RandomizedScheduleMutatorTest : BaseTest() {

    // MARK: - RandomizedScheduleMutator.mutateAllScheduledSessionStarts

    @Test
    fun testMutateAllScheduledSessionStarts() {
        val nowDateTime = LocalDateTime(2023, 6, 23, 9, 0)
        val startDate = LocalDate(2023, 6, 23)

        // Set up the scheduled sessions
        val startTimes = listOf(
            LocalTime(9, 0),
            LocalTime(12, 0),
            LocalTime(15, 0),
            LocalTime(18, 0)
        )
        val availabilityWindow = UserAvailabilityWindow(
            LocalTime(9, 0),
            LocalTime(21, 0)
        )
        val session = SessionInfo(
            guid = "session-guid-random",
            label = "Random",
            performanceOrder = PerformanceOrder.PARTICIPANT_CHOICE,
            repeatTimeWindow = RepeatTimeWindow(
                size = 4,
                expiration = DateTimePeriod(minutes = 90),
                availabilityWindow = availabilityWindow
            )
        )
        val sessionFixed = SessionInfo(
            guid = "session-guid-fixed",
            label = "Fixed",
            performanceOrder = PerformanceOrder.PARTICIPANT_CHOICE,
        )

        val randomizer = availabilityWindow.evenSpacedSessionTimes(session.repeatTimeWindow!!)

        // Set up the calculated schedule windows for both sessions
        val scheduledSessions = (0 until 7).map { ii ->
            val isRandom = ii < 6
            createScheduledSessions(
                refGuid = if (isRandom) session.guid else sessionFixed.guid,
                startTimes = if (isRandom) randomizer.randomize() else startTimes,
                startDate = startDate.plus(ii, DateTimeUnit.DAY),
            )
        }.flatten()

        val sessionStartTimes = scheduledSessions.mapNotNull {
            if (it.refGuid == "session-guid-random")
                ScheduledSessionStart(it.instanceGuid, it.startTime)
            else null
        }
        val updatedAvailabilityWindow = UserAvailabilityWindow(
            LocalTime(8, 0),
            LocalTime(16, 0)
        )

        val userScheduleData = UserScheduleData(
            _availability = updatedAvailabilityWindow,
            _sessionStartTimes = sessionStartTimes,
        )

        val scheduleConfig = StudyScheduleConfig(
            availabilityConfig = UserAvailabilityConfig(DateTimePeriod(hours = 8), DateTimePeriod(hours = 16)),
            scheduleTypeMap = mapOf(
                session.guid to SessionScheduleType.RANDOM,
                sessionFixed.guid to SessionScheduleType.FIXED
            )
        )

        // Run the test
        val result = RandomizedScheduleMutator.mutateAllScheduledSessionStarts(
            sessions = listOf(session, sessionFixed),
            schedule = scheduledSessions,
            initialUserScheduleData = userScheduleData,
            scheduleConfig = scheduleConfig,
            nowDateTime = nowDateTime
        )

        assertNotNull(result)

        // The fixed session should not be updated
        val fixedSessionTimes = scheduledSessions.filter {
            it.refGuid == sessionFixed.guid
        }.map { it.startTime }.sorted()
        assertEquals(startTimes, fixedSessionTimes)

        // The random session should be updated (except for today)
        val randomSessionTimes = scheduledSessions.filter {
            it.refGuid == session.guid
        }.sortedBy { it.startDateTime }.map {
            ScheduledSessionStart(it.instanceGuid, it.startTime)
        }
        assertNotNull(result)
        assertEquals(result.toSet(), randomSessionTimes.toSet())
        assertNotEquals(sessionStartTimes.toSet(), randomSessionTimes.toSet())
        assertEquals(sessionStartTimes.subList(0, 4), randomSessionTimes.subList(0, 4))
    }

    // MARK: - RandomizedScheduleMutator.mutateSessionStartTimes

    @Test
    fun testMutateSessionStartTimes() {
        val nowDateTime = LocalDateTime(2023, 6, 23, 9, 0)
        val startDate = LocalDate(2023, 6, 24)
        val startTimes = listOf(
            LocalTime(9, 0),
            LocalTime(12, 0),
            LocalTime(15, 0),
            LocalTime(18, 0)
        )
        val scheduledSessions = createScheduledSessions(
            startTimes = startTimes,
            startDate = startDate
        )
        val startTimeMap = mutableMapOf<String, LocalTime>()
        val session = SessionInfo(
            guid = "session-guid",
            label = "Session Label",
            performanceOrder = PerformanceOrder.PARTICIPANT_CHOICE,
        )
        val expectedRepeatTimeWindow = RepeatTimeWindow(
            size = 4,
            expiration = DateTimePeriod(minutes = 90),
            availabilityWindow = UserAvailabilityWindow(
                LocalTime(9, 0),
                LocalTime(21, 0)
            )
        )

        // Run the test
        RandomizedScheduleMutator.mutateSessionStartTimes(
            scheduleType = SessionScheduleType.RANDOM,
            scheduledSessions = scheduledSessions.mapByDay(),
            session = session,
            availabilityWindow = null,
            startTimeMap = startTimeMap,
            nowDateTime = nowDateTime
        )

        // The calculated repeat time window should be set on the session
        assertEquals(expectedRepeatTimeWindow, session.repeatTimeWindow)

        // For each session, the start time should be in the map, the start time on the schedule
        // should be updated, and the time should be within the calculated window.
        val actualTimes = scheduledSessions.mapIndexed { index, scheduledSession ->
            val expectedTime = startTimes[index]
            val mappedTime = startTimeMap[scheduledSession.instanceGuid]
            assertNotNull(mappedTime)
            assertEquals(scheduledSession.startTime, mappedTime)
            assertContains(expectedTime..<expectedTime.plusMinutes(90), mappedTime)
            mappedTime
        }

        // The times should be randomized
        assertNotEquals(startTimes, actualTimes)
    }

    // MARK: - List<ScheduledSession>.mapByDay()

    @Test
    fun testMapByDay_ThreeDay_ValidRepeat() {
        val startDate = LocalDate(2023, 6, 23)
        val scheduledSessions = createScheduledSessions(
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            ),
            startDate = startDate,
            numberOfDays = 3
        )

        // The assumption is that using the existing website UI/UX, study time windows will be set
        // up to have an equal spacing and duration. Therefore, the repeat time window needs to be
        // calculated using spacing (between each window) **and** duration (of each window).
        val expectedAvailabilityWindow = UserAvailabilityWindow(
            LocalTime(9, 0),
            LocalTime(21, 0)
        )

        val grouped = scheduledSessions.mapByDay()
        assertEquals(3, grouped.size)

        grouped.forEachIndexed { index, day ->
            val expectedDate = startDate.plus(index, DateTimeUnit.DAY)
            assertEquals(expectedDate, day.startDate)
            assertEquals(scheduledSessions.filter { it.startDate == expectedDate }, day.scheduledSessions)
            assertEquals(4, day.repeatTimeWindow.size)
            assertEquals(90, day.repeatTimeWindow.expiration.totalMinutes)
            assertEquals(expectedAvailabilityWindow, day.repeatTimeWindow.availabilityWindow)
        }
    }

    @Test
    fun testMapByDay_DifferentExpirationValues() {
        val startDate = LocalDate(2023, 6, 23)
        val scheduledSessions = createScheduledSessions(
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            ),
            startDate = startDate
        ).mapIndexed { index, scheduledSession ->
            scheduledSession.copy(expiration = DateTimePeriod(minutes = 30 * (index + 1)))
        }

        val grouped = scheduledSessions.mapByDay()
        assertEquals(1, grouped.size)

        val first = grouped.first()
        assertEquals(startDate, first.startDate)
        assertEquals(scheduledSessions, first.scheduledSessions)

        // The repeat time window should be invalid because the expiration times are different
        assertFalse(first.repeatTimeWindow.isValid())
    }

    @Test
    fun testMapByDay_DifferentSpacing() {
        val startDate = LocalDate(2023, 6, 23)
        val scheduledSessions = createScheduledSessions(
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(14, 0),
                LocalTime(18, 0)
            ),
            startDate = startDate
        )

        val grouped = scheduledSessions.mapByDay()
        assertEquals(1, grouped.size)

        val first = grouped.first()
        assertEquals(startDate, first.startDate)
        assertEquals(scheduledSessions, first.scheduledSessions)

        // The repeat time window should be invalid because the spacing is different
        assertFalse(first.repeatTimeWindow.isValid())
    }

    @Test
    fun testMapByDay_SessionGreaterThan24Hours() {
        val startDate = LocalDate(2023, 6, 23)
        val scheduledSessions = createScheduledSessions(
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            ),
            startDate = startDate,
            expirationMinutes = 12 * 60
        )

        val grouped = scheduledSessions.mapByDay()
        assertEquals(1, grouped.size)

        val first = grouped.first()
        assertEquals(startDate, first.startDate)
        assertEquals(scheduledSessions, first.scheduledSessions)

        // The repeat time window should be invalid because the duration is greater
        // than 24 hours for the day.
        assertFalse(first.repeatTimeWindow.isValid())
    }

    fun testMapByDay_Empty() {
        val scheduledSessions = listOf<ScheduledSession>()
        val grouped = scheduledSessions.mapByDay()
        assertEquals(0, grouped.size)
    }

    // MARK: - mutateStartTimesIfNeeded

    @Test
    fun testMutateStartTimesIfNeeded_NewParticipant() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val nowDateTime = LocalDateTime(2023, 6, 23, 9, 0)
        val startDate = LocalDate(2023, 6, 24)

        // If there isn't a previous availability window, then the map of start times will be empty
        // and the scheduled sessions will be created with the calculated start times.
        val startTimeMap = mutableMapOf<String, LocalTime>()
        val scheduledSessions = createScheduledSessions(
            startTimes = calculatedScheduleWindows.startTimes,
            startDate = startDate
        )

        // Run the test
        RandomizedScheduleMutator.mutateStartTimesIfNeeded(
            scheduledSessions = scheduledSessions,
            startDate = startDate,
            evenSpacedTimes = calculatedScheduleWindows,
            startTimeMap = startTimeMap,
            nowDateTime = nowDateTime
        )

        // For each session, the start time should be in the map, the start time on the schedule
        // should be updated, and the time should be within the calculated window.
        val actualTimes = scheduledSessions.mapIndexed { index, session ->
            val expectedTime = calculatedScheduleWindows.startTimes[index]
            val mappedTime = startTimeMap[session.instanceGuid]
            assertNotNull(mappedTime)
            assertEquals(session.startTime, mappedTime)
            assertContains(expectedTime..<expectedTime.plusMinutes(90), mappedTime)
            mappedTime
        }

        // The times should be randomized
        assertNotEquals(calculatedScheduleWindows.startTimes, actualTimes)
    }

    @Test
    fun testMutateStartTimesIfNeeded_Reinstall() {
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val nowDateTime = LocalDateTime(2023, 6, 23, 9, 0)
        val startDate = LocalDate(2023, 6, 24)

        // For a reinstall, the start times will begin with the calculated start times
        val scheduledSessions = createScheduledSessions(
            startTimes = calculatedScheduleWindows.startTimes,
            startDate = startDate
        )

        // And the map of start times will be populated with random start times
        val startTimeMap = calculatedScheduleWindows.randomize().withIndex().associate { (index, time) ->
            scheduledSessions[index].instanceGuid to time
        }.toMutableMap()
        // Add a key to ignore
        startTimeMap["ignore"] = LocalTime(9, 0)
        val expectedMap = startTimeMap.toMap()

        // Run the test
        RandomizedScheduleMutator.mutateStartTimesIfNeeded(
            scheduledSessions = scheduledSessions,
            startDate = startDate,
            evenSpacedTimes = calculatedScheduleWindows,
            startTimeMap = startTimeMap,
            nowDateTime = nowDateTime
        )

        // The map should be unchanged
        assertEquals(expectedMap, startTimeMap)

        // For each session, the start time should be updated to the time in the map
        scheduledSessions.forEach { session ->
            val expectedTime = expectedMap[session.instanceGuid]
            assertEquals(session.startTime, expectedTime)
        }
    }

    @Test
    fun testMutateStartTimesIfNeeded_UpdatedAvailability() {
        val previous = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val nowDateTime = LocalDateTime(2023, 6, 23, 9, 0)
        val startDate = LocalDate(2023, 6, 24)  // Tomorrow

        val previousTimes = previous.randomize()

        // When the availability window is updated, the start times will begin with the previous times
        val scheduledSessions = createScheduledSessions(
            startTimes = previousTimes,
            startDate = startDate
        )

        // And the map of start times will be populated with the previous times
        val startTimeMap = previousTimes.withIndex().associate { (index, time) ->
            scheduledSessions[index].instanceGuid to time
        }.toMutableMap()
        // Add a key to ignore
        startTimeMap["ignore"] = LocalTime(9, 0)

        // Set up a new availability window
        val spacing = 30
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = spacing,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(10, 0),
                LocalTime(12, 0),
                LocalTime(14, 0)
            )
        )

        // Run the test
        RandomizedScheduleMutator.mutateStartTimesIfNeeded(
            scheduledSessions = scheduledSessions,
            startDate = startDate,
            evenSpacedTimes = calculatedScheduleWindows,
            startTimeMap = startTimeMap,
            nowDateTime = nowDateTime
        )

        // For each session, the start time should be in the map, the start time on the schedule
        // should be updated, and the time should be within the calculated window.
        val actualTimes = scheduledSessions.mapIndexed { index, session ->
            val expectedTime = calculatedScheduleWindows.startTimes[index]
            val mappedTime = startTimeMap[session.instanceGuid]
            assertNotNull(mappedTime)
            assertEquals(session.startTime, mappedTime)
            assertContains(expectedTime..<expectedTime.plusMinutes(spacing), mappedTime)
            mappedTime
        }

        // The times should be randomized
        assertNotEquals(previousTimes, actualTimes)
    }

    @Test
    fun testMutateStartTimesIfNeeded_UpdatedAvailability_Today() {
        val previous = CalculatedScheduleWindows(
            spacing = 90,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(15, 0),
                LocalTime(18, 0)
            )
        )
        val nowDateTime = LocalDateTime(2023, 6, 23, 9, 0)
        val startDate = LocalDate(2023, 6, 23)  // Today

        val previousTimes = previous.randomize()

        // When the availability window is updated, the start times will begin with the previous times
        val scheduledSessions = createScheduledSessions(
            startTimes = previous.startTimes,
            startDate = startDate
        )

        // And the map of start times will be populated with the previous times
        val startTimeMap = previousTimes.withIndex().associate { (index, time) ->
            scheduledSessions[index].instanceGuid to time
        }.toMutableMap()
        // Add a key to ignore
        startTimeMap["ignore"] = LocalTime(9, 0)
        val expectedMap = startTimeMap.toMap()

        // Set up a new availability window
        val spacing = 30
        val calculatedScheduleWindows = CalculatedScheduleWindows(
            spacing = spacing,
            startTimes = listOf(
                LocalTime(9, 0),
                LocalTime(10, 0),
                LocalTime(12, 0),
                LocalTime(14, 0)
            )
        )

        // Run the test
        RandomizedScheduleMutator.mutateStartTimesIfNeeded(
            scheduledSessions = scheduledSessions,
            startDate = startDate,
            evenSpacedTimes = calculatedScheduleWindows,
            startTimeMap = startTimeMap,
            nowDateTime = nowDateTime
        )

        // The map should be unchanged
        assertEquals(expectedMap, startTimeMap)

        // For each session, the start time should be unchanged
        scheduledSessions.forEach { session ->
            val expectedTime = expectedMap[session.instanceGuid]
            assertEquals(session.startTime, expectedTime)
        }
    }

    // MARK: - helper methods

    private fun createScheduledSessions(
        refGuid: String = "session-guid",
        startTimes: List<LocalTime>,
        startDate: LocalDate,
        expirationMinutes: Int = 90,
        numberOfDays: Int = 1
    ): List<ScheduledSession> {
        return (0 until numberOfDays).map { ii ->
            startTimes.mapIndexed { index, time ->
                ScheduledSession(
                    refGuid = refGuid,
                    instanceGuid = "${refGuid}_${startDate}_${index}",
                    startTime = time,
                    expiration = DateTimePeriod(minutes = expirationMinutes),
                    startDate = startDate.plus(ii, DateTimeUnit.DAY),
                    endDate = startDate,
                    assessments = emptyList(),
                )
            }
        }.flatten()
    }
}