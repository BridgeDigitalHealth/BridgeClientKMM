package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The config used to support handling randomization of the assessments and time windows as well as
 * relative time windows that use the participant's availability to calculate the time windows.
 * This can be defined on either the [AppConfig.clientData] or the [Study.clientData].
 */
interface ScheduleConfig {
    /**
     * If set, this is used to allow the participant to set their availability and allow sessions
     * to be scheduled relative to the participant's preferred schedule rather than using fixed
     * time windows.
     */
    val availabilityConfig: UserAvailabilityConfig?

    /**
     * The default availability window to use if the participant's availability is not set.
     */
    val defaultAvailabilityWindow : UserAvailabilityWindow?

    /**
     * The session schedule type for a given session.
     */
    fun sessionScheduleType(refGuid: String) : SessionScheduleType

    /**
     * Should the schedule ignore the [SessionInfo.performanceOrder] flag and always randomize
     * the assessments in all sessions?
     */
    val alwaysRandomizedAssessments: Boolean
        get() = false

    /**
     * The subset of assessment ids within the list of [ScheduledSession.assessments] that should
     * be displayed in random order. If null, then all assessments are randomized.
     */
    fun randomizedAssessments(refGuid: String) : List<String>?
}


@Serializable
data class AppScheduleConfig(
    override val availabilityConfig: UserAvailabilityConfig? = null,
    override val alwaysRandomizedAssessments: Boolean = false,
    override val defaultAvailabilityWindow: UserAvailabilityWindow? = null,

    /**
     * A list of assessment identifiers that should always have their order randomized.
     */
    @SerialName("randomizedAssessments")
    val randomizedAssessmentsList: List<String>? = null,

    /**
     * The session schedule type to apply to all sessions.
     */
    val scheduleType: SessionScheduleType = SessionScheduleType.FIXED

) : ScheduleConfig {

    override fun randomizedAssessments(refGuid: String): List<String>? {
        return randomizedAssessmentsList
    }

    override fun sessionScheduleType(refGuid: String): SessionScheduleType {
        return scheduleType
    }
}

@Serializable
data class StudyScheduleConfig(
    override val availabilityConfig: UserAvailabilityConfig? = null,
    override val defaultAvailabilityWindow: UserAvailabilityWindow? = null,

    /**
     * A map of the session guid to the schedule type.
     */
    val scheduleTypeMap: Map<String, SessionScheduleType>? = null,

    /**
     * A list of the assessment identifiers for the assessments within a given [ScheduledSession]
     * that should be randomized for a given session where the key is the [ScheduledSession.refGuid].
     */
    val randomizedAssessmentsMap: Map<String, List<String>>? = null,

) : ScheduleConfig {

    override fun sessionScheduleType(refGuid: String): SessionScheduleType {
        return scheduleTypeMap?.get(refGuid) ?: SessionScheduleType.FIXED
    }

    override fun randomizedAssessments(refGuid: String): List<String>? {
        return randomizedAssessmentsMap?.get(refGuid)
    }
}