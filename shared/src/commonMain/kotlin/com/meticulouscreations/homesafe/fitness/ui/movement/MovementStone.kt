package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.FitnessTheme
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import com.meticulouscreations.homesafe.fitness.ui.shader.FitnessShader
import com.meticulouscreations.homesafe.fitness.ui.shader.fitnessShaderOrNull
import com.meticulouscreations.homesafe.fitness.ui.shader.rememberFitnessClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The stone figure: MovementStyle.MARBLE. Its shader, and the code that hands the rig to it.

/**
 * How many bones and how many rods of muscle the shader takes. Each is two four-float uniforms,
 * which with the other two makes 222: a phone drawing with OpenGL ES 3 need not take more than 224.
 */
internal const val MARBLE_BONES = 18
internal const val MARBLE_HEADS = 92

/** How much wider than its band a head's spindle is: neighbours run into one another, and what parts them is a furrow and not a gap. */
private const val GIRTH = 1.25f

/** How far past itself, in body heights, a head's light is worked out when it is working, and the rim's blue when it is not. */
private const val GLOW_REACH = 0.17f
private const val RIM_REACH = 0.03f

/** How much narrower than its middle the ends of the shader's spindle are, for a rod long for its width; and the lengths, in widths, between which that comes in. */
private const val PINCH = 0.28f
private const val PINCH_FROM = 0.5f
private const val PINCH_TO = 2.5f

/**
 * The lifter as a figure of pale stone. Nothing here is a picture: the rig's bones and muscle
 * heads arrive as rods, and each pixel works out the body's surface there and lights it: a soft
 * key light over the left shoulder, a cool fill, and a blue rim where the surface turns away.
 *
 * The surface is two things added. The flesh: every bone a round bar and every head a low lobe
 * beside it, all melted into one skin, which is what gives the body its outline. And on the flesh
 * the relief: each head a shallow swell that dies away to nothing at its border and its ends, so
 * that two heads side by side leave a furrow between them and none of them an edge. Each piece
 * gives its slope along with its height, so the surface is worked out once for a pixel and not
 * three times.
 *
 * A head that is working glows through the stone: red at its border to near white along the
 * strands down its middle, and throws that light past the body's outline, where the rim's blue
 * also spills a little way into the room.
 *
 * In pixels: `bones`/`heads` are a.xy, b.xy; `boneR` is ra, rb, unused, layer; `headR` is the
 * head's half-width at either end, how thick its lobe is, and its layer and heat together
 * (twice the layer, plus the heat 0–1). Layers are 0 the far leg, 1 the body, 2 an arm that lies
 * in front of it; a nearer layer covers a farther one and darkens it where they meet.
 */
internal const val MARBLE_SHADER = """
uniform float2 size;
uniform float unit;
uniform float4 bones[$MARBLE_BONES];
uniform float4 boneR[$MARBLE_BONES];
uniform float4 heads[$MARBLE_HEADS];
uniform float4 headR[$MARBLE_HEADS];

const float NONE = -10000.0;

// The higher of two surfaces, x a height and yz the way it rises. Where they are within k of one another the slope
// is shared between them, so that they meet in a hollow and not a crease; neither is raised by the other.
float3 melt(float3 a, float3 b, float k) {
    return float3(max(a.x, b.x), mix(b.yz, a.yz, smoothstep(-k, k, a.x - b.x)));
}

float3 glow(float heat) {
    float3 c = mix(float3(0.82, 0.15, 0.03), float3(1.0, 0.42, 0.07), smoothstep(0.08, 0.42, heat));
    c = mix(c, float3(1.0, 0.80, 0.34), smoothstep(0.45, 0.82, heat));
    return mix(c, float3(1.0, 0.97, 0.88), smoothstep(0.8, 1.05, heat));
}

// One layer's surface, lit: its flesh and relief, how deep in a furrow between heads, how far inside its outline, its heat,
// the light thrown on it, how much a nearer layer shades it, and whether it is the far side of the body.
float3 skin(float3 flesh, float3 relief, float furrow, float within, float heat, float aura, float shade, float behind) {
    float3 n = normalize(float3(-flesh.yz - relief.yz, 1.0));
    float3 key = normalize(float3(-0.46, -0.60, 0.66));
    float3 fill = normalize(float3(0.72, 0.12, 0.5));
    // A broad lamp: its light wraps a little way round.
    float lit = clamp((dot(n, key) + 0.3) / 1.3, 0.0, 1.0);
    float soft = max(dot(n, fill), 0.0);
    float3 stone = mix(float3(0.86, 0.87, 0.89), float3(0.42, 0.45, 0.52), behind);
    float3 c = stone * (0.17 + 0.8 * lit) + float3(0.30, 0.38, 0.56) * soft * 0.18;
    c += float3(1.0) * pow(max(dot(n, normalize(key + float3(0.0, 0.0, 1.0))), 0.0), 28.0) * 0.1;
    // Less light gets into the furrow round a head than onto its crown.
    c *= (1.0 - 0.5 * shade) * (1.0 - 0.2 * furrow);
    // The blue of the room, on whatever turns away, most on the side the lamp doesn't reach, and thinly along the outline.
    float turned = pow(1.0 - n.z, 3.0) * (0.3 + 0.7 * clamp(0.5 + dot(n.xy, float2(0.8, 0.45)), 0.0, 1.0));
    float rim = max(turned, 0.7 * (1.0 - smoothstep(0.0, max(unit * 0.006, 1.2), within)));
    c += float3(0.18, 0.40, 1.0) * rim * 0.95;

    // The glow sits in the stone: it takes the surface's shading a little, and adds light of its own.
    float3 hot = glow(heat);
    c = mix(c, hot * (0.6 + 0.4 * lit) * (1.0 - 0.3 * furrow), clamp(heat * 3.0, 0.0, 0.95));
    c += hot * heat * 0.2 + glow(0.4) * aura * 0.16 * (1.0 - heat);
    return clamp(c, 0.0, 1.0);
}

half4 main(float2 p) {
    // How near in height two bones melt together, a head's lobe into them (little: a lobe sunk in its limb must not show
    // through it), and one head's swell into the next (less: the furrow between them is to be seen).
    float k = unit * 0.014;
    float kl = unit * 0.006;
    float kr = unit * 0.0012;
    // Each layer's flesh and relief, how deep p is in a furrow, how far inside the outline (under zero, outside), and its heat.
    float3 flesh0 = float3(NONE, 0.0, 0.0);
    float3 flesh1 = flesh0;
    float3 flesh2 = flesh0;
    float3 relief0 = float3(0.0);
    float3 relief1 = relief0;
    float3 relief2 = relief0;
    float3 dark = float3(0.0);
    float3 inside = float3(NONE);
    float3 heat = float3(0.0);
    float aura = 0.0;

    for (int i = 0; i < $MARBLE_BONES; i++) {
        float2 pa = p - bones[i].xy;
        float2 ba = bones[i].zw - bones[i].xy;
        // A bone is every ball between its two ends: the one that stands highest over p is this far along it.
        float grow = boneR[i].y - boneR[i].x;
        float t = clamp((dot(pa, ba) + grow * boneR[i].x) / max(dot(ba, ba) - grow * grow, 0.0001), 0.0, 1.0);
        float2 q = pa - ba * t;
        float d = max(length(q), 0.001);
        float r = boneR[i].x + grow * t;
        float s = r - d;
        // Round inside its edge; past it, a slope down, so that it leaves the melt gently.
        float3 bar = float3(2.0 * s, -2.0 * q / d);
        if (s > 0.0) {
            float z = sqrt(max(s * (r + d), 0.01));
            bar = float3(z, -q / z);
        }
        float layer = boneR[i].w;
        if (layer < 0.5) { flesh0 = melt(flesh0, bar, k); inside.x = max(inside.x, s); } else if (layer < 1.5) { flesh1 = melt(flesh1, bar, k); inside.y = max(inside.y, s); } else { flesh2 = melt(flesh2, bar, k); inside.z = max(inside.z, s); }
    }

    for (int j = 0; j < $MARBLE_HEADS; j++) {
        float4 hr = headR[j];
        float wide = max(hr.x, hr.y);
        if (wide > 0.25) {
            float layer = floor(hr.w * 0.5);
            float warm = hr.w - 2.0 * layer;
            float2 pa = p - heads[j].xy;
            float2 ba = heads[j].zw - heads[j].xy;
            // A cold head matters only where it is; a warm one as far as its light carries.
            float reach = wide + unit * (warm > 0.001 ? $GLOW_REACH : $RIM_REACH);
            float2 off = abs(pa - 0.5 * ba) - 0.5 * abs(ba);
            if (off.x < reach && off.y < reach) {
                float bb = max(dot(ba, ba), 0.0001);
                float u = dot(pa, ba) / bb;
                float t = clamp(u, 0.0, 1.0);
                float2 q = pa - ba * t;
                float d = max(length(q), 0.001);
                float2 away = q / d;
                // A spindle: fullest in the middle of its length, by the more the longer it is for its width.
                float len = sqrt(bb);
                float pinch = $PINCH * smoothstep($PINCH_FROM, $PINCH_TO, len / wide);
                float belly = 1.0 - pinch + 4.0 * pinch * t * (1.0 - t);
                float taper = mix(hr.x, hr.y, t);
                float w = taper * belly;
                float2 dw = (u > 0.0 && u < 1.0) ? ba * (((hr.y - hr.x) * belly + taper * 4.0 * pinch * (1.0 - 2.0 * t)) / bb) : float2(0.0);
                float x = d / w;
                float2 dx = (away - dw * x) / w;
                // Its lobe keeps to the muscle's own width, and is thinner where it is narrower: drawn out to an end across a gap,
                // it must not stand there like a spike.
                float thick = hr.z * w / wide;
                float xl = x * $GIRTH;
                float3 lobe = float3(2.0 * (w / $GIRTH - d), -2.0 * away);
                if (xl < 1.0) {
                    float c = sqrt(max(1.0 - xl * xl, 0.0004));
                    lobe = float3(thick * c, dw * (c * hr.z / wide) - dx * ($GIRTH * thick * xl / c));
                }
                float3 swell = float3(0.0);
                float furrow = 0.0;
                // How far along the whole of it p is, its rounded ends included: it runs out to nothing at either end,
                // as a belly does into its tendon, so that no head is a closed shape.
                float whole = len + (1.0 - pinch) * (hr.x + hr.y);
                float tip = 2.0 * clamp((u * len + (1.0 - pinch) * hr.x) / whole, 0.0, 1.0) - 1.0;
                float level = 1.0 - tip * tip * tip * tip;
                float fade = level * level;
                float2 dfade = ba * (-16.0 * level * tip * tip * tip / (len * whole));
                if (x < 1.0) {
                    // Flat across its middle and turning down toward its border, where it meets its neighbour in a furrow.
                    float x2 = x * x;
                    float e = 1.0 - mix(x2, x2 * x2, 0.7);
                    // It stands by a share of its width: a broad sheet no more than a strap does, and a thread hardly at all.
                    float lean = min(w / (unit * 0.012), 1.0);
                    float thin = 0.19 * w * lean;
                    float most = 0.0075 * unit;
                    float work = 1.0 + 0.7 * warm;
                    float2 dstand = thin < most ? dw * (0.19 * work * (lean < 1.0 ? 2.0 * lean : 1.0)) : float2(0.0);
                    float stand = min(thin, most) * work;
                    float amp = stand * fade;
                    float e2 = e * e;
                    furrow = 6.75 * e2 * (1.0 - e2) * (1.0 - e2) * fade * lean;
                    swell = float3(amp * e2, (dstand * fade + dfade * stand) * e2 - dx * (2.0 * amp * e * mix(2.0 * x, 4.0 * x2 * x, 0.7)));
                }
                float mine = 0.0;
                if (warm > 0.001) {
                    // The heat of the whole belly, reaching a little past its border so that neighbours at work glow as one mass
                    // with a duller seam between them; and the strands down it, which is where it is brightest.
                    float mass = clamp(1.0 - x * x / 1.3, 0.0, 1.0);
                    // A muscle half at work is well alight already.
                    float alight = pow(warm, 0.85);
                    float across = (pa.x * ba.y - pa.y * ba.x) / (len * w);
                    float strands = clamp(w / (unit * 0.006), 1.0, 4.0);
                    float strand = pow(0.5 + 0.5 * cos(3.14159265 * strands * across + 0.6 * sin(t * 7.0 + float(j))), 8.0);
                    float along = smoothstep(0.0, 0.22, t) * (1.0 - smoothstep(0.78, 1.0, t));
                    mine = alight * (0.48 * mass * (0.3 + 0.7 * fade) + 0.6 * strand * along * clamp(1.0 - x * x, 0.0, 1.0));
                    // The light it throws is the nearest working head's, however many strips that head is laid as.
                    aura = max(aura, alight * exp(-max(d - w, 0.0) / (unit * 0.035)));
                }
                // A head with no lobe is only a swell on what is under it; one with hardly any carries the outline hardly at all.
                if (hr.z <= 0.0) { lobe.x = NONE; }
                float slight = thick - 0.004 * unit;
                float s = min(w / $GIRTH - d, 2.0 * slight) + 50.0 * min(slight, 0.0);
                if (layer < 0.5) {
                    flesh0 = melt(flesh0, lobe, kl);
                    if (x < 1.0) { dark.x = mix(dark.x, furrow, smoothstep(-kr, kr, swell.x - relief0.x)); relief0 = melt(relief0, swell, kr); }
                    inside.x = max(inside.x, s);
                    heat.x = max(heat.x, mine);
                } else if (layer < 1.5) {
                    flesh1 = melt(flesh1, lobe, kl);
                    if (x < 1.0) { dark.y = mix(dark.y, furrow, smoothstep(-kr, kr, swell.x - relief1.x)); relief1 = melt(relief1, swell, kr); }
                    inside.y = max(inside.y, s);
                    heat.y = max(heat.y, mine);
                } else {
                    flesh2 = melt(flesh2, lobe, kl);
                    if (x < 1.0) { dark.z = mix(dark.z, furrow, smoothstep(-kr, kr, swell.x - relief2.x)); relief2 = melt(relief2, swell, kr); }
                    inside.z = max(inside.z, s);
                    heat.z = max(heat.z, mine);
                }
            }
        }
    }

    // The layers, farthest first, each over the last; a nearer one darkens what is just behind its edge.
    float gap = unit * 0.035;
    float4 c = float4(0.0);
    if (inside.x > -0.5) {
        float cover = clamp(inside.x + 0.5, 0.0, 1.0);
        float shade = smoothstep(-gap, 0.0, max(inside.y, inside.z));
        c = float4(skin(flesh0, relief0, dark.x, inside.x, heat.x, aura, shade, 1.0) * cover, cover);
    }
    if (inside.y > -0.5) {
        float cover = clamp(inside.y + 0.5, 0.0, 1.0);
        float shade = smoothstep(-gap, 0.0, inside.z);
        c = c * (1.0 - cover) + float4(skin(flesh1, relief1, dark.y, inside.y, heat.y, aura, shade, 0.0) * cover, cover);
    }
    if (inside.z > -0.5) {
        float cover = clamp(inside.z + 0.5, 0.0, 1.0);
        c = c * (1.0 - cover) + float4(skin(flesh2, relief2, dark.z, inside.z, heat.z, aura, 0.0, 0.0) * cover, cover);
    }
    // Off the body: the light the working muscles throw, and close round the outline the blue of its rim.
    float past = max(-max(inside.x, max(inside.y, inside.z)), 0.0);
    float3 thrown = (glow(0.42) * aura * 0.42 + float3(0.14, 0.34, 1.0) * 0.34 * exp(-past / max(unit * 0.008, 1.0))) * (1.0 - c.a);
    c += float4(thrown, max(thrown.r, max(thrown.g, thrown.b)));
    return half4(clamp(c, 0.0, 1.0));
}
"""

/** The stone figure's bar and plates: plain grey steel, solid. */
internal val STUDIO_STEEL = Steel(frame = Color(0xFF8E96A8), pad = Color(0xFF20242E), iron = Color(0xFF2A2F3B), ironEdge = Color(0xFF6C7486), grip = Color(0xFFC9CED9))

/** What the stone figure stands on: a pool of light on the floor under it, and in it the shadow of each foot, which is what stands it there. */
internal fun DrawScope.stage(scene: Scene) {
    if (scene.compact) return
    val floor = scene.fit.at(Offset.Zero)
    val reach = scene.fit.scale * 0.46f
    val pool = Offset(size.width / 2, floor.y)
    // The floor is seen from just above it: a round pool lies on it as a flat oval.
    scale(1f, FLOOR, pool) { drawCircle(Brush.radialGradient(listOf(Color(0xFF2C364B), Color(0x002C364B)), pool, reach), reach, pool) }
    for (bone in scene.figure.bones) {
        if (bone.kind != BoneKind.FOOT || !bone.standing()) continue
        val under = scene.fit.at(Offset((bone.a.x + bone.b.x) / 2f, 0f))
        val spread = scene.fit.scale * (0.045f + abs(bone.b.x - bone.a.x) / 2f)
        scale(1f, FLOOR, under) { drawCircle(Brush.radialGradient(listOf(Color(0xC0000000), Color(0x00000000)), under, spread), spread, under) }
    }
}

/** Whether a foot is on the floor: its ankle at about the height it stands at, and the toe no higher. */
private fun Bone.standing(): Boolean = a.y < 0.09f && b.y < 0.09f

/** How much of its depth the floor shows, seen from where the figure is. */
private const val FLOOR = 0.2f

/** The stone figure is a little fuller than the rig it is made from: skin, and what is under it besides muscle. */
internal const val BULK = 1.1f

/** How much of the height its seat gives it a head's lobe keeps: it is there to fill the outline out, not to stand on the limb. */
private const val SUNK = 0.94f

/** A head's half-width beyond which it is laid as strips side by side, in body heights: one spindle that wide would stand up like a ball. */
private const val STRIP = 0.034f

/** The half-widths, in body heights, between which a head's lobe comes in: a thread hanging past a limb's edge would be a fin on it. */
private const val THREAD = 0.011f
private const val STRAP = 0.014f

/** The layer of the stone figure a group is drawn in: a nearer one covers a farther one. */
internal fun Scene.layer(group: Group): Float = when (group) {
    Group.FAR_LEG -> 0f

    Group.BODY, Group.LEG -> 1f

    // Only from the side does an arm lie in front of the body; from front and back it is beside it, and joins it.
    Group.ARM -> if (figure.view == View.SIDE) 2f else 1f
}

private fun Offset.pointing(): Offset = getDistance().let { if (it < 1e-6f) Offset.Zero else this / it }

/** Half of [this] band's width [x] steps along it, measured square to [along]. */
private fun Drawn.half(x: Float, along: Offset): Float {
    val span = at(x, 1f) - at(x, 0f)
    return abs(span.x * along.y - span.y * along.x) / 2f
}

/** The stretch of a head that is belly, as fractions of its length: the tendon at either end is the limb's own shape. */
private fun Head.belly(): Pair<Float, Float> = when (id) {
    null -> 0.04f to 0.96f

    // The rig's head is a ball and hides the top of this one; the stone figure's jaw is narrower, and it would stand out beside it.
    HeadId.STERNOCLEIDOMASTOID -> 0.36f to 0.95f

    else -> max(0.05f, tendonIn * 0.6f) to min(0.95f, 1f - tendonOut * 0.7f)
}

/** How a head is laid in the shader: as so many strips side by side, each of so many rods end to end. */
private class Lay(val strips: Int, val parts: Int) {
    fun rods(head: Head): Int = if (head.cuts.isNotEmpty()) head.cuts.size + 1 else strips * parts
}

private val ONE_ROD = Lay(1, 1)

/** How finely the heads may be laid: the strips a broad one may have, and whether one that bends is laid in two. Coarser as the list goes on. */
private val PLANS = listOf(3 to true, 2 to true, 2 to false, 1 to false)

/**
 * How each head a view shows is laid, settled once from the body standing and not from the pose
 * it is in: a head that went from one rod to two half way through a rep would jump. A broad one
 * is strips side by side; one that runs from the trunk onto a limb, and so bends with the
 * shoulder or the hip, is two rods end to end, unless it is a thread. It is the finest of
 * [PLANS] that is no more rods than the shader takes.
 */
private val LAYS: Map<View, Map<Head, Lay>> by lazy {
    View.entries.associateWith { view ->
        val figure = standing(view)
        val bands = bands(figure).filter { it.limb == 0 }
        val room = MARBLE_HEADS / figure.limbs.size - landmarks(view).size
        PLANS.map { (most, bends) -> bands.associate { it.head to it.lay(most, bends) } }.let { plans -> plans.firstOrNull { lays -> lays.entries.sumOf { it.value.rods(it.key) } <= room } ?: plans.last() }
    }
}

private fun Band.lay(most: Int, bends: Boolean): Lay {
    val (from, to) = head.belly()
    val last = BAND_STEPS - 1
    val first = (last * from).toInt()
    val end = (last * to).toInt()
    val along = ((a[end] + b[end]) / 2f - (a[first] + b[first]) / 2f).pointing()
    var widest = 0f
    for (i in first..end) widest = max(widest, abs((b[i] - a[i]).x * along.y - (b[i] - a[i]).y * along.x) / 2f)
    val segs = (head.a + head.b).map { it.seg }
    val crosses = Seg.SHOULDER in segs || Seg.HIP in segs || (Seg.TRUNK in segs && (Seg.UPPER_ARM in segs || Seg.THIGH in segs))
    val strips = (widest / STRIP).toInt().coerceIn(0, most - 1) + 1
    // Only a strap is worth laying in two: a thread shows no bend, and the halves of a short broad head would be two balls.
    val strap = widest * GIRTH >= STRAP && ((a[end] + b[end]) / 2f - (a[first] + b[first]) / 2f).getDistance() > widest * 2f / strips * SLENDER
    return Lay(strips, if (bends && crosses && strap) 2 else 1)
}

/**
 * The stone figure: the bones and the heads are handed to [MARBLE_SHADER] as rods, and it does
 * the rest. A bone goes as it is, but for the ends of the body, which the rig has as balls: the
 * skull, the hands and the feet are given their shapes here. A head goes as a spindle down its
 * belly or as several ([LAYS]), and one with tendinous lines across it as a pad between each
 * pair; its heat is how hard it is pulling.
 */
internal fun DrawScope.marble(scene: Scene, shapes: List<Drawn>, shader: FitnessShader) {
    val fit = scene.fit
    val scratch = scene.scratch
    val figure = scene.figure
    scratch.bones.fill(OFF_STAGE)
    scratch.boneR.fill(0f)
    scratch.heads.fill(OFF_STAGE)
    scratch.headR.fill(0f)
    // The part of the canvas the figure and its light can reach: the shader is run for no pixel outside it.
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    val reaches = { a: Offset, b: Offset, by: Float ->
        left = min(left, min(a.x, b.x) - by)
        top = min(top, min(a.y, b.y) - by)
        right = max(right, max(a.x, b.x) + by)
        bottom = max(bottom, max(a.y, b.y) + by)
    }
    val bones = figure.bones.take(MARBLE_BONES)
    val neck = bones.firstOrNull { it.kind == BoneKind.NECK }?.let { (it.b - it.a).pointing() } ?: Offset(0f, 1f)
    bones.forEachIndexed { i, bone ->
        var a = bone.a
        var b = bone.b
        var ra = bone.ra * BULK
        var rb = bone.rb * BULK
        when (bone.kind) {
            BoneKind.HEAD -> {
                // A skull, not a ball: full at the crown and narrowing to the chin, which from the side sits forward of it.
                val face = if (figure.view == View.SIDE) Offset(neck.y, -neck.x) else Offset.Zero
                a = bone.a + neck * 0.02f
                b = bone.a - neck * 0.032f + face * 0.014f
                ra = bone.ra * 0.86f
                rb = bone.ra * 0.66f
            }

            BoneKind.HAND -> {
                // A hand runs on from the forearm: closed round what it holds, or hanging open.
                val forearm = bones.getOrNull(i - 1)?.takeIf { it.kind == BoneKind.FOREARM }?.let { (it.b - it.a).pointing() } ?: Offset.Zero
                val open = figure.props.isEmpty()
                a = bone.a - forearm * 0.002f
                b = bone.a + forearm * if (open) 0.042f else 0.004f
                ra = bone.ra * 0.86f
                rb = bone.ra * if (open) 0.56f else 0.82f
            }

            BoneKind.FOOT -> {
                val toe = bone.b - bone.a
                if (bone.standing()) {
                    // A foot that is stood on lies along the floor from heel to toe, its sole flat on it.
                    a = Offset(bone.a.x - toe.x * 0.2f, bone.a.y * 0.5f)
                    b = Offset(bone.a.x + toe.x * 1.05f, bone.a.y * 0.34f)
                    ra = a.y.coerceIn(0.012f, 0.024f)
                    rb = b.y.coerceIn(0.009f, 0.018f)
                } else {
                    // One in the air (a leg curled, hanging or on a machine's plate) goes where the leg takes it.
                    a = bone.a - toe * 0.2f
                    b = bone.a + toe * 1.05f
                    ra = 0.022f
                    rb = 0.016f
                }
            }

            BoneKind.NECK -> {
                // A neck stops under the jaw, and is thick enough to carry the muscles that run up it.
                b = bone.b - neck * 0.07f
                ra = bone.ra * NECK_GIRTH
                rb = bone.rb * NECK_GIRTH
            }

            else -> Unit
        }
        val pa = fit.at(a)
        val pb = fit.at(b)
        scratch.bones[i * 4] = pa.x
        scratch.bones[i * 4 + 1] = pa.y
        scratch.bones[i * 4 + 2] = pb.x
        scratch.bones[i * 4 + 3] = pb.y
        scratch.boneR[i * 4] = ra * fit.scale
        scratch.boneR[i * 4 + 1] = rb * fit.scale
        scratch.boneR[i * 4 + 3] = scene.layer(bone.group)
        reaches(pa, pb, max(ra, rb) * fit.scale + fit.scale * RIM_REACH)
    }

    var slot = 0
    // A rod of a head that is [across] from border to middle where the rod lies: none, for what is only a rise on the surface.
    val rod = { a: Offset, b: Offset, ra: Float, rb: Float, layer: Float, heat: Float, across: Float ->
        if (slot < MARBLE_HEADS) {
            scratch.heads[slot * 4] = a.x
            scratch.heads[slot * 4 + 1] = a.y
            scratch.heads[slot * 4 + 2] = b.x
            scratch.heads[slot * 4 + 3] = b.y
            scratch.headR[slot * 4] = ra
            scratch.headR[slot * 4 + 1] = rb
            // No lobe stands much higher than its head is wide, whatever it lies on; and a thread has none.
            scratch.headR[slot * 4 + 2] = if (across > 0f) min(scratch.seat((a + b) / 2f, (ra + rb) / 2f, across, layer), across * 1.5f) * ramp(across, fit.scale * THREAD, fit.scale * STRAP) else 0f
            scratch.headR[slot * 4 + 3] = layer * 2f + heat.coerceIn(0f, 1f)
            reaches(a, b, max(ra, rb) + fit.scale * if (heat > 0f) GLOW_REACH else RIM_REACH)
            slot++
        }
    }
    val lays = LAYS.getValue(figure.view)
    val last = BAND_STEPS - 1f
    scene.bands.forEachIndexed { index, band ->
        val head = band.head
        val shape = shapes[index]
        val layer = scene.layer(head.group)
        // Only what does the lift or helps it glows: what merely steadies it would light half the body.
        val heat = if (scene.role(head) >= SYNERGIST) scene.pull(head) else 0f
        val (from, to) = head.belly()
        if (head.cuts.isNotEmpty()) {
            val count = head.cuts.size + 1
            for (i in 0 until count) {
                val x0 = last * if (i == 0) from else head.cuts[i - 1]
                val x1 = last * if (i == count - 1) to else head.cuts[i]
                val middle = (x0 + x1) / 2f
                val start = shape.at(x0, 0.5f)
                val end = shape.at(x1, 0.5f)
                val along = (end - start).pointing()
                val length = (end - start).getDistance()
                val half = shape.half(middle, along) * GIRTH
                if (length < half * 2.4f) {
                    // Wider than it is long: the pad lies across the muscle.
                    val side = Offset(-along.y, along.x)
                    val r = length / 2f * PAD_FILL
                    // Reaching to the muscle's borders, as near as its ends' narrowing can be told before its length is.
                    val out = side * max(half - r * (1f - pinch(2f * (half - r), r)), 0.25f)
                    rod(shape.at(middle, 0.5f) - out, shape.at(middle, 0.5f) + out, r, r, layer, heat, half)
                } else {
                    val cap = half * (1f - pinch(length - 2f * half, half))
                    rod(start + along * cap, end - along * cap, half, half, layer, heat, half)
                }
            }
        } else {
            val lay = lays[head] ?: ONE_ROD
            val count = lay.strips
            val parts = lay.parts
            // The strips of a sheet lie well over one another, the outer ones against its borders, so that it is one surface and not ridges.
            val share = if (count > 1) 0.18f + 0.36f / count else 0.5f
            for (strip in 0 until count) {
                val lane = if (count > 1) share + strip * (1f - 2f * share) / (count - 1) else 0.5f
                for (part in 0 until parts) {
                    // Two rods end to end run a little way into one another.
                    val x0 = last * if (part == 0) from else (from + to) / 2f - (to - from) * JOIN
                    val x1 = last * if (part == parts - 1) to else (from + to) / 2f + (to - from) * JOIN
                    val a = shape.at(x0, lane)
                    val b = shape.at(x1, lane)
                    val along = (b - a).pointing()
                    val across = (shape.half(x0 + (x1 - x0) * 0.25f, along) + shape.half(x0 + (x1 - x0) * 0.75f, along)) / 2f * GIRTH
                    val near = shape.half(x0 + (x1 - x0) * 0.25f, along) * 2f * share * GIRTH
                    val far = shape.half(x0 + (x1 - x0) * 0.75f, along) * 2f * share * GIRTH
                    // The ends that put the shader's spindle through both of those widths: a quarter of the way along, it has a quarter of its narrowing.
                    val quarter = 1f - pinch((b - a).getDistance(), max(near, far)) / 4f
                    // No thinner at one end than half the other: a sheet drawn out to a point across a gap would be a spike.
                    val least = max(near, far) * 0.5f
                    rod(a, b, max((1.5f * near - 0.5f * far) / quarter, least), max((1.5f * far - 0.5f * near) / quarter, least), layer, heat, across)
                }
            }
        }
    }
    // The bony points that show between the muscles, as small rises of their own.
    for (mark in landmarks(figure.view)) {
        for (limbs in figure.limbs) {
            val at = fit.at(limbs.at(mark.seg, mark.along, mark.out))
            val r = mark.r * fit.scale
            rod(at - Offset(r * 0.4f, 0f), at + Offset(r * 0.4f, 0f), r, r, scene.layer(mark.group), 0f, 0f)
        }
    }
    shader.setUniform("size", size.width, size.height)
    shader.setUniform("unit", fit.scale)
    shader.setUniform("bones", scratch.bones)
    shader.setUniform("boneR", scratch.boneR)
    shader.setUniform("heads", scratch.heads)
    shader.setUniform("headR", scratch.headR)
    left = left.coerceIn(0f, size.width)
    top = top.coerceIn(0f, size.height)
    drawRect(shader.brush(), Offset(left, top), Size((right.coerceIn(0f, size.width) - left).coerceAtLeast(0f), (bottom.coerceIn(0f, size.height) - top).coerceAtLeast(0f)))
}

/**
 * How thick the lobe of a rod [half] wide is, lying at [at] in [layer]: what the limb under it
 * would have there if it were as much wider as the rod makes it and no deeper. So a head within
 * its limb's width is sunk in it, and one that hangs past the edge carries the outline out round.
 * It is the most any limb gives it, and a limb gives it less the farther clear of it the rod
 * lies, by the measure of how wide its head is ([across]): so a sheet stretched from the trunk to
 * a raised arm keeps some body in between, and nothing jumps as the lifter moves.
 */
private fun Scratch.seat(at: Offset, half: Float, across: Float, layer: Float): Float {
    var thick = across * 0.4f
    for (i in 0 until MARBLE_BONES) {
        if (boneR[i * 4 + 3] != layer || max(boneR[i * 4], boneR[i * 4 + 1]) <= 0f) continue
        val a = Offset(bones[i * 4], bones[i * 4 + 1])
        val line = Offset(bones[i * 4 + 2], bones[i * 4 + 3]) - a
        val t = if (line.getDistanceSquared() < 0.01f) 0f else (((at - a).x * line.x + (at - a).y * line.y) / line.getDistanceSquared()).coerceIn(0f, 1f)
        val r = boneR[i * 4] + (boneR[i * 4 + 1] - boneR[i * 4]) * t
        val off = (at - a - line * t).getDistance()
        val near = (1f - (off - r - half) / max(across * CLEAR, 0.001f)).coerceIn(0f, 1f)
        val edge = max(off + half, 0.001f)
        thick = max(thick, r * sqrt(1f - (off / edge) * (off / edge)) * SUNK * near)
    }
    return thick
}

/** How far clear of a limb, in its head's half-widths, a rod may lie before the limb gives its lobe no thickness at all. */
private const val CLEAR = 4f

/** How many times longer than it is wide a head, or a strip of one, must be to be laid as two rods end to end. */
private const val SLENDER = 1.6f

/** How far past the middle of a belly laid as two rods each of them runs, as a share of its length. */
private const val JOIN = 0.06f

/** How much narrower than its middle the ends of a rod [length] long and [wide] at its widest are: the shader's own sum. */
private fun pinch(length: Float, wide: Float): Float = PINCH * ramp(length / max(wide, 0.001f), PINCH_FROM, PINCH_TO)

/** How much thicker the stone figure's neck is than the rig's. */
private const val NECK_GIRTH = 1.25f

/** How much of the room between two tendinous lines a pad fills: the rest is the furrow. */
private const val PAD_FILL = 0.94f

/** Where a shader slot with nothing in it is put. */
internal const val OFF_STAGE = -1e5f
