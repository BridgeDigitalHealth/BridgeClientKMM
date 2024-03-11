package org.sagebionetworks.bridge.kmm.shared.repo

import io.ktor.client.engine.config
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.sagebionetworks.bridge.kmm.shared.BaseTest
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceDatabaseHelper
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceStatus
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceType
import org.sagebionetworks.bridge.kmm.shared.getJsonReponseHandler
import org.sagebionetworks.bridge.kmm.shared.getTestClient
import org.sagebionetworks.bridge.kmm.shared.models.UploadFile
import org.sagebionetworks.bridge.kmm.shared.models.UploadMetadata
import org.sagebionetworks.bridge.kmm.shared.models.UploadRequestMetadata
import org.sagebionetworks.bridge.kmm.shared.randomUUID
import org.sagebionetworks.bridge.kmm.shared.testDatabaseDriver
import kotlin.test.*

class AdherenceRecordRepoTest: BaseTest() {

    val instanceGuid = "JB6aZ_lanz34C5iNF3TZ9Q"
    val startedOn = "2021-05-12T23:44:54.319Z"
    val finishedOn = "2021-05-12T23:44:54.319Z"
    val eventTimestamp = "2021-05-12T23:44:51.872Z"

    val json = "{\n" +
            "   \"items\":[\n" +
            "      {\n" +
            "         \"instanceGuid\":\""+ instanceGuid + "\",\n" +
            "         \"startedOn\":\""+ startedOn + "\",\n" +
            "         \"finishedOn\":\""+ finishedOn + "\",\n" +
            "         \"eventTimestamp\":\""+ eventTimestamp +"\",\n" +
            "         \"clientTimeZone\":\""+ TimeZone.currentSystemDefault().id +"\",\n" +
            "         \"type\":\"AdherenceRecord\"\n" +
            "      }\n" +
            "   ],\n" +
            "   \"total\":1,\n" +
            "   \"type\":\"PagedResourceList\"\n" +
            "}"

    @Test
    fun testAdherenceRepo() {
        runTest {
            val studyId = "testId"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val mockEngine = MockEngine.config {
                // 1 - Load adherence call
                addHandler (
                    getJsonReponseHandler(json)
                )
                // 2 - Process updates call needs to fail so we still have pending local changes
                addHandler { respondError(HttpStatusCode.GatewayTimeout) }
                // 3 - Second Load adherence call
                addHandler (
                    getJsonReponseHandler(json)
                )
                reuseHandlers = false
            }
            val testClient = getTestClient(mockEngine)

            val repo = AdherenceRecordRepo(testClient, null, ResourceDatabaseHelper(testDatabaseDriver()), scope)

            assertTrue(repo.loadRemoteAdherenceRecords(studyId))
            var adherenceRecord = repo.getCachedAdherenceRecord(instanceGuid, startedOn)
            assertNotNull(adherenceRecord)

            val recordMap =  repo.getAllCachedAdherenceRecords(studyId).first()
            assertEquals(1, recordMap.size)
            val recordList = recordMap[instanceGuid]
            assertNotNull(recordList)
            val record = recordList[0]
            assertNotNull(record)
            assertEquals(instanceGuid, record.instanceGuid)

            val updatedRecord = record.copy(clientData = JsonPrimitive("Test data"))
            repo.database.database.participantScheduleQueries.insertUpdateAdherenceRecord(
                studyId = studyId,
                instanceGuid = updatedRecord.instanceGuid,
                startedOn = updatedRecord.startedOn.toString(),
                finishedOn = updatedRecord.finishedOn?.toString(),
                declined = updatedRecord.declined,
                adherenceEventTimestamp = updatedRecord.eventTimestamp,
                adherenceJson = Json.encodeToString(updatedRecord),
                status = ResourceStatus.SUCCESS,
                needSave = true
            )
            var dbRecord = repo.database.database.participantScheduleQueries.getAdherence(instanceGuid, startedOn).executeAsOne()
            assertTrue(dbRecord.needSave)
            adherenceRecord = repo.getCachedAdherenceRecord(instanceGuid, startedOn)
            assertEquals("\"Test data\"", adherenceRecord!!.clientData.toString())

            // Trigger a load of data
            assertTrue(repo.loadRemoteAdherenceRecords(studyId))
            // Check that local changes did not get overwritten
            dbRecord = repo.database.database.participantScheduleQueries.getAdherence(instanceGuid, startedOn).executeAsOne()
            assertTrue(dbRecord.needSave)
            adherenceRecord = repo.getCachedAdherenceRecord(instanceGuid, startedOn)
            assertEquals("\"Test data\"", adherenceRecord!!.clientData.toString())

            val db = repo.database
            db.clearDatabase()
        }
    }

    @Test
    fun testRequestUploadMetadata() {
        runTest {
            val studyId = "testId"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = AdherenceRecordRepo(
                getTestClient(json),
                null,
                ResourceDatabaseHelper(testDatabaseDriver()),
                scope
            )

            assertTrue(repo.loadRemoteAdherenceRecords(studyId))
            val adherenceRecord =
                repo.getCachedAdherenceRecord(instanceGuid, startedOn)
            assertNotNull(adherenceRecord)
            val metadata = UploadMetadata(
                instanceGuid = instanceGuid,
                eventTimestamp = "eventTimestamp",
                startedOn = startedOn
            )
            val uploadFile = UploadFile(
                filePath = randomUUID(),
                contentType = "application/zip",
                fileLength = 1024,
                md5Hash = randomUUID(),
                metadata = metadata
            );
            val uploadRequest = uploadFile.getUploadRequest(repo)
            val expectedRequestMetadata = UploadRequestMetadata(adherenceRecord).toJsonMap()
            val uploadRequestMetadata = uploadRequest.metadata
            assertEquals(expectedRequestMetadata, uploadRequestMetadata)
            assertEquals(instanceGuid, uploadRequestMetadata?.get("instanceGuid")?.jsonPrimitive?.content)
            assertEquals(startedOn, uploadRequestMetadata?.get("startedOn")?.jsonPrimitive?.content)
            assertEquals(finishedOn, uploadRequestMetadata?.get("finishedOn")?.jsonPrimitive?.content)
            assertEquals(eventTimestamp, uploadRequestMetadata?.get("eventTimestamp")?.jsonPrimitive?.content)
            assertEquals(TimeZone.currentSystemDefault().id, uploadRequestMetadata?.get("clientTimeZone")?.jsonPrimitive?.content)
            assertEquals("false", uploadRequestMetadata?.get("declined").toString())

        }
    }

}

