package org.sagebionetworks.bridge.kmm.shared.repo

import co.touchlab.stately.ensureNeverFrozen
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.sagebionetworks.bridge.kmm.shared.BridgeConfig
import org.sagebionetworks.bridge.kmm.shared.apis.PublicApi
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceDatabaseHelper
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceResult
import org.sagebionetworks.bridge.kmm.shared.cache.ResourceType
import org.sagebionetworks.bridge.kmm.shared.models.AppConfig
import org.sagebionetworks.bridge.kmm.shared.models.ScheduleConfig

class AppConfigRepo(httpClient: HttpClient,
                    databaseHelper: ResourceDatabaseHelper,
                    backgroundScope: CoroutineScope,
                    val bridgeConfig: BridgeConfig ) :
    AbstractResourceRepo(databaseHelper, backgroundScope) {

    init {
        ensureNeverFrozen()
    }


    internal var publicApi = PublicApi(
        httpClient = httpClient
    )

    fun getAppConfig(): Flow<ResourceResult<AppConfig>> {
        return getResourceByIdAsFlow(
            identifier = bridgeConfig.appId,
            resourceType = ResourceType.APP_CONFIG,
            remoteLoad = { loadRemoteAppConfig() },
            studyId =  ResourceDatabaseHelper.APP_WIDE_STUDY_ID)
    }

    /**
     * Get the app-level schedule config.
     * @return ScheduleConfig or null
     */
    suspend fun getScheduleConfig() : ScheduleConfig? {
        val resource = getResourceById<AppConfig>(
            identifier = bridgeConfig.appId,
            resourceType = ResourceType.APP_CONFIG,
            remoteLoad = { loadRemoteAppConfig() },
            studyId =  ResourceDatabaseHelper.APP_WIDE_STUDY_ID)
        return (resource as? ResourceResult.Success)?.data?.scheduleConfig
    }

    private suspend fun loadRemoteAppConfig() : String {
        return Json.encodeToString(publicApi.getConfigForApp(bridgeConfig.appId))
    }

}