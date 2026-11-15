package app.hunsuvar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import app.hunsuvar.core.ParkingPath
import app.hunsuvar.core.QuickVerdict
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.hunsuvar.core.Candidate
import app.hunsuvar.core.CandidateKind
import app.hunsuvar.core.Claim
import app.hunsuvar.core.Excuse
import app.hunsuvar.core.Judged
import app.hunsuvar.core.Outcome
import app.hunsuvar.core.RouteReplay
import app.hunsuvar.core.Season
import app.hunsuvar.core.SeasonReport
import app.hunsuvar.core.Verdict
import app.hunsuvar.core.Week
import app.hunsuvar.core.carClaimFor
import app.hunsuvar.core.claimsFor
import app.hunsuvar.core.excuseLine
import app.hunsuvar.core.loserSeat
import app.hunsuvar.core.shareHeadline
import app.hunsuvar.core.Turn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Parked-only screens except Recording. Big touch targets (96dp), cards 28, pill buttons; color = verdict words + 앰버 for actions.

/**
 * The only thing shown while not in P (DO): a big, still 깜이 face (nothing on this screen moves by itself; only the
 * car's blinker lights on its face) and, with driver consent, one big "훈수!" button for the 조수석. Solo mode has no
 * 조수석, so no button: the car finds the scenes itself at P. A tap only marks the moment: no animation, no sound.
 * 깜이 switches once to LISTEN and says so in a still bubble; while stopped the latest tap's verdict takes the bubble
 * ([quick]): the claim, the verdict word and one short line. Evidence and the full card wait for P.
 */
@Composable
fun Recording(on: Boolean, solo: Boolean, today: Int, signal: Turn, quick: QuickVerdict?, onMark: () -> Unit) {
    val sub = MaterialTheme.colorScheme.textSub
    val talking = on && (quick != null || today > 0)
    val face = when {
        !on -> Face.REC
        quick != null -> outcomeFace(quick.outcome)
        today > 0 -> Face.LISTEN
        else -> Face.NEUTRAL
    }
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 깜이 takes whatever the bubble and the button leave (the face is 2:1).
            BoxWithConstraints(Modifier.weight(if (talking) 1.4f else 1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                LampFace(face, minOf(maxWidth, maxHeight * 2), dot = false, look = if (talking) 0.8f else 0f, signal = signal)
            }
            if (talking) KkamiBubble(Modifier.weight(1f).padding(start = 8.dp), tail = Tail.LEFT, at = 140.dp) {
                if (quick != null) {
                    Text("“${quick.claim.label}” 판독 결과".keepAll(), color = LampLit.copy(alpha = 0.8f), style = Label.copy(fontSize = 30.sp))
                    Text(quick.outcome.label, color = Color.White, style = VerdictType.copy(fontSize = 72.sp, lineHeight = 84.sp))
                    Text("자세한 건 주차하면 보여 드릴게요", color = LampLit.copy(alpha = 0.7f), style = Label.copy(fontSize = 26.sp))
                } else {
                    Text("찍었어요!", color = Color.White, style = VerdictType.copy(fontSize = 60.sp, lineHeight = 72.sp))
                    Text("주차하면 이 장면부터 판독할게요.".keepAll(), color = LampLit, style = Body.copy(fontSize = 36.sp, fontWeight = FontWeight.SemiBold))
                    Text("오늘 ${today}개", Modifier.padding(top = 8.dp).background(Amber, PillShape).padding(horizontal = 20.dp, vertical = 6.dp),
                        color = OnAmber, style = Label.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
        if (!on) return@Column T("기록 안 함 · 운전자 동의 전", Body, color = sub)
        if (solo) return@Column Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("혼자 모드예요. 주차하면 제가 장면을 찾아 훈수를 둘게요.", Body, color = sub)
            T("● 기록 중", Body, color = sub)
        }
        // No ripple (indication = null): 깜이's face and bubble are the only feedback while driving.
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).background(Amber, PillShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onMark),
            contentAlignment = Alignment.Center) {
            T("훈수!", VerdictType.copy(fontSize = 64.sp, lineHeight = 72.sp), color = OnAmber)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(when {
                quick != null -> "신호 대기 중이라 보여 드려요 · 소리는 내지 않아요"
                today == 0 -> "조수석에서 눌러 주세요. 그 장면을 찍어 둬요."
                else -> ""
            }, Body, color = sub)
            T("● 기록 중", Body, color = sub)
        }
    }
}

fun outcomeFace(o: Outcome) = when (o) { Outcome.HIT -> Face.HIT; Outcome.REJECTED -> Face.REJECT; Outcome.UNDECIDABLE -> Face.UNDECIDED }

/**
 * [enabled] = consent given (see App). Before that only the count shows. [recording] = false: nothing was recorded (no driver consent).
 * [marked] = scenes the 조수석 tapped "훈수!" for (listed first); [strays] = taps with nothing to judge nearby.
 * 깜이 hosts on the left; scenes are cards with a sketch of what kind of moment it was. Solo mode: the car proposes.
 */
@Composable
fun CandidateList(
    candidates: List<Candidate>, enabled: Boolean, solo: Boolean, recording: Boolean, marked: Set<Candidate>, strays: List<Long>,
    onSolo: () -> Unit, onPick: (Candidate) -> Unit, onVar: (Candidate) -> Unit, onAccept: (Candidate) -> Unit,
    onDemo: () -> Unit, onHistory: () -> Unit, onStats: () -> Unit, onConsent: () -> Unit, demo: Boolean,
) = Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
    val sub = MaterialTheme.colorScheme.textSub
    val ordered = candidates.sortedByDescending { it in marked }
    val host = when {
        !enabled -> if (solo) "판독하려면 운전자가 동의해야 해요." else "판독하려면 운전자와 훈수꾼 둘 다 동의해야 해요."
        candidates.isEmpty() -> if (recording) "오늘은 판독할 장면이 없어요. 평화로운 드라이브였네요!" else "운전자가 동의하면 다음 주행부터 기록할게요."
        solo -> "혼자 탈 땐 제가 훈수를 둘게요. 억울하면 VAR을 요청하세요."
        marked.isNotEmpty() -> "오늘 판독할 만한 장면이 ${candidates.size}개 있어요. 조수석이 찍은 장면부터 볼까요?"
        else -> "오늘 판독할 만한 장면이 ${candidates.size}개 있어요. 말다툼이 있었던 장면을 골라 주세요."
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(440.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("오늘의 판독 후보", Headline)
                if (candidates.isNotEmpty()) T("  ${candidates.size}", Headline, color = sub)
            }
            LampFace(Face.NEUTRAL, 380.dp, look = 0.6f, modifier = Modifier.padding(top = 24.dp))
            KkamiLine(host, Modifier.fillMaxWidth(), tail = Tail.UP, at = 176.dp, size = 32)
            Box(Modifier.weight(1f))
            if (demo) DemoTag()
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            strays.forEach { T("${clock(it)}에 찍은 순간 근처에는 판독할 장면이 없어요", Label, color = sub) }
            if (enabled) {
                ordered.firstOrNull()?.let { c -> SceneCard(c, c in marked, solo, featured = true, onPick, onVar, onAccept) }
                ordered.drop(1).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { c -> SceneCard(c, c in marked, solo, featured = false, onPick, onVar, onAccept, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Solo ON = selected state: primary fill.
        if (solo) Primary("혼자 모드 켬", Modifier.weight(1f), onClick = onSolo) else Secondary("혼자 모드 끔", Modifier.weight(1f), onClick = onSolo)
        Secondary("시연 리플레이", Modifier.weight(1f), onClick = onDemo)
        Secondary("지난 판정", Modifier.weight(1f), onClick = onHistory)
        Secondary("시즌 기록", Modifier.weight(1f), onClick = onStats)
        Secondary("동의 설정", Modifier.weight(1f), onClick = onConsent)
    }
}

/** One scene: sketch, a mini 깜이 (LISTEN = the 조수석 marked it, NEUTRAL = the car found it), time and kind. */
@Composable
private fun SceneCard(c: Candidate, marked: Boolean, solo: Boolean, featured: Boolean, onPick: (Candidate) -> Unit,
                      onVar: (Candidate) -> Unit, onAccept: (Candidate) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tap = if (solo) Modifier else Modifier.clip(CardShape).clickable(role = Role.Button) { onPick(c) }
    Column(modifier.fillMaxWidth().card().then(tap).then(if (featured && marked) Modifier.border(3.dp, Amber, CardShape) else Modifier).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SceneThumb(c.kind, Modifier.fillMaxWidth().height(if (featured) 200.dp else 150.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LampFace(if (marked) Face.LISTEN else Face.NEUTRAL, if (featured) 96.dp else 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    T(clock(c.startMs), Label, color = cs.textSub)
                    if (marked) Text("조수석이 찍은 장면", Modifier.background(Amber, PillShape).padding(horizontal = 16.dp, vertical = 4.dp),
                        color = OnAmber, style = Label.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    else if (featured) T("차가 찾은 장면", Label, color = cs.textSub)
                }
                T(if (solo) "제가 훈수 두자면 “${carClaimFor(c.kind).label}”" else c.kind.label,
                    if (featured) Headline.copy(fontSize = 44.sp) else Title)
            }
            if (featured && !solo) Primary("이 장면 판독", Modifier) { onPick(c) }
        }
        if (solo) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Primary("VAR 요청", Modifier.weight(1f)) { onVar(c) }
            Secondary("인정", Modifier.weight(1f)) { onAccept(c) }
        }
    }
}

/** First-run consent. Driver (+ rider unless solo) ON or judging stays disabled. Each flip is saved by the caller at once (so it doubles as revoke). */
@Composable
fun Consent(driver: Boolean, rider: Boolean, solo: Boolean, onDriver: (Boolean) -> Unit, onRider: (Boolean) -> Unit, onDone: () -> Unit) =
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        T("판독 전에 확인할게요", Headline)
        T("주행 기록은 이 차 안에서만 쓰여요.", Body, color = MaterialTheme.colorScheme.textSub)
        T("운전자 동의를 끄면 쌓인 주행 기록과 판정·시즌 기록을 바로 지워요.", Body, color = MaterialTheme.colorScheme.textSub)
        ConsentRow("운전자 동의", "내 주행 신호를 기록하고 판독해도 좋아요", driver, onDriver)
        ConsentRow("훈수꾼 동의", "내 훈수가 판독돼도 좋아요", rider, onRider)
        if (solo) T("혼자 모드에선 운전자 동의만 있으면 돼요.", Body, color = MaterialTheme.colorScheme.textSub)
        Box(Modifier.weight(1f))
        if (driver && (solo || rider)) Primary("시작하기", Modifier.fillMaxWidth(0.3f).align(Alignment.End), onClick = onDone)
        else Secondary("나중에", Modifier.fillMaxWidth(0.3f).align(Alignment.End), onClick = onDone)
    }

@Composable
private fun ConsentRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().card().clip(CardShape).heightIn(min = 96.dp)
        .toggleable(on, role = Role.Switch, onValueChange = onChange).padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { T(title, Title); T(sub, Label, color = cs.textSub) }
        Switch(on, null, colors = SwitchDefaults.colors(
            uncheckedThumbColor = cs.disabled, uncheckedBorderColor = cs.disabled, uncheckedTrackColor = cs.surface))
    }
}

/** 깜이 asks, the 조수석 answers: each claim is a bubble of what was said. Only shown when a scene has more than one. */
@Composable
fun ClaimPicker(c: Candidate, marked: Boolean, onClaim: (Claim) -> Unit, onBack: () -> Unit) =
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().card().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneThumb(c.kind, Modifier.width(520.dp).height(150.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("${clock(c.startMs)} · ${if (marked) "조수석이 찍은 장면" else "차가 찾은 장면"}", Label.copy(fontSize = 26.sp), color = cs.textSub)
                T(c.kind.label, Headline)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                LampFace(Face.LISTEN, 300.dp, look = 0.8f, modifier = Modifier.padding(start = 40.dp))
                KkamiLine("그때 조수석에서 뭐라고 했어요? 들은 말과 가장 비슷한 걸 골라 주세요.", tail = Tail.UP, at = 170.dp, size = 34)
                T("고르면 바로 제 기록으로 판독해요 · 1.5초", Label, color = cs.textSub)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.End) {
                T("조수석이 한 말", Label.copy(fontSize = 26.sp), color = cs.textSub)
                claimsFor(c.kind).forEach { claim -> SaidBubble(claim.label, tail = Tail.RIGHT, at = 40.dp, size = 48) { onClaim(claim) } }
            }
        }
        Secondary("뒤로", Modifier.width(400.dp), onClick = onBack)
    }

/** The 1.5 s replay: 깜이 with no face (both lamps on), the scene redrawn, and the checks lighting one by one. */
@Composable
fun Judging(claim: Claim, kind: CandidateKind, r: RouteReplay, path: ParkingPath?, progress: Float, demo: Boolean, solo: Boolean) =
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SaidBubble(claim.label, who = if (solo) "차의 훈수" else "조수석", tail = Tail.NONE, size = 32)
            Box(Modifier.weight(1f))
            if (demo) DemoTag()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            LampFace(Face.JUDGING, 300.dp)
            KkamiBubble(Modifier.weight(1f), tail = Tail.LEFT, at = 60.dp) {
                Text("VAR 판독 중", color = Color.White, style = VerdictType.copy(fontSize = 56.sp, lineHeight = 64.sp))
                Text("Vehicle Assistant Referee · 제 기록을 그 장면 처음부터 다시 돌려 보고 있어요.".keepAll(), color = LampLit, style = Body.copy(fontSize = 29.sp))
            }
        }
        if (path != null) ParkingPathView(path, progress, Modifier.fillMaxWidth().height(380.dp))
        else RouteReplayView(r, progress, demo, MaterialTheme.colorScheme.accent)
        CheckLamps(checksFor(kind), progress, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f))
        ReplayBar(progress)
    }

/** Turn-signal claims with a real verdict are replayed on the road seen from above (solo practice). */
fun usesRoad(claim: Claim, kind: CandidateKind, v: Verdict) =
    kind == CandidateKind.TURN && (claim == Claim.SIGNAL_LATE || claim == Claim.SIGNAL_MISSING) && v.outcome != Outcome.UNDECIDABLE

/**
 * 판정 화면 v5: [what was said + verdict word + "판독 결과 · 근거"] beside [깜이 + its line] → the scene redrawn →
 * [law | 다음엔 이렇게] → buttons. The data layer (word, evidence, scene) decides; 깜이 only explains and looks after
 * whoever lost. [judging] = the turn replay before the verdict. No seat win/lose: the loser loses to the line.
 */
@Composable
fun VerdictCard(claim: Claim, v: Verdict, j: Judged, r: RouteReplay, kind: CandidateKind, demo: Boolean,
                onM: Double?, path: ParkingPath? = null, judging: Float? = null,
                onNext: () -> Unit = {}, onStats: () -> Unit = {}, onShare: () -> Unit = {}, onUpdate: (Judged) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val solo = j.solo
    val loser = loserSeat(v.outcome, solo)
    val road = usesRoad(claim, kind, v)
    var objecting by remember { mutableStateOf(false) }
    val again = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val p = judging ?: again.value
    val done = p >= 1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1200.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SaidBubble(claim.label, who = if (solo) "차의 훈수" else "조수석", size = 36)
                        if (done) Chip("신뢰도 ${v.confidence.label}", Modifier.padding(bottom = 6.dp))
                        if (demo) DemoTag(Modifier.padding(bottom = 6.dp))
                    }
                    VerdictWord(v.outcome, done)
                    if (done) T("판독 결과 · ${v.evidence}", Body.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold))
                    if (done) j.excuseLine?.let { T(it, Body, color = cs.textSub) }
                }
                if (wide) Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LampFace(if (done) outcomeFace(v.outcome) else Face.JUDGING, 280.dp, look = -0.6f, modifier = Modifier.padding(start = 56.dp))
                    if (done) KkamiLine(kkamiSays(v, solo), tail = Tail.UP, at = 190.dp, size = 29)
                }
            }
            if (!wide && done) KkamiLine(kkamiSays(v, solo), tail = Tail.NONE, size = 29)

            when {
                road -> RoadReplay(onM, p, demo)
                path != null -> ParkingPathView(path, p, Modifier.fillMaxWidth().height(380.dp))
                else -> RouteReplayView(r, p, demo, v.outcome.color)
            }

            if (done) {
                val law: @Composable (Modifier) -> Unit = { m -> Panel(m) { if (claim.littleKnown) LittleKnown(); T(v.law, Label, color = cs.textSub) } }
                val tip: @Composable (Modifier) -> Unit = { m -> TipCard(v.tip, m) }
                if (wide) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    law(Modifier.weight(1.3f).fillMaxHeight()); tip(Modifier.weight(1f).fillMaxHeight())
                } else { law(Modifier.fillMaxWidth()); tip(Modifier.fillMaxWidth()) }
            }

            // 이의 있음: the loser adds their side from a fixed list. Stored next to the verdict; the verdict stays as it is.
            if (done && objecting && loser != null) Panel(Modifier.fillMaxWidth()) {
                T("$loser 사정을 골라 주세요. 판정은 바뀌지 않아요.", Body)
                Excuse.values().toList().chunked(if (wide) 3 else 2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { e -> Secondary(e.label, Modifier.weight(1f), Label) { onUpdate(j.copy(excuse = e)); objecting = false } }
                    }
                }
            }
            if (judging == null) {
                val buttons = buildList<@Composable (Modifier) -> Unit> {
                    if (road || path != null) add { m -> Secondary("다시 보기", m) { scope.launch { again.snapTo(0f); again.animateTo(1f, tween(1_500, easing = LinearEasing)) } } }
                    add { m -> Secondary("다른 후보", m, onClick = onNext) }
                    add { m -> Secondary("시즌 기록", m, onClick = onStats) }
                    if (loser != null) add { m -> Secondary("이의 있어요", m) { objecting = !objecting } }
                    // 둘 다 인정 (not in solo: the car can't agree). Toggle; filled = recorded.
                    if (!solo) add { m ->
                        if (j.agreed) Primary("둘 다 인정함", m) { onUpdate(j.copy(agreed = false)) }
                        else Secondary("둘 다 인정", m) { onUpdate(j.copy(agreed = true)) }
                    }
                    add { m -> Primary("공유 카드 만들기", m, onClick = onShare) }
                }
                buttons.chunked(if (wide) 6 else 3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { it(Modifier.weight(1f)) } }
                }
            }
        }
    }
}

/** 다음엔 이렇게: on lamp cream by day (깜이's color), the inner surface at night. */
@Composable
private fun TipCard(tip: String, modifier: Modifier) = Column(
    modifier.background(if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else LampLit, CardShape).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    T("다음엔 이렇게", Label.copy(fontWeight = FontWeight.Bold), color = amberInk)
    T(tip, Body.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
}

/** 판정 단어 + 판정색 밑줄 (the 빛의 선). Before the verdict: "판독 중…" in the disabled ink, no line. */
@Composable
private fun VerdictWord(o: Outcome, done: Boolean) = Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    T(if (done) o.label else "판독 중…", VerdictType, color = if (done) o.color else MaterialTheme.colorScheme.disabled)
    Box(Modifier.width(480.dp).height(8.dp).alpha(if (done) 1f else 0f).background(o.color, PillShape))
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(text, modifier.background(cs.surface, PillShape).border(2.dp, cs.outline, PillShape).padding(horizontal = 24.dp, vertical = 10.dp),
        style = Label.copy(fontWeight = FontWeight.Bold))
}

/** 깜이's line: never repeats the verdict, never mocks whoever lost; at most one fact (the other side's circumstance). */
fun kkamiSays(v: Verdict, solo: Boolean): String = when (v.outcome) {
    Outcome.UNDECIDABLE -> "제 기록으로는 여기까지만 알 수 있어요. 이번\u00A0판은 무승부!"
    Outcome.REJECTED -> "제 기록으로는 괜찮았어요. " + if (solo) "이번엔 제가 졌네요." else "그래도 챙겨 준 마음은 잘 받았어요."
    Outcome.HIT -> if ("옆 차로 뒤쪽이 비어" in v.evidence) "그 전에는 옆 차가 지나가는 중이었어요. 망설일 만했어요."
        else "제 기록도 같은 말을 하네요. 급할 땐 그럴\u00A0수\u00A0있어요."
}

private fun claimLine(claim: Claim, solo: Boolean) = if (solo) "차의 훈수 “${claim.label}”" else "“${claim.label}”"

/**
 * Share preview + capture. What you see is exactly what gets shared: no clock time, no location, no speed numbers
 * (the 판정 근거 line is left out; [Verdict.shareHeadline] drops speed readings). [onShare] gets the rendered card.
 */
@Composable
fun ShareScreen(claim: Claim, v: Verdict, j: Judged, demo: Boolean, status: String?, onShare: (android.graphics.Bitmap) -> Unit, onBack: () -> Unit) {
    val solo = j.solo
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
            Column(Modifier.fillMaxWidth().background(cs.surface).padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    T("훈수 VAR", Headline.copy(fontWeight = FontWeight.ExtraBold))
                    T("Vehicle Assistant Referee", Label, color = cs.textSub)
                    Box(Modifier.weight(1f))
                    if (demo) DemoTag()
                }
                SaidBubble(claim.label, who = if (solo) "차의 훈수" else "조수석", size = 48)
                VerdictWord(v.outcome, true)
                T(v.shareHeadline, Body)
                j.excuseLine?.let { T(it, Body, color = cs.textSub) }
                if (j.agreed) T("둘 다 인정한 판정", Label, color = cs.textSub)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    LampFace(outcomeFace(v.outcome), 200.dp, look = 0.6f)
                    KkamiLine(kkamiSays(v, solo), Modifier.weight(1f), tail = Tail.LEFT, at = 40.dp, size = 28)
                }
                TipCard(v.tip, Modifier.fillMaxWidth())
                T("위치·시각·속도는 지우고 공유해요", Label, color = cs.textSub)
            }
        }
        if (status != null) T(status, Body, color = cs.textSub)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Secondary("뒤로", Modifier.weight(1f), onClick = onBack)
            Primary("공유하기", Modifier.weight(1f)) { scope.launch { onShare(layer.toImageBitmap().asAndroidBitmap()) } }
        }
    }
}

/** 지난 판정: every logged verdict, newest first; tap one to read it again. [demo] = the example season's verdicts. */
@Composable
fun History(judged: List<Judged>, demo: Boolean, onOpen: (Judged) -> Unit, onBack: () -> Unit) =
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        val sub = MaterialTheme.colorScheme.textSub
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("지난 판정", Headline)
            if (judged.isNotEmpty()) T("  ${judged.size}건", Headline)
            if (demo) DemoTag(Modifier.padding(start = 24.dp))
        }
        if (judged.isEmpty()) { T("아직 판정한 장면이 없어요.", Body, color = sub); Box(Modifier.weight(1f)) }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(judged.sortedByDescending { it.tMs }) { j ->
                Row(Modifier.fillMaxWidth().card().clip(CardShape).clickable(role = Role.Button) { onOpen(j) }.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    LampFace(outcomeFace(j.outcome), 96.dp)
                    T(day(j.tMs), Label, color = sub)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        T(claimLine(j.claim, j.solo), Title)
                        listOfNotNull(if (j.agreed) "둘 다 인정" else null, j.excuseLine).takeIf { it.isNotEmpty() }
                            ?.let { T(it.joinToString(" · "), Label, color = sub) }
                    }
                    OutcomeChip(j.outcome)
                }
            }
        }
        Secondary("목록으로", Modifier.fillMaxWidth(0.3f), onClick = onBack)
    }

/** One past verdict, read-only, from what was logged (no replay: the trip itself isn't kept). */
@Composable
fun PastVerdict(j: Judged, demo: Boolean, onShare: () -> Unit, onBack: () -> Unit) {
    val sub = MaterialTheme.colorScheme.textSub
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${day(j.tMs)} · ${claimLine(j.claim, j.solo)}", Label.copy(fontSize = 28.sp), color = sub)
            if (demo) DemoTag(Modifier.padding(start = 24.dp))
        }
        VerdictWord(j.outcome, true)
        T(j.headline, Body)
        j.excuseLine?.let { T(it, Body, color = sub) }
        if (j.agreed) T("둘 다 인정한 판정", Label, color = sub)
        Panel(Modifier.fillMaxWidth()) {
            if (j.claim.littleKnown) LittleKnown()
            T(j.law, Label, color = sub)
            Tip(j.tip)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Secondary("지난 판정으로", Modifier.weight(1f), onClick = onBack)
            Primary("공유", Modifier.weight(1f), onClick = onShare)
        }
    }
}

/** Verdict word as an outlined chip: the word itself carries the meaning, color only repeats it. */
@Composable
private fun OutcomeChip(o: Outcome) =
    Text(o.label, Modifier.border(2.dp, o.color, PillShape).padding(horizontal = 24.dp, vertical = 10.dp),
        color = o.color, style = Title.copy(fontWeight = FontWeight.ExtraBold))

/** Outline tag: demo runs use bundled data, not this car's trip. */
@Composable
private fun DemoTag(modifier: Modifier = Modifier) {
    val sub = MaterialTheme.colorScheme.textSub
    Text("예시 · 시연 데이터", modifier.border(2.dp, sub, ControlShape).padding(horizontal = 16.dp, vertical = 8.dp), color = sub, style = Label)
}

/** 기획 §3: rules most drivers get wrong (Claim.littleKnown) get this line above the law card, with the amber lamp dot. */
@Composable
private fun LittleKnown() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.size(14.dp).background(Amber, CircleShape))
    T("이 규칙 몰랐죠?", Title, color = amberInk)
}

@Composable
private fun Tip(tip: String) = Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, ControlShape).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    T("다음엔 이렇게", Label, color = MaterialTheme.colorScheme.textSub)
    T(tip, Body)
}

/**
 * 시즌 기록 v5 (기획 §8): story cards, one big number each. First what the car and the two of them did together (no seat
 * scoreboard up front), then the blinker habit with rest weeks as commas, the week's best call, and 깜이's rule note:
 * the rules this car missed most this season (recorded quietly, not argued over), with the law and what to do.
 */
@Composable
fun Stats(r: SeasonReport, solo: Boolean, demo: Boolean, onShare: (Judged) -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = r.record
    val total = s.hits + s.rejects + s.undecided
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("시즌 ${r.number} · ${r.day}일째", Headline.copy(fontSize = 52.sp, fontWeight = FontWeight.ExtraBold))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Chip("한 시즌은 4주"); if (demo) DemoTag() }
            }
            Box(Modifier.weight(1f))
            KkamiLine(if (total == 0) "이번 시즌엔 아직 판독한 훈수가 없어요." else "이번 시즌 우리 차에서 훈수 ${total}건을 판독했어요.",
                Modifier.width(560.dp), tail = Tail.RIGHT, at = 34.dp, size = 29)
            LampFace(Face.NEUTRAL, 220.dp, look = -0.6f)
        }
        Row(Modifier.fillMaxWidth().height(620.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Story(Modifier.weight(1f), Color(0xFF15171A)) {
                T("우리 차 판독", Label.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = LampLit)
                Row(verticalAlignment = Alignment.Bottom) { T("$total", Huge, color = Color.White); T("건", Headline.copy(fontSize = 64.sp), color = Color.White) }
                if (total > 0) Row(Modifier.fillMaxWidth().height(20.dp).clip(PillShape), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.hits > 0) Box(Modifier.weight(s.hits.toFloat()).fillMaxHeight().background(Color(0xFF3CCB84)))
                    if (s.rejects > 0) Box(Modifier.weight(s.rejects.toFloat()).fillMaxHeight().background(Color(0xFFF2F3F5)))
                    if (s.undecided > 0) Box(Modifier.weight(s.undecided.toFloat()).fillMaxHeight().background(Color(0xFFA3A9B1)))
                }
                T("적중 ${s.hits} · 기각 ${s.rejects} · 판독 불가 ${s.undecided}", Label.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFFF2F3F5))
                Box(Modifier.weight(1f))
                T("운전자 무죄율 ${pct(s.innocenceRate)}", Label, color = Color(0xFFA3A9B1))
                T("훈수꾼 적중률 ${pct(s.hitRate)}", Label, color = Color(0xFFA3A9B1))
                T("둘 다 인정한 판정 ${r.agreed}건", Label, color = Color(0xFFA3A9B1))
            }
            val latest = r.weeks.lastOrNull { it.avgLeadMs != null }
            Story(Modifier.weight(1f), Amber) {
                T("깜빡이 습관", Label.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = OnAmber)
                Row(verticalAlignment = Alignment.Bottom) {
                    T(latest?.avgLeadMs?.let { "%.1f".format(it / 1000.0) } ?: "–", Huge, color = OnAmber)
                    T("초", Headline.copy(fontSize = 64.sp), color = OnAmber)
                }
                T(if (latest == null) "이번 시즌엔 아직 회전 기록이 없어요." else "깜빡이를 핸들보다 평균 ${lead(latest.avgLeadMs)} 켰어요.",
                    Body.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = OnAmber)
                latest?.avgLeadMs?.let { ms ->
                    Text(when { ms >= 1_800 -> "미리 켜는 편"; ms >= 500 -> "아슬아슬한 편"; else -> "늦게 켜는 편" },
                        Modifier.background(OnAmber, PillShape).padding(horizontal = 20.dp, vertical = 8.dp), color = LampLit, style = Label.copy(fontWeight = FontWeight.Bold))
                }
                Box(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    (0 until 4).forEach { i -> WeekBar("${i + 1}주차", r.weeks.getOrNull(i), Modifier.weight(1f)) }
                }
            }
            Story(Modifier.weight(1f), cs.surface, shadow = true) {
                T("이번 주 명판정", Label.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = cs.textSub)
                val b = r.best
                if (b == null) T("아직 명판정이 없어요. 다음 말다툼을 기다릴게요.", Body)
                else {
                    T("“${b.claim.label}”", Title.copy(fontWeight = FontWeight.Bold))
                    T(b.outcome.label, Headline.copy(fontSize = 56.sp, fontWeight = FontWeight.ExtraBold), color = b.outcome.color)
                    T(b.headline, Label.copy(fontSize = 25.sp), color = cs.textSub)
                    b.excuseLine?.let { T(it, Label, color = cs.textSub) }
                    Box(Modifier.weight(1f))
                    Primary("명판정 카드 공유", Modifier.fillMaxWidth()) { onShare(b) }
                }
            }
            Story(Modifier.weight(1f), if (isSystemInDarkTheme()) cs.surfaceVariant else LampLit) {
                T("깜이의 규칙 노트", Label.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = amberInk)
                val top = r.rules.firstOrNull()
                if (top == null) T("이번 시즌 놓친 규칙이 없어요. 이대로만 가요!", Body.copy(fontWeight = FontWeight.SemiBold))
                else {
                    LittleKnown()
                    T(top.line, Body.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold))
                    T(top.law, Label.copy(fontSize = 21.sp), color = cs.textSub)
                    T("다음엔 이렇게 · ${top.tip}", Label.copy(fontWeight = FontWeight.Bold))
                    if (r.rules.size > 1) T("그 밖에 " + r.rules.drop(1).joinToString(" · ") { "${it.kind.label} ${it.count}번" }, Label, color = cs.textSub)
                }
            }
        }
        // Solo season: 기각 = driver beat the car, 적중 = car beat the driver, 판독 불가 = 무승부.
        if (solo || r.vsCar != Season()) T("혼자 탈 때 차 상대 전적 · 운전자 ${r.vsCar.rejects}승 · 차 ${r.vsCar.hits}승 · 무승부 ${r.vsCar.undecided}", Body, color = cs.textSub)
        val g = r.graduation
        if (g == null) T("도로연수 졸업 리포트: ${r.daysToGraduation}일 뒤에 열려요 (첫 기록부터 100일)", Body, color = cs.textSub)
        else Panel(Modifier.fillMaxWidth()) {
            T("도로연수 졸업 리포트 (100일)", Title, color = cs.textSub)
            T("깜빡이를 핸들보다 먼저 켠 시간: 첫 주 ${lead(g.first.avgLeadMs)} → 최근 1주 ${lead(g.last.avgLeadMs)}", Body)
            T("급제동: 첫 주 ${g.first.harshBrakes}회 → 최근 1주 ${g.last.harshBrakes}회", Body)
            T("판독 ${g.judged}건 · 운전자 무죄율 ${pct(g.innocenceRate)}", Body)
        }
        Secondary("목록으로", Modifier.width(400.dp), onClick = onBack)
    }
}

private val Huge = VerdictType.copy(fontSize = 150.sp, lineHeight = 150.sp, letterSpacing = (-0.05).em)

@Composable
private fun Story(modifier: Modifier, color: Color, shadow: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
    Column((if (shadow) modifier.card(color, RoundedCornerShape(32.dp)) else modifier.background(color, RoundedCornerShape(32.dp))).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

/** One week of the season: bar = average signal lead; a week with no turn yet is a comma (쉼), not a failure. */
@Composable
private fun WeekBar(name: String, w: Week?, modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val ms = w?.avgLeadMs
    if (ms == null) Box(Modifier.fillMaxWidth().height(70.dp).border(3.dp, OnAmber.copy(alpha = 0.35f), ControlShape))
    else Box(Modifier.fillMaxWidth().height((70f * (ms.coerceIn(200L, 2_500L)) / 2_500L).dp.coerceAtLeast(14.dp)).background(OnAmber, ControlShape))
    Text(if (w != null && ms == null) "쉼표" else name, Modifier.fillMaxWidth(), color = OnAmber, style = Label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center)
}

/** Signal lead for display: "1.9초 먼저" / "0.4초 늦게" / no turn that week. */
private fun lead(ms: Long?) = when {
    ms == null -> "회전 기록 없음"
    ms >= 0 -> "%.1f초 먼저".format(ms / 1000.0)
    else -> "%.1f초 늦게".format(-ms / 1000.0)
}

// --- tiny helpers ---

@Composable
private fun T(text: String, style: TextStyle, color: Color = Color.Unspecified) = Text(text.keepAll(), color = color, style = style)

/**
 * 한글 keep-all: Android breaks Hangul between any two syllables ("마 / 음은"). Word joiners (U+2060, invisible)
 * between the letters of each word leave only the spaces as break points. A word wider than the line still breaks.
 */
fun String.keepAll() = split(' ').joinToString(" ") { w -> w.toList().joinToString("⁠") }

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.card().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)

/** Primary (앰버) fill, ink text. One per screen, bottom right where there is a choice; also the selected state. */
@Composable
private fun Primary(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier.heightIn(min = 96.dp), enabled, shape = PillShape) { T(text, Body.copy(fontWeight = FontWeight.ExtraBold)) }

/** Surface + 2dp line. */
@Composable
private fun Secondary(text: String, modifier: Modifier, style: TextStyle = Body, enabled: Boolean = true, shape: Shape = PillShape, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedButton(onClick, modifier.heightIn(min = 96.dp), enabled, shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = cs.surface, contentColor = cs.onSurface),
        border = BorderStroke(2.dp, cs.outline)) { T(text, style) }
}

private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(ms))
private fun day(ms: Long) = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(ms))
private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
