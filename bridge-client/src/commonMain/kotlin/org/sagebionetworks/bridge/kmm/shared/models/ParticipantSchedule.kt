/**
* Bridge Server API
* No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
*
* OpenAPI spec version: 0.23.30
* 
*
* NOTE: This class is auto generated by the swagger code generator program.
* https://github.com/swagger-api/swagger-codegen.git
* Do not edit the class manually.
*/
package org.sagebionetworks.bridge.kmm.shared.models


import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * A detailed description of when a participant should perform specific sessions as part of a study. The ParticipantSchedule is similar to the Timeline in structure, but includes the specific dates when this participant should perform each session, as well as state information about the participant’s completion of the schedule. Entries in the `schedule` collection should be ordered by `startDate` and `startTime`, and entries that are not available to the participant are not included in the collection (ie. entries that have the state `not_applicable`). However, definitions for those sessions are still included in the `sessions`, `assessments`, and `studyBursts` collections for future reference. *Persistent* time windows do not have state (they should always be available to the participant so they are never shown as finished, even if one or more adherence records have been filed for that session).  
 * @param createdOn The date and time this schedule was calculated.
 * @param dateRange
 * @param schedule 
 * @param assessments 
 * @param sessions 
 * @param studyBursts 
 * @param type ParticipantSchedule
 */
@Serializable
data class ParticipantSchedule (

    /* The date and time this schedule was calculated. */
    @SerialName("createdOn")
    val createdOn: String,

    @SerialName("dateRange")
    val dateRange: DateRange? = null,

    @SerialName("schedule")
    val schedule: List<ScheduledSession>? = null,

    @SerialName("assessments")
    val assessments: List<AssessmentInfo>? = null,

    @SerialName("sessions")
    val sessions: List<SessionInfo>? = null,

    @SerialName("studyBursts")
    val studyBursts: List<StudyBurstInfo>? = null,

    @SerialName("eventTimestamps")
    val eventTimestamps: Map<String, Instant>? = null,

    /* ParticipantSchedule */
    @SerialName("type")
    val type: String? = null

)

