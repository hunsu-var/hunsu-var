package app.hunsuvar.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One scripted signal change. Only non-null fields change; `t` is ms from scenario start. */
@Serializable
data class ScenarioEvent(
    val t: Long,
    val gear: Gear? = null,
    val speedKmh: Double? = null,
    val steeringDeg: Double? = null,
    val turn: Turn? = null,
    val brake: Boolean? = null,
    val wiperOn: Boolean? = null,
    val headlightOn: Boolean? = null,
    val unbeltedSeats: Int? = null,
    val speedLimitKmh: Int? = null,
    val schoolZone: Boolean? = null,
    val blindSpotLeft: Boolean? = null,
    val blindSpotRight: Boolean? = null,
    val nextManeuverM: Double? = null,
    val maneuverSide: Turn? = null,
    val reroutes: Int? = null,
    val remainSec: Int? = null,
)

@Serializable
data class Scenario(val name: String, val events: List<ScenarioEvent>)

private val json = Json { ignoreUnknownKeys = true }

fun parseScenario(text: String): Scenario = json.decodeFromString(text)

/** The demo trip: a made-up 7-minute drive (not a real recording) that puts every judged scene in one trip. Bundled as a classpath resource so tests and the app share one file. */
fun demoScenario(): Scenario = parseScenario(
    Scenario::class.java.getResourceAsStream("/demo_scenario.json")!!.bufferedReader().use { it.readText() })

fun ScenarioEvent.applyTo(s: Snapshot) = s.copy(
    gear = gear ?: s.gear,
    speedKmh = speedKmh ?: s.speedKmh,
    speedKnown = s.speedKnown || speedKmh != null,
    steeringDeg = steeringDeg ?: s.steeringDeg,
    turn = turn ?: s.turn,
    brake = brake ?: s.brake,
    wiperOn = wiperOn ?: s.wiperOn,
    headlightOn = headlightOn ?: s.headlightOn,
    unbeltedSeats = unbeltedSeats ?: s.unbeltedSeats,
    speedLimitKmh = speedLimitKmh ?: s.speedLimitKmh,
    schoolZone = schoolZone ?: s.schoolZone,
    blindSpotLeft = blindSpotLeft ?: s.blindSpotLeft,
    blindSpotRight = blindSpotRight ?: s.blindSpotRight,
    nextManeuverM = nextManeuverM ?: s.nextManeuverM,
    maneuverSide = maneuverSide ?: s.maneuverSide,
    reroutes = reroutes ?: s.reroutes,
    remainSec = remainSec ?: s.remainSec,
)

/** Feeds the whole scenario into [recorder] instantly, timestamps offset by [t0]. */
fun Scenario.replayInto(recorder: SignalRecorder, t0: Long = 0) =
    events.forEach { e -> recorder.update(t0 + e.t) { e.applyTo(it) } }
