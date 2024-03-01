//
//  ScheduledAssessmentHandler.swift
//

import Foundation
import BridgeClient
import JsonModel
import ResultModel

/// Light-weight protocol for handling state of a displayed assessment.
/// - Note: All the methods within this protocol are marked as `@MainActor` which means that they are
///         required to launch from the main thread.
public protocol ScheduledAssessmentHandler : AnyObject {
    
    /// An async method for fetching the config for a given scheduled assessment.
    @MainActor func fetchAssessmentConfig(for scheduleInfo: AssessmentScheduleInfo) async throws -> ScheduledAssessmentConfig
    
    /// Hand off updating the adherence record, any published objects, uploading, etc.
    @MainActor func updateAssessmentStatus(_ scheduleInfo: AssessmentScheduleInfo, status: ScheduledAssessmentStatus)
}

/// The  status of a scheduled assessment when it is finished
public enum ScheduledAssessmentStatus {
    case declined(Date)
    case saveForLater(AssessmentResult)
    case restartLater
    case error(Error)
    case readyToSave(ResultArchiveBuilder)
    case finished
    case saveAndFinish(ResultArchiveBuilder)
}

/// Thread-safe result of fetching the config and restored result for a given scheduled assessment.
public struct ScheduledAssessmentConfig : Identifiable {
    public var id: String { scheduleInfo.instanceGuid }
    public let scheduleInfo: AssessmentScheduleInfo
    public let config: Data?
    public let restoreResult: Data?
    
    public init(scheduleInfo: AssessmentScheduleInfo, config: Data?, restoreResult: Data?) {
        self.scheduleInfo = scheduleInfo
        self.config = config
        self.restoreResult = restoreResult
    }
}

public protocol AdherenceRecordId {
    var instanceGuid: String { get }
    var eventTimestamp: String { get }
    var scheduledOn: String { get }
}

/// Thread-safe assessment schedule info. Kotlin objects can only be accessed from the main thread or it causes
/// a crash so this must be initialized on the main thread.
public struct AssessmentScheduleInfo : AdherenceRecordId, Identifiable, Hashable, Codable {
    public var id: String { instanceGuid }
    
    /// The ``BridgeClient.ScheduledAssessment/instanceGuid`` for this scheduled assessment.
    public let instanceGuid: String
    
    /// Information about the session.
    public let session: Session
    
    /// Information about the assessment.
    public let assessmentInfo: Info
    
    public var eventTimestamp: String {
        session.eventTimestamp
    }
    
    public var scheduledOn: String {
        ISO8601TimestampFormatter.string(from: session.scheduledOn)
    }
    
    public var assessmentIdentifier : String {
        assessmentInfo.identifier
    }
    
    public var isFirstAssessment: Bool {
        session.assessments.firstIndex(of: assessmentIdentifier) == 0
    }
    
    public var isLastAssessment: Bool {
        session.assessments.firstIndex(of: assessmentIdentifier) == (session.assessments.count - 1)
    }
    
    public init(session: NativeScheduledSessionWindow, assessment: NativeScheduledAssessment) {
        self.instanceGuid = assessment.instanceGuid
        self.session = Session(instanceGuid: session.instanceGuid,
                               eventTimestamp: session.eventTimestamp,
                               scheduledOn: session.startDateTime,
                               assessments: session.assessments.map { $0.assessmentInfo.identifier }
        )
        self.assessmentInfo = Info(identifier: assessment.assessmentInfo.identifier,
                                   key: assessment.assessmentInfo.key,
                                   guid: assessment.assessmentInfo.guid,
                                   label: assessment.assessmentInfo.label)
    }
    
    /// Internal init that can be used for testing.
    init(instanceGuid: String, session: Session, assessmentInfo: Info) {
        self.instanceGuid = instanceGuid
        self.session = session
        self.assessmentInfo = assessmentInfo
    }
    
    /// A thread-safe struct with information required to load and save the results from an assessment.
    public struct Info : Hashable, Codable {
        /// The ``BridgeClient.AssessmentInfo/identifier``.
        public let identifier: String
        /// The ``BridgeClient.AssessmentInfo/key``.
        public let key: String?
        /// The ``BridgeClient.AssessmentInfo/guid``.
        public let guid: String?
        /// The ``BridgeClient.AssessmentInfo/label``.
        public let label: String?
    }
    
    /// A thread-safe struct with information needed to find and update the Kotlin ``BridgeClient.NativeScheduledSessionWindow``
    /// for the session to which this assessment belongs.
    public struct Session : Hashable, Codable {
        /// The ``BridgeClient.ScheduledSession/instanceGuid`` for this scheduled assessment.
        public let instanceGuid: String
        /// The ``BridgeClient.StudyActivityEvent/timestamp`` for the event used as the reference
        /// when *this* schedule was built. This value is mutable on the server so the timestamp is used rather
        /// than the event id.
        public let eventTimestamp: String
        /// The calculated start time for when this session opens.
        public let scheduledOn: Date
        /// A list of the assessment guids
        public let assessments: [String]
    }
}

