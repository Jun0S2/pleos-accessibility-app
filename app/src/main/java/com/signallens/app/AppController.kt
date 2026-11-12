package com.signallens.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.signallens.app.data.DemoForecast
import com.signallens.app.data.DemoStationProvider
import com.signallens.app.data.Prefs
import com.signallens.app.data.StationProvider
import com.signallens.app.engine.AccidentGuide
import com.signallens.app.engine.Alert
import com.signallens.app.engine.AlertAction
import com.signallens.app.engine.AlertEngine
import com.signallens.app.engine.BrakeCoach
import com.signallens.app.engine.CarState
import com.signallens.app.engine.Gear
import com.signallens.app.engine.ParkingSummary
import com.signallens.app.engine.Profile
import com.signallens.app.engine.RawSignal
import com.signallens.app.engine.SignalMapper
import com.signallens.app.engine.areaName
import com.signallens.app.engine.Level
import com.signallens.app.ui.FloatModel
import com.signallens.app.ui.FloatingCard
import com.signallens.app.vehicle.CarPropertySource
import com.signallens.app.vehicle.PleosAutoSource
import com.signallens.app.voice.Speaker
import com.signallens.app.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Screen { ONBOARD, DRIVE, ACCIDENT, PARKING, SETTINGS, TEST }
enum class StopKind { SMOOTH, HARD, LATE, PUMPING }

/**
 * 앱의 두뇌. 차 신호 → 엔진 → 화면/음성.
 * 화면(Compose)은 여기 있는 상태만 보고 그린다.
 */
class AppController(private val ctx: Context) {

    val prefs = Prefs(ctx)
    // 주의: Speaker보다 먼저 만들어야 한다. 음성 엔진이 없으면 TTS가 생성 도중에 바로 콜백을 불러서,
    // main이 아직 없으면 앱이 켜지자마자 죽는다.
    private val main = Handler(Looper.getMainLooper())
    private val speaker = Speaker(ctx) { text, ok ->
        main.post {
            ttsStatus = text
            ttsOk = ok
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stations: StationProvider = DemoStationProvider()
    private val forecast = DemoForecast()

    // ───────── 설정 ─────────
    var profile by mutableStateOf(prefs.profile)
        private set
    var demoMode by mutableStateOf(prefs.demoMode)
        private set
    var insurerName by mutableStateOf(prefs.insurerName)
        private set
    var insurerPhone by mutableStateOf(prefs.insurerPhone)
        private set

    var screen by mutableStateOf(if (prefs.onboarded) Screen.DRIVE else Screen.ONBOARD)

    // ───────── 차 상태 ─────────
    /** 실제 차(또는 가상 콘솔)에서 온 신호 */
    var real by mutableStateOf(CarState())
        private set
    /** 시연 패널에서 만든 신호 */
    var sim by mutableStateOf(CarState(gear = Gear.P, lowBeam = true, rangeKm = 420f))
    /** true면 시연 신호, false면 실제 차 신호를 쓴다 */
    var simOn by mutableStateOf(true)
    val state: CarState get() = if (simOn) sim else real

    var sourceInfo by mutableStateOf("연결 전")
        private set
    var realSignalCount by mutableStateOf(0)
        private set
    private var pleosCount = 0
    private var aaosCount = 0

    // ───────── 알림 · 음성 ─────────
    var current by mutableStateOf<Alert?>(null)
        private set
    val recent = mutableStateListOf<Pair<Long, Alert>>()
    /** 마지막으로 말한 문장 (화면 자막) */
    var caption by mutableStateOf<String?>(null)
        private set
    var listening by mutableStateOf(false)
        private set
    var rain by mutableStateOf(false)
        private set
    var snow by mutableStateOf(false)
        private set
    var brakeText by mutableStateOf("세게 0 · 늦게 0 · 여러 번 0")
        private set

    // ───────── 주차 · 사고 ─────────
    var parking by mutableStateOf<ParkingSummary?>(null)
        private set
    var accidentStep by mutableStateOf<AccidentGuide.Step?>(null)
        private set
    var accidentConfirmed by mutableStateOf(false)
        private set
    var accidentStartText by mutableStateOf("")
        private set
    val accidentLog = mutableStateListOf<String>()

    private val engine = AlertEngine(
        demoMode = { demoMode },
        nightFallback = {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            h >= 19 || h < 6
        },
    )
    private val brake = BrakeCoach(threshold = { if (demoMode) 2 else 5 })
    private val ruleIds = setOf("turn_long", "high_beam", "headlight_off", "door_moving", "fuel_predict", "fuel_low")

    private var guide: AccidentGuide? = null
    private var detectedAt = 0L
    private var lastGear = Gear.UNKNOWN
    private var maxSpeedSinceP = 0f
    private var simJob: Job? = null

    private val pleos = PleosAutoSource(ctx, ::onRaw, ::log)
    private val carProps = CarPropertySource(ctx, ::onRaw, ::log)
    private val voice = VoiceInput(
        ctx,
        onText = ::onVoice,
        onProblem = { say(it, float = true) },
        onListening = {
            listening = it
            if (it && !appVisible) {
                floating.show(FloatModel(Level.INFO, "듣고 있어요", "말씀하세요", "예: 방금 뭐 켜졌어? · 사고 났어", sticky = false), profile)
            }
        },
        onDebug = { line -> main.post { addVoiceLog(line) } },
        onLevel = { v -> main.post { micLevel = v } },
    )

    // ───────── 음성 실시간 로그 (화면에 보임 + Logcat 'SignalLens') ─────────
    val voiceLog = mutableStateListOf<String>()
    var micLevel by mutableStateOf(0f)
        private set

    private fun addVoiceLog(line: String) {
        Log.i(PleosAutoSource.TAG, "[음성] $line")
        voiceLog.add(0, "${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())}  $line")
        while (voiceLog.size > 8) voiceLog.removeAt(voiceLog.lastIndex)
    }
    private val floating = FloatingCard(ctx) { listen() }

    // ───────── 음성 · 지도 위 카드 상태 (테스트 패널 '음성 점검'에 표시) ─────────
    var ttsStatus by mutableStateOf("음성 엔진 확인 중…")
        private set
    var ttsOk by mutableStateOf(false)
        private set
    var sttStatus by mutableStateOf("")
        private set
    var overlayOk by mutableStateOf(false)
        private set
    /** 앱 화면이 지금 보이는지. 안 보이면(지도 화면) 떠 있는 카드로 알린다. */
    var appVisible = false
        set(v) {
            field = v
            if (v) {
                floating.hide()
            } else {
                // 알림이 떠 있는 채로 지도로 가면 지도 위에 다시 띄운다
                current?.let { floatAlert(it) }
            }
            refreshChecks()
        }

    fun refreshChecks() {
        sttStatus = voice.status()
        overlayOk = floating.canShow()
    }

    // ───────── 시작 · 종료 ─────────
    fun start() {
        speaker.setRate(profile.speechRate)
        pleosCount = pleos.start()
        simOn = pleosCount == 0 // SDK가 붙으면 실제 신호부터
        updateSourceInfo()
        scope.launch {
            while (isActive) {
                tick()
                delay(200)
            }
        }
    }

    /** 권한 응답 뒤에 호출 (속도 권한이 필요해서) */
    fun startCarProperty() {
        if (aaosCount > 0) return
        aaosCount = carProps.start()
        updateSourceInfo()
    }

    fun release() {
        floating.hide()
        scope.cancel()
        pleos.stop()
        carProps.stop()
        voice.release()
        speaker.release()
    }

    private fun updateSourceInfo() {
        sourceInfo = "Pleos 구독 ${pleosCount}건 · AAOS 속성 ${aaosCount}건"
    }

    private fun log(msg: String) {
        Log.i(PleosAutoSource.TAG, msg)
    }

    private fun onRaw(sig: RawSignal) {
        main.post {
            SignalMapper.apply(real, sig)?.let {
                real = it
                realSignalCount++
            }
        }
    }

    // ───────── 200ms마다 ─────────
    private fun tick() {
        val now = System.currentTimeMillis()
        val s = state
        val cheapest = s.rangeKm?.let { stations.cheapestOnRoute(it) }

        val fresh = engine.update(s, now, cheapest)
        if (screen == Screen.DRIVE || screen == Screen.TEST) fresh.forEach(::deliver)

        // '바로 확인' 알림은 해결될 때까지 다시 알린다 (테스트 모드 15초, 보통 30초)
        current?.let { a ->
            if (a.level == Level.STOP && a.id in engine.activeIds &&
                now - lastSpokeAt > (if (demoMode) 15_000L else 30_000L)
            ) {
                deliver(a, remember = false)
            }
        }

        // 상황이 사라진 알림은 내린다
        current?.let { a ->
            if (a.id in ruleIds && a.id !in engine.activeIds) {
                current = null
                floating.hide()
            }
            if (a.id == "brake_coach" && s.speedKmh > 10f) current = null
        }

        // 브레이크 코칭: 멈춘 뒤에만 나온다
        brake.feed(s.speedKmh, s.brakePressed, now)?.let { if (screen == Screen.DRIVE || screen == Screen.TEST) deliver(it) }
        brakeText = BrakeCoach.Pattern.entries.joinToString(" · ") { "${it.label} ${brake.counts.getValue(it)}" }

        // 주차: 달리다가 P로
        if (s.gear != Gear.P) maxSpeedSinceP = maxOf(maxSpeedSinceP, s.speedKmh)
        if (lastGear != Gear.P && lastGear != Gear.UNKNOWN && s.gear == Gear.P && (screen == Screen.DRIVE || screen == Screen.TEST) && maxSpeedSinceP > 5f) {
            showParking()
        }
        lastGear = s.gear

        // 사고 가이드: 차 신호로 단계 확인 → 자동으로 다음
        val g = guide
        if (g != null && screen == Screen.ACCIDENT) {
            if (g.detected(s)) {
                if (!accidentConfirmed) {
                    accidentConfirmed = true
                    detectedAt = now
                    accidentLog += "${time(now)} ${g.step.title} ✓"
                    say("확인했어요.", en = "Confirmed.")
                } else if (now - detectedAt >= 1500) {
                    accidentNext()
                }
            }
        }
    }

    private var lastSpokeAt = 0L

    private fun deliver(a: Alert, remember: Boolean = true) {
        current = a
        lastSpokeAt = System.currentTimeMillis()
        if (remember) {
            recent.add(0, System.currentTimeMillis() to a)
            while (recent.size > 10) recent.removeAt(recent.lastIndex)
        }
        if (a.level != Level.INFO) speaker.chime(urgent = a.level == Level.STOP)
        if (!appVisible) floatAlert(a)
        // 알림음 뒤에 말하도록 살짝 늦춘다
        main.postDelayed({ speak(a.speech, english(a.id)) }, if (a.level == Level.INFO) 0L else 350L)
    }

    /** 지도 화면일 때: 지도 위 카드 (고령 모드는 짧은 문장) */
    private fun floatAlert(a: Alert) {
        run {
            floating.show(
                FloatModel(
                    level = a.level,
                    label = a.level.label,
                    title = if (profile == Profile.ELDER) a.shortTitle else a.title,
                    sub = if (profile == Profile.ELDER) null else a.detail,
                    sticky = a.level == Level.STOP,
                ),
                profile,
            )
        }
    }

    /** 한국어 음성이 없는 환경(에뮬레이터)에서 대신 읽을 영어 */
    private fun english(id: String): String = when (id) {
        "turn_long" -> "Your turn signal has been on for a while. Turn it off if you are not changing lanes."
        "high_beam" -> "Your high beams are on, and it is bright. Please switch them off."
        "headlight_off" -> "It is dark and your headlights are off. Turn them on now."
        "door_moving" -> "A door is open while driving. Stop in a safe place and close it."
        "fuel_predict" -> "You do not have enough fuel to reach your destination. Refuel on the way."
        "fuel_low" -> "Your fuel range is low. Please refuel soon."
        "brake_coach" -> "A quick tip. Start braking earlier, and press gently."
        else -> "Signal Lens. Please check the screen."
    }

    private fun speak(text: String, en: String? = null) {
        caption = text
        Log.i(PleosAutoSource.TAG, "[말함] $text")
        speaker.speak(text, en)
    }

    /** 말하기. float = true면 지도 화면일 때 답을 카드로도 보여준다. */
    fun say(text: String, float: Boolean = false, en: String? = null) {
        speak(text, en)
        if (float && !appVisible) {
            floating.show(FloatModel(Level.INFO, "Signal Lens", text, null, sticky = false), profile)
        }
    }

    /** 설정은 정차 중에만. 달리는 중에 누르면 이유를 말해준다. */
    fun openSettings() {
        if (state.stopped) screen = Screen.SETTINGS
        else say("설정은 차가 멈췄을 때 열 수 있어요. 테스트 신호라면 속도를 0으로 내려 주세요.", en = "Settings open only when the car is stopped.")
    }

    /** 테스트 패널: 음성 · 카드가 되는지 바로 확인 */
    fun testVoice() {
        speaker.chime(urgent = false)
        main.postDelayed({ speak("안녕하세요, 시그널 렌즈예요. 소리가 잘 들리나요?", "Hello, this is Signal Lens. Can you hear me?") }, 350)
    }

    fun previewFloat() {
        floating.show(
            FloatModel(Level.CAUTION, "주의", "왼쪽 깜빡이가 1분째 켜져 있어요", "차선을 바꾸지 않으셨다면 꺼주세요.", sticky = false),
            profile,
        )
    }

    fun dismiss() {
        current = null
        speaker.stop()
    }

    fun onAction(a: AlertAction) {
        when (a) {
            AlertAction.ADD_STATION_WAYPOINT -> {
                current = null
                say("시연용 예시로 주유소를 경유지에 넣었어요. 실제 내비 연동은 준비 중이에요.")
            }
            AlertAction.FIND_SERVICE -> say("가까운 정비소 찾기는 준비 중이에요.")
        }
    }

    // ───────── 설정 ─────────
    fun chooseProfile(p: Profile) {
        profile = p
        prefs.profile = p
        speaker.setRate(p.speechRate)
    }

    fun finishOnboarding(p: Profile) {
        chooseProfile(p)
        prefs.onboarded = true
        screen = Screen.DRIVE
        say(
            when (p) {
                Profile.NOVICE -> "초보 모드로 시작할게요. 놓치기 쉬운 걸 이유까지 같이 알려드릴게요."
                Profile.ELDER -> "크게, 천천히 모드로 시작할게요. 짧고 또박또박 말할게요."
                Profile.COLOR -> "색 구분 모드로 시작할게요. 색 대신 모양과 글자로 알려드릴게요."
            }
        )
    }

    fun changeDemoMode(on: Boolean) {
        demoMode = on
        prefs.demoMode = on
    }

    fun saveInsurer(name: String, phone: String) {
        insurerName = name
        insurerPhone = phone
        prefs.insurerName = name
        prefs.insurerPhone = phone
    }

    fun setRainForecast(on: Boolean) {
        rain = on
        forecast.rain = on
    }

    fun setSnowForecast(on: Boolean) {
        snow = on
        forecast.snow = on
    }

    fun resetBrake() = brake.reset()

    // ───────── "방금 뭐 켜졌어?" ─────────
    var answer by mutableStateOf<String?>(null)
        private set

    fun askWhatsOn() {
        val now = System.currentTimeMillis()
        val last = recent.firstOrNull()?.takeIf { now - it.first < 120_000 }?.second
        val s = state
        val on = buildList {
            when {
                s.hazard -> add("비상등")
                s.turnLeft -> add("왼쪽 깜빡이")
                s.turnRight -> add("오른쪽 깜빡이")
            }
            if (s.highBeam) add("상향등") else if (s.lowBeam) add("전조등")
            s.openDoors.forEach { add("${areaName(it)} 문 열림") }
            if (s.trunkOpen) add("트렁크 열림")
            s.openWindows.forEach { add("${areaName(it)} 창문 열림") }
        }
        val text = buildString {
            if (last != null) append("방금 알려드린 건 이거예요. ${last.title}. ${last.detail} ")
            if (on.isEmpty()) append("지금 켜져 있거나 열려 있는 건 없어요.")
            else append("지금은 ${on.joinToString(", ")} 상태예요.")
            s.rangeKm?.let { append(" 주행 가능 거리는 ${it.toInt()}킬로미터예요.") }
        }
        answer = text
        say(text, float = true)
    }

    fun closeAnswer() {
        answer = null
    }

    // ───────── 음성 명령 ─────────
    fun listen() {
        speaker.stop() // 내 목소리를 듣도록 안내 음성은 멈춘다
        voice.listen()
    }
    fun voiceAvailable() = voice.available()

    private fun onVoice(t: String) {
        Log.i(PleosAutoSource.TAG, "음성: $t")
        addVoiceLog("→ 명령으로 처리: \"$t\"")
        val g = guide
        when {
            g != null && accidentStep == AccidentGuide.Step.START && (t.contains("없") || t.contains("괜찮") || t.contains("안 다")) ->
                answerInjury(false)
            g != null && accidentStep == AccidentGuide.Step.START && (t.contains("다쳤") || t.contains("있어") || t.contains("아파")) ->
                answerInjury(true)
            g != null && (t.contains("다음") || t.contains("했어") || t.contains("켰어") || t.contains("열었")) -> accidentNext()
            t.contains("사고") -> startAccident()
            t.contains("기름") || t.contains("주유") || t.contains("연료") -> fuelAnswer()
            t.contains("켜졌") || t.contains("뭐야") || t.contains("방금") || t.contains("불") || t.contains("경고") -> askWhatsOn()
            else -> say("\"방금 뭐 켜졌어\"나 \"사고 났어\"라고 말해 주세요.", float = true)
        }
    }

    private fun fuelAnswer() {
        val s = state
        val r = s.rangeKm
        val route = s.routeRemainingKm
        say(
            float = true,
            text = when {
                r == null -> "주행 가능 거리를 아직 못 받았어요."
                route != null && r < route -> "목적지까지 ${route.toInt()}킬로미터, 주행 가능 거리는 ${r.toInt()}킬로미터예요. 가는 길에 주유가 필요해요."
                route != null -> "목적지까지 ${route.toInt()}킬로미터, 주행 가능 거리는 ${r.toInt()}킬로미터예요. 충분해요."
                else -> "주행 가능 거리는 ${r.toInt()}킬로미터예요."
            }
        )
    }

    // ───────── 주차 요약 ─────────
    private fun showParking() {
        val p = ParkingSummary.build(state, forecast.tomorrow(), brake)
        parking = p
        current = null
        screen = Screen.PARKING
        say(if (p.hasAnything) p.speech else "수고하셨어요. 창문과 문이 모두 닫혀 있어요.")
    }

    fun closeParking() {
        parking = null
        maxSpeedSinceP = 0f
        brake.reset()
        screen = Screen.DRIVE
    }

    fun shareFamily() {
        val text = parking?.familyText() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            ctx.startActivity(Intent.createChooser(send, "가족에게 요약 보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            say("이 기기에는 보낼 앱이 없어요. 화면의 요약을 확인해 주세요.")
        }
    }

    // ───────── 사고 가이드 ─────────
    fun startAccident() {
        val g = AccidentGuide()
        guide = g
        accidentLog.clear()
        accidentStartText = time(g.startedAt)
        accidentLog += "$accidentStartText 가이드 시작"
        current = null
        answer = null
        screen = Screen.ACCIDENT
        onStep()
    }

    fun answerInjury(injured: Boolean) {
        val g = guide ?: return
        g.answerInjury(injured)
        accidentLog += "${time(System.currentTimeMillis())} 다친 사람: ${if (injured) "있음" else "없음"}"
        onStep()
    }

    fun accidentNext() {
        val g = guide ?: return
        g.next()
        onStep()
    }

    fun accidentBack() {
        val g = guide ?: return
        g.back()
        onStep()
    }

    private fun onStep() {
        val g = guide ?: return
        accidentStep = g.step
        accidentConfirmed = false
        say(g.step.speech, en = when (g.step) {
            AccidentGuide.Step.START -> "Are you okay? Is anyone hurt?"
            AccidentGuide.Step.HAZARD -> "Turn on the hazard lights. It is the red triangle button."
            AccidentGuide.Step.TRUNK -> "Open the trunk, so cars behind can see you."
            AccidentGuide.Step.EVACUATE -> "Shift to park, get out, and move behind the guardrail."
            AccidentGuide.Step.CONTACT -> "Now call for help. Call one one nine if anyone is hurt."
            AccidentGuide.Step.AFTER -> "Take photos, and get the other driver's contact and insurance."
            AccidentGuide.Step.DONE -> "Well done."
        })
    }

    fun endAccident() {
        guide = null
        accidentStep = null
        screen = Screen.DRIVE
        speaker.stop()
    }

    fun dial(number: String) {
        accidentLog += "${time(System.currentTimeMillis())} 전화: $number"
        try {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            say("이 기기에는 전화 앱이 없어요. 번호는 ${number.toCharArray().joinToString(" ")}이에요.")
        }
    }

    // ───────── 시연: 브레이크 정지 흉내 ─────────
    fun simStop(kind: StopKind) {
        simJob?.cancel()
        simJob = scope.launch {
            sim = sim.copy(gear = Gear.D, brakePressed = false)
            ramp(50f, 50f, 1200, brake = false) // 50km/h로 달리는 중
            when (kind) {
                StopKind.SMOOTH -> ramp(50f, 0f, 10_000)
                StopKind.HARD -> ramp(50f, 0f, 3_000)
                StopKind.LATE -> {
                    ramp(50f, 20f, 6_000)
                    ramp(20f, 0f, 1_800)
                }
                StopKind.PUMPING -> {
                    ramp(50f, 40f, 1_000)
                    ramp(40f, 38f, 600, brake = false)
                    ramp(38f, 28f, 1_200)
                    ramp(28f, 27f, 600, brake = false)
                    ramp(27f, 0f, 4_000)
                }
            }
            sim = sim.copy(speedKmh = 0f, brakePressed = true)
        }
    }

    fun simGo() {
        simJob?.cancel()
        simJob = scope.launch {
            sim = sim.copy(gear = Gear.D, brakePressed = false)
            ramp(sim.speedKmh, 40f, 3_000, brake = false)
        }
    }

    private suspend fun ramp(from: Float, to: Float, ms: Int, brake: Boolean = true) {
        val n = (ms / 200).coerceAtLeast(1)
        for (i in 1..n) {
            sim = sim.copy(speedKmh = from + (to - from) * i / n, brakePressed = brake)
            delay(200)
        }
    }

    /** 시연 시나리오 한 번에 세팅 */
    fun scenario(name: String) {
        simJob?.cancel()
        simOn = true
        current = null
        sim = when (name) {
            "turn" -> CarState(gear = Gear.D, speedKmh = 60f, turnLeft = true, lowBeam = true, rangeKm = 420f)
            "high" -> CarState(gear = Gear.D, speedKmh = 70f, highBeam = true, lowBeam = true, isNight = false, rangeKm = 420f)
            "dark" -> CarState(gear = Gear.D, speedKmh = 50f, isNight = true, rangeKm = 420f)
            "door" -> CarState(gear = Gear.D, speedKmh = 15f, openDoors = setOf("ROW_2_RIGHT"), lowBeam = true, rangeKm = 420f)
            "fuel" -> CarState(gear = Gear.D, speedKmh = 90f, rangeKm = 90f, routeRemainingKm = 140f, lowBeam = true)
            "park" -> CarState(gear = Gear.D, speedKmh = 8f, openWindows = setOf("ROW_1_RIGHT"), lowBeam = true, rangeKm = 320f)
            else -> CarState(gear = Gear.P, lowBeam = true, rangeKm = 420f)
        }
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
}
