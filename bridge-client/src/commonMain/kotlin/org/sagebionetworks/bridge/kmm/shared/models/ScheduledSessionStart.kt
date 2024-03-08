package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledSessionStart(
    /** The instanceGuid of a ScheduledSession object */
    val guid: String,
    /** The LocalTime start time of the session, in format "HH:mm" */
    val start: LocalTime
)
