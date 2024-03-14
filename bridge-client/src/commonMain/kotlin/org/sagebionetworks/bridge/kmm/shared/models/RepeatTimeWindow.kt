package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.datetime.DateTimePeriod
import kotlinx.serialization.Serializable

@Serializable
data class RepeatTimeWindow(
    /** The default availability window **/
    val availabilityWindow: UserAvailabilityWindow = UserAvailabilityWindow(),
    /** The amount of time before a time window expires.  **/
    val expiration: DateTimePeriod = DateTimePeriod(),
    /** The number of time windows in a day. **/
    val count: Int = 0,
) {
    /**
     * Is this repeat time window valid for setting up a daily repeating schedule?
     */
    fun isValid() : Boolean {
        return count > 0 &&
                expiration.totalMinutes > 0 &&
                !availabilityWindow.isEmpty() &&
                UserAvailabilityWindow.canRepeatDaily(count, expiration)
    }

    fun ifValid() : RepeatTimeWindow? {
        return if (isValid()) this else null
    }
}
