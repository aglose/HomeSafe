package com.meticulouscreations.homesafe.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.meticulouscreations.homesafe.ui.screens.HomeFeedAwayPreview
import com.meticulouscreations.homesafe.ui.screens.HomeFeedEmptyPreview
import com.meticulouscreations.homesafe.ui.screens.HomeFeedLoadingPreview
import com.meticulouscreations.homesafe.ui.screens.HomeFeedPreview
import com.meticulouscreations.homesafe.ui.screens.StatusBadgePreview

/*
 * The shared module's previews, drawn by Layoutlib — Android's own renderer, the one Android
 * Studio's preview pane uses — through Compose Preview Screenshot Testing:
 *
 *     ./gradlew :androidApp:updateDebugScreenshotTest    # draw them under src/screenshotTestDebug/reference/
 *     ./gradlew :androidApp:validateDebugScreenshotTest  # compare against those, HTML report in build/reports
 *
 * The tool only looks for @PreviewTest functions in an Android module's screenshotTest source
 * set, and it doesn't reach into a Kotlin Multiplatform module's common code, so each preview in
 * :shared that should be drawn here gets a one-line wrapper: the same @Preview size, and a call
 * to the shared preview. `:shared:renderPreviews` draws every shared preview on the JVM with no
 * list at all; this list is the ones worth Android's exact rendering, and they must be public
 * (compose-rules would have them private, so HomePreviews.kt says why not). See docs/ui-previews.md.
 */

private const val PHONE_WIDTH_DP = 412
private const val PHONE_HEIGHT_DP = 915

@PreviewTest
@Preview(name = "Home", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeed() = HomeFeedPreview()

@PreviewTest
@Preview(name = "Home, loading", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedLoading() = HomeFeedLoadingPreview()

@PreviewTest
@Preview(name = "Home, everyone away", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedAway() = HomeFeedAwayPreview()

@PreviewTest
@Preview(name = "Home, no cameras", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
@Composable
fun HomeFeedEmpty() = HomeFeedEmptyPreview()

@PreviewTest
@Preview(name = "Status badges")
@Composable
fun StatusBadges() = StatusBadgePreview()
