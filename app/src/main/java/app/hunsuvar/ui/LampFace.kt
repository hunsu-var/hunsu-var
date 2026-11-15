package app.hunsuvar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hunsuvar.core.Turn

/**
 * 판독관 깜이: a front lamp unit. Headlamps = eyes, DRL = mouth, amber ends = turn signals, side mirrors = ears.
 * Geometry is the canvas spec (LampFace.dc.html) in a 240×120 box. Always still: nothing here animates (the driving screen
 * must not move by itself); parked screens may move it from outside.
 * JUDGING = no face, only both signals lit (the verdict is the car's data, not a face). REC = lamps off, one amber dot.
 */
enum class Face(val alt: String) {
    REC("기록 중: 램프가 꺼지고 앰버 점 하나만 켜짐"), JUDGING("판독 중: 얼굴 없이 양쪽 깜빡이만 켜짐"),
    NEUTRAL("기본 표정"), LISTEN("듣는 표정, 사이드미러 귀가 살짝 섬"), HIT("웃는 표정"), REJECT("차분한 기각 표정"),
    UNDECIDED("한쪽 눈이 꺼진 판독 불가 표정"), SORRY("미안한 표정"),
}

private val Housing = Color(0xFF17191D)
private val Off = Color(0xFF2E3238)
private val On = Color(0xFFFFF3DC)

/**
 * [dot] = REC only: false when nothing is being recorded (no driver consent).
 * [look] = -1..1, glance and tilt toward a side.
 * [signal] = the car's blinker, lit steady on that side (JUDGING lights both).
 */
@Composable
fun LampFace(face: Face, width: Dp = 240.dp, dot: Boolean = true, modifier: Modifier = Modifier,
             look: Float = 0f, signal: Turn = Turn.OFF) {
    val night = isSystemInDarkTheme()
    // At night the lit lamps are dimmed so a big face on the 17" screen doesn't glare at the driver.
    val On = if (night) On.copy(alpha = 0.7f) else On
    Canvas(modifier.size(width, width / 2).graphicsLayer { rotationZ = look * 3f }.semantics { contentDescription = "판독관 깜이, ${face.alt}" }) {
        val s = size.width / 240f
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        val rim = if (night) Color(0xFF3A3F47) else null

        // Ears = side mirrors. Relaxed: spread out (±20°). Listening: up a little. Sorry: drooped.
        val (dy, deg) = when (face) {
            Face.LISTEN -> -4f to 11f
            Face.HIT -> -3f to 10f
            Face.SORRY -> 6f to 38f
            else -> 0f to 20f
        }
        val glass = if (face == Face.LISTEN) On else Off
        listOf(Triple(34f, 52f, -deg), Triple(170f, 188f, deg)).forEach { (mx, pivot, d) ->
            withTransform({ translate(0f, dy * s); rotate(d, o(pivot, 30f)) }) {
                rr(mx + 14, 22f, 8f, 12f, 3f, Housing, s)
                rr(mx, 6f, 36f, 22f, 10f, Housing, s)
                rim?.let { drawRoundRect(it, o(mx, 6f), Size(36 * s, 22 * s), CornerRadius(10 * s), style = Stroke(2 * s)) }
                if (glass == On) glowRR(mx + 6, 11f, 24f, 12f, 6f, s)
                rr(mx + 6, 11f, 24f, 12f, 6f, glass, s)
            }
        }

        rr(8f, 20f, 224f, 80f, 40f, Housing, s)
        rim?.let { drawRoundRect(it, o(8f, 20f), Size(224 * s, 80 * s), CornerRadius(40 * s), style = Stroke(2 * s)) }

        listOf(22f to Turn.LEFT, 198f to Turn.RIGHT).forEach { (x, side) ->
            val lit = face == Face.JUDGING || signal == side
            if (lit) glowRR(x, 42f, 20f, 36f, 10f, s, Amber)
            rr(x, 42f, 20f, 36f, 10f, if (lit) Amber else Off, s)
        }

        fun eye(cx: Float, cy: Float, r: Float, lit: Boolean = true) {
            drawCircle(Off, r * s, o(cx, cy))
            if (!lit) return
            drawOval(On.copy(alpha = 0.28f), o(cx - r - 4, cy - r - 4), Size((2 * r + 8) * s, (2 * r + 8) * s))
            drawOval(On, o(cx - r, cy - r), Size(2 * r * s, 2 * r * s))
        }
        fun line(vararg pts: Float, w: Float = 5f) {
            val p = Path().apply {
                moveTo(pts[0] * s, pts[1] * s)
                var i = 2
                while (i + 3 < pts.size) { quadraticTo(pts[i] * s, pts[i + 1] * s, pts[i + 2] * s, pts[i + 3] * s); i += 4 }
            }
            drawPath(p, On.copy(alpha = 0.28f), style = Stroke((w + 5) * s, cap = StrokeCap.Round))
            drawPath(p, On, style = Stroke(w * s, cap = StrokeCap.Round))
        }
        // straight line as a degenerate quad (control on the line)
        fun straight(x1: Float, x2: Float, y: Float) = line(x1, y, (x1 + x2) / 2, y, x2, y)

        withTransform({ translate(look * 10 * s, 0f) }) { when (face) {
            Face.REC -> { eye(92f, 56f, 15f, false); eye(148f, 56f, 15f, false)
                if (dot) { drawCircle(Amber.copy(alpha = 0.35f), 10 * s, o(120f, 60f)); drawCircle(Amber, 6 * s, o(120f, 60f)) } }
            Face.JUDGING -> { eye(92f, 56f, 15f, false); eye(148f, 56f, 15f, false) }
            Face.NEUTRAL -> { eye(92f, 56f, 15f); eye(148f, 56f, 15f); straight(84f, 156f, 82f) }
            Face.LISTEN -> { eye(92f, 52f, 15f); eye(148f, 52f, 15f); straight(108f, 132f, 84f) }
            Face.HIT -> { line(78f, 62f, 92f, 42f, 106f, 62f, w = 6f); line(134f, 62f, 148f, 42f, 162f, 62f, w = 6f)
                line(84f, 78f, 120f, 98f, 156f, 78f) }
            Face.REJECT -> { glowRR(78f, 54f, 28f, 12f, 6f, s); rr(78f, 54f, 28f, 12f, 6f, On, s)
                glowRR(134f, 54f, 28f, 12f, 6f, s); rr(134f, 54f, 28f, 12f, 6f, On, s); straight(100f, 140f, 82f) }
            Face.UNDECIDED -> { eye(92f, 56f, 15f); eye(148f, 56f, 15f, false)
                line(86f, 82f, 94.5f, 74f, 103f, 82f, 111.5f, 90f, 120f, 82f, 128.5f, 74f, 137f, 82f, 145.5f, 90f, 154f, 82f) }
            Face.SORRY -> { eye(92f, 60f, 10f); eye(148f, 60f, 10f); line(98f, 88f, 120f, 76f, 142f, 88f) }
        } }
    }
}

private fun DrawScope.rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color, s: Float) =
    drawRoundRect(c, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s))

/** Soft halo behind a lit lamp (stands in for the SVG blur filter). */
private fun DrawScope.glowRR(x: Float, y: Float, w: Float, h: Float, r: Float, s: Float, c: Color = On) =
    drawRoundRect(c.copy(alpha = 0.3f), Offset((x - 3) * s, (y - 3) * s), Size((w + 6) * s, (h + 6) * s), CornerRadius((r + 3) * s))
