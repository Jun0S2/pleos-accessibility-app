package com.signallens.app.engine

import kotlin.math.max

/**
 * 브레이크 감각 코칭.
 * 정지할 때마다 아래 패턴을 센다.
 *  - HARD: 최대 감속도가 편안한 범위를 넘음
 *  - LATE: 감속의 대부분이 정지 직전에 몰림 (늦게 알아채고 꾹)
 *  - PUMPING: 짧은 시간 안에 밟았다 뗐다를 여러 번
 * 같은 주행에서 패턴이 [threshold]번 이상 쌓이면, 브레이크를 밟는 순간이 아니라
 * 다음 정차 때 한 번만 알려준다.
 */
class BrakeCoach(private val threshold: () -> Int) {

    enum class Pattern(val label: String) { HARD("세게"), LATE("늦게"), PUMPING("여러 번") }

    // 설정값 (m/s²). 실차 테스트로 조정 필요
    var hardDecel = 3.5f
    var lateDecel = 2.5f

    private var lastSpeed: Float? = null
    private var lastTime = 0L

    // 이번 정지 과정
    private var approachStartSpeed = 0f
    private var inApproach = false
    private var peakDecel = 0f
    private var speedAtPeak = 0f
    private val presses = ArrayDeque<Long>()
    private var lastBrake = false
    private var pumpingFlag = false

    private var stoppedSince: Long? = null

    val counts = mutableMapOf(Pattern.HARD to 0, Pattern.LATE to 0, Pattern.PUMPING to 0)
    private var coached = false

    val total: Int get() = counts.values.sum()

    fun reset() {
        counts.keys.forEach { counts[it] = 0 }
        coached = false
    }

    /** @return 정차 중 코칭 알림 (조건이 될 때 한 번) */
    fun feed(speedKmh: Float, brake: Boolean, now: Long): Alert? {
        // 브레이크 펌핑
        if (brake && !lastBrake) {
            presses.addLast(now)
            while (presses.isNotEmpty() && now - presses.first() > 8_000) presses.removeFirst()
            if (presses.size >= 3) pumpingFlag = true
        }
        lastBrake = brake

        val prev = lastSpeed
        val dt = (now - lastTime) / 1000f
        if (prev != null && dt in 0.1f..2f) {
            val decel = (prev - speedKmh) / 3.6f / dt // m/s², 양수 = 감속
            if (!inApproach && speedKmh > 15f && decel > 0.5f) {
                inApproach = true
                approachStartSpeed = prev
                peakDecel = 0f
            }
            if (inApproach && decel > peakDecel) {
                peakDecel = decel
                speedAtPeak = speedKmh
            }
            if (inApproach && speedKmh > prev + 3f) inApproach = false // 다시 가속 = 정지 아님
        }
        if (prev == null || dt >= 0.1f) {
            lastSpeed = speedKmh
            lastTime = now
        }

        // 정지 완료
        if (speedKmh < 1f) {
            if (inApproach) {
                finishApproach()
                inApproach = false
            }
            val s = stoppedSince ?: now.also { stoppedSince = it }
            if (now - s >= 2_000 && !coached && total >= threshold()) {
                coached = true
                return coachingAlert()
            }
        } else {
            stoppedSince = null
        }
        return null
    }

    private fun finishApproach() {
        if (peakDecel >= hardDecel) counts[Pattern.HARD] = counts.getValue(Pattern.HARD) + 1
        // 최대 감속이 출발 속도의 40% 아래에서 나왔다 = 정지 직전에 꾹
        if (peakDecel >= lateDecel && speedAtPeak < max(approachStartSpeed * 0.4f, 1f)) {
            counts[Pattern.LATE] = counts.getValue(Pattern.LATE) + 1
        }
        if (pumpingFlag) counts[Pattern.PUMPING] = counts.getValue(Pattern.PUMPING) + 1
        pumpingFlag = false
        presses.clear()
    }

    fun summaryText(): String? {
        if (total == 0) return null
        // 동점이면 LATE > HARD > PUMPING (늦게 알아채고 꾹 밟는 게 초보의 핵심 문제)
        val priority = listOf(Pattern.LATE, Pattern.HARD, Pattern.PUMPING)
        val best = counts.values.max()
        val main = priority.first { counts.getValue(it) == best }
        return when (main) {
            Pattern.LATE -> "신호 앞에서 브레이크를 늦게 시작하는 편이에요. 50km/h라면 30~50m 앞에서부터 살살 밟기 시작해 보세요."
            Pattern.HARD -> "브레이크를 세게 밟는 순간이 있었어요. 처음엔 살짝, 그다음 조금씩 더 밟아 보세요."
            Pattern.PUMPING -> "브레이크를 짧게 여러 번 밟았어요. 한 번에 부드럽게 이어서 밟아 보세요."
        }
    }

    private fun coachingAlert(): Alert {
        val text = summaryText() ?: ""
        return Alert(
            "brake_coach", Level.INFO,
            title = "브레이크 습관 한마디",
            detail = text,
            speech = "잠깐 한마디 할게요. $text",
            shortTitle = "브레이크는 일찍, 살살",
        )
    }
}
