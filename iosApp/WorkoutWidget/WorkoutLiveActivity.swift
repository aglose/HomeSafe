import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The widget extension's one widget: the workout in progress as a Live Activity.
@main
struct WorkoutWidgetBundle: WidgetBundle {
    var body: some Widget {
        WorkoutLiveActivity()
    }
}

/// The workout in progress on the Lock Screen and in the Dynamic Island, drawn from what the app
/// last handed over (`WorkoutActivityAttributes`). The stopwatch, the rest counting down and the
/// ring draining are drawn by the system from dates, so they keep time with the app asleep; when
/// a rest runs out the activity goes stale (its `staleDate` is the rest's end) and is drawn
/// saying so. Between rests it reads like the workout's own page: what it has come to and what
/// comes next. A tap opens that page; the buttons change the rest without opening anything.
struct WorkoutLiveActivity: Widget {
    static let openURL = URL(string: "homesafe://fitness?page=workout")

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WorkoutActivityAttributes.self) { context in
            WorkoutLockScreenView(attributes: context.attributes, state: context.state, phase: context.phase)
                .activityBackgroundTint(Color.black.opacity(0.82))
                .activitySystemActionForegroundColor(.white)
                .widgetURL(Self.openURL)
        } dynamicIsland: { context in
            let attributes = context.attributes
            let state = context.state
            let phase = context.phase
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    PhaseBadge(attributes: attributes, state: state, phase: phase, size: 46)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    BigClock(attributes: attributes, state: state, phase: phase, width: 82)
                        .font(.system(size: 30, weight: .semibold, design: .rounded))
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(headline(state, phase))
                            .font(.headline)
                            .lineLimit(1)
                        Text(state.title)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        DetailLines(state: state, phase: phase)
                        if phase == .resting {
                            RestButtons(attributes: attributes)
                        }
                    }
                    .padding(.horizontal, 4)
                }
            } compactLeading: {
                CompactMark(attributes: attributes, state: state, phase: phase)
            } compactTrailing: {
                CompactClock(attributes: attributes, state: state, phase: phase)
            } minimal: {
                CompactMark(attributes: attributes, state: state, phase: phase)
            }
            .keylineTint(attributes.accentColor)
            .widgetURL(Self.openURL)
        }
    }
}

private func headline(_ state: WorkoutActivityAttributes.ContentState, _ phase: WorkoutPhase) -> String {
    switch phase {
    case .training: return state.title
    case .resting: return state.resting
    case .restOver: return state.restOver
    }
}

private func detail(_ state: WorkoutActivityAttributes.ContentState, _ phase: WorkoutPhase) -> String {
    switch phase {
    case .training: return state.trainingDetail
    case .resting: return state.restingDetail
    case .restOver: return state.restOverDetail
    }
}

extension ActivityViewContext where Attributes == WorkoutActivityAttributes {
    /// Where the workout stands as this is drawn: once the activity has gone stale the rest it was drawn for is over.
    var phase: WorkoutPhase {
        if isStale, state.restEnd != nil { return .restOver }
        return state.phase(at: Date())
    }
}

/// The Lock Screen's (and a banner's) whole card.
struct WorkoutLockScreenView: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState
    let phase: WorkoutPhase

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 12) {
                PhaseBadge(attributes: attributes, state: state, phase: phase, size: 46)
                VStack(alignment: .leading, spacing: 1) {
                    Text(headline(state, phase))
                        .font(.headline)
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    if phase != .training {
                        Text(state.title)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.55))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 6)
                BigClock(attributes: attributes, state: state, phase: phase, width: 92)
                    .font(.system(size: 32, weight: .semibold, design: .rounded))
            }
            DetailLines(state: state, phase: phase)
            if phase == .resting {
                RestButtons(attributes: attributes)
            }
        }
        .padding(16)
    }
}

/// The line under the headline, and the heart under that when a sensor is on.
private struct DetailLines: View {
    let state: WorkoutActivityAttributes.ContentState
    let phase: WorkoutPhase

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(detail(state, phase))
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.78))
                .lineLimit(2)
            HStack(spacing: 6) {
                if let heart = state.heart {
                    Label(heart, systemImage: "heart.fill")
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(.pink)
                }
                Text(state.summary)
                    .foregroundStyle(.white.opacity(0.55))
            }
            .font(.caption)
            .lineLimit(1)
        }
    }
}

/// The workout's mark in its colour: a dumbbell between rests, the rest's ring draining while one
/// counts, and a filled disc once it has run out.
private struct PhaseBadge: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState
    let phase: WorkoutPhase
    let size: CGFloat

    var body: some View {
        ZStack {
            switch phase {
            case .resting:
                if let range = state.restRange {
                    ProgressView(timerInterval: range, countsDown: true) {
                        EmptyView()
                    } currentValueLabel: {
                        EmptyView()
                    }
                    .progressViewStyle(.circular)
                    .tint(attributes.accentColor)
                    .frame(width: size, height: size)
                }
                Image(systemName: "timer")
                    .font(.system(size: size * 0.36, weight: .semibold))
                    .foregroundStyle(attributes.accentColor)
            case .restOver:
                Circle()
                    .fill(LinearGradient(colors: [attributes.accentColor, attributes.accent2Color], startPoint: .topLeading, endPoint: .bottomTrailing))
                Text(attributes.labels.go)
                    .font(.system(size: size * 0.3, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.6)
            case .training:
                Circle()
                    .fill(attributes.accentColor.opacity(0.18))
                Circle()
                    .strokeBorder(attributes.accentColor.opacity(0.7), lineWidth: 2)
                Image(systemName: "dumbbell.fill")
                    .font(.system(size: size * 0.4, weight: .semibold))
                    .foregroundStyle(attributes.accentColor)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The large time: the rest counting down while one counts, go once it has run out, and between
/// rests how long the workout has been going.
private struct BigClock: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState
    let phase: WorkoutPhase
    /// A timer's text is as wide as its widest time would be unless it is told otherwise.
    let width: CGFloat

    var body: some View {
        Group {
            switch phase {
            case .resting:
                if let range = state.restRange {
                    Text(timerInterval: range, countsDown: true)
                        .foregroundStyle(.white)
                }
            case .restOver:
                Text(attributes.labels.go)
                    .foregroundStyle(attributes.accentColor)
            case .training:
                Text(attributes.startedAt, style: .timer)
                    .foregroundStyle(.white.opacity(0.9))
            }
        }
        .monospacedDigit()
        .multilineTextAlignment(.trailing)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        .frame(width: width, alignment: .trailing)
    }
}

/// The Dynamic Island's left side, at its smallest: the badge alone.
private struct CompactMark: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState
    let phase: WorkoutPhase

    var body: some View {
        switch phase {
        case .resting:
            if let range = state.restRange {
                ProgressView(timerInterval: range, countsDown: true) {
                    EmptyView()
                } currentValueLabel: {
                    Image(systemName: "timer")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(attributes.accentColor)
                }
                .progressViewStyle(.circular)
                .tint(attributes.accentColor)
                .accessibilityLabel(attributes.labels.rest)
            }
        case .restOver:
            Image(systemName: "bell.fill")
                .foregroundStyle(attributes.accentColor)
                .accessibilityLabel(state.restOver)
        case .training:
            Image(systemName: "dumbbell.fill")
                .foregroundStyle(attributes.accentColor)
                .accessibilityLabel(state.title)
        }
    }
}

/// The Dynamic Island's right side, at its smallest: the time.
private struct CompactClock: View {
    let attributes: WorkoutActivityAttributes
    let state: WorkoutActivityAttributes.ContentState
    let phase: WorkoutPhase

    var body: some View {
        switch phase {
        case .resting:
            if let range = state.restRange {
                Text(timerInterval: range, countsDown: true)
                    .monospacedDigit()
                    .foregroundStyle(attributes.accentColor)
                    .frame(width: 44)
            }
        case .restOver:
            Text(attributes.labels.go)
                .font(.body.weight(.bold))
                .foregroundStyle(attributes.accentColor)
        case .training:
            Text(attributes.startedAt, style: .timer)
                .monospacedDigit()
                .frame(width: 50)
        }
    }
}

/// Fifteen seconds less, fifteen more, and skip: run in the app, on the rest the app shows too.
private struct RestButtons: View {
    let attributes: WorkoutActivityAttributes

    var body: some View {
        HStack(spacing: 8) {
            Button(intent: AdjustRestIntent(seconds: -15)) {
                Text(attributes.labels.minusShort).frame(width: 52)
            }
            .buttonStyle(.bordered)
            .tint(.white)
            .accessibilityLabel(attributes.labels.minus)
            Button(intent: AdjustRestIntent(seconds: 15)) {
                Text(attributes.labels.plusShort).frame(width: 52)
            }
            .buttonStyle(.bordered)
            .tint(.white)
            .accessibilityLabel(attributes.labels.plus)
            Button(intent: SkipRestIntent()) {
                Label(attributes.labels.skip, systemImage: "forward.end.fill")
                    .labelStyle(.titleAndIcon)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(attributes.accentColor)
        }
        .font(.subheadline.weight(.semibold))
        .buttonBorderShape(.capsule)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
    }
}

// MARK: - Previews

extension WorkoutActivityAttributes {
    static let preview = WorkoutActivityAttributes(
        workoutId: 1,
        startedAt: Date().addingTimeInterval(-24 * 60),
        accent: 0xFFFF6A2B,
        accent2: 0xFFB3123A,
        labels: Labels(rest: "Rest", go: "Go", skip: "Skip rest", minusShort: "−15", minus: "15 seconds less rest", plusShort: "+15", plus: "15 seconds more rest", open: "Open workout")
    )
}

extension WorkoutActivityAttributes.ContentState {
    static let resting = WorkoutActivityAttributes.ContentState(
        title: "Chest day",
        summary: "6 sets · 2 records",
        resting: "Resting after Bench press",
        restOver: "Rest's up",
        trainingDetail: "Bench press · last 185 lb × 8",
        restingDetail: "Up next: Incline press · aim for 70 lb × 10",
        restOverDetail: "Bench press · last 185 lb × 8",
        heart: "128 bpm · down 24",
        restStart: Date().addingTimeInterval(-38),
        restEnd: Date().addingTimeInterval(82)
    )

    static let training = WorkoutActivityAttributes.ContentState(
        title: "Chest day",
        summary: "6 sets · 2 records",
        resting: "Resting",
        restOver: "Rest's up",
        trainingDetail: "Bench press · last 185 lb × 8",
        restingDetail: "Up next: Incline press · aim for 70 lb × 10",
        restOverDetail: "Bench press · last 185 lb × 8",
        heart: nil,
        restStart: nil,
        restEnd: nil
    )
}

#Preview("Lock Screen", as: .content, using: WorkoutActivityAttributes.preview) {
    WorkoutLiveActivity()
} contentStates: {
    WorkoutActivityAttributes.ContentState.resting
    WorkoutActivityAttributes.ContentState.training
}

#Preview("Island", as: .dynamicIsland(.expanded), using: WorkoutActivityAttributes.preview) {
    WorkoutLiveActivity()
} contentStates: {
    WorkoutActivityAttributes.ContentState.resting
    WorkoutActivityAttributes.ContentState.training
}

#Preview("Compact", as: .dynamicIsland(.compact), using: WorkoutActivityAttributes.preview) {
    WorkoutLiveActivity()
} contentStates: {
    WorkoutActivityAttributes.ContentState.resting
    WorkoutActivityAttributes.ContentState.training
}
