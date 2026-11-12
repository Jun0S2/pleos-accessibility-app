package com.signallens.app.engine

/*
 * 이 패키지(engine)는 안드로이드와 무관한 순수 Kotlin 코드예요.
 * 차 신호를 해석하는 규칙이 전부 여기 있어서 단위 테스트로 검증할 수 있어요.
 */

enum class Gear { P, R, N, D, UNKNOWN }

/** 지금 차 상태를 한 덩어리로 */
data class CarState(
    val speedKmh: Float = 0f,
    val gear: Gear = Gear.UNKNOWN,
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val hazard: Boolean = false,
    val highBeam: Boolean = false,
    val lowBeam: Boolean = false,
    /** 주변이 어두운지. null이면 모름 (조도 신호 없음) */
    val isNight: Boolean? = null,
    /** 열린 문 (영역 이름, 예: ROW_1_LEFT) */
    val openDoors: Set<String> = emptySet(),
    val trunkOpen: Boolean = false,
    /** 열린 창문 (영역 이름) */
    val openWindows: Set<String> = emptySet(),
    /** 주행 가능 거리 km */
    val rangeKm: Float? = null,
    val fuelPercent: Float? = null,
    val brakePressed: Boolean = false,
    /** 목적지까지 남은 거리 km (내비 연동, 없으면 null) */
    val routeRemainingKm: Float? = null,
) {
    /** 비상등은 깜빡이 알림에서 제외 */
    val turnOn: Boolean get() = (turnLeft || turnRight) && !hazard
    val moving: Boolean get() = speedKmh > 3f
    val stopped: Boolean get() = speedKmh < 1f
    val driverDoorOpen: Boolean get() = openDoors.any { isDriverSide(it) }
}

/** 알림 수준. 색이 아니라 모양과 글자로도 구분해요. */
enum class Level(val label: String, val shape: Shape) {
    INFO("안내", Shape.CIRCLE),
    CAUTION("주의", Shape.TRIANGLE),
    STOP("바로 확인", Shape.SQUARE),
}

enum class Shape { CIRCLE, TRIANGLE, SQUARE }

/** 운전자에게 보여주고 읽어줄 알림. 무엇이 · 왜 · 지금 할 일 */
data class Alert(
    val id: String,
    val level: Level,
    val title: String,
    val detail: String,
    val speech: String,
    /** 고령 모드에서 쓰는 아주 짧은 문장 */
    val shortTitle: String = title,
    val action: AlertAction? = null,
)

enum class AlertAction { ADD_STATION_WAYPOINT, FIND_SERVICE }

/** 운전자 프로필 */
enum class Profile(val label: String, val textScale: Float, val speechRate: Float) {
    NOVICE("초보", 1.0f, 1.0f),
    ELDER("크게 · 천천히", 1.45f, 0.8f),
    COLOR("색 구분이 어려워요", 1.0f, 1.0f),
}

/** 주유소 정보 (오피넷 연동 또는 예시) */
data class Station(val name: String, val distanceKm: Float, val pricePerLiter: Int?, val isDemo: Boolean)

/** 내일 날씨 (기상청 연동 또는 예시) */
data class Forecast(val rainOrSnow: Boolean, val isSnow: Boolean, val whenText: String, val isDemo: Boolean)

/** 영역 이름을 사람이 읽는 말로 */
fun areaName(raw: String): String {
    val s = raw.uppercase()
    return when {
        s.contains("TRUNK") || s.contains("TAIL") || s.contains("HATCH") -> "트렁크"
        s.contains("HOOD") -> "보닛"
        s.contains("ROW_1") && s.contains("LEFT") -> "운전석"
        s.contains("ROW_1") && s.contains("RIGHT") -> "조수석"
        s.contains("ROW_2") && s.contains("LEFT") -> "뒷좌석 왼쪽"
        s.contains("ROW_2") && s.contains("RIGHT") -> "뒷좌석 오른쪽"
        s.contains("ROW_3") -> "3열"
        s.contains("ROOF") || s.contains("SUN") -> "선루프"
        else -> raw
    }
}

/** 한국 차 기준 운전석 = 1열 왼쪽 */
fun isDriverSide(raw: String): Boolean {
    val s = raw.uppercase()
    return s.contains("ROW_1") && s.contains("LEFT")
}

fun humanDuration(ms: Long): String {
    val sec = ms / 1000
    return if (sec >= 60) "${sec / 60}분" else "${sec}초"
}
