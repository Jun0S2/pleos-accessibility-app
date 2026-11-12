package com.signallens.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android Studio에서 이 파일 왼쪽 ▶ 를 누르면 에뮬레이터 없이 규칙을 검사해요. */
class EngineTest {

    @Test fun 깜빡이_1분_한번만() {
        val e = AlertEngine({ false }, { false })
        val s = CarState(speedKmh = 50f, gear = Gear.D, turnLeft = true)
        assertTrue(e.update(s, 0).isEmpty())
        assertTrue(e.update(s, 59_000).isEmpty())
        assertEquals("왼쪽 깜빡이가 1분째 켜져 있어요", e.update(s, 61_000).single().title)
        assertTrue(e.update(s, 90_000).isEmpty())
    }

    @Test fun 비상등은_깜빡이_알림_아님() {
        val e = AlertEngine({ false }, { false })
        val s = CarState(speedKmh = 50f, hazard = true, turnLeft = true)
        e.update(s, 0)
        assertTrue(e.update(s, 120_000).none { it.id == "turn_long" })
    }

    @Test fun 밝을때만_상향등_알림() {
        val day = CarState(speedKmh = 60f, highBeam = true, isNight = false)
        val e = AlertEngine({ false }, { false })
        e.update(day, 0)
        assertTrue(e.update(day, 301_000).any { it.id == "high_beam" })
        val e2 = AlertEngine({ false }, { false })
        val night = day.copy(isNight = true)
        e2.update(night, 0)
        assertTrue(e2.update(night, 400_000).none { it.id == "high_beam" })
    }

    @Test fun 밤에_전조등_꺼짐() {
        val e = AlertEngine({ false }, { true })
        val s = CarState(speedKmh = 40f)
        e.update(s, 0)
        assertTrue(e.update(s, 11_000).any { it.id == "headlight_off" && it.level == Level.STOP })
    }

    @Test fun 문_열고_주행() {
        val e = AlertEngine({ false }, { false })
        val a = e.update(CarState(speedKmh = 10f, openDoors = setOf("ROW_2_LEFT"), trunkOpen = true), 0)
        assertEquals("뒷좌석 왼쪽, 트렁크 열려 있어요", a.single().title)
    }

    @Test fun 연료_예측_주행가능거리_안쪽_주유소만() {
        val fuel = CarState(speedKmh = 80f, rangeKm = 90f, routeRemainingKm = 120f)
        val near = AlertEngine({ false }, { false }).update(fuel, 0, Station("예시", 30f, null, true)).single()
        assertEquals(AlertAction.ADD_STATION_WAYPOINT, near.action)
        val far = AlertEngine({ false }, { false }).update(fuel, 0, Station("예시", 100f, null, true)).single()
        assertEquals("가는 길에 주유가 필요해요", far.title)
    }

    private fun brake(vararg segs: Triple<Float, Float, Pair<Int, Boolean>>): BrakeCoach {
        val bc = BrakeCoach { 99 }
        var t = 0L
        for ((a, b, p) in segs) {
            val n = maxOf(1, p.first / 200)
            for (i in 1..n) { t += 200; bc.feed(a + (b - a) * i / n, p.second, t) }
        }
        repeat(15) { t += 200; bc.feed(0f, true, t) }
        return bc
    }

    private val cruise = Triple(50f, 50f, 1200 to false)

    @Test fun 브레이크_패턴() {
        assertEquals(0, brake(cruise, Triple(50f, 0f, 10_000 to true)).total)
        assertEquals(1, brake(cruise, Triple(50f, 0f, 3_000 to true)).counts[BrakeCoach.Pattern.HARD])
        assertEquals(1, brake(cruise, Triple(50f, 20f, 6_000 to true), Triple(20f, 0f, 1_800 to true)).counts[BrakeCoach.Pattern.LATE])
    }

    @Test fun 브레이크_코칭은_정차_후에만() {
        val bc = BrakeCoach { 1 }
        var t = 0L
        bc.feed(50f, false, 0)
        for (i in 1..15) { t += 200; assertEquals(null, bc.feed(50f - i * 50f / 15, true, t)) }
        var coach: Alert? = null
        repeat(12) { t += 200; coach = coach ?: bc.feed(0f, true, t) }
        assertEquals("brake_coach", coach?.id)
    }

    @Test fun 사고_가이드_차_신호로_확인() {
        val g = AccidentGuide()
        g.answerInjury(false)
        assertEquals(AccidentGuide.Step.HAZARD, g.step)
        assertTrue(g.detected(CarState(hazard = true)))
        g.next(); assertTrue(g.detected(CarState(trunkOpen = true)))
        g.next(); assertTrue(g.detected(CarState(gear = Gear.P, openDoors = setOf("ROW_1_LEFT"))))
        val g2 = AccidentGuide(); g2.answerInjury(true)
        assertEquals(AccidentGuide.Step.CONTACT, g2.step)
    }

    @Test fun 신호_매핑() {
        var c = CarState()
        c = SignalMapper.apply(c, RawSignal("registerTurnSignalState", null, "LEFT"))!!
        assertTrue(c.turnLeft)
        c = SignalMapper.apply(c, RawSignal("PERF_VEHICLE_SPEED", null, 10f))!!
        assertEquals(36f, c.speedKmh, 0.01f)
        c = SignalMapper.apply(c, RawSignal("GEAR_SELECTION", null, 4))!!
        assertEquals(Gear.P, c.gear)
        c = SignalMapper.apply(c, RawSignal("registerWindowPosition", "ROW_2_LEFT", 30))!!
        assertEquals(setOf("ROW_2_LEFT"), c.openWindows)
    }

    @Test fun 주차_요약_비_예보와_창문() {
        val s = CarState(gear = Gear.P, openWindows = setOf("ROW_2_LEFT"))
        val p = ParkingSummary.build(s, Forecast(true, false, "내일 오전", true), BrakeCoach { 1 })
        assertTrue(p.speech.startsWith("내일 오전 비 예보예요. 뒷좌석 왼쪽 창문이 열려 있어요"))
    }
}
