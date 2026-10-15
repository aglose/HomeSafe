package com.meticulouscreations.homesafe.ui.notification

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status-bar icons under `androidMain/res/drawable` are written from [NotificationGlyph], so
 * the icon in the status bar and the picture in the shade can't come apart. This fails when a
 * drawable has drifted from its glyph; to write them again after changing one, run
 *
 * `UPDATE_NOTIFICATION_ICONS=1 ./gradlew :shared:testAndroidHostTest --tests '*NotificationGlyphsTest*' --rerun`
 */
class NotificationGlyphsTest {
    // Gradle runs a module's tests from the module's own directory.
    private val drawables = File("src/androidMain/res/drawable")

    @Test
    fun everyGlyphIsTheDrawableAndroidShows() {
        val update = System.getenv("UPDATE_NOTIFICATION_ICONS") == "1"
        NotificationGlyph.entries.forEach { glyph ->
            val file = File(drawables, "${glyph.resourceName}.xml")
            val expected = vectorXml(glyph)
            if (update) file.writeText(expected)
            assertTrue(file.exists(), "${file.path} is missing: run with UPDATE_NOTIFICATION_ICONS=1")
            assertEquals(expected, file.readText(), "${file.path} has drifted from NotificationGlyph.${glyph.name}: run with UPDATE_NOTIFICATION_ICONS=1")
        }
    }

    @Test
    fun noNotificationDrawableIsLeftWithoutAGlyph() {
        val known = NotificationGlyph.entries.map { "${it.resourceName}.xml" }.toSet()
        val strays = drawables.list().orEmpty().filter { it.startsWith("ic_notification_") && it !in known }
        assertEquals(emptyList(), strays)
    }

    @Test
    fun resourceNamesAreDistinct() {
        assertEquals(NotificationGlyph.entries.size, NotificationGlyph.entries.map { it.resourceName }.toSet().size)
    }

    /** [glyph] as an Android vector drawable: white on nothing, which is all the status bar keeps of an icon. */
    private fun vectorXml(glyph: NotificationGlyph): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<!-- Written from NotificationGlyph.${glyph.name} (ui/notification/NotificationGlyphs.kt) by NotificationGlyphsTest: change the glyph there, not this file. -->")
        appendLine("""<vector xmlns:android="http://schemas.android.com/apk/res/android"""")
        appendLine("""    android:width="24dp"""")
        appendLine("""    android:height="24dp"""")
        appendLine("""    android:viewportWidth="24"""")
        appendLine("""    android:viewportHeight="24"""")
        appendLine("""    android:tint="#FFFFFFFF">""")
        glyph.layers.forEach { layer ->
            appendLine("    <path")
            if (layer.stroke > 0f) {
                appendLine("""        android:strokeColor="#FFFFFFFF"""")
                appendLine("""        android:strokeWidth="${layer.stroke}"""")
                appendLine("""        android:strokeLineCap="round"""")
                appendLine("""        android:strokeLineJoin="round"""")
            } else {
                appendLine("""        android:fillColor="#FFFFFFFF"""")
                if (layer.evenOdd) appendLine("""        android:fillType="evenOdd"""")
            }
            appendLine("""        android:pathData="${layer.path}" />""")
        }
        appendLine("</vector>")
    }
}
