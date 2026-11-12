package com.signallens.app.engine

/**
 * "사고 났어" 가이드. 정부 안내 '비트박스' 순서를 따른다:
 * 비상등 → 트렁크 → 대피 → 신고. 각 단계는 차 신호로 확인한다.
 * 과실 · 법적 판단은 하지 않는다.
 */
class AccidentGuide {

    enum class Step(val title: String, val body: String, val speech: String, val check: String?) {
        START(
            "괜찮으세요?",
            "제가 순서대로 알려드릴게요. 먼저 하나만 알려주세요.",
            "괜찮으세요? 제가 순서대로 알려드릴게요. 다친 사람이 있나요?",
            null,
        ),
        HAZARD(
            "비상등을 켜세요",
            "가운데 빨간 세모 버튼이에요. 뒤차에 사고를 먼저 알려요.",
            "비상등을 켜세요. 가운데 빨간 세모 버튼이에요.",
            "비상등",
        ),
        TRUNK(
            "트렁크를 열어두세요",
            "뒤차가 멀리서도 멈춘 차라는 걸 알아볼 수 있어요.",
            "트렁크를 열어두세요. 뒤차가 멀리서도 알아볼 수 있어요.",
            "트렁크",
        ),
        EVACUATE(
            "차 밖, 가드레일 밖으로",
            "고속도로라면 삼각대 설치보다 대피가 먼저예요. 차 앞뒤에 서 있지 마세요.",
            "기어를 P에 두고, 차 밖 가드레일 밖으로 피하세요.",
            "기어 P · 운전석 문",
        ),
        CONTACT(
            "이제 연락하세요",
            "다친 사람이 있으면 119, 고속도로라면 긴급견인, 그다음 보험사예요.",
            "이제 연락하세요. 다친 사람이 있으면 119예요.",
            null,
        ),
        AFTER(
            "안전해졌다면, 이것만 챙기세요",
            "현장 · 번호판 · 파손 부위 사진, 상대 연락처와 보험사, 블랙박스 영상 보존. 과실은 현장에서 정하지 말고 보험사에 맡기세요.",
            "안전해졌다면 사진을 찍고, 상대 연락처와 보험사를 받아두세요. 과실은 보험사에 맡기세요.",
            null,
        ),
        DONE(
            "잘 대처하셨어요",
            "사고 시간과 체크리스트를 정리해 뒀어요. 보험사와 통화할 때 보여주세요.",
            "잘 대처하셨어요.",
            null,
        );
    }

    var step: Step = Step.START
        private set
    var injured = false
        private set
    val startedAt = System.currentTimeMillis()

    /** 현재 단계가 차 신호로 확인됐는지 */
    fun detected(s: CarState): Boolean = when (step) {
        Step.HAZARD -> s.hazard
        Step.TRUNK -> s.trunkOpen
        Step.EVACUATE -> s.gear == Gear.P && s.driverDoorOpen
        else -> false
    }

    fun answerInjury(injured: Boolean) {
        this.injured = injured
        step = if (injured) Step.CONTACT else Step.HAZARD
    }

    fun next() {
        step = Step.entries.getOrElse(step.ordinal + 1) { Step.DONE }
    }

    fun back() {
        if (step.ordinal > 0) step = Step.entries[step.ordinal - 1]
    }

    /** 확인 가능한 단계(1~3) 진행 번호 */
    val progressIndex: Int get() = when (step) {
        Step.START -> 0
        Step.HAZARD -> 1
        Step.TRUNK -> 2
        Step.EVACUATE -> 3
        Step.CONTACT -> 4
        Step.AFTER -> 5
        Step.DONE -> 6
    }
}
