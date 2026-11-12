package com.signallens.app.engine

/**
 * SDK에서 들어온 원시 신호 한 건.
 * @param method 구독한 함수 이름 (예: registerTurnSignalState) 또는 AAOS 속성 이름
 * @param area 영역 이름 (예: ROW_1_LEFT), 없으면 null
 * @param value SDK가 준 값 (Int, Float, Boolean, enum, 객체 등)
 */
data class RawSignal(val method: String, val area: String?, val value: Any?)

/**
 * 원시 신호를 CarState에 반영한다.
 * Pleos SDK의 정확한 반환 타입을 문서에서 확인할 수 없어서 함수 이름 키워드로 판단한다.
 * Logcat(SignalLens)에 찍힌 값을 보고 규칙이 틀리면 여기만 고치면 된다.
 */
object SignalMapper {

    fun apply(s: CarState, sig: RawSignal): CarState? {
        val m = sig.method.lowercase()
        val v = sig.value
        return when {
            m.contains("turnsignal") -> {
                val t = text(v)
                when {
                    t.contains("HAZARD") || t.contains("EMERGENCY") || t.contains("BOTH") ->
                        s.copy(hazard = true, turnLeft = false, turnRight = false)
                    t.contains("LEFT") -> s.copy(turnLeft = true, turnRight = false, hazard = false)
                    t.contains("RIGHT") -> s.copy(turnRight = true, turnLeft = false, hazard = false)
                    else -> if (isOn(v)) s else s.copy(turnLeft = false, turnRight = false, hazard = false)
                }
            }
            m.contains("hazard") -> s.copy(hazard = isOn(v))
            m.contains("highbeam") -> s.copy(highBeam = isOn(v))
            m.contains("lowbeam") || m.contains("headlight") -> s.copy(lowBeam = isOn(v))
            m.contains("night") -> s.copy(isNight = isOn(v))
            m.contains("trunk") || m.contains("tailgate") -> s.copy(trunkOpen = isOn(v))
            m.contains("door") && (m.contains("position") || m.contains("open")) -> {
                val area = sig.area ?: return null
                val open = isOn(v)
                if (areaName(area) == "트렁크") s.copy(trunkOpen = open)
                else s.copy(openDoors = if (open) s.openDoors + area else s.openDoors - area)
            }
            m.contains("window") && (m.contains("position") || m.contains("open")) -> {
                val area = sig.area ?: return null
                val open = isOn(v)
                s.copy(openWindows = if (open) s.openWindows + area else s.openWindows - area)
            }
            m.contains("gear") -> s.copy(gear = gear(v))
            m.contains("speed") && !m.contains("limit") -> {
                val n = number(v) ?: return null
                // AAOS PERF_VEHICLE_SPEED는 m/s
                val kmh = if (m.contains("perf_vehicle_speed")) n * 3.6f else n
                s.copy(speedKmh = kotlin.math.abs(kmh))
            }
            m.contains("range") -> {
                val n = number(v) ?: return null
                // AAOS RANGE_REMAINING은 m
                s.copy(rangeKm = if (m.contains("range_remaining")) n / 1000f else n)
            }
            m.contains("stateofcharge") || (m.contains("fuel") && m.contains("level")) ->
                number(v)?.let { s.copy(fuelPercent = it) }
            m.contains("brake") && (m.contains("pedal") || m.contains("state") || m.contains("press")) ->
                s.copy(brakePressed = isOn(v))
            else -> null
        }
    }

    /** enum · 객체는 이름 문자열로 */
    fun text(v: Any?): String = (v?.toString() ?: "").uppercase()

    fun isOn(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toFloat() != 0f
        else -> {
            val t = text(v)
            !(t.isEmpty() || t.contains("OFF") || t.contains("NONE") || t.contains("CLOSE") ||
                t == "0" || t == "FALSE" || t.contains("UNKNOWN") || t.contains("NOT_"))
        }
    }

    fun number(v: Any?): Float? = when (v) {
        is Number -> v.toFloat()
        null -> null
        else -> Regex("-?\\d+(\\.\\d+)?").find(v.toString())?.value?.toFloatOrNull()
    }

    fun gear(v: Any?): Gear {
        // AAOS VehicleGear: NEUTRAL=1, REVERSE=2, PARK=4, DRIVE=8, 1단~=16 이상
        if (v is Number) return when (v.toInt()) {
            4 -> Gear.P
            2 -> Gear.R
            1 -> Gear.N
            0 -> Gear.UNKNOWN
            else -> Gear.D
        }
        val t = text(v)
        return when {
            t.contains("PARK") || t == "P" -> Gear.P
            t.contains("REVERSE") || t == "R" -> Gear.R
            t.contains("NEUTRAL") || t == "N" -> Gear.N
            t.contains("DRIVE") || t == "D" -> Gear.D
            else -> Gear.UNKNOWN
        }
    }
}
