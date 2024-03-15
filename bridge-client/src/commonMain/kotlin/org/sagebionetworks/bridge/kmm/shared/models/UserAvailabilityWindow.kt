package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class UserAvailabilityWindow(
    /** LocalTime HH:mm format of the time the user wakes up and is available **/
    @SerialName("wake")
    var wakeTime: LocalTime = LocalTime.MIDNIGHT,
    /** LocalTime HH:mm format of the time the user goes to bed and is not available **/
    @SerialName("bed")
    var bedTime: LocalTime = LocalTime.MIDNIGHT,
) {

    companion object {

        /**
         * @param sessionsPerDay The number of sessions per day
         * @param windowDuration The duration of the session window
         * @return Whether or not this schedule can be randomized.
         */
        fun canRepeatDaily(sessionsPerDay: Int, windowDuration: DateTimePeriod) : Boolean {
            return windowDuration.totalMinutes * sessionsPerDay <= 24 * 60
        }
    }

    /**
     * Does this availability window have `zero` duration?
     */
    fun isEmpty() : Boolean {
        return wakeTime == bedTime
    }

    /**
     * Calculates the availability, or the minutes between wake and bed times
     */
    fun availabilityInMinutes(): Int {
        return wakeTime.minutesUntil(bedTime)
    }

    /**
     * Calculate random session window times.
     *
     * It's not that critical that they have a pure model of randomization. It's more about
     * spreading the session windows throughout their day and trying to make the times
     * somewhat spontaneous/irregular.
     */
    fun randomSessionTimes(sessionsPerDay: Int, windowDuration: DateTimePeriod): List<LocalTime>? {
        return if (canRepeatDaily(sessionsPerDay, windowDuration)) {
            evenSpacedSessionTimes(sessionsPerDay, windowDuration).randomize()
        } else null
    }

    /**
     * Calculate evenly spaced session times throughout the repeat window.
     */
    internal fun evenSpacedSessionTimes(repeatTimeWindow: RepeatTimeWindow) : CalculatedScheduleWindows
        = evenSpacedSessionTimes(repeatTimeWindow.count, repeatTimeWindow.expiration)

    private fun evenSpacedSessionTimes(sessionsPerDay: Int, windowDuration: DateTimePeriod) : CalculatedScheduleWindows {
        val totalAvailabilityInMinutes = availabilityInMinutes()
        val desiredSessionWindow = totalAvailabilityInMinutes / sessionsPerDay
        val minimumSessionWindow = windowDuration.totalMinutes
        // if the availability is less than the required availability then use the minimumSessionWindow
        val actualSessionWindow = max(desiredSessionWindow, minimumSessionWindow)
        val spacing = actualSessionWindow - minimumSessionWindow
        val times = mutableListOf<LocalTime>()
        for(ii in 0 until sessionsPerDay) {
            // Each session window is banded to be within an equal spread of times.
            val minutesFromWake = (actualSessionWindow * ii)
            times.add(wakeTime.plusMinutes(minutesFromWake))
        }
        return CalculatedScheduleWindows(spacing, times)
    }
}

/**
 * Calculates the minutes between two local times in a way that supports
 * an overnight availability calculation.
 * @param endTime The end time to an availability window.
 * @return The minutes between two local times.
 */
internal fun LocalTime.minutesUntil(endTime: LocalTime): Int {
    return if (this > endTime) {
        this.minutesUntilEndOfDay() + endTime.toMinuteOfDay()
    } else {
        endTime.toMinuteOfDay() - this.toMinuteOfDay()
    }
}

val LocalTime.Companion.MIDNIGHT : LocalTime
    get() = LocalTime(0, 0)

internal val DateTimePeriod.totalMinutes
    get() = hours * 60 + minutes

internal fun LocalTime.toMinuteOfDay() : Int {
    return this.hour * 60 + this.minute
}

internal fun LocalTime.minutesUntilEndOfDay() : Int {
    return 24 * 60 - this.toMinuteOfDay()
}

internal fun LocalTime.plusMinutes(minutes: Int) : LocalTime {
    val newTimeInMinutes = this.toMinuteOfDay() + minutes
    val hour = newTimeInMinutes / 60
    val minute = newTimeInMinutes - hour * 60
    // The "hour" will be greater than 24 if the minutes added crosses midnight.
    return LocalTime(hour % 24, minute)
}
