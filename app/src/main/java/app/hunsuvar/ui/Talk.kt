package app.hunsuvar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hunsuvar.core.CandidateKind
import app.hunsuvar.core.Gear
import app.hunsuvar.core.ParkingPath
import app.hunsuvar.core.PathPoint

// v5 「중계석」: two voices. What people said = a white bubble. What 깜이 says = a bubble made of 깜이 itself
// (lamp housing, lit cream text). The verdict word and the evidence stay in the data layer, never in a bubble.

val LampHousing = Color(0xFF17191D)
val LampLit = Color(0xFFFFF3DC)
val LampNumber = Color(0xFFFFB84D)
private val NightRim = Color(0xFF3A3F47)

/** Where the bubble's tail points. [at] = dp from the start of that side. */
enum class Tail { UP, DOWN, LEFT, RIGHT, NONE }

@Composable
private fun BubbleBox(fill: Color, tail: Tail, at: Dp, rim: Color?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Box(modifier) {
        val shape = RoundedCornerShape(28.dp)
        // Place first, then draw: offset must come before size/rotate/background or the tail stays in the corner.
        fun Modifier.tail() = size(30.dp).rotate(45f).background(fill, RoundedCornerShape(5.dp))
        when (tail) {
            Tail.UP -> Box(Modifier.align(Alignment.TopStart).offset(x = at, y = (-12).dp).tail())
            Tail.DOWN -> Box(Modifier.align(Alignment.BottomStart).offset(x = at, y = 12.dp).tail())
            Tail.LEFT -> Box(Modifier.align(Alignment.TopStart).offset(x = (-12).dp, y = at).tail())
            Tail.RIGHT -> Box(Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = at).tail())
            Tail.NONE -> {}
        }
        Column((if (rim != null) Modifier.border(2.dp, rim, shape) else Modifier).background(fill, shape).padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }

/** 깜이 speaking: lamp housing with lit text. At night a thin rim keeps it off the dark background. */
@Composable
fun KkamiBubble(modifier: Modifier = Modifier, tail: Tail = Tail.UP, at: Dp = 120.dp, content: @Composable ColumnScope.() -> Unit) =
    BubbleBox(LampHousing, tail, at, if (isSystemInDarkTheme()) NightRim else null, modifier, content)

@Composable
fun KkamiLine(text: String, modifier: Modifier = Modifier, tail: Tail = Tail.UP, at: Dp = 120.dp, size: Int = 30) =
    KkamiBubble(modifier, tail, at) { Text(text.keepAll(), color = LampLit, style = Body.copy(fontSize = size.sp, lineHeight = (size * 1.45).sp)) }

/** What a person said ("아까 들어갔어야지!"). [who] = the seat, small above the quote. */
@Composable
fun SaidBubble(text: String, modifier: Modifier = Modifier, who: String? = null, tail: Tail = Tail.DOWN, at: Dp = 44.dp,
               size: Int = 40, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val m = if (onClick != null) modifier.clip(RoundedCornerShape(28.dp)).clickable(role = Role.Button, onClick = onClick) else modifier
    BubbleBox(cs.surface, tail, at, if (selected) Amber else if (isSystemInDarkTheme()) cs.outline else null, m.card(shape = RoundedCornerShape(28.dp))) {
        who?.let { Text(it, color = cs.textSub, style = Label.copy(fontWeight = FontWeight.SemiBold)) }
        Text("“$text”".keepAll(), style = Title.copy(fontSize = size.sp, lineHeight = (size * 1.3).sp, fontWeight = FontWeight.Bold))
    }
}

/** A small top-down sketch of the scene kind for candidate cards. Drawn, not photographed: it says what kind of moment. */
@Composable
fun SceneThumb(kind: CandidateKind, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.clip(ControlShape).background(bg)) {
        val w = size.width; val h = size.height
        val road = Color(0xFF3B3F46); val path = LampLit.copy(alpha = 0.5f)
        when (kind) {
            CandidateKind.LANE_CHANGE_ATTEMPT -> {
                drawPath(Path().apply { moveTo(w * 0.70f, h * 0.80f); lineTo(w * 0.82f, h * 0.80f); lineTo(w * 0.95f, h); lineTo(w * 0.83f, h); close() }, road)
                drawRect(road, Offset(0f, h * 0.2f), Size(w, h * 0.6f))
                drawLine(bg, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f)))
                drawRoundRect(Color(0xFFD24B4B).copy(alpha = 0.5f), Offset(w * 0.18f, h * 0.55f), Size(w * 0.32f, h * 0.2f), CornerRadius(h * 0.1f))
                drawLine(Amber, Offset(w * 0.2f, h * 0.35f), Offset(w * 0.62f, h * 0.35f), 7.dp.toPx(), StrokeCap.Round)
                drawRoundRect(Color.White, Offset(w * 0.84f, h * 0.27f), Size(w * 0.09f, h * 0.16f), CornerRadius(8f))
            }
            CandidateKind.PARKING -> {
                val bay = Color.White.copy(alpha = 0.9f)
                for (i in 0..3) drawLine(bay, Offset(w * (0.22f + i * 0.18f), h * 0.12f), Offset(w * (0.22f + i * 0.18f), h * 0.55f), 3.dp.toPx())
                drawPath(Path().apply { moveTo(w * 0.05f, h * 0.85f); quadraticTo(w * 0.62f, h * 0.88f, w * 0.49f, h * 0.30f) },
                    Amber, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                drawRoundRect(Color.White, Offset(w * 0.435f, h * 0.16f), Size(w * 0.11f, h * 0.24f), CornerRadius(8f))
            }
            CandidateKind.ROUTE_CHANGE -> {
                drawLine(road, Offset(0f, h * 0.55f), Offset(w, h * 0.55f), h * 0.22f)
                drawLine(road, Offset(w * 0.45f, h * 0.55f), Offset(w * 0.75f, 0f), h * 0.18f)
                drawLine(LampLit.copy(alpha = 0.6f), Offset(w * 0.45f, h * 0.55f), Offset(w * 0.73f, h * 0.05f), 4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)), cap = StrokeCap.Round)
                drawLine(Amber, Offset(w * 0.05f, h * 0.55f), Offset(w * 0.92f, h * 0.55f), 6.dp.toPx(), StrokeCap.Round)
            }
            else -> {
                drawRect(road, Offset(0f, h * 0.3f), Size(w, h * 0.4f))
                drawLine(path, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), 5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/**
 * The parking episode from above, drawn up to [progress]. Reverse = amber, forward = lit cream; each correction
 * gets a dot. No bay lines: the car never saw them.
 */
@Composable
fun ParkingPathView(p: ParkingPath, progress: Float, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(CardShape).background(cs.surfaceContainerHighest)) {
        Canvas(Modifier.matchParentSize()) {
            if (p.points.size < 2) return@Canvas
            val xs = p.points.map { it.x }; val ys = p.points.map { it.y }
            val spanX = (xs.max() - xs.min()).coerceAtLeast(4.0); val spanY = (ys.max() - ys.min()).coerceAtLeast(4.0)
            val pad = 80.dp.toPx()
            val s = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
            val cx = ((xs.max() + xs.min()) / 2).toFloat(); val cy = ((ys.max() + ys.min()) / 2).toFloat()
            fun o(pt: PathPoint) = Offset(size.width / 2 + (pt.x.toFloat() - cx) * s, size.height / 2 - (pt.y.toFloat() - cy) * s)
            val shown = p.points.take(maxOf(2, (p.points.size * progress).toInt()))
            shown.zipWithNext().forEach { (a, b) ->
                val c = if (a.gear == Gear.R) Amber else Color(0xFF8A9099)
                drawLine(c.copy(alpha = 0.3f), o(a), o(b), 22.dp.toPx(), StrokeCap.Round)
                drawLine(c, o(a), o(b), 8.dp.toPx(), StrokeCap.Round)
            }
            // A marker-sized car (not to scale) so it never hides the path it drove.
            val car = shown.last(); val u = 12.dp.toPx()
            rotate(-Math.toDegrees(car.headingRad).toFloat(), o(car)) {
                drawRoundRect(Color(0xFF15171A), o(car) - Offset(4.6f * u, 2.1f * u), Size(9.2f * u, 4.2f * u), CornerRadius(1.4f * u))
                drawRoundRect(Color.White, o(car) - Offset(4.3f * u, 1.8f * u), Size(8.6f * u, 3.6f * u), CornerRadius(1.2f * u))
                drawRoundRect(Color(0xFF15171A), o(car) + Offset(1.4f * u, -1.4f * u), Size(1.4f * u, 2.8f * u), CornerRadius(0.4f * u))
            }
            if (progress >= 1f) {
                p.turns.forEach { drawCircle(Color(0xFF15171A), 18.dp.toPx(), o(it)) }
            }
        }
        if (progress >= 1f) Column(Modifier.align(Alignment.TopStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Legend(Amber, "후진")
            Legend(Color(0xFF8A9099), "전진")
            if (p.turns.isNotEmpty()) Legend(Color(0xFF15171A), "고쳐 댄 곳 ${p.turns.size}번")
        }
        Text("설명용 그림 · 핸들과 속도로 다시 그린 동선이라 실제 위치와 조금 달라요".keepAll(),
            Modifier.align(Alignment.BottomStart).padding(24.dp), color = cs.textSub, style = Label.copy(fontSize = 20.sp))
    }
}

@Composable
private fun Legend(c: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.size(22.dp).background(c, CircleShape))
    Text(text, style = Label.copy(fontWeight = FontWeight.Bold))
}

/** The 판독 중 check strip: each criterion is a lamp that lights in turn ([progress] 0..1). */
@Composable
fun CheckLamps(items: List<String>, progress: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.card().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, label ->
            val lit = progress >= (i + 1f) / (items.size + 0.5f)
            if (i > 0) Box(Modifier.size(2.dp, 64.dp).background(cs.outline))
            Row(Modifier.weight(1f).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).then(if (lit) Modifier.background(Amber, CircleShape) else Modifier.border(3.dp, cs.disabled, CircleShape)))
                Column {
                    Text("${i + 1}. $label".keepAll(), color = cs.textSub, style = Label.copy(fontWeight = FontWeight.SemiBold))
                    Text(if (lit) "확인했어요" else "확인 중…", color = if (lit) cs.onSurface else cs.disabled, style = Title.copy(fontSize = 30.sp))
                }
            }
        }
    }
}

/** What 판독 checks, per scene, in order (the last one decides). */
fun checksFor(kind: CandidateKind): List<String> = when (kind) {
    CandidateKind.LANE_CHANGE_ATTEMPT -> listOf("깜빡이를 켠 곳", "빠질 길로 빠졌나", "옆 차로가 빈 틈")
    CandidateKind.PARKING -> listOf("다시 그린 주차 동선", "앞뒤로 고쳐 댄 횟수", "마지막 후진 때 핸들")
    CandidateKind.ROUTE_CHANGE -> listOf("벗어나기 전 도착 예상", "다시 찾은 경로", "도착 시간 차이")
    CandidateKind.TURN -> listOf("깜빡이를 켠 곳", "회전 30 m 전", "핸들을 꺾은 곳")
    else -> listOf("그 장면의 기록", "기준과 비교", "판정")
}

/** Thin progress line for the 1.5 s replay. */
@Composable
fun ReplayBar(progress: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(12.dp).background(cs.outline, PillShape)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(12.dp).background(Amber, PillShape))
    }
}
