package org.sagebionetworks.bridge.kmm.shared.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import co.touchlab.stately.ensureNeverFrozen
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.util.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.sagebionetworks.bridge.kmm.shared.BridgeConfig
import org.sagebionetworks.bridge.kmm.shared.apis.SchedulesV2Api
import org.sagebionetworks.bridge.kmm.shared.buildClientData
import org.sagebionetworks.bridge.kmm.shared.cache.*
import org.sagebionetworks.bridge.kmm.shared.models.AdherenceRecord
import org.sagebionetworks.bridge.kmm.shared.models.AdherenceRecordList
import org.sagebionetworks.bridge.kmm.shared.models.AdherenceRecordsSearch
import org.sagebionetworks.bridge.kmm.shared.models.SortOrder

class AdherenceRecordRepo(httpClient: HttpClient,
                          val bridgeConfig: BridgeConfig?,
                          databaseHelper: ResourceDatabaseHelper,
                          backgroundScope: CoroutineScope) :
        AbstractResourceRepo(databaseHelper, backgroundScope) {

    init {
        ensureNeverFrozen()
    }


    private var scheduleV2Api = SchedulesV2Api(
        httpClient = httpClient
    )

    private val dbQuery = databaseHelper.database.participantScheduleQueries

    fun getAllCompletedCachedAssessmentAdherence(studyId: String): Flow <List<AssessmentHistoryRecord>> {
        return dbQuery.completedAssessmentAdherence(studyId).asFlow().mapToList(Dispatchers.Default).mapNotNull { list ->
            list.map {
                val adherenceRecord = Json.decodeFromString<AdherenceRecord>(it.adherenceJson!!)
                AssessmentHistoryRecord(
                    instanceGuid = it.assessmentInstanceGuid,
                    assessmentInfo = Json.decodeFromString(it.assessmentInfoJson),
                    startedOn = adherenceRecord.startedOn!!,
                    finishedOn = adherenceRecord.finishedOn!!,
                    clientTimeZone = adherenceRecord.clientTimeZone
                )
            } .sortedBy {
                it.finishedOn
            }
        }
    }

    /**
     * Get all of the locally cached [AdherenceRecord]s.
     */
    fun getAllCachedAdherenceRecords(studyId: String): Flow<Map<String, List<AdherenceRecord>>> {
        return dbQuery.allAdherence(studyId).asFlow().mapToList(Dispatchers.Default).mapNotNull { list ->
            list.map {
                Json.decodeFromString<AdherenceRecord>(it.adherenceJson)
            }.groupBy { adherenceRecord ->
                adherenceRecord.instanceGuid
            }
        }
    }

    fun getCachedAdherenceRecord(instanceId: String, startedOn: String) : AdherenceRecord? {
        return dbQuery.getAdherence(instanceId, startedOn).executeAsOneOrNull()?.let {
                Json.decodeFromString<AdherenceRecord>(it.adherenceJson)
        }
    }


    // TODO: Remove once this method is not longer being used by ScheduleTimelineRep -nbrown 10/17/22
    /**
     * Get the locally cached [AdherenceRecord]s for the specified [instanceIds].
     */
    @Deprecated("Uses old resource based cache, needs replaced with new implementation")
    fun getCachedAdherenceRecords(instanceIds: List<String>, studyId: String) : Map<String, List<AdherenceRecord>> {
        val list: List<AdherenceRecord> = database.getResourcesByIds(instanceIds, ResourceType.ADHERENCE_RECORD, studyId).mapNotNull { it.loadResource() }
        return list.groupBy { it.instanceGuid }
    }

    /**
     * Download and cache locally all [AdherenceRecord]s for the given study.
     * For now this only requests records using the current timestamps.
     */
    suspend fun loadRemoteAdherenceRecords(studyId: String) : Boolean {
        // First check to see if we have any records that need to be saved
        processAdherenceRecordUpdates(studyId)
        val pageSize = 500
        var pageOffset = 0
        var adherenceRecordsSearch = AdherenceRecordsSearch(sortOrder = SortOrder.DESC, pageSize = pageSize, includeRepeats = true, currentTimestampsOnly = true)
        try {
            do {
                val curOffset = pageOffset
                val recordResult = loadAndCacheResults(studyId, adherenceRecordsSearch)
                pageOffset+= pageSize
                adherenceRecordsSearch = adherenceRecordsSearch.copy(offsetBy = pageOffset)
            } while (curOffset + pageSize < recordResult.total)
        } catch (throwable: Throwable) {
            Logger.e("Error loading remote adherence records", throwable)
            return false
        }
        return true
    }

    suspend fun processAdherenceRecordUpdates(studyId: String) {
        processUpdates(studyId)
        processUpdatesV2(studyId)
    }

    private suspend fun loadAndCacheResults(studyId: String, adherenceRecordsSearch: AdherenceRecordsSearch) : AdherenceRecordList {
        val recordsResult = scheduleV2Api.searchForAdherenceRecords(studyId, adherenceRecordsSearch)
        for (record in recordsResult.items) {
            insertUpdate(studyId, record, needSave = false)
        }
        return recordsResult
    }

    private fun insertUpdate(studyId: String, adherenceRecord: AdherenceRecord, needSave: Boolean) {
        //TODO: Don't overwrite records that have needSave=true -nbrown 10/10/22
        val json = Json.encodeToString(adherenceRecord)
        //TODO: removing writing to resource table -nbrown 10/17/22
        val resource = Resource(
            identifier = adherenceRecord.instanceGuid,
            secondaryId = adherenceRecord.startedOn.toString(),
            type = ResourceType.ADHERENCE_RECORD,
            studyId = studyId,
            json = json,
            lastUpdate = Clock.System.now().toEpochMilliseconds(),
            status = ResourceStatus.SUCCESS,
            needSave = false)
        database.insertUpdateResource(resource)
        //Insert into new adherence specific table
        dbQuery.insertUpdateAdherenceRecord(
            studyId = studyId,
            instanceGuid = adherenceRecord.instanceGuid,
            startedOn = adherenceRecord.startedOn.toString(),
            finishedOn = adherenceRecord.finishedOn?.toString(),
            declined = adherenceRecord.declined,
            adherenceEventTimestamp = adherenceRecord.eventTimestamp,
            adherenceJson = Json.encodeToString(adherenceRecord),
            status = ResourceStatus.SUCCESS,
            needSave = needSave
        )

    }

    /**
     * Update the local cache with the specified [AdherenceRecord] and trigger a background call
     * to save to Bridge server.
     */
    fun createUpdateAdherenceRecord(adherenceRecord: AdherenceRecord, studyId: String) {
        val record = if (adherenceRecord.clientData == null && bridgeConfig != null) adherenceRecord.copy(clientData = bridgeConfig.buildClientData()) else adherenceRecord
        Logger.i("Updating adherence record:${adherenceRecord.instanceGuid}, finished:${adherenceRecord.finishedOn}")
        insertUpdate(studyId, record, needSave = true)
        backgroundScope.launch {
            processUpdates(studyId)
            processUpdatesV2(studyId)
        }
    }

    /**
     * Save any locally cached [AdherenceRecord]s that haven't been uploaded to Bridge server.
     * Any failures will remain in a needSave state and be tried again the next time.
     */
    //TODO: Remove in future release -nbrown 10/17/22
    private suspend fun processUpdates(studyId: String) {
        val resourcesToUpload = database.getResourcesNeedSave(ResourceType.ADHERENCE_RECORD, studyId)
        if (resourcesToUpload.isEmpty()) return
        val chunkedList = resourcesToUpload.chunked(25)
        for (resources in chunkedList) {
            processUpdates(studyId, resources)
        }
    }

    private suspend fun processUpdates(studyId: String, resourcesToUpload: List<Resource>) {
        val records: List<AdherenceRecord> = resourcesToUpload.mapNotNull { it.loadResource() }
        if (records.isEmpty()) return
        var status = ResourceStatus.FAILED
        var needSave = true
        try {
            scheduleV2Api.updateAdherenceRecords(studyId, records)
            status = ResourceStatus.SUCCESS
            needSave = false
        } catch (throwable: Throwable) {
            println(throwable)
            when (throwable) {
                is ResponseException -> {
                    when (throwable.response.status) {
                        HttpStatusCode.Unauthorized -> {
                            // 401 unauthorized
                            // User hasn't authenticated or re-auth has failed.
                        }
                        HttpStatusCode.Gone -> {
                            //410 Gone
                            // The version of the client making the request no longer has access to this service.
                            // The user must update their app in order to continue using Bridge.
                        }
                        HttpStatusCode.PreconditionFailed -> {
                            //412 Not consented
                        }
                    }
                }

                is UnresolvedAddressException -> {
                    //Internet connection error
                    status = ResourceStatus.RETRY
                }
            }
        }
        for (resource in resourcesToUpload) {
            val toUpdate = resource.copy(status = status, needSave = needSave)
            database.insertUpdateResource(toUpdate)
        }

    }

    /**
     * Save any locally cached [AdherenceRecord]s that haven't been uploaded to Bridge server.
     * Any failures will remain in a needSave state and be tried again the next time.
     */
    private suspend fun processUpdatesV2(studyId: String) {
        val recordsToUpload = dbQuery.selectAdherenceNeedSave(studyId).executeAsList()
        if (recordsToUpload.isEmpty()) return
        val chunkedList = recordsToUpload.chunked(25)
        for (records in chunkedList) {
            processUpdatesV2(studyId, records)
        }
    }

    private suspend fun processUpdatesV2(studyId: String, recordsToUpload: List<AdherenceRecords>) {
        val adherenceToUpload: List<AdherenceRecord> = recordsToUpload.mapNotNull { Json.decodeFromString(it.adherenceJson) }

        if (adherenceToUpload.isEmpty()) return
        var status = ResourceStatus.FAILED
        var needSave = true
        try {
            scheduleV2Api.updateAdherenceRecords(studyId, adherenceToUpload)
            status = ResourceStatus.SUCCESS
            needSave = false
        } catch (throwable: Throwable) {
            println(throwable)
            when (throwable) {
                is ResponseException -> {
                    when (throwable.response.status) {
                        HttpStatusCode.Unauthorized -> {
                            // 401 unauthorized
                            // User hasn't authenticated or re-auth has failed.
                        }
                        HttpStatusCode.Gone -> {
                            //410 Gone
                            // The version of the client making the request no longer has access to this service.
                            // The user must update their app in order to continue using Bridge.
                        }
                        HttpStatusCode.PreconditionFailed -> {
                            //412 Not consented
                        }
                    }
                }

                is UnresolvedAddressException -> {
                    //Internet connection error
                    status = ResourceStatus.RETRY
                }
            }
        }
        for (resource in recordsToUpload) {
            dbQuery.insertUpdateAdherenceRecord(
                studyId = studyId,
                instanceGuid = resource.instanceGuid,
                startedOn = resource.startedOn,
                finishedOn = resource.finishedOn,
                declined = resource.declined,
                adherenceEventTimestamp = resource.adherenceEventTimestamp,
                adherenceJson = resource.adherenceJson,
                status = status,
                needSave = needSave
            )
        }

    }
}