import ActivityKit
import Foundation
import SwiftUI

/// The workout in progress as a Live Activity: what stays the same for the whole workout (when it
/// began, its colours, the buttons' words) and what changes with it (`ContentState`). Compiled
/// into the app, which starts and updates the activity (`WorkoutLiveActivities.swift`), and the
/// widget extension, which draws it (`WorkoutLiveActivityViews.swift`).
///
/// Every word arrives already in the reader's language, from the shared code's resources, so the
/// Live Activity says what the app and Android's notification say.
struct WorkoutActivityAttributes: ActivityAttributes {
    /// The log's id for the workout: an activity left from before the app last started is taken
    /// up again if it is still this one.
    var workoutId: Int64
    var startedAt: Date
    /// The workout's two colours, brighter first, as 0xAARRGGBB.
    var accent: UInt32
    var accent2: UInt32
    var labels: Labels

    struct Labels: Codable, Hashable {
        var rest: String
        var go: String
        var skip: String
        var minusShort: String
        var minus: String
        var plusShort: String
        var plus: String
        var open: String
    }

    struct ContentState: Codable, Hashable {
        /// "Chest day".
        var title: String
        /// "6 sets · 2 records".
        var summary: String
        /// "Resting after Bench press".
        var resting: String
        /// "Rest's up".
        var restOver: String
        /// The line under the headline, for each phase (see `LiveWorkoutWords.detail`).
        var trainingDetail: String
        var restingDetail: String
        var restOverDetail: String
        /// "142 bpm · zone 3", where a sensor is on.
        var heart: String?
        /// The rest counting down, from `restStart` to `restEnd`; both nil between rests.
        var restStart: Date?
        var restEnd: Date?

        /// Where the workout stands at `now`: the activity is drawn ahead of time, so it works this out itself.
        func phase(at now: Date) -> WorkoutPhase {
            guard let restEnd else { return .training }
            return now < restEnd ? .resting : .restOver
        }

        /// The rest still to come, as a range for the countdown text and the draining ring.
        var restRange: ClosedRange<Date>? {
            guard let restStart, let restEnd, restStart < restEnd else { return nil }
            return restStart...restEnd
        }
    }
}

enum WorkoutPhase {
    case training, resting, restOver
}

extension WorkoutActivityAttributes {
    var accentColor: Color { Color(argb: accent) }
    var accent2Color: Color { Color(argb: accent2) }
}

extension Color {
    /// A colour from 0xAARRGGBB, as Compose writes one.
    init(argb: UInt32) {
        self.init(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }
}
