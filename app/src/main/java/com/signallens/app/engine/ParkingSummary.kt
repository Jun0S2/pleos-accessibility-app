package com.signallens.app.engine

/** 주차할 때(기어 P) 보여줄 하루 마무리 */
data class ParkingSummary(
    val weatherLine: String?,
    val weatherIsDemo: Boolean,
    val openWindows: List<String>,
    val openDoors: List<String>,
    val brakeTip: String?,
    val items: List<Pair<String, Boolean>>, // (항목, 문제 있음)
) {
    val speech: String
        get() = buildString {
            if (weatherLine != null && openWindows.isNotEmpty()) {
                append("$weatherLine ${openWindows.joinToString(", ")} 창문이 열려 있어요. 닫고 내리세요. ")
            } else if (weatherLine != null) {
                append("$weatherLine ")
            }
            if (openDoors.isNotEmpty()) append("${openDoors.joinToString(", ")} 문이 열려 있어요. ")
            if (brakeTip != null) append(brakeTip)
        }.trim()

    val hasAnything: Boolean
        get() = weatherLine != null || openWindows.isNotEmpty() || openDoors.isNotEmpty() || brakeTip != null

    /** 가족에게 보낼 요약 (위치 없이 상태만) */
    fun familyText(): String = buildString {
        appendLine("[운전비서] 오늘 운전 요약")
        items.forEach { (name, problem) -> appendLine("${if (problem) "▲" else "●"} $name") }
        brakeTip?.let { appendLine("브레이크: $it") }
    }.trim()

    companion object {
        fun build(s: CarState, forecast: Forecast?, brake: BrakeCoach): ParkingSummary {
            val windows = s.openWindows.map(::areaName).distinct()
            val doors = (s.openDoors.map(::areaName) + if (s.trunkOpen) listOf("트렁크") else emptyList()).distinct()
            val weather = forecast?.takeIf { it.rainOrSnow }?.let {
                if (it.isSnow) "${it.whenText} 눈 예보예요. 와이퍼를 세워두거나 실내 주차를 권해요."
                else "${it.whenText} 비 예보예요."
            }
            val items = listOf(
                (if (windows.isEmpty()) "창문 모두 닫힘" else "창문 열림: ${windows.joinToString(", ")}") to windows.isNotEmpty(),
                (if (doors.isEmpty()) "도어 · 트렁크 모두 닫힘" else "열림: ${doors.joinToString(", ")}") to doors.isNotEmpty(),
                (if (s.lowBeam || s.highBeam) "라이트 켜짐" else "라이트 꺼짐") to (s.lowBeam || s.highBeam),
            ) + (s.rangeKm?.let { listOf("주행 가능 ${it.toInt()}km" to (it <= 50f)) } ?: emptyList())
            return ParkingSummary(weather, forecast?.isDemo ?: false, windows, doors, brake.summaryText(), items)
        }
    }
}
