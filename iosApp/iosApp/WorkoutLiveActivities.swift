import ActivityKit
import Foundation
import Shared

/// The shared code's way to the workout's Live Activity (`IosLiveActivities` in IosLiveWorkout.kt):
/// ActivityKit is Swift only, so the activity is started, updated and ended here, from the state
/// the shared code hands over whenever the workout changes. The widget extension draws it
/// (`WorkoutLiveActivity.swift`).
///
/// One activity at a time, for the workout in progress. One left from before the app last
/// started is taken up again if it is the same workout, and ended if it isn't.
final class WorkoutLiveActivities: NSObject, IosLiveActivities {
    static let shared = WorkoutLiveActivities()

    /// What was last handed to ActivityKit, so a state that changes nothing on the activity isn't sent again.
    private var last: (workoutId: Int64, content: WorkoutActivityAttributes.ContentState)?

    /// Hooks the activity's buttons up to the shared code's rest (see `WorkoutActivityActions`).
    @MainActor static func install() {
        WorkoutActivityActions.handler = { action in
            switch action {
            case .skip:
                return IosAppKt.onWorkoutRestAction(action: "skip", seconds: 0)
            case .adjust(let seconds):
                return IosAppKt.onWorkoutRestAction(action: "adjust", seconds: Int32(seconds))
            }
        }
    }

    func show(state: IosLiveWorkoutState) {
        let content = WorkoutActivityAttributes.ContentState(state)
        Task { @MainActor in self.apply(workoutId: state.workoutId, attributes: WorkoutActivityAttributes(state), content: content) }
    }

    func end() {
        Task { @MainActor in
            self.last = nil
            for activity in Activity<WorkoutActivityAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    @MainActor private func apply(workoutId: Int64, attributes: WorkoutActivityAttributes, content: WorkoutActivityAttributes.ContentState) {
        if let last, last.workoutId == workoutId, last.content == content { return }
        last = (workoutId, content)
        // Stale once the rest runs out, which is when it is drawn saying so.
        let activityContent = ActivityContent(state: content, staleDate: content.restEnd)
        let running = Activity<WorkoutActivityAttributes>.activities
        if let current = running.first(where: { $0.attributes.workoutId == workoutId }) {
            Task { await current.update(activityContent) }
            for other in running where other.id != current.id {
                Task { await other.end(nil, dismissalPolicy: .immediate) }
            }
            return
        }
        for other in running {
            Task { await other.end(nil, dismissalPolicy: .immediate) }
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            _ = try Activity.request(attributes: attributes, content: activityContent, pushType: nil)
        } catch {
            // Only an app in front may start one; the next change with it in front tries again.
            last = nil
            print("HomeSafe: no Live Activity for the workout: \(error.localizedDescription)")
        }
    }
}

private extension Date {
    init(epochMillis: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
    }
}

private extension WorkoutActivityAttributes {
    init(_ state: IosLiveWorkoutState) {
        self.init(
            workoutId: state.workoutId,
            startedAt: Date(epochMillis: state.startedAtEpochMillis),
            accent: UInt32(bitPattern: state.accentArgb),
            accent2: UInt32(bitPattern: state.accent2Argb),
            labels: Labels(
                rest: state.restLabel,
                go: state.goLabel,
                skip: state.skipLabel,
                minusShort: state.minusShort,
                minus: state.minusLabel,
                plusShort: state.plusShort,
                plus: state.plusLabel,
                open: state.openLabel
            )
        )
    }
}

private extension WorkoutActivityAttributes.ContentState {
    init(_ state: IosLiveWorkoutState) {
        let resting = state.restEndsAtEpochMillis > 0
        let end = Date(epochMillis: state.restEndsAtEpochMillis)
        self.init(
            title: state.title,
            summary: state.summary,
            resting: state.resting,
            restOver: state.restOver,
            trainingDetail: state.trainingDetail,
            restingDetail: state.restingDetail,
            restOverDetail: state.restOverDetail,
            heart: state.heart,
            restStart: resting ? end.addingTimeInterval(-TimeInterval(state.restTotalSeconds)) : nil,
            restEnd: resting ? end : nil
        )
    }
}
