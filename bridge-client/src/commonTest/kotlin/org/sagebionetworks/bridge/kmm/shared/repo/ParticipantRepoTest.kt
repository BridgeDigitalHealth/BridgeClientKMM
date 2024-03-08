package org.sagebionetworks.bridge.kmm.shared.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.BridgeConfig
import org.sagebionetworks.bridge.kmm.shared.PlatformConfig
import org.sagebionetworks.bridge.kmm.shared.TestEncryptedSharedSettings
import org.sagebionetworks.bridge.kmm.shared.TestHttpClientConfig
import org.sagebionetworks.bridge.kmm.shared.cache.Resource
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceDatabaseHelper
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceStatus
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceType
import org.sagebionetworks.bridge.kmm.shared.getTestClient
import org.sagebionetworks.bridge.kmm.shared.models.UserAvailabilityWindow
import org.sagebionetworks.bridge.kmm.shared.models.UserSessionInfo
import org.sagebionetworks.bridge.kmm.shared.testDatabaseDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParticipantRepoTest : BaseTest() {

    private val userSessionInfo = UserSessionInfo(
        id = "uniqueId",
        authenticated = true,
        studyIds = listOf("testStudyId"),
        sessionToken = "testSessionToken",
        reauthToken = "testReauthToken"
    )

    @Test
    fun testUpdateParticipant_NullInitialClientData() {
        runTest {
            val db = ResourceDatabaseHelper(testDatabaseDriver())
            // Setup database with a cached UserSessionInfo object
            val resource = Resource(
                identifier = AuthenticationRepository.USER_SESSION_ID,
                secondaryId = ResourceDatabaseHelper.DEFAULT_SECONDARY_ID,
                type = ResourceType.USER_SESSION_INFO,
                studyId = ResourceDatabaseHelper.APP_WIDE_STUDY_ID,
                json = Json.encodeToString(userSessionInfo),
                lastUpdate = Clock.System.now().toEpochMilliseconds(),
                status = ResourceStatus.SUCCESS,
                needSave = false
            )
            db.insertUpdateResource(resource)
            val bridgeConfig = TestBridgeConfig()
            val testConfig = TestHttpClientConfig(bridgeConfig = bridgeConfig, db = db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val authRepo = AuthenticationRepository(getTestClient("", config = testConfig.copy(authProvider = null)), bridgeConfig,  db, scope, TestEncryptedSharedSettings())
            val participantRepo = ParticipantRepo(getTestClient("", config = testConfig), db, scope, authRepo)
            val session = authRepo.session()
            assertNotNull(session)
            assertNull(session.clientData)

            // Add client data
            val updateParticipantRecord = ParticipantRepo.UpdateParticipantRecord.getUpdateParticipantRecord(session)
            val clientData = Json.decodeFromString<JsonObject>(UserClientDataJsonResource.exampleArcClientDataJson)
            updateParticipantRecord.appClientData = clientData
            participantRepo.updateParticipant(updateParticipantRecord)
            // Verify the client data is saved to the session
            val updatedSession = authRepo.session()
            assertEquals(clientData, updatedSession?.clientData)
            assertEquals("testSessionToken", updatedSession?.sessionToken)
        }
    }

    @Test
    fun testUpdateParticipant_InitialArcClientData() {
        runTest {
            val db = ResourceDatabaseHelper(testDatabaseDriver())

            val initialClientData = Json.decodeFromString<JsonObject>(UserClientDataJsonResource.exampleArcClientDataJson)
            val sessionInfo = userSessionInfo.copy(
                clientData = initialClientData
            )

            // Setup database with a cached UserSessionInfo object
            val resource = Resource(
                identifier = AuthenticationRepository.USER_SESSION_ID,
                secondaryId = ResourceDatabaseHelper.DEFAULT_SECONDARY_ID,
                type = ResourceType.USER_SESSION_INFO,
                studyId = ResourceDatabaseHelper.APP_WIDE_STUDY_ID,
                json = Json.encodeToString(sessionInfo),
                lastUpdate = Clock.System.now().toEpochMilliseconds(),
                status = ResourceStatus.SUCCESS,
                needSave = false
            )
            db.insertUpdateResource(resource)
            val bridgeConfig = TestBridgeConfig()
            val testConfig = TestHttpClientConfig(bridgeConfig = bridgeConfig, db = db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val authRepo = AuthenticationRepository(getTestClient("", config = testConfig.copy(authProvider = null)), bridgeConfig,  db, scope, TestEncryptedSharedSettings())
            val participantRepo = ParticipantRepo(getTestClient("", config = testConfig), db, scope, authRepo)
            val session = authRepo.session()
            assertNotNull(session)
            assertEquals(session.clientData, initialClientData)
            assertNotNull(session.userScheduleData)

            // Update schedule
            val updateParticipantRecord = ParticipantRepo.UpdateParticipantRecord.getUpdateParticipantRecord(session)
            assertNotNull(updateParticipantRecord.userScheduleData)
            assertNotNull(updateParticipantRecord.userScheduleData?.availability)
            assertNotNull(updateParticipantRecord.userScheduleData?.sessionStartTimes)

            val newAvailabilityWindow = UserAvailabilityWindow(
                wakeTime = LocalTime(8, 30),
                bedTime = LocalTime(22, 30)
            )
            updateParticipantRecord.availability = newAvailabilityWindow

            // check that setting the availability did what was expected
            assertEquals(newAvailabilityWindow, updateParticipantRecord.userScheduleData?.availability)

            // check that the client data is merged successfully
            val mergedClientData = participantRepo.mergeClientData(session, updateParticipantRecord)
            assertNotNull(mergedClientData)

            participantRepo.updateParticipant(updateParticipantRecord)
            // Verify the client data is saved to the session
            val updatedSession = authRepo.session()
            assertEquals(newAvailabilityWindow, updatedSession?.availability)
            assertEquals("testSessionToken", updatedSession?.sessionToken)
        }
    }

    @Test
    fun testUpdateParticipant_InitialOpenBridgeClientData() {
        runTest {
            val db = ResourceDatabaseHelper(testDatabaseDriver())
            val sessionInfo = userSessionInfo

            // Setup database with a cached UserSessionInfo object
            val resource = Resource(
                identifier = AuthenticationRepository.USER_SESSION_ID,
                secondaryId = ResourceDatabaseHelper.DEFAULT_SECONDARY_ID,
                type = ResourceType.USER_SESSION_INFO,
                studyId = ResourceDatabaseHelper.APP_WIDE_STUDY_ID,
                json = Json.encodeToString(sessionInfo),
                lastUpdate = Clock.System.now().toEpochMilliseconds(),
                status = ResourceStatus.SUCCESS,
                needSave = false
            )
            db.insertUpdateResource(resource)
            val bridgeConfig = TestBridgeConfig()
            val testConfig = TestHttpClientConfig(bridgeConfig = bridgeConfig, db = db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val authRepo = AuthenticationRepository(getTestClient("", config = testConfig.copy(authProvider = null)), bridgeConfig,  db, scope, TestEncryptedSharedSettings())
            val participantRepo = ParticipantRepo(getTestClient("", config = testConfig), db, scope, authRepo)
            val session = authRepo.session()
            assertNotNull(session)
            assertNull(session.clientData)
            assertNull(session.userScheduleData)

            // Update schedule
            val updateParticipantRecord = ParticipantRepo.UpdateParticipantRecord.getUpdateParticipantRecord(session)
            val newAvailabilityWindow = UserAvailabilityWindow(
                wakeTime = LocalTime(8, 30),
                bedTime = LocalTime(22, 30)
            )
            updateParticipantRecord.availability = newAvailabilityWindow

            participantRepo.updateParticipant(updateParticipantRecord)
            // Verify the client data is saved to the session
            val updatedSession = authRepo.session()
            assertNotNull(updatedSession?.clientData)
            assertEquals(newAvailabilityWindow, updatedSession?.availability)
            assertEquals("testSessionToken", updatedSession?.sessionToken)
        }
    }
}

class TestBridgeConfig: BridgeConfig {
    override val appId: String
        get() = "bridge-client-kmm-test"
    override val appName: String
        get() = "BridgeClientKMM Tests"
    override val sdkVersion: Int
        get() = 1
    override val appVersion: Int
        get() = 1
    override val appVersionName: String
        get() = "Unit Tests"
    override val bridgeEnvironment: PlatformConfig.BridgeEnvironment
        get() = PlatformConfig.BridgeEnvironment.PRODUCTION
    override val osName: String
        get() = "Test"
    override val osVersion: String
        get() = "Test"
    override val deviceName: String
        get() = "Test"
    override var cacheCredentials: Boolean = false


}