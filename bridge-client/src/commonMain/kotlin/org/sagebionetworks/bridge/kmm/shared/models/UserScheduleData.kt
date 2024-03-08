package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.sagebionetworks.bridge.kmm.shared.repo.ParticipantRepo.UpdateParticipantRecord

/**
 * Data stored on the participant record of [UserSessionInfo] and [UpdateParticipantRecord] that is
 * used to set up randomized start times.
 */
@Serializable
internal data class UserScheduleData(
    /** User availability. This is set by the participant during onboarding or through the app settings. **/
    @SerialName("availability")
    private var _availability : UserAvailabilityWindow? = null,
    /** List of light-weight objects storing the randomized start times for a participant. **/
    @SerialName("sessionStartLocalTimes")
    private var _sessionStartTimes: List<ScheduledSessionStart>? = null,
    /** When was the availability last set? **/
    var availabilityUpdatedOn: Instant? = null,
    /** When was the schedule last calculated? **/
    var startTimesCalculatedOn: Instant? = null,
) {

    var availability : UserAvailabilityWindow?
        get() = _availability
        set(newValue) {
            _availability = newValue
            availabilityUpdatedOn = Clock.System.now()
        }

    var sessionStartTimes: List<ScheduledSessionStart>?
        get() = _sessionStartTimes
        set(newValue) {
            _sessionStartTimes = newValue
            startTimesCalculatedOn = Clock.System.now()
        }

    fun isEmpty() : Boolean {
        return (_availability == null) && _sessionStartTimes.isNullOrEmpty()
    }

    fun setTimestampsIfNeeded() {
        if (_availability != null) {
            availabilityUpdatedOn = Clock.System.now()
        }
        if (_sessionStartTimes != null) {
            startTimesCalculatedOn = Clock.System.now()
        }
    }

    var timestampedAvailability : TimestampedValue<UserAvailabilityWindow>
        get() = TimestampedValue(_availability, availabilityUpdatedOn)
        private set(newValue) {
            _availability = newValue.value
            availabilityUpdatedOn = newValue.timestamp
        }

    var timestampedSessionStartTimes : TimestampedValue<List<ScheduledSessionStart>>
        get() = TimestampedValue(_sessionStartTimes, startTimesCalculatedOn)
        private set(newValue) {
            _sessionStartTimes = newValue.value
            startTimesCalculatedOn = newValue.timestamp
        }

    companion object {
        fun union(left: UserScheduleData?, right: UserScheduleData?) : UserScheduleData? {
            return if (left.isNullOrEmpty()) {
                right
            } else if (right.isNullOrEmpty()) {
                left
            } else {
                var userScheduleData = UserScheduleData()
                userScheduleData.timestampedAvailability =
                    left!!.timestampedAvailability.mostRecent(right!!.timestampedAvailability)
                userScheduleData.timestampedSessionStartTimes =
                    left!!.timestampedSessionStartTimes.mostRecent(right!!.timestampedSessionStartTimes)
                userScheduleData
            }
        }
    }

}

/**
 * A wrapper that allows a value to be timestamped so that merging can use the most recent value.
 */
@Serializable
internal data class TimestampedValue<T>(
    val value: T?,
    val timestamp: Instant? = null
) {
    fun mostRecent(other: TimestampedValue<T>) : TimestampedValue<T> {
        return if (this.timestamp.nullCompareTo(other.timestamp) >= 0) this else other
    }
}

internal inline fun UserScheduleData?.isNullOrEmpty() : Boolean {
    return this == null || this.isEmpty()
}

internal fun Instant?.nullCompareTo(other: Instant?) : Int {
    return if (other == null) 1 else this?.compareTo(other) ?: -1
}
