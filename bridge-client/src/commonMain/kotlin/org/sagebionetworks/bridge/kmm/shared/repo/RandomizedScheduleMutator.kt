package org.sagebionetworks.bridge.kmm.shared.repo

import co.touchlab.kermit.Logger
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.EncodeDefault
import org.sagebionetworks.bridge.kmm.shared.models.CalculatedScheduleWindows
import org.sagebionetworks.bridge.kmm.shared.models.ParticipantSchedule
import org.sagebionetworks.bridge.kmm.shared.models.RepeatTimeWindow
import org.sagebionetworks.bridge.kmm.shared.models.ScheduleConfig
import org.sagebionetworks.bridge.kmm.shared.models.ScheduledSession
import org.sagebionetworks.bridge.kmm.shared.models.ScheduledSessionStart
import org.sagebionetworks.bridge.kmm.shared.models.SessionInfo
import org.sagebionetworks.bridge.kmm.shared.models.SessionScheduleType
import org.sagebionetworks.bridge.kmm.shared.models.UserAvailabilityWindow
import org.sagebionetworks.bridge.kmm.shared.models.UserScheduleData
import org.sagebionetworks.bridge.kmm.shared.models.UserSessionInfo
import org.sagebionetworks.bridge.kmm.shared.models.minutesUntil
import org.sagebionetworks.bridge.kmm.shared.models.plusMinutes
import org.sagebionetworks.bridge.kmm.shared.models.totalMinutes

internal class RandomizedScheduleMutator(
    private val authenticationRepo: AuthenticationRepository,
    private val participantRepo: ParticipantRepo,
    private val appConfigRepo: AppConfigRepo,
    private val studyRepo: StudyRepo,
) {

    // TODO: syoung 03/13/2024 Consider using cached values for the AppConfig and Study.
    //  I don't know if these can be assumed to have already been fetched on startup.

    /**
     * Before the scheduled sessions are saved to the database,
     * change their startTimes to one that matches our custom startTimes
     * that were calculated based on a user's availability window.
     * @param participantSchedule for the user that we can mutate
     * @return a participant's schedule with whatever changes we would like to do
     */
    suspend fun mutateParticipantSchedule(
        participantSchedule: ParticipantSchedule,
        nowDateTime: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())   // allow setting to make unit testing cleaner
    ) : ParticipantSchedule {

        // Get the user session info. Exit early if we are attempting to mutate a schedule and the
        // participant is not signed in.
        val userSessionInfo = authenticationRepo.session() ?: run {
            Logger.e("Participant schedule set before user is authenticated")
            return participantSchedule
        }

        // Get the studyId for the study to use.
        val studyId = userSessionInfo.studyIds.firstOrNull() ?: run {
            Logger.e("Participant schedule set without a study id")
            return participantSchedule
        }

        // Get the schedule config to use for this app - this may change based on whether or not the app
        // is designed to use the same schedule configuration across all studies within the app.
        val scheduleConfig = (appConfigRepo.getScheduleConfig() ?: studyRepo.getScheduleConfig(studyId)) ?: run {
            Logger.w("Attempting to mutate a participant schedule without a schedule config")
            return participantSchedule
        }

        // Check that the sessions is non-null.
        val sessions = participantSchedule.sessions ?: run {
            Logger.e("Mutating a participant schedule with no sessions")
            return participantSchedule
        }

        // Map the session guid to the scheduled sessions per day.
        val schedule = participantSchedule.schedule ?: run {
            Logger.e("Mutating a participant schedule with no scheduled sessions")
            return participantSchedule
        }

        // Get the existing info stored on the user session info.
        val initialUserScheduleData = userSessionInfo.userScheduleData

        val newStartTimes = mutateAllScheduledSessionStarts(
            sessions,
            schedule,
            initialUserScheduleData,
            scheduleConfig,
            nowDateTime
        )

        // Now that we've iterated through the full schedule and randomized start times (if needed)
        // we may need to update the participant record with the new times.
        if (initialUserScheduleData?.sessionStartTimes != newStartTimes) {
            updateParticipantRecord(userSessionInfo, newStartTimes)
        }

        // Finally, return the participant schedule.
        // Note: All objects in Kotlin are actually pointers so even though the participant schedule
        // is itself immutable, the objects within it may have been mutated by this function. syoung 03/13/2024
        return participantSchedule
    }

    private fun updateParticipantRecord(
        userSessionInfo: UserSessionInfo,
        newStartTimes: List<ScheduledSessionStart>?
    ) {
        val record = ParticipantRepo.UpdateParticipantRecord.getUpdateParticipantRecord(userSessionInfo)
        val userScheduleData = record.userScheduleData ?: UserScheduleData()
        userScheduleData.sessionStartTimes = newStartTimes
        record.userScheduleData = userScheduleData
        participantRepo.updateParticipant(record)
    }

    companion object {

        private fun listFromStartTimeMap(startTimeMap: Map<String, LocalTime>) : List<ScheduledSessionStart>? {
            return if (startTimeMap.isEmpty()) null else startTimeMap.map { ScheduledSessionStart(it.key, it.value) }
        }

        internal fun mutateAllScheduledSessionStarts(
            sessions: List<SessionInfo>,
            schedule: List<ScheduledSession>,
            initialUserScheduleData: UserScheduleData?,
            scheduleConfig: ScheduleConfig,
            nowDateTime: LocalDateTime
        ): List<ScheduledSessionStart>? {

            // Get the schedule map, start times, and the availability window to use.
            val scheduleMap = schedule.scheduleGroupedByDay()
            val availabilityWindow =
                initialUserScheduleData?.availability ?: scheduleConfig.defaultAvailabilityWindow
            val startTimeMap = initialUserScheduleData?.sessionStartTimes?.associate {
                it.guid to it.start
            }?.toMutableMap() ?: mutableMapOf()

            // Loop through the sessions and mutate as needed.
            sessions.forEach sessionLoop@{ session ->
                val scheduleType = scheduleConfig.sessionScheduleType(session.guid)
                val scheduledSessions = scheduleMap[session.guid] ?: return@sessionLoop
                mutateSessionStartTimes(
                    scheduleType,
                    scheduledSessions,
                    session,
                    availabilityWindow,
                    startTimeMap,
                    nowDateTime
                )
            } // end sessionLoop

            return listFromStartTimeMap(startTimeMap)
        }

        internal fun mutateSessionStartTimes(
            scheduleType: SessionScheduleType,
            scheduledSessions: List<ScheduleDayTuple>,
            session: SessionInfo,
            availabilityWindow: UserAvailabilityWindow?,
            startTimeMap: MutableMap<String, LocalTime>,
            nowDateTime: LocalDateTime
        ) {
            // Exit early if this is a fixed schedule.
            if (scheduleType == SessionScheduleType.FIXED) return

            // Look for a repeat time window.
            val repeatTimeWindow =
                session.repeatTimeWindow?.ifValid() ?: scheduledSessions.getRepeatTimeWindow()

            // If the repeat time window is not valid then exit early because we cannot calculate
            if (!repeatTimeWindow.isValid()) {
                Logger.e("Cannot calculate repeat time window for session: ${session.guid}")
                return
            }

            // Set the repeat so it will be saved on the cached version - this is used to
            // store the original time window in case the user's availability window is null
            // and we need to use the availability window calculated from the scheduled sessions.
            //
            // Note: When a researcher wants to test this with a preview user, they will need to sign out
            // (or delete the app if sign out is not supported) in order to reset this to *not* use the
            // cached repeat time window. syoung 03/13/2024
            session.repeatTimeWindow = repeatTimeWindow

            // Get the availability we are using to test whether or not to update the window.
            val availability = availabilityWindow ?: repeatTimeWindow.availabilityWindow
            val evenSpacedTimes = availability.evenSpacedSessionTimes(repeatTimeWindow)

            // The schedules are grouped by day. For each day, look to see if the schedules need to
            // be updated.
            scheduledSessions.forEach { sessionDay ->
                mutateStartTimesIfNeeded(
                    evenSpacedTimes,
                    sessionDay.startDate,
                    sessionDay.scheduledSessions,
                    startTimeMap,
                    nowDateTime
                )
            }
        }

        internal fun mutateStartTimesIfNeeded(
            evenSpacedTimes: CalculatedScheduleWindows,
            startDate: LocalDate,
            scheduledSessions: List<ScheduledSession>,
            startTimeMap: MutableMap<String, LocalTime>,
            nowDateTime: LocalDateTime,
        ) {
            if (startDate <= nowDateTime.date) {
                // If the start date is today or in the past, then we need to use the start times
                // that are already set on the schedule and not randomize them.
                scheduledSessions.forEach { schedule ->
                    startTimeMap[schedule.instanceGuid]?.run {
                        schedule.startTime = this
                    }
                }

            } else {
                // Get the start times for the scheduled sessions.
                val startTimes = scheduledSessions.mapNotNull {
                    startTimeMap[it.instanceGuid]
                }.let {
                    // If we have all the times, then we can check if they are valid.
                    evenSpacedTimes.ifValidElseRandomize(it)
                }

                // Set the times on the schedule and the map of instanceGuid to startTime.
                scheduledSessions.forEachIndexed { ii, schedule ->
                    val newTime = startTimes[ii]
                    schedule.startTime = newTime
                    startTimeMap[schedule.instanceGuid] = newTime
                }
            }
        }
    }
}

internal data class ScheduleDayTuple(
    val startDate: LocalDate,
    val scheduledSessions: List<ScheduledSession>,
    val repeatTimeWindow: RepeatTimeWindow,
)

internal fun List<ScheduleDayTuple>.getRepeatTimeWindow() : RepeatTimeWindow {
    // Check if the calculated repeat windows are all the same and only return non-null
    // if they are AND that repeat window is valid.
    val windows = this.map{ it.repeatTimeWindow }.toHashSet()
    return if (windows.size == 1) windows.first() else RepeatTimeWindow()
}

internal fun List<ScheduledSession>.scheduleGroupedByDay() : Map<String, List<ScheduleDayTuple>> {
    return groupBy { it.refGuid }.mapValues { it.value.mapByDay() }
}

internal fun List<ScheduledSession>.mapByDay() : List<ScheduleDayTuple> {
    return groupBy { it.startDate }.map { entry ->

        // Start by sorting the scheduled sessions by start time.
        val sorted = entry.value.sortedBy { it.startTime }

        // Calculate the expiration and minutes between the first and last scheduled session.
        val expiration = sorted.map { it.expiration }.ifSame() ?: DateTimePeriod()
        val minutesBetween = sorted.zipWithNext { a, b ->
            a.startTime.minutesUntil(b.startTime)
        }.ifSame(expiration.totalMinutes) ?: 0

        // Calculate the availability window.
        val availabilityWindow = if (minutesBetween > 0) {
            val firstTime = sorted.firstOrNull()?.startTime ?: LocalTime(0,0)
            val lastTime = sorted.lastOrNull()?.startTime ?: LocalTime(0,0)
            UserAvailabilityWindow(firstTime, lastTime.plusMinutes(minutesBetween))
        } else {
            UserAvailabilityWindow()
        }

        // Return the tuple.
        ScheduleDayTuple(
            startDate = entry.key,
            scheduledSessions = sorted,
            repeatTimeWindow = RepeatTimeWindow(
                availabilityWindow = availabilityWindow,
                expiration = expiration,
                count = sorted.size
            )
        )
    }
}

internal fun <T> List<T>.ifSame(valueIfEmpty: T? = null) : T? {
    val set = toSet()
    return when(set.size) {
        0 -> valueIfEmpty
        1 -> set.first()
        else -> null
    }
}


