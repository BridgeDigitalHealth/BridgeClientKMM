package org.sagebionetworks.bridge.kmm.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionScheduleType {

    @SerialName("fixed")
    FIXED,

    @SerialName("random")
    RANDOM,

    // TODO: syoung 03/07/2024 Implement support for fixed relative time windows.
//    @SerialName("fixed_relative")
//    FIXED_RELATIVE,
}