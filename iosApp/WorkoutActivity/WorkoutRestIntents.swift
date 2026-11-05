import ActivityKit
import AppIntents
import Foundation

/// What a button on the workout's Live Activity asks of the rest.
enum WorkoutRestAction {
    case skip
    case adjust(seconds: Int)
}

/// Where the Live Activity's buttons go. The intents below are compiled into the app as well as
/// the widget extension, so iOS runs them in the app (a `LiveActivityIntent` is run in the app
/// that contains it), where `handler` hands them to the shared code's rest: the app's own page
/// and Android's notification follow the same one. When the app was started afresh just to run
/// a button and has no workout in hand, the activity is changed here by itself instead.
enum WorkoutActivityActions {
    /// Set by the app at launch (`WorkoutLiveActivities.install`); true when the shared code took the action.
    @MainActor static var handler: ((WorkoutRestAction) -> Bool)?

    @MainActor static func perform(_ action: WorkoutRestAction) async {
        if handler?(action) == true { return }
        for activity in Activity<WorkoutActivityAttributes>.activities {
            var state = activity.content.state
            switch action {
            case .skip:
                state.restStart = nil
                state.restEnd = nil
            case .adjust(let seconds):
                guard let end = state.restEnd else { continue }
                state.restEnd = end.addingTimeInterval(TimeInterval(seconds))
            }
            await activity.update(ActivityContent(state: state, staleDate: state.restEnd))
        }
    }
}

/// The Live Activity's skip button.
struct SkipRestIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Skip rest"
    static let isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult {
        await WorkoutActivityActions.perform(.skip)
        return .result()
    }
}

/// The Live Activity's fifteen seconds more, or less.
struct AdjustRestIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Change the rest"
    static let isDiscoverable = false

    @Parameter(title: "Seconds")
    var seconds: Int

    init() {}

    init(seconds: Int) {
        self.seconds = seconds
    }

    func perform() async throws -> some IntentResult {
        await WorkoutActivityActions.perform(.adjust(seconds: seconds))
        return .result()
    }
}
