package org.sagebionetworks.bridge.kmm.shared.models

import co.touchlab.kermit.Logger
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.math.max

@Serializable
data class UserAvailabilityWindow(
    /** LocalTime HH:mm format of the time the user wakes up and is available **/
    @SerialName("wake")
    var wakeTime: LocalTime,
    /** LocalTime HH:mm format of the time the user goes to bed and is not available **/
    @SerialName("bed")
    var bedTime: LocalTime,
) {

    companion object {

        /**
         * @param sessionsPerDay The number of sessions per day
         * @param windowDuration The duration of the session window
         * @return Whether or not this schedule can be randomized.
         */
        fun canRandomize(sessionsPerDay: Int, windowDuration: DateTimePeriod) : Boolean {
            return windowDuration.totalMinutes * sessionsPerDay <= 24 * 60
        }
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

        // Exit early if attempting to randomize the times for a window duration that is
        // more than 24 hours total.
        // Note: Because uncaught exceptions will crash the app, and there's no way in Kotlin to
        // specific that this function **could** throw an error, just log the failure and use
        // null to indicate that randomization failed. syoung 03/12/2024
        if (!canRandomize(sessionsPerDay, windowDuration)) {
            Logger.e("Attempting to randomize session start times where the total duration of the windows is more than 24 hours.")
            return null
        }

        val totalAvailabilityInMinutes = availabilityInMinutes()
        val desiredSessionWindow = totalAvailabilityInMinutes / sessionsPerDay
        val minimumSessionWindow = windowDuration.totalMinutes
        // if the availability is less that the required availability then use the minimumSessionWindow
        val actualSessionWindow = max(desiredSessionWindow, minimumSessionWindow)
        val spacing = actualSessionWindow - minimumSessionWindow
        val randomTimes = mutableListOf<LocalTime>()
        for(ii in 0 until sessionsPerDay) {
            // fixes issues where random.nextInt(0) throws exception
            val randomOffset = if (spacing <= 0) 0 else Random.nextInt(spacing)
            // Each session window is banded to be within an equal spread of times.
            val minutesFromWake = (actualSessionWindow * ii) + randomOffset
            randomTimes.add(wakeTime.plusMinutes(minutesFromWake))
        }

        // Sorting the random times will allow for the days sessions to always
        // be ascending in time.  This will fix any overnight availability.
        // As outlined in https://sagebionetworks.jira.com/browse/DIAN-425
        return randomTimes.sorted()
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
        this.minutesUntilEndOfDay() + endTime.toMinuteInDay()
    } else {
        endTime.toMinuteInDay() - this.toMinuteInDay()
    }
}

internal val DateTimePeriod.totalMinutes
    get() = hours * 60 + minutes

internal fun LocalTime.toMinuteInDay() : Int {
    return this.hour * 60 + this.minute
}

internal fun LocalTime.minutesUntilEndOfDay() : Int {
    return 24 * 60 - this.toMinuteInDay()
}

internal fun LocalTime.plusMinutes(minutes: Int) : LocalTime {
    val newTimeInMinutes = this.toMinuteInDay() + minutes
    val hour = newTimeInMinutes / 60
    val minute = newTimeInMinutes - hour * 60
    // The "hour" will be greater than 24 if the minutes added crosses midnight.
    return LocalTime(hour % 24, minute)
}
