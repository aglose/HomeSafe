package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import kotlin.math.max
import kotlin.math.min

/** How a plate shows: a muscle the door works, one that helps it, or the body around them. */
private enum class Glow { LIT, HALF, FAINT }

/** A muscle as a closed path in its drawing's units, drawn as it is and, when [mirrored], again on the other side. */
private class Plate(val glow: Glow, val path: String, val mirrored: Boolean)

private fun one(glow: Glow, path: String) = Plate(glow, path, mirrored = false)

private fun pair(glow: Glow, path: String) = Plate(glow, path, mirrored = true)

/** What a door shows: muscle plates, or a figure in thick strokes. */
private sealed interface Art

/**
 * Muscles as plates, seen through the square [size] units across from [left], [top]. Plates are
 * painted in order and each cuts a [seam] (in dp) out of those under it, so neighbours only need
 * to overlap. A mirrored plate is mirrored about x = [mirrorAt]. The body around the muscles
 * shows at [faint]; [shadow] puts a soft dark shadow under the lit ones, for a pale card.
 */
private class Drawing(
    val left: Float,
    val top: Float,
    val size: Float,
    val mirrorAt: Float?,
    val seam: Float,
    val plates: List<Plate>,
    val faint: Float = 0.18f,
    val shadow: Float = 0f,
) : Art

/** A pictogram in a box 100 on a side: a head and limbs as round-capped strokes, each [Limb] its points and width. */
private class Figure(val head: Offset, val headRadius: Float, val limbs: List<Limb>) : Art

private class Limb(val width: Float, vararg val points: Float)

/**
 * The part of the body a door works, as white muscle plates with a soft light from above: the
 * pecs for Chest, the whole back for Back, the quads for Legs, one shoulder for Shoulders and a
 * flexed arm for Arms, the worked muscles lit and the body around them faint. Runs is a runner
 * in mid-stride. The back and the arm are traced from anatomy drawings.
 */
internal enum class BodyOutline(private val art: Art) {
    /** The pecs, front delts half-lit, over the front of the torso. */
    CHEST(
        Drawing(
            left = 30f,
            top = 56f,
            size = 140f,
            mirrorAt = 100f,
            seam = 1.9f,
            plates = listOf(
                // head
                one(Glow.FAINT, "M100,10 C112,10 120,20 120,33 C120,46 112,57 100,57 C88,57 80,46 80,33 C80,20 88,10 100,10 Z"),
                // neck
                pair(Glow.FAINT, "M89,52 L89,74 L100,76 L100,52 Z"),
                // upper trapezius
                pair(Glow.FAINT, "M90,62 C86,70 76,77 60,82 C70,86 80,86 88,82 C92,76 93,70 92,62 Z"),
                // obliques
                pair(Glow.FAINT, "M63,124 C64,150 67,178 70,202 C72,214 76,226 86,238 L88,140 C80,134 70,128 63,124 Z"),
                // abs
                pair(Glow.FAINT, "M84,138 L100,138 L100,164 L84,166 Z"),
                // abs
                pair(Glow.FAINT, "M84,166 L100,164 L100,190 L85,192 Z"),
                // abs
                pair(Glow.FAINT, "M85,192 L100,190 L100,216 L86,218 Z"),
                // pecs
                pair(Glow.LIT, "M100,90 C94,86 84,82 72,83 C65,85 60,91 56,100 C57,110 60,118 65,125 C72,133 83,138 92,137 C97,136 100,133 100,130 Z"),
                // biceps
                pair(Glow.FAINT, "M47,124 C36,140 31,164 34,190 C38,199 49,201 54,194 C59,174 62,150 60,130 C57,123 51,121 47,124 Z"),
                // forearm
                pair(Glow.FAINT, "M36,200 C29,220 26,246 28,272 L41,274 C47,250 53,226 54,202 C48,196 40,196 36,200 Z"),
                // deltoid
                pair(Glow.HALF, "M72,82 C58,74 43,80 40,98 C38,112 42,128 50,144 C53,130 57,116 64,104 C70,96 74,89 72,82 Z"),
            ),
        ),
    ),

    /** The whole back, every major muscle lit: traps, rear delts, the shoulder blades' muscles, lats and spinal erectors. */
    BACK(
        Drawing(
            left = 91f,
            top = 82f,
            size = 200f,
            mirrorAt = 191f,
            seam = 2f,
            plates = listOf(
                // head
                one(Glow.FAINT, "M191,34 C195.7,34 201.3,35 205,38 C208.7,41 211.5,47 213,52 C214.5,57 214.8,62.7 214,68 C213.2,73.3 211.8,80 208,84 C204.2,88 196.7,92 191,92 C185.3,92 177.8,88 174,84 C170.2,80 168.8,73.3 168,68 C167.2,62.7 167.5,57 169,52 C170.5,47 173.3,41 177,38 C180.7,35 186.3,34 191,34 Z"),
                // neck
                pair(Glow.FAINT, "M169,84 C172.3,81.3 187.3,77.3 191,84 C194.7,90.7 193.2,119 191,124 C188.8,129 181.3,118 178,114 C174.7,110 172.5,105 171,100 C169.5,95 165.7,86.7 169,84 Z"),
                // glutes
                pair(Glow.FAINT, "M148,266 C150.8,262.7 155.3,264 160,264 C164.7,264 170.8,264.7 176,266 C181.2,267.3 188.5,266.3 191,272 C193.5,277.7 199.5,295.3 191,300 C182.5,304.7 148,302.7 140,300 C132,297.3 141.7,289.7 143,284 C144.3,278.3 145.2,269.3 148,266 Z"),
                // spinal erectors
                pair(Glow.LIT, "M191,196 C191.7,207 192.8,259.7 191,272 C189.2,284.3 183.8,271.3 180,270 C176.2,268.7 169.3,267.3 168,264 C166.7,260.7 170.3,255 172,250 C173.7,245 176.2,239.3 178,234 C179.8,228.7 181.5,222.7 183,218 C184.5,213.3 185.7,209.7 187,206 C188.3,202.3 190.3,185 191,196 Z"),
                // obliques
                pair(Glow.HALF, "M137,222 C137.8,222.2 139.7,232 141,237 C142.3,242 143.8,247.2 145,252 C146.2,256.8 148.8,263.3 148,266 C147.2,268.7 142.2,268 140,268 C137.8,268 135.8,269 135,266 C134.2,263 134.8,255 135,250 C135.2,245 135.7,240.7 136,236 C136.3,231.3 136.2,221.8 137,222 Z"),
                // lats
                pair(Glow.LIT, "M128,178 C130.3,176.8 139,182 144,183 C149,184 154.3,184.2 158,184 C161.7,183.8 163.8,183.3 166,182 C168.2,180.7 169.7,176 171,176 C172.3,176 172.5,179.5 174,182 C175.5,184.5 178,188 180,191 C182,194 185.2,196.5 186,200 C186.8,203.5 185.8,208.5 185,212 C184.2,215.5 183.2,217.3 181,221 C178.8,224.7 175.2,229.8 172,234 C168.8,238.2 164.8,242.3 162,246 C159.2,249.7 157.3,252.7 155,256 C152.7,259.3 149.7,266.7 148,266 C146.3,265.3 146.2,256.8 145,252 C143.8,247.2 142.3,242 141,237 C139.7,232 138.3,227.5 137,222 C135.7,216.5 134.2,209.3 133,204 C131.8,198.7 130.8,194.3 130,190 C129.2,185.7 125.7,179.2 128,178 Z"),
                // teres major
                pair(Glow.LIT, "M128,174 C127.7,171.8 134.3,171.2 138,171 C141.7,170.8 146,172.2 150,173 C154,173.8 158.7,175.8 162,176 C165.3,176.2 168.2,173.2 170,174 C171.8,174.8 173.5,178.7 173,181 C172.5,183.3 170.2,186.8 167,188 C163.8,189.2 158.5,188.7 154,188 C149.5,187.3 144.3,186.3 140,184 C135.7,181.7 128.3,176.2 128,174 Z"),
                // infraspinatus
                pair(Glow.LIT, "M124,162 C123.8,159.7 130.2,156.5 134,154 C137.8,151.5 142.8,149 147,147 C151.2,145 156.2,141.2 159,142 C161.8,142.8 162.5,148.7 164,152 C165.5,155.3 167.2,158.8 168,162 C168.8,165.2 170.3,168.8 169,171 C167.7,173.2 163.5,174.7 160,175 C156.5,175.3 152.2,174.2 148,173 C143.8,171.8 139,169.8 135,168 C131,166.2 124.2,164.3 124,162 Z"),
                // lower trapezius
                pair(Glow.LIT, "M191,158 C189.2,148.3 183.8,154 180,152 C176.2,150 171.5,147.8 168,146 C164.5,144.2 159.7,140 159,141 C158.3,142 162.5,148.3 164,152 C165.5,155.7 166.3,158.8 168,163 C169.7,167.2 172,172.5 174,177 C176,181.5 178,186.2 180,190 C182,193.8 184.2,196.7 186,200 C187.8,203.3 190.2,217 191,210 C191.8,203 192.8,167.7 191,158 Z"),
                // middle trapezius
                pair(Glow.LIT, "M191,124 C188.5,118.5 181.5,124.3 176,125 C170.5,125.7 164,127.3 158,128 C152,128.7 145.7,129 140,129 C134.3,129 124,127.2 124,128 C124,128.8 134.7,131.8 140,134 C145.3,136.2 151,138.7 156,141 C161,143.3 165.7,146 170,148 C174.3,150 178.5,151.3 182,153 C185.5,154.7 189.5,162.8 191,158 C192.5,153.2 193.5,129.5 191,124 Z"),
                // upper trapezius
                pair(Glow.LIT, "M191,88 C189.5,82.3 184.7,87.7 182,90 C179.3,92.3 177.7,98.7 175,102 C172.3,105.3 169.5,107.8 166,110 C162.5,112.2 158.7,113.2 154,115 C149.3,116.8 143,119 138,121 C133,123 123.7,125.7 124,127 C124.3,128.3 134.3,128.8 140,129 C145.7,129.2 152,128.7 158,128 C164,127.3 170.5,125.7 176,125 C181.5,124.3 188.5,130.2 191,124 C193.5,117.8 192.5,93.7 191,88 Z"),
                // triceps, outer head
                pair(Glow.FAINT, "M104,174 C105.7,171 106.2,172.5 108,172 C109.8,171.5 114.2,168 115,171 C115.8,174 113.7,182.7 113,190 C112.3,197.3 111.7,206.7 111,215 C110.3,223.3 109.8,231.8 109,240 C108.2,248.2 108.8,260 106,264 C103.2,268 94.2,268 92,264 C89.8,260 92.5,248.2 93,240 C93.5,231.8 94.2,223.3 95,215 C95.8,206.7 96.5,196.8 98,190 C99.5,183.2 102.3,177 104,174 Z"),
                // triceps, long head
                pair(Glow.FAINT, "M115,171 C116.5,168 119.8,171 122,172 C124.2,173 127,173.7 128,177 C129,180.3 128.3,186.8 128,192 C127.7,197.2 126.7,203 126,208 C125.3,213 124.8,217 124,222 C123.2,227 122,233 121,238 C120,243 118.8,247.7 118,252 C117.2,256.3 118,262 116,264 C114,266 107.2,268 106,264 C104.8,260 108.2,248.2 109,240 C109.8,231.8 110.3,223.3 111,215 C111.7,206.7 112.3,197.3 113,190 C113.7,182.7 113.5,174 115,171 Z"),
                // side delt
                pair(Glow.HALF, "M122,128 C121.3,126.3 114.7,128.5 112,130 C109.3,131.5 107.5,134.3 106,137 C104.5,139.7 103.8,142.8 103,146 C102.2,149.2 101.3,152.8 101,156 C100.7,159.2 100.5,162 101,165 C101.5,168 102.8,172.7 104,174 C105.2,175.3 106.7,176.2 108,173 C109.3,169.8 110.7,160.5 112,155 C113.3,149.5 114.3,144.5 116,140 C117.7,135.5 122.7,129.7 122,128 Z"),
                // rear delt
                pair(Glow.LIT, "M122,128 C124.7,126.8 128.3,131.5 132,133 C135.7,134.5 140,135.5 144,137 C148,138.5 155.7,140 156,142 C156.3,144 149.3,146.7 146,149 C142.7,151.3 139,154 136,156 C133,158 131,159.2 128,161 C125,162.8 121.3,165 118,167 C114.7,169 109,175 108,173 C107,171 110.7,160.5 112,155 C113.3,149.5 114.3,144.5 116,140 C117.7,135.5 119.3,129.2 122,128 Z"),
            ),
        ),
    ),

    /** The quads and adductors, from the front. */
    LEGS(
        Drawing(
            left = 40f,
            top = 220f,
            size = 136f,
            mirrorAt = 100f,
            seam = 1.9f,
            plates = listOf(
                // obliques
                pair(Glow.FAINT, "M63,124 C64,150 67,178 70,202 C72,214 76,226 86,238 L88,140 C80,134 70,128 63,124 Z"),
                // abs
                pair(Glow.FAINT, "M85,192 L100,190 L100,216 L86,218 Z"),
                // abs
                pair(Glow.FAINT, "M86,218 L100,216 L100,256 C95,252 90,242 86,230 Z"),
                // forearm
                pair(Glow.FAINT, "M36,200 C29,220 26,246 28,272 L41,274 C47,250 53,226 54,202 C48,196 40,196 36,200 Z"),
                // hand
                pair(Glow.FAINT, "M27,276 C24,288 26,302 32,308 C38,310 42,304 42,296 C43,288 42,280 41,276 Z"),
                // hip flexor
                pair(Glow.HALF, "M68,226 C63,236 62,248 64,258 L84,252 C82,244 80,236 78,230 Z"),
                // quads
                pair(Glow.LIT, "M63,238 C57,264 57,298 66,324 C72,332 80,332 85,325 C87,300 88,272 90,252 C84,244 74,238 63,238 Z"),
                // quads
                pair(Glow.LIT, "M75,244 C70,264 71,292 77,318 C79,323 84,323 86,318 C89,292 89,266 85,246 C82,242 78,242 75,244 Z"),
                // adductors
                pair(Glow.LIT, "M86,240 L99,252 C99,264 97,276 94,284 C91,270 89,256 86,240 Z"),
                // quads
                pair(Glow.LIT, "M85,325 C86,304 89,288 94,282 C98,294 98,312 93,325 C90,331 87,331 85,325 Z"),
                // knee
                pair(Glow.FAINT, "M70,332 C70,345 76,351 83,351 C90,351 94,344 93,332 Z"),
                // calf
                pair(Glow.FAINT, "M69,352 C61,368 61,392 69,414 L76,414 C74,392 74,372 78,354 Z"),
                // shin
                pair(Glow.FAINT, "M78,354 C76,374 76,396 78,416 L88,416 C92,396 95,372 92,352 C87,348 82,348 78,354 Z"),
            ),
        ),
    ),

    /** One shoulder close up, front and side delts lit, clear of a small door's title. */
    SHOULDERS(
        Drawing(
            left = 88f,
            top = 62f,
            size = 98f,
            mirrorAt = 100f,
            faint = 0.25f,
            seam = 2f,
            shadow = 0.5f,
            plates = listOf(
                // neck
                pair(Glow.FAINT, "M89,52 L89,74 L100,76 L100,52 Z"),
                // upper trapezius
                pair(Glow.HALF, "M90,58 C86,68 76,76 58,80 C70,85 82,86 90,82 C94,76 95,68 94,58 Z"),
                // pecs
                pair(Glow.FAINT, "M100,90 C94,86 84,82 72,83 C65,85 60,91 56,100 C57,110 60,118 65,125 C72,133 83,138 92,137 C97,136 100,133 100,130 Z"),
                // biceps
                pair(Glow.HALF, "M44,122 C34,140 30,164 33,190 C37,199 49,201 54,194 C60,174 63,150 61,128 C57,120 49,118 44,122 Z"),
                // side delt
                pair(Glow.LIT, "M56,74 C55.2,72.8 49.2,72.3 46,73 C42.8,73.7 39.7,75.5 37,78 C34.3,80.5 31.7,84 30,88 C28.3,92 27.2,97 27,102 C26.8,107 27.7,113 29,118 C30.3,123 32.7,127.7 35,132 C37.3,136.3 40.3,141 43,144 C45.7,147 50,152.3 51,150 C52,147.7 49.7,136.3 49,130 C48.3,123.7 47.3,118 47,112 C46.7,106 46.3,99.3 47,94 C47.7,88.7 49.5,83.3 51,80 C52.5,76.7 56.8,75.2 56,74 Z"),
                // front delt
                pair(Glow.LIT, "M56,74 C58.2,73.2 61.5,74.2 64,75 C66.5,75.8 69,77.2 71,79 C73,80.8 75.5,83.3 76,86 C76.5,88.7 75.3,91.8 74,95 C72.7,98.2 70,101.3 68,105 C66,108.7 63.8,112.3 62,117 C60.2,121.7 58.8,127.5 57,133 C55.2,138.5 52.3,150.5 51,150 C49.7,149.5 49.7,136.3 49,130 C48.3,123.7 47.3,118 47,112 C46.7,106 46.3,99.3 47,94 C47.7,88.7 49.5,83.3 51,80 C52.5,76.7 53.8,74.8 56,74 Z"),
            ),
        ),
    ),

    /** A flexed arm, side on: biceps, triceps and the forearm's extensors and flexors lit. */
    ARMS(
        Drawing(
            left = 0f,
            top = -40f,
            size = 372f,
            mirrorAt = null,
            seam = 2f,
            plates = listOf(
                // triceps, long head
                one(Glow.LIT, "M18,212 C18.3,210 29.3,220.3 40,222 C50.7,223.7 62,220.3 82,222 C102,223.7 135.3,229.5 160,232 C184.7,234.5 206.5,236.2 230,237 C253.5,237.8 282.7,237.8 301,237 C319.3,236.2 328.2,233.8 340,232 C351.8,230.2 366.7,217.3 372,226 C377.3,234.7 379,274 372,284 C365,294 343.7,285 330,286 C316.3,287 305,289.3 290,290 C275,290.7 258.3,290.3 240,290 C221.7,289.7 200,290.3 180,288 C160,285.7 138.3,282 120,276 C101.7,270 83.7,259 70,252 C56.3,245 46.7,240.7 38,234 C29.3,227.3 17.7,214 18,212 Z"),
                // biceps
                one(Glow.LIT, "M82,222 C80.3,218.5 96.2,211.8 105,206 C113.8,200.2 125,191.5 135,187 C145,182.5 155.3,181 165,179 C174.7,177 185.2,175.5 193,175 C200.8,174.5 205.8,175 212,176 C218.2,177 224.7,178.7 230,181 C235.3,183.3 237.7,186 244,190 C250.3,194 260.3,199.7 268,205 C275.7,210.3 284.5,216.7 290,222 C295.5,227.3 311,234.3 301,237 C291,239.7 253.5,238.8 230,238 C206.5,237.2 179.2,233.8 160,232 C140.8,230.2 128,228.7 115,227 C102,225.3 83.7,225.5 82,222 Z"),
                // deltoid
                one(Glow.HALF, "M244,190 C242.7,186.7 253.3,182.2 258,179 C262.7,175.8 265.7,173.7 272,171 C278.3,168.3 287.8,165.3 296,163 C304.2,160.7 312.8,158.5 321,157 C329.2,155.5 336.5,154.7 345,154 C353.5,153.3 367.5,141 372,153 C376.5,165 377.3,214.5 372,226 C366.7,237.5 350,223.7 340,222 C330,220.3 320.3,218.3 312,216 C303.7,213.7 297.7,210.8 290,208 C282.3,205.2 273.7,202 266,199 C258.3,196 245.3,193.3 244,190 Z"),
                // forearm extensors
                one(Glow.LIT, "M6,196 C3.5,191.3 9.8,192.5 15,186 C20.2,179.5 26.8,168.7 37,157 C47.2,145.3 62,130.3 76,116 C90,101.7 106,85 121,71 C136,57 154.3,41.2 166,32 C177.7,22.8 184.7,19.2 191,16 C197.3,12.8 199.2,7.2 204,13 C208.8,18.8 218.8,45.2 220,51 C221.2,56.8 215.2,47.3 211,48 C206.8,48.7 202.2,50.7 195,55 C187.8,59.3 176,66.5 168,74 C160,81.5 153.3,90.7 147,100 C140.7,109.3 135.3,120 130,130 C124.7,140 120,150 115,160 C110,170 105,181.3 100,190 C95,198.7 91.7,207 85,212 C78.3,217 69.2,219.7 60,220 C50.8,220.3 39,218 30,214 C21,210 8.5,200.7 6,196 Z"),
                // forearm flexors
                one(Glow.LIT, "M211,48 C211,48.2 202.2,50.7 195,55 C187.8,59.3 176,66.5 168,74 C160,81.5 153.3,90.7 147,100 C140.7,109.3 135.3,120 130,130 C124.7,140 120,150 115,160 C110,170 105,181.3 100,190 C95,198.7 85,209.5 85,212 C85,214.5 94.5,207.7 100,205 C105.5,202.3 112.3,199.7 118,196 C123.7,192.3 130.3,189 134,183 C137.7,177 137.8,168 140,160 C142.2,152 144.8,143.3 147,135 C149.2,126.7 151,117.5 153,110 C155,102.5 156.2,96 159,90 C161.8,84 166.7,78.3 170,74 C173.3,69.7 174.8,67.3 179,64 C183.2,60.7 189.7,56.7 195,54 C200.3,51.3 211,47.8 211,48 Z"),
                // hand
                one(Glow.FAINT, "M204,13 C205.8,5.5 220,7.5 231,6 C242,4.5 258.2,4.2 270,4 C281.8,3.8 294,2.5 302,5 C310,7.5 314.2,11.5 318,19 C321.8,26.5 323.8,41.3 325,50 C326.2,58.7 326.2,62.7 325,71 C323.8,79.3 321.8,95.8 318,100 C314.2,104.2 306.7,99.3 302,96 C297.3,92.7 293.2,84.2 290,80 C286.8,75.8 288.5,74.2 283,71 C277.5,67.8 265.7,64.3 257,61 C248.3,57.7 237.2,52.7 231,51 C224.8,49.3 224.5,57.3 220,51 C215.5,44.7 202.2,20.5 204,13 Z"),
            ),
        ),
    ),

    /** A runner in mid-stride. */
    RUNS(
        Figure(
            head = Offset(62f, 14f),
            headRadius = 7f,
            limbs = listOf(
                // body, then the arms, then the legs
                Limb(10f, 57f, 27f, 48f, 52f),
                Limb(7f, 56f, 32f, 66f, 42f, 74f, 34f),
                Limb(7f, 55f, 32f, 44f, 40f, 36f, 50f),
                Limb(9f, 48f, 54f, 66f, 62f, 60f, 82f),
                Limb(9f, 47f, 55f, 40f, 74f, 22f, 80f),
            ),
        ),
    ),
    ;

    /** The plates' paths and fills, or the figure's limbs, built the first time the outline is drawn. */
    private val paths: List<Piece> by lazy { pieces(art) }

    /** Draws the outline as large as fits and centred, at [alpha]. */
    fun draw(scope: DrawScope, alpha: Float) {
        when (val art = art) {
            is Drawing -> scope.drawPlates(art, paths, alpha)
            is Figure -> scope.drawFigure(art, paths, alpha)
        }
    }

    companion object {
        fun of(focus: WorkoutFocus): BodyOutline = when (focus) {
            WorkoutFocus.CHEST -> CHEST
            WorkoutFocus.BACK -> BACK
            WorkoutFocus.LEGS -> LEGS
            WorkoutFocus.SHOULDERS -> SHOULDERS
            WorkoutFocus.ARMS -> ARMS
        }
    }
}

/** A plate or a limb ready to draw: its path, how it shows, and for a plate its fill. */
private class Piece(val path: Path, val glow: Glow, val brush: Brush, val width: Float = 0f)

private fun pieces(art: Art): List<Piece> = when (art) {
    is Drawing -> art.plates.flatMap { plate ->
        val path = PathParser().parsePathString(plate.path).toPath()
        if (plate.mirrored && art.mirrorAt != null) {
            val mirror = PathParser().parsePathString(plate.path).toPath()
            // x becomes 2 × mirrorAt − x: the other side.
            val flip = Matrix()
            flip.translate(art.mirrorAt * 2)
            flip.scale(-1f, 1f)
            mirror.transform(flip)
            listOf(Piece(path, plate.glow, fill(path, plate.glow, art.faint)), Piece(mirror, plate.glow, fill(mirror, plate.glow, art.faint)))
        } else {
            listOf(Piece(path, plate.glow, fill(path, plate.glow, art.faint)))
        }
    }

    is Figure -> art.limbs.map { limb ->
        val path = Path()
        path.moveTo(limb.points[0], limb.points[1])
        for (i in 2 until limb.points.size step 2) path.lineTo(limb.points[i], limb.points[i + 1])
        Piece(path, Glow.LIT, SolidColor(Color.White), limb.width)
    }
}

/** A lit plate is brightest a little below its top and dims toward its edges, a helper the same but half as bright. */
private fun fill(path: Path, glow: Glow, faint: Float): Brush {
    if (glow == Glow.FAINT) return SolidColor(Color.White.copy(alpha = faint))
    val bounds = path.getBounds()
    val (from, to) = if (glow == Glow.LIT) 1f to 0.65f else 0.52f to 0.36f
    return Brush.radialGradient(
        0f to Color.White.copy(alpha = from),
        1f to Color.White.copy(alpha = to),
        center = Offset(bounds.left + bounds.width * 0.5f, bounds.top + bounds.height * 0.28f),
        radius = max(bounds.width, bounds.height) * 0.85f,
    )
}

/** Scales the drawing's square to fit, centred, and clips to it. */
private inline fun DrawScope.inBox(left: Float, top: Float, box: Float, block: DrawScope.(scale: Float) -> Unit) {
    val scale = min(size.width, size.height) / box
    translate((size.width - box * scale) / 2, (size.height - box * scale) / 2) {
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(-left, -top)
        }) {
            clipRect(left, top, left + box, top + box) { block(scale) }
        }
    }
}

private fun DrawScope.drawPlates(drawing: Drawing, pieces: List<Piece>, alpha: Float) = inBox(drawing.left, drawing.top, drawing.size) { scale ->
    val bounds = Rect(drawing.left, drawing.top, drawing.left + drawing.size, drawing.top + drawing.size)
    val canvas = drawContext.canvas
    val glowing = pieces.filter { it.glow != Glow.FAINT }
    val spread = 2.2.dp.toPx() / scale
    if (drawing.shadow > 0f) {
        // A soft dark shadow a little below the lit muscles.
        canvas.saveLayer(bounds, Paint().apply { this.alpha = drawing.shadow * alpha })
        translate(0f, 1.2.dp.toPx() / scale) {
            glowing.forEach {
                // Darkest under the lit muscles, and fading out past their edges in two steps.
                val dark = if (it.glow == Glow.LIT) 1f else 0.5f
                drawPath(it.path, Color.Black.copy(alpha = 0.12f * dark), style = Stroke(spread * 3f, join = StrokeJoin.Round))
                drawPath(it.path, Color.Black.copy(alpha = 0.25f * dark), style = Stroke(spread * 1.5f, join = StrokeJoin.Round))
                drawPath(it.path, Color.Black.copy(alpha = 0.6f * dark))
            }
        }
        canvas.restore()
    }
    // A faint halo round the lit muscles.
    canvas.saveLayer(bounds, Paint().apply { this.alpha = 0.08f * alpha })
    glowing.forEach { if (it.glow == Glow.LIT) drawPath(it.path, Color.White, style = Stroke(spread * 2f, join = StrokeJoin.Round)) }
    canvas.restore()
    // Each plate replaces what is under it, then its seam is cleared out.
    canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
    val seam = Stroke(drawing.seam.dp.toPx() / scale, join = StrokeJoin.Round)
    pieces.forEach {
        drawPath(it.path, it.brush, blendMode = BlendMode.Src)
        drawPath(it.path, Color.Black, style = seam, blendMode = BlendMode.Clear)
    }
    canvas.restore()
}

private fun DrawScope.drawFigure(figure: Figure, limbs: List<Piece>, alpha: Float) = inBox(0f, 0f, FIGURE_BOX) { scale ->
    val bounds = Rect(0f, 0f, FIGURE_BOX, FIGURE_BOX)
    val canvas = drawContext.canvas
    val spread = 2.dp.toPx() / scale
    // The same figure, wider and faint, as a halo.
    canvas.saveLayer(bounds, Paint().apply { this.alpha = 0.16f * alpha })
    drawCircle(Color.White, figure.headRadius + spread, figure.head)
    limbs.forEach { drawPath(it.path, Color.White, style = Stroke(it.width + spread * 2, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    canvas.restore()
    drawCircle(Color.White.copy(alpha = alpha), figure.headRadius, figure.head)
    canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
    limbs.forEach { drawPath(it.path, Color.White, style = Stroke(it.width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    canvas.restore()
}

private const val FIGURE_BOX = 100f

/**
 * [outline] drawn as large as fits and centred, in white with a soft halo, so it reads over a
 * card's moving fibres without competing with the words. Decoration: it says nothing to a screen
 * reader, the card's own label does.
 */
@Composable
internal fun BodyOutlineDrawing(outline: BodyOutline, modifier: Modifier = Modifier, alpha: Float = 1f) {
    // Drawn once into a picture of its own and that picture put up after: it sits over fibres that move
    // every frame, and its halo and seams are a few passes off screen each, too many to draw again each frame.
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { outline.draw(this, alpha) }
}
