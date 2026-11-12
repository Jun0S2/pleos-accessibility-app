package com.signallens.app.engine

/**
 * 차 상태를 계속 받아서, 알려야 할 순간에 알림을 만든다.
 * 같은 상황에서는 한 번만 알리고, 상황이 끝나면(예: 깜빡이를 끄면) 다시 알릴 수 있게 초기화한다.
 */
class AlertEngine(
    /** 시연 모드면 기준 시간을 짧게 */
    private val demoMode: () -> Boolean,
    /** 조도 신호가 없을 때 쓸 '지금 밤인가' 추정 (시간대 등) */
    private val nightFallback: () -> Boolean,
) {
    private val since = mutableMapOf<String, Long>()
    private val fired = mutableSetOf<String>()

    private fun turnLimit() = if (demoMode()) 10_000L else 60_000L
    private fun highBeamLimit() = if (demoMode()) 20_000L else 5 * 60_000L
    private fun headlightLimit() = if (demoMode()) 5_000L else 10_000L

    /** 지금 활성인 문제들의 id. 상황이 사라진 알림을 화면에서 내릴 때 쓴다. */
    val activeIds: Set<String> get() = since.keys

    /**
     * @return 이번에 새로 알려야 할 알림들 (보통 0~1개)
     */
    fun update(s: CarState, now: Long, cheapest: Station? = null): List<Alert> {
        val out = mutableListOf<Alert>()
        val night = s.isNight ?: nightFallback()

        // 1. 깜빡이 장시간
        track("turn_long", s.turnOn, now)?.let { elapsed ->
            if (elapsed >= turnLimit()) once("turn_long") {
                val side = when {
                    s.turnLeft && !s.turnRight -> "왼쪽 "
                    s.turnRight && !s.turnLeft -> "오른쪽 "
                    else -> ""
                }
                val t = humanDuration(elapsed)
                out += Alert(
                    "turn_long", Level.CAUTION,
                    title = "${side}깜빡이가 ${t}째 켜져 있어요",
                    detail = "차선을 바꾸지 않으셨다면 레버를 가운데로 돌려 꺼주세요.",
                    speech = "${side}깜빡이가 ${t}째 켜져 있어요. 차선을 바꾸지 않으셨다면 꺼주세요.",
                    shortTitle = "깜빡이를 꺼주세요",
                )
            }
        }

        // 2. 밝은데 상향등
        track("high_beam", s.highBeam && !night, now)?.let { elapsed ->
            if (elapsed >= highBeamLimit()) once("high_beam") {
                val t = humanDuration(elapsed)
                out += Alert(
                    "high_beam", Level.CAUTION,
                    title = "상향등이 ${t}째 켜져 있어요",
                    detail = "지금은 주변이 밝아요. 켜두신 게 아니라면 레버를 원래 위치로 돌려 꺼주세요.",
                    speech = "상향등이 ${t}째 켜져 있어요. 켜두신 거 맞나요? 마주 오는 차가 눈부실 수 있어요.",
                    shortTitle = "상향등을 꺼주세요",
                )
            }
        }

        // 3. 어두운데 전조등 꺼짐 (가장 위험)
        track("headlight_off", night && s.moving && !s.lowBeam && !s.highBeam, now)?.let { elapsed ->
            if (elapsed >= headlightLimit()) once("headlight_off") {
                out += Alert(
                    "headlight_off", Level.STOP,
                    title = "어두운데 전조등이 꺼져 있어요",
                    detail = "다른 차가 내 차를 못 볼 수 있어요. 라이트 스위치를 AUTO나 전조등으로 돌려주세요.",
                    speech = "어두운데 전조등이 꺼져 있어요. 지금 라이트를 켜주세요.",
                    shortTitle = "라이트를 켜주세요",
                )
            }
        }

        // 4. 문 · 트렁크 열린 채 주행
        val openList = s.openDoors.map(::areaName) + if (s.trunkOpen) listOf("트렁크") else emptyList()
        track("door_moving", s.moving && openList.isNotEmpty(), now)?.let {
            once("door_moving") {
                val what = openList.distinct().joinToString(", ")
                out += Alert(
                    "door_moving", Level.STOP,
                    title = "$what 열려 있어요",
                    detail = "안전한 곳에 멈추고 닫아주세요.",
                    speech = "$what 열려 있어요. 안전한 곳에 멈추고 닫아주세요.",
                    shortTitle = "문이 열려 있어요",
                )
            }
        }

        // 5. 연료 예측: 남은 경로 > 주행 가능 거리
        val range = s.rangeKm
        val route = s.routeRemainingKm
        val short = range != null && route != null && range < route
        track("fuel_predict", short, now)?.let {
            once("fuel_predict") {
                val r = range!!.toInt()
                val d = route!!.toInt()
                val st = cheapest?.takeIf { it.distanceKm < range }
                val price = st?.pricePerLiter?.let { " · ${it}원/L" } ?: ""
                // 실제 유가 데이터(오피넷)는 '현재 위치 근처 최저가', 예시 데이터는 '경로상'으로 말한다
                val live = st != null && !st.isDemo
                val km = st?.let { if (it.distanceKm < 10f) "%.1f".format(it.distanceKm) else it.distanceKm.toInt().toString() } ?: ""
                val where = when {
                    st == null -> "경로상 주유소를 찾고 있어요"
                    live -> "근처에서 가장 싼 곳은 ${st.name}(${km}km$price)"
                    else -> "경로상 ${st.distanceKm.toInt()}km 뒤 ${st.name}${price}"
                }
                out += Alert(
                    "fuel_predict", Level.CAUTION,
                    title = when {
                        st == null -> "가는 길에 주유가 필요해요"
                        live -> "가까운 주유소에서 주유하세요"
                        else -> "${st.distanceKm.toInt()}km 뒤에서 주유하세요"
                    },
                    detail = "목적지까지 ${d}km, 주행 가능 거리는 ${r}km예요. $where",
                    speech = "목적지까지 ${d}킬로미터 남았는데 주행 가능 거리는 ${r}킬로미터예요." +
                        when {
                            st == null -> ""
                            live -> " 근처에서 가장 싼 주유소는 ${st.name}이에요. 경유지로 추가할까요?"
                            else -> " 경로상 ${st.distanceKm.toInt()}킬로미터 뒤 주유소가 그중 제일 싸요. 안내할까요?"
                        },
                    shortTitle = "기름 넣을 때예요",
                    action = if (st != null) AlertAction.ADD_STATION_WAYPOINT else null,
                )
            }
        }

        // 6. 경로 정보가 없을 때: 주행 가능 거리 부족
        track("fuel_low", route == null && range != null && range <= 50f, now)?.let {
            once("fuel_low") {
                val r = range!!.toInt()
                out += Alert(
                    "fuel_low", Level.CAUTION,
                    title = "주행 가능 거리가 ${r}km 남았어요",
                    detail = "가까운 주유소나 충전소에 들러주세요.",
                    speech = "주행 가능 거리가 ${r}킬로미터 남았어요. 가까운 곳에서 주유해 주세요.",
                    shortTitle = "기름 넣을 때예요",
                )
            }
        }

        return out
    }

    /**
     * 조건이 참인 동안 경과 시간을 돌려준다. 거짓이 되면 초기화.
     */
    private fun track(id: String, condition: Boolean, now: Long): Long? {
        if (!condition) {
            since.remove(id)
            fired.remove(id)
            return null
        }
        val start = since.getOrPut(id) { now }
        return now - start
    }

    private inline fun once(id: String, block: () -> Unit) {
        if (fired.add(id)) block()
    }
}
