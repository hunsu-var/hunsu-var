package app.hunsuvar

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.hunsuvar.core.Candidate
import app.hunsuvar.core.Claim
import app.hunsuvar.core.Gear
import app.hunsuvar.core.Judged
import app.hunsuvar.core.Records
import app.hunsuvar.core.SignalRecorder
import app.hunsuvar.core.Snapshot
import app.hunsuvar.core.Turn
import app.hunsuvar.core.on
import app.hunsuvar.core.stopped
import app.hunsuvar.core.Verdict
import app.hunsuvar.core.detectCandidates
import app.hunsuvar.core.varCandidates
import app.hunsuvar.core.carClaimFor
import app.hunsuvar.core.exampleRecords
import app.hunsuvar.core.habitsOf
import app.hunsuvar.core.judge
import app.hunsuvar.core.Marked
import app.hunsuvar.core.markCandidates
import app.hunsuvar.core.recordsFromJson
import app.hunsuvar.core.quickVerdict
import app.hunsuvar.core.QuickVerdict
import app.hunsuvar.core.parkingPathFor
import app.hunsuvar.core.claimsFor
import app.hunsuvar.core.CandidateKind
import app.hunsuvar.core.routeReplayFor
import app.hunsuvar.core.seasonReport
import app.hunsuvar.core.signalOnMeters
import app.hunsuvar.core.toJson
import app.hunsuvar.core.toJudged
import app.hunsuvar.core.toVerdict
import app.hunsuvar.ui.CandidateList
import app.hunsuvar.ui.ClaimPicker
import app.hunsuvar.ui.Consent
import app.hunsuvar.ui.History
import app.hunsuvar.ui.HunsuTheme
import app.hunsuvar.ui.Judging
import app.hunsuvar.ui.PastVerdict
import app.hunsuvar.ui.Recording
import app.hunsuvar.ui.ShareScreen
import app.hunsuvar.ui.Stats
import app.hunsuvar.ui.VerdictCard
import app.hunsuvar.ui.usesRoad
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Process-wide recorder, so recording survives activity recreation (and AAOS blocking, if the DO flag is ignored).
 * ponytail: process-scoped, not a foreground service. Upgrade if Pleos confirms the process gets killed
 * while driving (plan §9 "주행 중 기록이 막힘").
 */
object Session {
    val recorder = SignalRecorder()
    var source: VehicleSignalSource? = null
}

class MainActivity : ComponentActivity() {
    // CAR_SPEED is a dangerous permission. Signals flow either way; without speed, turn verdicts → 판독 불가.
    private val askSpeed = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Session.source == null) Session.source = PleosVehicleSignalSource(this).also { it.start(Session.recorder) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No trip history until the driver consents; the gear still flows for the parked gate.
        Session.recorder.recording = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("driverConsent", false)
        askSpeed.launch("android.car.permission.CAR_SPEED")
        setContent { HunsuTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() } } }
    }
}

private sealed interface Screen {
    data object Candidates : Screen
    data class PickClaim(val c: Candidate) : Screen
    data class Judging(val c: Candidate, val claim: Claim) : Screen
    /** [j] = this verdict as logged, plus what the people said after it (이의 있음, 둘 다 인정). */
    data class Result(val c: Candidate, val claim: Claim, val v: Verdict, val j: Judged) : Screen
    data class Share(val claim: Claim, val v: Verdict, val j: Judged) : Screen
    data object Stats : Screen
    data object History : Screen
    data class Past(val j: Judged) : Screen
}

/** A known moving gear always blocks the UI. Neutral counts as moving (the car can coast). */
private val MOVING = setOf(Gear.D, Gear.R, Gear.N)

@Composable
private fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Candidates) }
    var demo by remember { mutableStateOf<List<Snapshot>?>(null) }
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    // Season = derived from these device-only logs (core/Record.kt). Solo verdicts: REJECTED = driver beat the car.
    var records by remember { mutableStateOf(recordsFromJson(prefs.getString("records", null))) }
    fun save(r: Records) { records = r; prefs.edit().putString("records", r.toJson()).apply() }
    var driverOk by remember { mutableStateOf(prefs.getBoolean("driverConsent", false)) }
    var riderOk by remember { mutableStateOf(prefs.getBoolean("riderConsent", false)) }
    var solo by remember { mutableStateOf(prefs.getBoolean("solo", false)) } // the toggle decides; no seat-occupancy auto-detect
    var consentOpen by remember { mutableStateOf(!(driverOk && (solo || riderOk))) } // first run
    var accepted by remember { mutableStateOf(setOf<Long>()) } // solo [인정]: acknowledged, not judged
    var shareMsg by remember { mutableStateOf<String?>(null) }
    var gear by remember { mutableStateOf(Session.recorder.current()?.gear) }
    DisposableEffect(Unit) {
        Session.recorder.onGear = { gear = it } // pushed on gear change only; no polling
        gear = Session.recorder.current()?.gear // catch a change between remember and here
        onDispose { Session.recorder.onGear = null }
    }

    // Parked gate. UNKNOWN/no gear = not parked (safety), except in demo mode (bundled trip, no live gear needed).
    val parked = gear == Gear.P || (demo != null && gear !in MOVING)
    LaunchedEffect(parked) { if (!parked) { screen = Screen.Candidates; demo = null } }

    // 조수석 "훈수!" taps (recorder memory; dropped with driver consent). markTick re-reads them after a tap.
    var markTick by remember { mutableStateOf(0) }
    val marks = remember(markTick, gear, driverOk) { Session.recorder.marks() }
    // While driving 깜이 shows the car's blinker on its face (a still face: no glance, no blink).
    var signal by remember { mutableStateOf(Turn.OFF) }
    var quick by remember { mutableStateOf<QuickVerdict?>(null) } // one-line verdict for the latest tap, shown only while stopped
    LaunchedEffect(parked) {
        quick = null // never carried over: not into P, not into the next trip
        var judged: Int? = null // taps already judged at this stop; null = not standing still
        while (!parked) {
            val s = Session.recorder.current()
            val taps = Session.recorder.marks()
            when {
                // Moving, or speed unknown → the line goes at once; the next stop judges again.
                s?.stopped() != true -> { quick = null; judged = null }
                // Just stopped (e.g. a red light) → judge the latest tap once, off the main thread. Both seats must have agreed.
                judged == null -> {
                    judged = taps.size
                    if (driverOk && riderOk && !solo && taps.isNotEmpty())
                        withContext(Dispatchers.Default) { quickVerdict(Session.recorder.snapshots(), taps) }
                            .let { if (Session.recorder.current()?.stopped() == true) quick = it } // pulled away meanwhile → drop it
                }
                // Tapped during this stop → "찍었어요!" again; that tap gets its line at the next stop.
                taps.size != judged -> { quick = null; judged = taps.size }
            }
            signal = s?.turn?.takeIf { it.on } ?: Turn.OFF
            delay(200) // ponytail: 5 Hz poll of the latest snapshot; only a changed value recomposes
        }
    }
    if (!parked) {
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Recording(driverOk, solo, today = marks.count { it >= dayStart }, signal = signal, quick = quick,
            onMark = { Session.recorder.mark(nowMs()); markTick++; quick = null })
    }
    if (consentOpen) return Consent(driverOk, riderOk, solo,
        // Revoking also deletes the saved verdicts and habit log at once, not only the trip in memory.
        onDriver = { driverOk = it; Session.recorder.recording = it; prefs.edit().putBoolean("driverConsent", it).apply(); if (!it) save(Records()) },
        onRider = { riderOk = it; prefs.edit().putBoolean("riderConsent", it).apply() },
        onDone = { consentOpen = false })
    val consented = driverOk && (solo || riderOk) // solo: no rider whose 훈수 gets judged
    // Re-read the trip only when the gear changes (e.g. into P) or demo mode toggles. Nothing heavy runs while driving.
    // The recorder holds only the trip that just ended (see SignalRecorder), so the list is this trip's scenes.
    val trace = remember(gear, demo, driverOk) { demo ?: Session.recorder.snapshots() } // consent off wipes the recorder: re-read
    // Scanned off the main thread (a long trip takes a moment). null = not scanned yet.
    val all by produceState<List<Candidate>?>(null, trace, solo) {
        value = null // no stale list while rescanning
        value = withContext(Dispatchers.Default) { if (solo) detectCandidates(trace) else varCandidates(trace) }
    }
    // Habit log for the season report (signal lead per turn, harsh brakes). Live trips with driver consent only.
    LaunchedEffect(trace) {
        if (demo == null && driverOk) withContext(Dispatchers.Default) { habitsOf(trace, detectCandidates(trace)) }
            .let { h -> records.withHabits(h).let { if (it != records) save(it) } }
    }
    val candidates = all?.let { a -> if (solo) a.filter { it.startMs !in accepted } else a }
    // Taps belong to the live trip only; the bundled demo trip has none.
    val marked = remember(all, marks, demo) { if (demo == null) markCandidates(all.orEmpty(), marks) else Marked() }
    fun route(c: Candidate) = routeReplayFor(c, trace)
    fun parking(c: Candidate) = if (c.kind == CandidateKind.PARKING) parkingPathFor(c, trace) else null
    when (val s = screen) {
        Screen.Candidates -> if (candidates != null) CandidateList(
            candidates, consented, solo, recording = driverOk || demo != null, marked = marked.candidates, strays = marked.strays,
            onSolo = { solo = !solo; prefs.edit().putBoolean("solo", solo).apply() },
            // One thing anyone could have said about this scene → no picker, straight to 판독.
            onPick = { c -> screen = claimsFor(c.kind).singleOrNull()?.let { Screen.Judging(c, it) } ?: Screen.PickClaim(c) },
            onVar = { screen = Screen.Judging(it, carClaimFor(it.kind)) },
            onAccept = { accepted = accepted + it.startMs },
            onDemo = { demo = SignalRecorder().also { r -> FakeSignalSource().start(r) }.snapshots() },
            onHistory = { screen = Screen.History },
            onStats = { screen = Screen.Stats },
            onConsent = { consentOpen = true },
            demo = demo != null,
        )
        is Screen.PickClaim -> ClaimPicker(s.c, s.c in marked.candidates, onClaim = { screen = Screen.Judging(s.c, it) }, onBack = { screen = Screen.Candidates })
        is Screen.Judging -> {
            val r = remember(s, trace) { route(s.c) }
            val path = remember(s, trace) { parking(s.c) }
            val v = remember(s, trace) { judge(s.claim, s.c, trace) }
            val sweep = remember(s) { Animatable(0f) }
            LaunchedEffect(s) {
                sweep.animateTo(1f, tween(1_500, easing = LinearEasing)) // VAR suspense (≤ 1.5 s)
                val j = v.toJudged(System.currentTimeMillis(), s.claim, solo, scene = s.c.startMs)
                if (demo == null) save(records.withJudged(j)) // demo runs never count; judging a scene again replaces its verdict
                screen = Screen.Result(s.c, s.claim, v, j)
            }
            // Signal claims replay on the road; the same screen then becomes the verdict (no jump between layouts).
            if (usesRoad(s.claim, s.c.kind, v)) VerdictCard(s.claim, v, v.toJudged(0, s.claim, solo), r, s.c.kind, demo != null,
                onM = remember(s, trace) { signalOnMeters(trace, s.c) }, judging = sweep.value)
            else Judging(s.claim, s.c.kind, r, path, sweep.value, demo != null, solo)
        }
        is Screen.Result -> VerdictCard(s.claim, s.v, s.j, remember(s.c, trace) { route(s.c) }, s.c.kind, demo != null,
            onM = remember(s.c, trace) { signalOnMeters(trace, s.c) }, path = remember(s.c, trace) { parking(s.c) },
            onNext = { screen = Screen.Candidates }, onStats = { screen = Screen.Stats },
            onShare = { shareMsg = null; screen = Screen.Share(s.claim, s.v, s.j) },
            // 이의 있음 / 둘 다 인정: the verdict itself never changes. Demo: shown, not saved.
            onUpdate = { j -> if (demo == null) save(records.update(j)); screen = s.copy(j = j) })
        is Screen.Share -> ShareScreen(s.claim, s.v, s.j, demo != null, shareMsg,
            onShare = { shareMsg = if (ctx.shareCard(it)) null else "공유할 앱이 없어요" },
            onBack = { screen = Screen.Candidates })
        Screen.Stats -> {
            // Demo mode shows the bundled example season (labelled 예시), never the real one.
            val report = remember(records, demo) { System.currentTimeMillis().let { now -> seasonReport(if (demo != null) exampleRecords(now) else records, now) } }
            Stats(report, solo, demo != null,
                onShare = { j -> shareMsg = null; screen = Screen.Share(j.claim, j.toVerdict(), j) },
                onBack = { screen = Screen.Candidates })
        }
        // Demo mode lists the example season's verdicts (labelled 예시), never the real ones.
        Screen.History -> History(remember(records, demo) { if (demo != null) exampleRecords(System.currentTimeMillis()).judged else records.judged },
            demo != null, onOpen = { screen = Screen.Past(it) }, onBack = { screen = Screen.Candidates })
        is Screen.Past -> PastVerdict(s.j, demo != null,
            onShare = { shareMsg = null; screen = Screen.Share(s.j.claim, s.j.toVerdict(), s.j) },
            onBack = { screen = Screen.History })
    }
}

/** Saves the card PNG under cacheDir/shared (see res/xml/file_paths.xml) and sends it. false = nothing can handle it (likely a head unit). */
private fun Context.shareCard(bmp: Bitmap): Boolean {
    val file = File(cacheDir, "shared").apply { mkdirs() }.resolve("hunsu-var.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .apply { clipData = ClipData.newRawUri("", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    return try { startActivity(send); true } catch (_: ActivityNotFoundException) { false }
}
