/**
 * Bridge Server API
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * OpenAPI spec version: 0.21.29
 *
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */
package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Information about a session in order to render it in a UI prior to execution.
 * @param guid The unique identifier for this model.
 * @param label A required name for this session that will be shown to schedule authors, and can be used as a label for participants if no label can be found.
 * @param symbol A unicode symbol or token identifier (32 characters or less) for a compact/symbolic representation of a session in UI tools.
 * @param performanceOrder
 * @param timeWindowGuids A list of all the time window GUIDs for this session. The number of time windows in a session can be useful information for some UI grouping tasks.
 * @param minutesToComplete
 * @param notifications
 * @param type SessionInfo
 */
@Serializable
data class SessionInfo (
    /* The unique identifier for this model. */

    @SerialName("guid")
    val guid: String,

    /* A required name for this session that will be shown to schedule authors, and can be used as a label for participants if no label can be found. */
    @SerialName("label")
    val label: String,

    @SerialName("symbol")
    val symbol: kotlin.String? = null,

    @SerialName("performanceOrder")
    val performanceOrder: PerformanceOrder,
    /* A list of all the time window GUIDs for this session. The number of time windows in a session can be useful information for some UI grouping tasks. */

    @SerialName("timeWindowGuids")
    val timeWindowGuids: List<String>? = null,

    @SerialName("minutesToComplete")
    val minutesToComplete: Int? = null,

    @SerialName("notifications")
    val notifications: List<NotificationInfo>? = null,
    /* SessionInfo */

    @SerialName("type")
    val type: String? = null

)
