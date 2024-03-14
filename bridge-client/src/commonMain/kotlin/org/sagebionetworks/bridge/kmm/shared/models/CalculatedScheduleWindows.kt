package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.datetime.LocalTime
import kotlin.random.Random

/**
 * Internal data class to hold the calculated evenly spaced schedule windows.
 */
internal data class CalculatedScheduleWindows(
    val spacing: Int,
    val startTimes: List<LocalTime>,
) {
    fun randomize() : List<LocalTime> {
        return startTimes.map {
            // fixes issues where random.nextInt(0) throws exception
            val randomOffset = if (spacing <= 0) 0 else Random.nextInt(spacing)
            it.plusMinutes(randomOffset)
        // Sorting the random times will allow for the days sessions to always
        // be ascending in time.  This will fix any overnight availability.
        // As outlined in https://sagebionetworks.jira.com/browse/DIAN-425
        }.sorted()
    }

    fun ifValidElseRandomize(previousTimes: List<LocalTime>) : List<LocalTime> {
        return if (areTimesValid(previousTimes)) {
            previousTimes
        } else {
            randomize()
        }
    }

    fun areTimesValid(previousTimes: List<LocalTime>) : Boolean {
        // If the number of previous times does not match the number of start times, then the times are not valid
        if (previousTimes.size != startTimes.size) return false
        // Create a set of time windows
        var windows = startTimes.map { Pair(it, it.plusMinutes(spacing)) }.toMutableSet()
        // Remove any previous times from the set of windows
        previousTimes.forEach { prev ->
            windows.firstOrNull { it.contains(prev) }?.run {
                windows -= this
            }
        }
        // If there are no windows left, then the times are valid
        return windows.size == 0
    }
}

internal fun Pair<LocalTime, LocalTime>.contains(time: LocalTime) : Boolean {
    return if (first < second) {
        time in first..second
    } else {
        time >= first || time <= second
    }
}
