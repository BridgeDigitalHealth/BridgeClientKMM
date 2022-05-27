//
//  SurveyView.swift
//  
//
//  Copyright © 2022 Sage Bionetworks. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without modification,
// are permitted provided that the following conditions are met:
//
// 1.  Redistributions of source code must retain the above copyright notice, this
// list of conditions and the following disclaimer.
//
// 2.  Redistributions in binary form must reproduce the above copyright notice,
// this list of conditions and the following disclaimer in the documentation and/or
// other materials provided with the distribution.
//
// 3.  Neither the name of the copyright holder(s) nor the names of any contributors
// may be used to endorse or promote products derived from this software without
// specific prior written permission. No license is granted to the trademarks of
// the copyright holders even if such marks are included in this software.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//

import SwiftUI
import JsonModel
import AssessmentModel
import AssessmentModelUI

public struct SurveyView: View {
    let assessmentInfo: AssessmentScheduleInfo
    let handler: ScheduledAssessmentHandler
    @StateObject var viewModel: ViewModel = .init()
    
    public init(_ assessmentInfo: AssessmentScheduleInfo, handler: ScheduledAssessmentHandler) {
        self.assessmentInfo = assessmentInfo
        self.handler = handler
    }
    
    public var body: some View {
        content()
            .onAppear {
                Task {
                    await viewModel.loadAssessment(assessmentInfo, using: handler)
                }
            }
    }
    
    @ViewBuilder
    func content() -> some View {
        if let assessment = viewModel.assessmentState {
            AssessmentView(assessment)
                .assessmentStateListener(assessment, info: assessmentInfo, handler: handler)
        }
        else {
            ProgressView()
        }
    }

    @MainActor
    class ViewModel : ObservableObject {
        @Published var assessmentState: AssessmentState?
        
        func loadAssessment(_ assessmentInfo: AssessmentScheduleInfo, using handler: ScheduledAssessmentHandler) async {
            guard assessmentState == nil else { return }
            do {
                let config = try await handler.fetchAssessmentConfig(for: assessmentInfo)
                let decoder = AssessmentModel.AssessmentFactory.defaultFactory.createJSONDecoder()
                let assessment = try decoder.decode(AssessmentObject.self, from: config.config)
                let restoredResult = try config.restoreResult.map {
                    try decoder.decode(AssessmentResultObject.self, from: $0)
                }
                assessmentState = .init(assessment, restoredResult: restoredResult)
            } catch {
                debugPrint("Failed to load assessment \(assessmentInfo.assessmentInfo.identifier): \(error)")
                handler.updateAssessmentStatus(assessmentInfo, status: .error(error))
            }
        }
    }
}

public extension View {
    func assessmentStateListener(_ assessmentState: AssessmentState, info: AssessmentScheduleInfo, handler: ScheduledAssessmentHandler) -> some View {
        modifier(AssessmentViewListener(assessmentState: assessmentState, assessmentInfo: info, handler: handler))
    }
}

struct AssessmentViewListener : ViewModifier {
    @ObservedObject var assessmentState: AssessmentState
    let assessmentInfo: AssessmentScheduleInfo
    let handler: ScheduledAssessmentHandler
    
    func body(content: Content) -> some View {
        content
            .onChange(of: assessmentState.status) { _ in
                guard let status = bridgeStatus() else { return }
                handler.updateAssessmentStatus(assessmentInfo, status: status)
            }
    }
    
    func bridgeStatus() -> ScheduledAssessmentStatus? {
        switch assessmentState.status {
        case .running:
            return nil
            
        case .readyToSave:
            return AssessmentResultArchive(assessmentState.assessmentResult.deepCopy(), schedule: assessmentInfo).map {
                .readyToSave($0)
            }
            
        case .finished:
            return .finished
            
        case .continueLater:
            if assessmentState.interruptionHandling.canSaveForLater {
                return .saveForLater(assessmentState.assessmentResult)
            }
            else {
                return .restartLater
            }
            
        case .declined:
            return .declined(assessmentState.assessmentResult.startDate)
            
        case .error:
            return .error(assessmentState.navigationError ?? ValidationError.unknown)
        }
    }
}