package app.hunsuvar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import app.hunsuvar.core.RouteReplay
import app.hunsuvar.core.RouteSpan
import app.hunsuvar.core.Stripe
import app.hunsuvar.core.Turn
import kotlin.math.abs
import kotlin.math.roundToInt

// Road replay: the turn seen from above (canvas spec Road.dc.html, 1604×420 design units).
// Car drives left → right in the lower lane and turns right (down) at x 1290. Scale: 40 m at x 160 … 0 m at x 1290.

private const val W = 1604f
private const val H = 420f
fun roadX(m: Double) = (1290 - 28.25 * m.coerceIn(0.0, 40.0)).toFloat()

/** Car front (design x) at replay progress [p] 0..1; at 1 the car waits at the stop line. */
fun roadFront(p: Float) = 158f + p.coerceIn(0f, 1f) * 962f + 84f

enum class RoadMark { LAW, PIN, TURN }
data class RoadEvent(val mark: RoadMark, val metres: Double, val n: Int) {
    /** Lit once the car front reaches it; a signal after the wheel lights only at the end. */
    fun lit(p: Float) = p >= 1f || (metres >= 0 && roadFront(p) >= roadX(metres))
}

/** The 30 m line, the signal pin (if any) and the turn, numbered in the order the car meets them. */
fun roadEvents(onM: Double?): List<RoadEvent> =
    listOfNotNull(RoadMark.LAW to 30.0, RoadMark.TURN to 0.0, onM?.let { RoadMark.PIN to it })
        .sortedByDescending { it.second }
        .mapIndexed { i, (m, d) -> RoadEvent(m, d, i + 1) }

private class RoadInk(
    val blocks: Color, val bldg: Color, val tree: Color, val road: Color, val lane: Color, val center: Color,
    val text: Color, val sub: Color, val chip: Color, val chipLine: Color, val lawLine: Color, val car: Color,
    val pool: Float, val glow: Float,
)
private val DayInk = RoadInk(Color(0xFFE3E6EA), Color(0xFFD3D7DD), Color(0xFFA7BDA2), Color(0xFF41454C), Color(0xFFF2F3F5),
    Color(0xFFD4B04A), Color(0xFF15171A), Color(0xFF5A6069), Color.White, Color(0xFFD4D8DE), Color.White, Color(0xFFF7F7F8), 0f, 0.28f)
private val NightInk = RoadInk(Color(0xFF121519), Color(0xFF1B1F24), Color(0xFF1E2A23), Color(0xFF1F2227), Color(0xFFA9AEB5),
    Color(0xFF8C7634), Color(0xFFF2F3F5), Color(0xFF9AA1AA), Color(0xFF24282E), Color(0xFF3A3F47), Color(0xFFE9EAEE), Color(0xFFD5D8DD), 1f, 0.5f)

/** [onM] = metres before the wheel the signal came on (negative = after, null = never). [demo] adds the 예시 label. */
@Composable
fun RoadReplay(onM: Double?, progress: Float, demo: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val ink = if (isSystemInDarkTheme()) NightInk else DayInk
    val events = roadEvents(onM)
    val done = progress >= 1f
    val desc = if (onM == null) "위에서 본 도로: 회전할 때까지 깜빡이 기록 없음" else "위에서 본 도로: 법 기준 회전 30 m 전, 깜빡이 ${label(onM)}"
    Canvas(modifier.fillMaxWidth().aspectRatio(W / H).semantics { contentDescription = desc }) {
        val s = size.width / W
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(28 * s))) }) {
            drawRect(ink.blocks)
            box(1494f, 16f, 96f, 108f, 12f, ink.bldg, s); box(1494f, 336f, 96f, 74f, 12f, ink.bldg, s)
            listOf(90f, 200f, 960f, 1080f, 1180f).forEach { drawCircle(ink.tree, 14 * s, o(it, 120f)) }
            listOf(90f, 330f, 700f, 900f, 1120f).forEach { drawCircle(ink.tree, 14 * s, o(it, 404f)) }
            drawRect(ink.road, o(0f, 140f), Size(W * s, 180 * s))
            drawRect(ink.road, o(1290f, 0f), Size(180 * s, H * s))
            listOf(146f, 314f).forEach { drawLine(ink.lane.copy(alpha = 0.6f), o(0f, it), o(1222f, it), 3 * s) }
            listOf(228f, 233f).forEach { drawLine(ink.center, o(0f, it), o(1222f, it), 3 * s) }
            listOf(1378f, 1383f).forEach { x ->
                drawLine(ink.center, o(x, 0f), o(x, 140f), 3 * s); drawLine(ink.center, o(x, 320f), o(x, H), 3 * s)
            }
            (0 until 8).forEach { box(1232f, 146f + it * 21f, 46f, 11f, 2f, ink.lane, s) }
            drawLine(ink.lane, o(1222f, 235f), o(1222f, 316f), 6 * s)
            if (ink.pool > 0f) listOf(560f, 1010f).forEach { x ->
                drawCircle(Brush.radialGradient(listOf(Color(0x52FFE7B8), Color(0x00FFE7B8)), o(x, 275f), 150 * s), 150 * s, o(x, 275f))
            }

            // The whole route, faint; the lit part is drawn over it in amber.
            val route = Path().apply { moveTo(0f, 275 * s); lineTo(1250 * s, 275 * s); quadraticTo(1335 * s, 275 * s, 1335 * s, 360 * s); lineTo(1335 * s, H * s) }
            drawPath(route, ink.lane.copy(alpha = 0.55f), style = Stroke(5 * s, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * s, 12 * s))))

            val front = roadFront(progress)
            val pinX = onM?.let { if (it < 0) 1290f else roadX(it) }
            val passed = pinX != null && (done || front >= pinX)
            if (passed) {
                val lit = Path().apply {
                    if (!done) { moveTo(pinX!! * s, 275 * s); lineTo(front * s, 275 * s) }
                    else if (pinX!! < 1250f) { moveTo(pinX * s, 275 * s); lineTo(1250 * s, 275 * s); quadraticTo(1335 * s, 275 * s, 1335 * s, 360 * s); lineTo(1335 * s, H * s) }
                    else { moveTo(1290 * s, 278 * s); quadraticTo(1335 * s, 290 * s, 1335 * s, 360 * s); lineTo(1335 * s, H * s) }
                }
                drawPath(lit, Amber.copy(alpha = ink.glow), style = Stroke(18 * s, cap = StrokeCap.Round))
                drawPath(lit, Amber, style = Stroke(7 * s, cap = StrokeCap.Round))
            }

            val carX = front - 84f
            box(carX, 253f, 84f, 44f, 12f, ink.car, s)
            box(carX + 48f, 259f, 18f, 32f, 5f, Color(0xFF2A2E35), s)
            box(carX + 8f, 261f, 12f, 28f, 4f, Color(0xFF2A2E35), s)
            if (passed) drawCircle(Amber, 5 * s, o(carX + 80f, 292f))

            // Honest legend, top right: only our car's distances are recorded; the road around it is drawn for explanation.
            val legend = "설명용 그림 · 다른 차와 신호는 기록하지 않아요" + if (demo) " · 예시 데이터" else ""
            val lm = tm.measure(legend, style(20f, FontWeight.Medium, ink.text, s))
            val legendLeft = W - 24f - lm.size.width / s - 48f

            val law = roadX(30.0)
            if (done && pinX != null) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(4 * s, 6 * s))
                listOf(law, pinX).forEach { drawLine(ink.sub, o(it, 110f), o(it, 230f), 2 * s, pathEffect = dash) }
                val l = minOf(law, pinX); val r = maxOf(law, pinX)
                drawLine(ink.text, o(l, 100f), o(r, 100f), 3 * s)
                listOf(l, r).forEach { drawLine(ink.text, o(it, 90f), o(it, 110f), 3 * s) }
                // Centered over the bracket, kept inside the road's left edge.
                val gm = tm.measure(gapLabel(onM!!), style(24f, FontWeight.Bold, ink.text, s))
                val gx = maxOf((l + r) / 2 * s - gm.size.width / 2f, 24f * s)
                drawText(gm, topLeft = Offset(gx, 52f * s))
            }

            fun chip(x: Float, y: Float, n: Int, label: String, fill: Color, line: Color?, fg: Color, badge: Color, badgeFg: Color, centerX: Boolean) {
                val m = tm.measure(label, style(22f, FontWeight.Bold, fg, s))
                val w = m.size.width / s + 70f
                val left = if (centerX) (x - w / 2).coerceIn(12f, W - 12f - w) else x
                drawRoundRect(fill, o(left, y), Size(w * s, 44 * s), CornerRadius(22 * s))
                line?.let { drawRoundRect(it, o(left, y), Size(w * s, 44 * s), CornerRadius(22 * s), style = Stroke(2 * s)) }
                drawCircle(badge, 13 * s, o(left + 24f, y + 22f))
                text(tm, "$n", left + 24f, y + 11f, 16f, FontWeight.ExtraBold, badgeFg, s, center = true)
                drawText(m, topLeft = Offset((left + 46f) * s, (y + 22f) * s - m.size.height / 2f))
            }
            drawLine(ink.lawLine, o(law, 236f), o(law, 334f), 4 * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10 * s, 8 * s)))
            events.forEach { e ->
                when (e.mark) {
                    RoadMark.LAW -> chip(law, 334f, e.n, "법 기준 · 회전 30 m 전", ink.chip, ink.chipLine, ink.text, ink.text, ink.chip, true)
                    RoadMark.TURN -> chip(1020f, 334f, e.n, "핸들 꺾음 · 회전 지점", ink.chip, ink.chipLine, ink.text, ink.text, ink.chip, false)
                    RoadMark.PIN -> if (passed) {
                        drawLine(Amber, o(pinX!!, 210f), o(pinX, 253f), 3 * s)
                        drawCircle(ink.road, 22 * s, o(pinX, 275f)); drawCircle(Amber, 22 * s, o(pinX, 275f), style = Stroke(6 * s))
                        drawCircle(Amber, 8 * s, o(pinX, 275f))
                        chip(pinX, 166f, e.n, "깜빡이 켬 · ${label(onM!!)}", Amber, null, OnAmber, OnAmber, Amber, true)
                    }
                }
            }
            text(tm, "40 m", 160f, 386f, 20f, FontWeight.Medium, ink.sub, s)
            drawRoundRect(ink.chip.copy(alpha = 0.92f), o(legendLeft, 24f), Size(lm.size.width + 48 * s, 40 * s), CornerRadius(20 * s))
            drawText(lm, topLeft = Offset((legendLeft + 24f) * s, 44 * s - lm.size.height / 2f))
        }
    }
}

/**
 * Every other verdict: the car's run seen from above, laid out by metres travelled (core/Route.kt). Shows where the
 * signal was on, where a car sat in the next lane, the navi exit, the reroute, the brake — at their real distances.
 * [decisive] colours the mark the verdict turned on (reroute / brake). Lateral moves aren't recorded, so the car keeps
 * its lane unless the navi says it took the exit.
 */
@Composable
fun RouteReplayView(r: RouteReplay, progress: Float, demo: Boolean, decisive: Color, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val ink = if (isSystemInDarkTheme()) NightInk else DayInk
    val done = progress >= 1f
    val two = r.side == Turn.LEFT || r.side == Turn.RIGHT
    val flip = r.side == Turn.LEFT // exit/next lane on top instead of below
    val desc = buildString {
        append("위에서 본 동선: ${r.lengthM.roundToInt()} m")
        r.exitM?.let { append(", 깜빡이 켠 곳에서 빠질 길까지 ${(it - r.startM).roundToInt()} m") }
        if (r.rerouteM != null) append(", 내비가 경로를 다시 찾음")
        if (r.tookExit) append(", 빠질 길로 빠짐")
    }
    Canvas(modifier.fillMaxWidth().aspectRatio(W / H).semantics { contentDescription = desc }) {
        val s = size.width / W
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun fy(y: Float) = if (flip) H - y else y
        val len = r.lengthM.coerceAtLeast(1.0)
        fun x(m: Double) = (60 + (m / len) * 1484).toFloat()
        val carM = r.mAt(r.fromMs + ((r.toMs - r.fromMs) * progress.coerceIn(0f, 1f)).toLong())
        fun seen(m: Double) = done || carM >= m

        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(28 * s))) }) {
            drawRect(ink.blocks)
            // Road (design y, before the LEFT flip): our lane, the next lane on the blinker side, the navi exit as a ramp.
            val top = if (two) 150f else 190f
            val bottom = if (two) 330f else 300f
            drawRect(ink.road, o(0f, if (flip) H - bottom else top), Size(W * s, (bottom - top) * s))
            val ownY = fy(if (two) 195f else 245f)
            val nextY = fy(285f)
            if (two) drawLine(ink.lane, o(0f, fy(240f)), o(W, fy(240f)), 3 * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(28 * s, 22 * s)))
            r.exitM?.let { em ->
                val ex = x(em)
                drawPath(Path().apply {
                    moveTo((ex - 150) * s, fy(bottom) * s); lineTo(ex * s, fy(bottom) * s)
                    lineTo((ex + 120) * s, fy(H) * s); lineTo((ex - 20) * s, fy(H) * s); close()
                }, ink.road)
            }

            fun band(sp: RouteSpan, y: Float, c: Color, w: Float) {
                val to = if (done) sp.toM else minOf(sp.toM, carM)
                if (to > sp.fromM) drawLine(c, o(x(sp.fromM), y), o(x(to), y), w * s, cap = StrokeCap.Round)
            }
            val dir = if (flip) -1f else 1f
            // The gap the verdict turns on (drawn first so the next-lane car sits on top at the seam).
            r.gap?.takeIf { seen(it.fromM) }?.let { band(it, nextY, GapGreen.copy(alpha = 0.45f), 64f) }
            // Next-lane car: a translucent band plus a car shape where it sat.
            r.stripes[Stripe.SIDE_CAR].orEmpty().filter { seen(it.fromM) }.forEach { sp ->
                band(sp, nextY, SideCar.copy(alpha = 0.35f), 64f)
                box(x((sp.fromM + sp.toM) / 2) - 42f, nextY - 22f, 84f, 44f, 12f, ink.sub, s)
            }
            r.stripes[Stripe.SIGNAL].orEmpty().forEach { band(it, ownY, Amber.copy(alpha = ink.glow), 26f); band(it, ownY, Amber, 9f) }
            r.stripes[Stripe.BRAKE].orEmpty().forEach { band(it, ownY + 30f * dir, BrakeRed, 9f) }
            OTHER.forEach { k -> r.stripes[k].orEmpty().forEach { band(it, ownY - 30f * dir, OtherBlue, 9f) } }

            // The car keeps its lane; if the navi says it took the exit, it slides over and down the ramp.
            val em = r.exitM
            val carY = if (r.tookExit && em != null && carM > em - 40) {
                val k = ((carM - (em - 40)) / 40).toFloat().coerceIn(0f, 1f)
                ownY + (nextY - ownY) * k + (fy(H) - nextY) * ((carM - em) / 60).toFloat().coerceIn(0f, 0.6f)
            } else ownY
            val carX = x(carM) - 84f
            box(carX, carY - 22f, 84f, 44f, 12f, ink.car, s)
            box(carX + 48f, carY - 16f, 18f, 32f, 5f, Color(0xFF2A2E35), s)

            // Pills: "near" = the road edge away from the exit (signal, reroute), "far" = the exit side (exit, next-lane car, brake).
            data class Pill(val m: Double, val text: String, val fill: Color, val fg: Color, val near: Boolean)
            val pills = buildList {
                r.stripes[Stripe.SIGNAL].orEmpty().firstOrNull()?.let { add(Pill(it.fromM, "깜빡이 켬", Amber, OnAmber, true)) }
                r.rerouteM?.let { add(Pill(it, "내비가 경로를 다시 찾음", decisive, Color.White, true)) }
                OTHER.forEach { k -> r.stripes[k].orEmpty().firstOrNull()?.let { add(Pill(it.fromM, k.label, OtherBlue, ink.text, true)) } }
                r.exitM?.let { add(Pill(it, if (r.tookExit) "빠질 길 · 여기로 빠짐" else "빠질 길", ink.chip, ink.text, false)) }
                r.stripes[Stripe.SIDE_CAR].orEmpty().firstOrNull()?.let { add(Pill(it.fromM, "옆 차로에 차", SideCar, Color.White, false)) }
                r.stripes[Stripe.BRAKE].orEmpty().firstOrNull()?.let { b ->
                    val (v0, v1) = r.brakeKmh ?: (0.0 to 0.0)
                    add(Pill(b.fromM, "브레이크 밟음 · 시속 ${v0.roundToInt()} km", BrakeRed, Color.White, false))
                    add(Pill(b.toM, if (v1 < 1) "멈춤" else "발 뗌 · 시속 ${v1.roundToInt()} km", ink.chip, ink.text, false))
                }
            }.filter { seen(it.m) }.sortedBy { it.m }
            val rowEnd = mutableMapOf(true to 0f, false to 0f)
            pills.forEach { p ->
                val m = tm.measure(p.text, style(22f, FontWeight.Bold, p.fg, s))
                val w = m.size.width / s + 40f
                val left = maxOf((x(p.m) - 20f).coerceIn(12f, W - 12f - w), rowEnd.getValue(p.near) + 12f).coerceAtMost(W - 12f - w)
                rowEnd[p.near] = left + w
                val upper = p.near != flip // drawn above the road
                val y = if (upper) 78f else 338f
                drawLine(p.fill, o(x(p.m), if (upper) y + 44f else if (flip) H - top else bottom), o(x(p.m), if (upper) (if (flip) H - bottom else top) else y), 3 * s)
                drawRoundRect(p.fill, o(left, y), Size(w * s, 44 * s), CornerRadius(22 * s))
                if (p.fill == ink.chip) drawRoundRect(ink.chipLine, o(left, y), Size(w * s, 44 * s), CornerRadius(22 * s), style = Stroke(2 * s))
                drawText(m, topLeft = Offset((left + 20f) * s, (y + 22f) * s - m.size.height / 2f))
            }

            // The number the verdict turns on, bracketed at the top: how long the next lane sat empty, else signal → exit metres.
            val sig = r.stripes[Stripe.SIGNAL].orEmpty().firstOrNull()
            val gap = r.gap
            if (done && gap != null) {
                val l = x(gap.fromM); val rr = x(gap.toM)
                drawLine(GapGreen, o(l, 60f), o(rr, 60f), 3 * s)
                listOf(l, rr).forEach { drawLine(GapGreen, o(it, 50f), o(it, 70f), 3 * s) }
                text(tm, "옆 차로가 비어 있던 ${"%.1f".format((r.gapMs ?: 0) / 1000.0)}초", (l + rr) / 2, 12f, 24f, FontWeight.Bold, ink.text, s, center = true)
            } else if (done && sig != null && em != null) {
                val l = x(sig.fromM); val rr = x(em)
                drawLine(ink.text, o(l, 60f), o(rr, 60f), 3 * s)
                listOf(l, rr).forEach { drawLine(ink.text, o(it, 50f), o(it, 70f), 3 * s) }
                text(tm, "깜빡이 켠 곳 → 빠질 길 ${(em - sig.fromM).roundToInt()} m", (l + rr) / 2, 12f, 24f, FontWeight.Bold, ink.text, s, center = true)
            }

            val brake = r.stripes[Stripe.BRAKE].orEmpty().firstOrNull()
            if (done && brake != null && em == null) {
                val l = x(brake.fromM); val rr = x(brake.toM)
                drawLine(ink.text, o(l, 60f), o(rr, 60f), 3 * s)
                listOf(l, rr).forEach { drawLine(ink.text, o(it, 50f), o(it, 70f), 3 * s) }
                text(tm, "브레이크를 밟고 ${(brake.toM - brake.fromM).roundToInt()} m에 걸쳐 속도를 줄임", (l + rr) / 2, 12f, 24f, FontWeight.Bold, ink.text, s, center = true)
            }

            val legend = "설명용 그림 · 우리 차가 달린 거리만 기록해요" + if (demo) " · 예시 데이터" else ""
            text(tm, legend, 24f, 392f, 18f, FontWeight.Medium, ink.sub, s) // bottom-left: the exit ramp sits on the right
        }
    }
}

private val SideCar = Color(0xFFE5484D)
private val GapGreen = Color(0xFF2FB67C)
private val BrakeRed = Color(0xFFD93A3F) // brake lamps
private val OtherBlue = Color(0xFF8DB8F2)
private val OTHER = listOf(Stripe.OVERSPEED, Stripe.DARK_IN_RAIN, Stripe.UNBELTED)

private fun label(onM: Double) = if (onM >= 0) "${onM.roundToInt()} m 전" else "회전 뒤 ${abs(onM).roundToInt()} m"

/** Distance to the law line, the number the verdict turns on. */
fun gapLabel(onM: Double): String {
    val gap = abs(onM - 30).roundToInt()
    return when {
        onM < 0 -> "핸들을 꺾은 뒤에 켬"
        gap == 0 -> "법 기준 지점에서 켬"
        onM > 30 -> "법 기준보다 $gap m 먼저 켬"
        else -> "법 기준보다 $gap m 늦게 켬"
    }
}

private fun DrawScope.style(px: Float, w: FontWeight, c: Color, s: Float) =
    TextStyle(fontFamily = AstaSans, fontSize = (px * s).toSp(), fontWeight = w, color = c)

private fun DrawScope.text(tm: TextMeasurer, t: String, x: Float, top: Float, px: Float, w: FontWeight, c: Color, s: Float, center: Boolean = false) {
    val m = tm.measure(t, style(px, w, c, s))
    drawText(m, topLeft = Offset(if (center) x * s - m.size.width / 2f else x * s, top * s))
}

private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color, s: Float) =
    drawRoundRect(c, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r * s))
