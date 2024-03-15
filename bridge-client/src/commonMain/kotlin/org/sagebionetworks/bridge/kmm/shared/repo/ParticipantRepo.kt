package org.sagebionetworks.bridge.kmm.shared.repo

import co.touchlab.kermit.Logger
import co.touchlab.stately.ensureNeverFrozen
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.util.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.sagebionetworks.bridge.kmm.shared.apis.ParticipantApi
import org.sagebionetworks.bridge.kmm.shared.cache.*
import org.sagebionetworks.bridge.kmm.shared.models.*
import org.sagebionetworks.bridge.kmm.shared.models.ConsentSignature

class ParticipantRepo(httpClient: HttpClient,
                      databaseHelper: ResourceDatabaseHelper,
                      backgroundScope: CoroutineScope,
                      private val authenticationRepo: AuthenticationRepository) :
        AbstractResourceRepo(databaseHelper, backgroundScope) {

    init {
        ensureNeverFrozen()
    }

    private var participantApi = ParticipantApi(
        httpClient = httpClient
    )

    /**
     * Update user's participant record. This will update the locally cached [UserSessionInfo] object,
     * and trigger a background process to update the Bridge server.
     */
    fun updateParticipant(record: UpdateParticipantRecord) {
        val previousSession = authenticationRepo.session()
        if (previousSession == null) {
            // TODO: syoung 03/10/2024 Um, huh? does this mean the participant is signed out and the update should be ignored?
            Logger.e("Attempting to update a participant record for a signed-out user.")
            return
        }

        // Merge the client data that might be stored by the app with the client data that is stored
        // by schedule mutation.
        val clientData = try {
            mergeClientData(previousSession, record)
        } catch (throwable: Throwable) {
            Logger.e("Failed to encode `UserScheduleData`", throwable)
            null
        }

        // Update UserSessionInfo resource and mark as needing update
        val userSessionInfo = previousSession.copy(
            firstName = record.firstName,
            lastName = record.lastName,
            sharingScope = record.sharingScope,
            dataGroups = record.dataGroups,
            clientData = clientData,
            email = record.email,
            phone = record.phone
        )
        val resource = Resource(
            identifier = AuthenticationRepository.USER_SESSION_ID,
            secondaryId = ResourceDatabaseHelper.DEFAULT_SECONDARY_ID,
            type = ResourceType.USER_SESSION_INFO,
            studyId = ResourceDatabaseHelper.APP_WIDE_STUDY_ID,
            json = Json.encodeToString(userSessionInfo),
            lastUpdate = Clock.System.now().toEpochMilliseconds(),
            status = ResourceStatus.SUCCESS,
            needSave = true
        )
        database.insertUpdateResource(resource)
        backgroundScope.launch {
            processLocalUpdates()
        }
    }

    internal fun mergeClientData(previousSession: UserSessionInfo, record: UpdateParticipantRecord) : JsonElement? {
        // Merge the client data that might be stored by the app with the client data that is stored
        // by schedule mutation.
        val clientDataMap : MutableMap <String, JsonElement> =
            record.appClientData?.jsonObject?.toMutableMap() ?: mutableMapOf()
        val userScheduleData = UserScheduleData.union(record.userScheduleData, previousSession.userScheduleData)
        if (!userScheduleData.isNullOrEmpty()) {
            Json.encodeToJsonElement(userScheduleData).jsonObject.let {
                it.entries.forEach { pair ->
                    clientDataMap[pair.key] = pair.value
                }
            }
        }
        return if (clientDataMap.isEmpty()) null else JsonObject(clientDataMap)
    }

    suspend fun createConsentSignature(subpopulationGuid: String): ResourceResult<UserSessionInfo> {
        try {
            val sessionInfo = authenticationRepo.session()
            val name = sessionInfo?.let {
                listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifEmpty { null }
            } ?: "Name"
            val scope = sessionInfo?.sharingScope ?: SharingScope.SPONSORS_AND_PARTNERS
            val consentSignature = ConsentSignature(name = name, scope = scope)
            val userSession = participantApi.createConsentSignature(subpopulationGuid, consentSignature)
            authenticationRepo.updateCachedSession(null, userSession)
            return ResourceResult.Success(userSession, ResourceStatus.SUCCESS)
        } catch (err: Throwable) {
            Logger.e("Error adding consent", err)
        }
        return ResourceResult.Failed(ResourceStatus.FAILED)
    }

    internal suspend fun processLocalUpdates() {
        //Check if session needs updating
        val sessionResource = authenticationRepo.sessionResource()
        sessionResource?.let { resource ->
            if (resource.needSave) {
                val session = resource.loadResource<UserSessionInfo>()
                val studyParticipant =
                    session?.let { it1 -> UpdateParticipantRecord.getStudyParticipant(it1) }

                studyParticipant?.let {
                    var status = ResourceStatus.FAILED
                    var needSave = true
                    try {
                        participantApi.updateUsersParticipantRecord(it)
                        status = ResourceStatus.SUCCESS
                        needSave = false
                    } catch (throwable: Throwable) {
                        println(throwable)
                        when (throwable) {
                            is ResponseException -> {
                                // Can look at throwable.response.status if we find scenarios we want to mark for retry
                            }

                            is UnresolvedAddressException -> {
                                //Internet connection error
                                status = ResourceStatus.RETRY
                            }
                        }
                    }
                    val toUpdate = resource.copy(status = status, needSave = needSave)
                    database.insertUpdateResource(toUpdate)
                }

            }
        }

    }

    /**
     * Data class used for updating the editable fields on a study participant.
     */
    data class UpdateParticipantRecord private constructor (
        /* An ID assigned to this account by Bridge system. */
        val id: String,
        /* First name (given name) of the user. */
        var firstName: String? = null,
        /* Last name (family name) of the user. */
        var lastName: String? = null,
        var sharingScope: SharingScope? = null,
        /* The data groups set for this user. Data groups must be strings that are defined in the list of all valid data groups for the app, as part of the app object.   */
        val dataGroups: MutableList<String>? = null,
        /* The user's email. */
        var email: String? = null,
        var phone: Phone? = null,

        /* Data used to set up custom schedule times. */
        internal var userScheduleData: UserScheduleData?,

        // Wrap the client data to allow both the app to store app-specific properties on the
        // participant, as well as allowing a "future" model to include these outside the client
        // data blob.
        internal var appClientData: JsonElement?,
    ) {

        @Deprecated("This is included for reverse-compatibility to older apps that use the schedule mutator.")
        var clientData: JsonElement?
            get() = appClientData
            set(newValue) {
                // Look to see if this is an old schedule mutator implementation and store the
                // properties from it to the parsed out schedule data.
                val newScheduleData = newValue.decodeObject(UserScheduleData.serializer())
                if (!newScheduleData.isNullOrEmpty()) {
                    newScheduleData!!.setTimestampsIfNeeded()
                    userScheduleData = newScheduleData
                }
                appClientData = newValue
            }

        /**
         * Set the participant's availability window.
         */
        var availability : UserAvailabilityWindow?
            get() = userScheduleData?.availability
            set(newValue) {
                if (userScheduleData == null) {
                    userScheduleData = UserScheduleData()
                }
                userScheduleData!!.availability = newValue
            }

        companion object {

            /**
             * Given a [UserSessionInfo] object, get an [UpdateParticipantRecord] which can be used to
             * update values on a study participant.
             */
            fun getUpdateParticipantRecord(session: UserSessionInfo) : UpdateParticipantRecord {
                return UpdateParticipantRecord(
                    id = session.id,
                    firstName = session.firstName,
                    lastName = session.lastName,
                    sharingScope = session.sharingScope,
                    dataGroups = session.dataGroups?.toMutableList(),
                    email = session.email,
                    phone = session.phone,
                    userScheduleData = session.userScheduleData,
                    appClientData = session.clientData,
                )
            }

            internal fun getStudyParticipant(session: UserSessionInfo) : StudyParticipant {
                return StudyParticipant(
                    id = session.id,
                    firstName = session.firstName,
                    lastName = session.lastName,
                    sharingScope = session.sharingScope,
                    dataGroups = session.dataGroups?.toMutableList(),
                    email = session.email,
                    phone = session.phone,
                    clientData = session.clientData,
                )
            }

        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object ClientDataHelper {
    val jsonCoder = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}

val UserSessionInfo.availability : UserAvailabilityWindow?
    get() = userScheduleData?.availability

internal val UserSessionInfo.userScheduleData : UserScheduleData?
    get() = clientData?.decodeObject(UserScheduleData.serializer())

@OptIn(ExperimentalSerializationApi::class)
internal inline fun <reified T> JsonElement?.decodeObject(deserializer: DeserializationStrategy<T>): T? {
    return try {
        this?.let {
            ClientDataHelper.jsonCoder.decodeFromJsonElement(deserializer, it)
        }
    } catch (err: Throwable) {
        Logger.e("Failed to decode ${deserializer.descriptor.serialName}", err)
        null
    }
}

internal inline fun <reified T> JsonElement.decodeObjectWithKey(key: String, deserializer: DeserializationStrategy<T>): T? {
    return jsonObject[key]?.let {
        it.decodeObject(deserializer)
    }
}

