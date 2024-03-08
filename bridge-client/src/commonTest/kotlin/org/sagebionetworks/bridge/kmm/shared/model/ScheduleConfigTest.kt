package org.sagebionetworks.bridge.kmm.shared.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.models.AppConfig
import org.sagebionetworks.bridge.kmm.shared.models.SessionScheduleType
import org.sagebionetworks.bridge.kmm.shared.models.Study
import org.sagebionetworks.bridge.kmm.shared.models.StudyPhase
import org.sagebionetworks.bridge.kmm.shared.models.scheduleConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleConfigTest : BaseTest() {

    @Test
    fun testAppScheduleConfig_NoValuesSet() {
        val appConfig = AppConfig(
            label = "testApp",
            clientData = JsonObject(mapOf("foo" to JsonPrimitive("baloo")))
        )

        val scheduleConfig = appConfig.scheduleConfig
        assertNull(scheduleConfig)
    }

    @Test
    fun testAppScheduleConfig_ArcSetup() {
        val inputString = """
            {
                "scheduleConfig": {
                    "availabilityConfig": {
                        "minimumDuration": "PT8H",
                        "maximumDuration": "PT16H"
                    },
                    "scheduleType": "random",
                    "alwaysRandomizedAssessments": true,
                    "randomizedAssessments": [
                        "grid_test",
                        "price_test",
                        "symbol_test"
                    ]
                }
            }
            """

        val appConfig = AppConfig(
            label = "testApp",
            clientData = Json.decodeFromString(inputString)
        )
        assertNotNull(appConfig.clientData)
        assertTrue(appConfig.clientData is JsonObject)

        val scheduleConfig = appConfig.scheduleConfig
        assertNotNull(scheduleConfig)
        assertNotNull(scheduleConfig.availabilityConfig)
        assertEquals(8, scheduleConfig.availabilityConfig!!.minimumDuration.hours)
        assertEquals(16, scheduleConfig.availabilityConfig!!.maximumDuration.hours)
        val expectedIds = listOf("grid_test","price_test","symbol_test")
        assertEquals(expectedIds, scheduleConfig.randomizedAssessments("any-guid"))
        assertEquals(true, scheduleConfig.alwaysRandomizedAssessments)
        assertEquals(SessionScheduleType.RANDOM, scheduleConfig.sessionScheduleType("any-guid"))
    }

    @Test
    fun testStudyScheduleConfig_NoValuesSet() {
        val study = Study(
            identifier = "abc123",
            name = "test study",
            phase = StudyPhase.DESIGN,
            version = 1,
            clientData = JsonObject(mapOf("foo" to JsonPrimitive("baloo")))
        )

        val scheduleConfig = study.scheduleConfig
        assertNull(scheduleConfig)
    }

    @Test
    fun testStudyScheduleConfig_OpenBridgeSetup() {
        val inputString = """
            {
                "scheduleConfig": {
                    "availabilityConfig": {
                        "minimumDuration": "PT8H",
                        "maximumDuration": "PT16H"
                    },
                    "scheduleTypeMap": {
                        "guid_123": "random",
                        "guid_abc": "fixed"
                    },
                    "randomizedAssessmentsMap": {
                        "guid_123": [ "grid_test", "price_test", "symbol_test" ]
                    }
                }
            }
            """

        val study = Study(
            identifier = "abc123",
            name = "test study",
            phase = StudyPhase.DESIGN,
            version = 1,
            clientData = Json.decodeFromString(inputString)
        )
        assertNotNull(study.clientData)
        assertTrue(study.clientData is JsonObject)

        val scheduleConfig = study.scheduleConfig
        assertNotNull(scheduleConfig)
        assertNotNull(scheduleConfig.availabilityConfig)
        assertEquals(8, scheduleConfig.availabilityConfig!!.minimumDuration.hours)
        assertEquals(16, scheduleConfig.availabilityConfig!!.maximumDuration.hours)
        val expectedIds = listOf("grid_test","price_test","symbol_test")
        assertEquals(expectedIds, scheduleConfig.randomizedAssessments("guid_123"))
        assertNull(scheduleConfig.randomizedAssessments("guid_abc"))
        assertEquals(false, scheduleConfig.alwaysRandomizedAssessments)
        assertEquals(SessionScheduleType.RANDOM, scheduleConfig.sessionScheduleType("guid_123"))
        assertEquals(SessionScheduleType.FIXED, scheduleConfig.sessionScheduleType("guid_abc"))
        assertEquals(SessionScheduleType.FIXED, scheduleConfig.sessionScheduleType("any-guid"))
    }
}