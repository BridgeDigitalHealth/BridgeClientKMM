package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.datetime.DateTimePeriod
import kotlinx.serialization.Serializable

/**
 * A configurable value for setting a participant's availability.
 */
@Serializable
class UserAvailabilityConfig (
    /* The minimum allowed availability duration */
    val minimumDuration: DateTimePeriod,
    /* The maximum allowed availability duration */
    val maximumDuration: DateTimePeriod,
)