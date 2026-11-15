package app.hunsuvar

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import app.hunsuvar.core.Gear
import app.hunsuvar.core.SignalRecorder
import app.hunsuvar.core.Snapshot
import app.hunsuvar.core.Turn
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Live signals. Every API below was checked against the Vehicle 2.3.2 / NaviHelper 3.0.5 AARs (javap)
 * and the Pleos docs sitemap. Speed and gear come from AAOS CarPropertyManager, as Pleos docs instruct.
 * (Vehicle 2.3.2 also ships an undocumented `getGear()`; not used because it isn't in the public docs.)
 */
class PleosVehicleSignalSource(context: Context) : VehicleSignalSource {
    private val ctx = context.applicationContext
    private val vehicle = Vehicle(ctx)
    private val navi = NaviHelper(ctx)
    private var car: Car? = null
    private var rec: SignalRecorder? = null

    private fun put(change: (Snapshot) -> Snapshot) {
        rec?.let { r ->
            r.update(nowMs(), change)
            if (BuildConfig.DEBUG) Log.d(TAG, "put ${Throwable().stackTrace.getOrNull(2)?.methodName} ${r.current()}")
        }
    }
    private fun fail(e: Throwable) { Log.w(TAG, "signal unavailable on this car/emulator", e) }
    private inline fun safe(block: () -> Unit) = try { block() } catch (e: Exception) { fail(e) }

    // --- Vehicle SDK listeners (all values are nullable in the SDK) ---
    private val turnL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = put {
            it.copy(turn = when (value) { TurnSignalValue.LEFT -> Turn.LEFT; TurnSignalValue.RIGHT -> Turn.RIGHT; null -> Turn.UNKNOWN; else -> Turn.OFF })
        }
        override fun onFailed(e: Exception) = fail(e)
    }
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) { value?.let { v -> put { it.copy(steeringDeg = v.toDouble()) } } }
        override fun onFailed(e: Exception) = fail(e)
    }
    /** Pedal pressed yes/no only — harsh braking is inferred from speed drop while pressed. */
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) { value?.let { v -> put { it.copy(brake = v) } } }
        override fun onFailed(e: Exception) = fail(e)
    }
    private val wiperL = object : WiperStateListener {
        override fun onWiperStateUpdated(value: WiperState?) = put {
            it.copy(wiperOn = when (value) { WiperState.ON -> true; WiperState.OFF -> false; else -> null })
        }
        override fun onFailed(e: Exception) = fail(e)
    }
    /** Actual lamp state (works for auto-light cars). DRL is not 전조등 → counts as off. */
    private val lowBeamL = object : LightStateListener {
        override fun onLightStateUpdated(value: LightState?) = put {
            it.copy(headlightOn = when (value) { LightState.ON -> true; null -> null; else -> false })
        }
        override fun onFailed(e: Exception) = fail(e)
    }

    /**
     * Blind-spot (후측방) warnings, left and right apart. Needs PRIVATE_CAR_SAFETY: without it registering throws and the
     * values stay unknown (null). The list holds only the lit telltales (emulator: OFF items drop out), so absent = off.
     * TODO(verify): the emulator cannot light LCA, so whether a real car always sends the full list (not just changes) is
     * unconfirmed; if it sends only changes, a still-lit LCA would read as off. Log one real callback to confirm.
     */
    private val telltaleL = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            val lit = telltales?.telltales?.filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }?.map { it.type }
            put { it.copy(blindSpotLeft = lit?.contains(TelltaleType.LCA_LEFT), blindSpotRight = lit?.contains(TelltaleType.LCA_RIGHT)) }
        }
        override fun onFailed(e: Exception) = fail(e)
    }

    private val seats = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
    private val occupied = ConcurrentHashMap<SeatValue, Boolean>()
    private val buckled = ConcurrentHashMap<SeatValue, Boolean>()
    // The emulator reports every belt unbuckled forever (and can't change it) → a fake 벨트 candidate. Real cars report
    // buckled=true at some point, so belts count as unknown until one seat has ever been buckled in this process.
    @Volatile private var everBuckled = false

    private fun publishBelts() {
        val known = everBuckled && seats.any { occupied.containsKey(it) && buckled.containsKey(it) }
        val unbelted = seats.count { occupied[it] == true && buckled[it] == false }
        put { it.copy(unbeltedSeats = if (known) unbelted else null) }
    }

    private fun record(map: ConcurrentHashMap<SeatValue, Boolean>, seat: SeatValue, v: Boolean?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "seat raw ${if (map === occupied) "occupied" else "buckled"} $seat=$v")
        if (v == null) map.remove(seat) else { map[seat] = v }
        if (map === buckled && v == true) everBuckled = true
        publishBelts()
    }

    private inner class SeatWatch(val seat: SeatValue) {
        val area = SeatArea(seat)
        val occupancy = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) = record(occupied, seat, value)
            override fun onFailed(e: Exception) = fail(e)
        }
        val belt = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) = record(buckled, seat, value)
            override fun onFailed(e: Exception) = fail(e)
        }
    }
    private val seatWatches = seats.map { SeatWatch(it) }
    private val frontWiper = WindowArea(WindowValue.FRONT_WINDSHIELD)

    // --- NaviHelper (only works when the Pleos navigation app is installed and guiding) ---
    private val naviL = object : NaviHelperEventListener {
        // TODO(verify): unit assumed km/h; 0 assumed "unknown".
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = put { it.copy(speedLimitKmh = speedLimit.takeIf { l -> l > 0 }) }

        // TODO(verify): whether the list means "ahead" or "inside". Conservative: in zone only at remainDistance <= 0.
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = put {
            it.copy(schoolZone = cautionPointList.any { p -> p.type == CautionPointType.SCHOOL_ZONE && p.remainDistance <= 0 })
        }

        // "아까 들어갔어야지!": distance to the next guided exit/turn, and reroutes (= left the guided route).
        // TODO(verify): TBT distance unit assumed metres, first item = the next maneuver.
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = put {
            val next = tbtInfoList.firstOrNull()
            it.copy(nextManeuverM = next?.distance, maneuverSide = next?.type?.side(), reroutes = it.reroutes ?: 0)
        }
        override fun onReRouteEnded() = put { it.copy(reroutes = (it.reroutes ?: 0) + 1) }
        // "이 길로 오면 돌아가잖아!": remaining time before vs after a reroute. TODO(verify): remainTime unit assumed seconds.
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = put { it.copy(remainSec = destinationInfo.remainTime) }
        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            if (navigationStatus != NavigationStatus.ROUTE_DRIVE) put { it.copy(nextManeuverM = null, maneuverSide = null, remainSec = null) }
        }
    }

    // --- AAOS CarPropertyManager: speed (m/s) and gear ---
    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    (value.value as? Float)?.let { v -> put { it.copy(speedKmh = abs(v) * 3.6, speedKnown = true) } }
                VehiclePropertyIds.GEAR_SELECTION ->
                    (value.value as? Int)?.let { g -> put { it.copy(gear = g.toGear()) } }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = fail(IllegalStateException("car property $propertyId error"))
    }

    /** Exit-, branch- and turn-like guidance to one side (TURN_RIGHT, TURN_KEEP_R, TURN_EXIT_IC_R, …); anything else (straight, tunnel, toll, centre, roundabout) = OFF. */
    private fun TurnType.side() = when {
        this == TurnType.TURN_RIGHT || name.endsWith("_R") -> Turn.RIGHT
        this == TurnType.TURN_LEFT || name.endsWith("_L") -> Turn.LEFT
        else -> Turn.OFF
    }

    private fun Int.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.D // DRIVE and numbered gears
    }

    override fun start(recorder: SignalRecorder) {
        rec = recorder

        // Lifecycle form, not createCar(ctx): that one returns null if car_service isn't up yet and never reconnects,
        // so gear stays unknown and the app is stuck on "기록 중" (seen on a degraded emulator). This listener fires
        // whenever car_service becomes ready, including after it crashes and restarts; registrations are lost then.
        safe {
            car = Car.createCar(ctx, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                Log.i(TAG, "car_service ready=$ready")
                // Lost: gear and speed are unknown until it comes back → not parked, not stopped (driving-safe screen).
                if (!ready) put { it.copy(gear = Gear.UNKNOWN, speedKnown = false) }
                if (ready) {
                    val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    safe { cpm.registerCallback(carCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                    // Throws SecurityException if the user denied CAR_SPEED → speed stays unknown → no stop-time verdicts, turn verdicts 판독 불가.
                    safe { cpm.registerCallback(carCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f) }
                }
            }
        }

        // Navi doesn't need the Vehicle SDK: start it first so 빠질 길 / 길 선택 data survive a Vehicle SDK failure.
        safe { navi.initialize({ navi.addListener(naviL) }, ::fail) }

        // Pleos sample: initialize() must complete before any property call; on failure, stop here.
        try { vehicle.initialize() } catch (e: Exception) { fail(e); return }
        safe { vehicle.getTurnSignal().registerTurnSignalState(turnL) }
        safe { vehicle.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        safe { vehicle.getBrake().registerBrakePedalStatus(brakeL) }
        safe { vehicle.getWiper().registerWiperState(frontWiper, wiperL) }
        safe { vehicle.getLight().registerLowBeamLightState(lowBeamL) }
        seatWatches.forEach { w ->
            safe { vehicle.getSeat().registerSeatOccupancy(w.area, w.occupancy) }
            safe { vehicle.getSeat().registerSeatBeltBuckledState(w.area, w.belt) }
        }
        safe { vehicle.getSafety().registerTelltales(telltaleL) }
    }

    override fun stop() {
        safe { car?.getCarManager(Car.PROPERTY_SERVICE)?.let { (it as CarPropertyManager).unregisterCallback(carCallback) } }
        safe { car?.disconnect() }
        safe { vehicle.getTurnSignal().unregisterTurnSignalState(turnL) }
        safe { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
        safe { vehicle.getBrake().unregisterBrakePedalStatus(brakeL) }
        safe { vehicle.getWiper().unregisterWiperState(frontWiper, wiperL) }
        safe { vehicle.getLight().unregisterLowBeamLightState(lowBeamL) }
        safe { vehicle.getSafety().unregisterTelltales(telltaleL) }
        seatWatches.forEach { w ->
            safe { vehicle.getSeat().unregisterSeatOccupancy(w.area, w.occupancy) }
            safe { vehicle.getSeat().unregisterSeatBeltBuckledState(w.area, w.belt) }
        }
        safe { vehicle.release() }
        safe { navi.removeListener(naviL); navi.release() }
        rec = null
    }

    private companion object { const val TAG = "HunsuVAR" }
}
