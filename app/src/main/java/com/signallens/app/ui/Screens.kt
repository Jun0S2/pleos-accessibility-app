package com.signallens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signallens.app.AppController
import com.signallens.app.Screen
import com.signallens.app.StopKind
import com.signallens.app.engine.AccidentGuide.Step
import com.signallens.app.engine.Alert
import com.signallens.app.engine.AlertAction
import com.signallens.app.engine.CarState
import com.signallens.app.engine.Gear
import com.signallens.app.engine.Level
import com.signallens.app.engine.Profile
import com.signallens.app.engine.areaName

/** 화면에서 액티비티에 부탁할 일 (권한 요청 등) */
class Actions(val requestMic: () -> Unit, val openOverlaySettings: () -> Unit)

@Composable
fun App(c: AppController, requestMic: () -> Unit = {}, openOverlaySettings: () -> Unit = {}) {
    val act = remember { Actions(requestMic, openOverlaySettings) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        // Pleos 가로 창은 가로로 길고 세로가 낮다 → 납작한 배치
        val compact = maxHeight < 520.dp && maxWidth > maxHeight
        val t = typeFor(c.profile, compact)
        when (c.screen) {
            Screen.ONBOARD -> OnboardScreen(c, t)
            Screen.DRIVE -> DriveScreen(c, t, act)
            Screen.ACCIDENT -> AccidentScreen(c, t)
            Screen.PARKING -> ParkingScreen(c, t)
            Screen.SETTINGS -> SettingsScreen(c, t)
            Screen.TEST -> TestScreen(c, t, act)
        }
    }
}

// ═══════════════════ 1. 처음 설정: 운전자 프로필 ═══════════════════

@Composable
fun OnboardScreen(c: AppController, t: Type) {
    var testing by remember { mutableStateOf(false) }
    var recommended by remember { mutableStateOf<Profile?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(t.gap),
    ) {
        T("Signal Lens", t.small, Cyan, bold = true)
        if (testing) {
            ColorTest(t) { passed ->
                testing = false
                recommended = if (passed) null else Profile.COLOR
            }
        } else {
            ProfileChoices(c, t, recommended) { testing = true }
        }
    }
}

@Composable
private fun ProfileChoices(c: AppController, t: Type, recommended: Profile?, startTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(t.gap)) {
        T("어떤 도움이 필요하세요?", t.hero, bold = true)
        T("나중에 설정에서 언제든 바꿀 수 있어요.", t.body, Text2)
        recommended?.let {
            Card(border = Cyan) {
                T("테스트에서 일부 색이 헷갈렸던 것 같아요. '색 구분이 어려워요'를 추천해요. (의학적 검사는 아니에요)", t.body, Cyan)
            }
        }
        ProfileOption(t, Profile.NOVICE, "처음 운전해요", "놓치기 쉬운 걸 이유와 할 일까지 알려드려요.", recommended == null) { c.finishOnboarding(Profile.NOVICE) }
        ProfileOption(t, Profile.ELDER, "크게, 천천히", "글자를 크게, 말은 천천히 · 짧게 해요.", false) { c.finishOnboarding(Profile.ELDER) }
        ProfileOption(t, Profile.COLOR, "색 구분이 어려워요", "빨강·초록 대신 모양(○△□)과 글자로 구분해요.", recommended == Profile.COLOR) { c.finishOnboarding(Profile.COLOR) }
        BigButton("색 구분 테스트 해보기 (30초)", t, Modifier.fillMaxWidth(), filled = false) { startTest() }
    }
}

@Composable
private fun ProfileOption(t: Type, p: Profile, title: String, desc: String, highlight: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (highlight) PanelHi else Panel, RoundedCornerShape(24.dp))
            .border(3.dp, if (highlight) Cyan else Line, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(28.dp)
    ) {
        Column {
            T(title, t.title, bold = true)
            T(desc, t.body, Text2)
        }
    }
}

/**
 * 간단한 색 구분 확인. 계기판 경고등에 흔한 빨강을 초록 · 올리브 사이에서 고른다.
 * 의료 검사(이시하라 등)가 아니라 프로필 추천용.
 */
@Composable
private fun ColorTest(t: Type, onDone: (passed: Boolean) -> Unit) {
    val rounds = remember {
        listOf(
            listOf(Color(0xFF6E8B3D), Color(0xFFC2453A), Color(0xFF7A8A3A), Color(0xFF5E8F45)) to 1,
            listOf(Color(0xFFB5523B), Color(0xFF8C8C3A), Color(0xFF6F9A4A), Color(0xFF8A7F3C)) to 0,
            listOf(Color(0xFF7D9446), Color(0xFF8E8A42), Color(0xFF6A8F4F), Color(0xFFB8483E)) to 3,
        )
    }
    var round by remember { mutableIntStateOf(0) }
    var correct by remember { mutableIntStateOf(0) }
    T("빨간 점을 눌러주세요", t.hero, bold = true)
    T("${round + 1} / ${rounds.size}", t.body, Text2)
    val (colors, answer) = rounds[round]
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        colors.forEachIndexed { i, col ->
            Box(
                Modifier
                    .size(150.dp)
                    .background(col, CircleShape)
                    .clickable {
                        if (i == answer) correct++
                        if (round + 1 < rounds.size) round++ else onDone(correct == rounds.size)
                    }
            )
        }
    }
    BigButton("잘 모르겠어요", t, filled = false) {
        if (round + 1 < rounds.size) round++ else onDone(correct == rounds.size)
    }
}

// ═══════════════════ 2. 주행 화면 ═══════════════════

@Composable
fun DriveScreen(c: AppController, t: Type, act: Actions) {
    if (t.compact) {
        DriveStrip(c, t, act)
        return
    }
    var showSim by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(t.gap),
                ) { DriveMain(c, t, act, showSim) { showSim = !showSim } }
                if (showSim) {
                    Column(Modifier.weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState())) { SimPanel(c, act) }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(t.gap),
            ) {
                DriveMain(c, t, act, showSim) { showSim = !showSim }
                if (showSim) SimPanel(c, act)
            }
        }
    }
}

@Composable
private fun DriveMain(c: AppController, t: Type, act: Actions, simShown: Boolean, toggleSim: () -> Unit) {
    val s = c.state
    // 상단: 모드 · 신호 출처 · 속도 · 기어
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        T("Signal Lens", t.small, Cyan, bold = true)
        Pill(c.profile.label, Cyan)
        Pill(if (c.simOn) "테스트 신호" else "차 신호", if (c.simOn) Color(0xFFFFD23F) else Text2)
        Spacer(Modifier.weight(1f))
        T("${s.speedKmh.toInt()} km/h · ${gearText(s.gear)}", t.small, Text2, bold = true)
    }

    val a = c.current
    if (a != null) AlertCard(c, t, a) else IdleCard(c, t, s)

    c.answer?.let { ans ->
        Card(border = Cyan) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("방금 뭐 켜졌어?", t.small, Cyan, bold = true)
                T(ans, t.body)
                T("계기판 경고등은 아직 SDK에서 읽을 수 없어서, 차 신호(라이트 · 문 · 창문 · 연료)로 답해요.", t.small, Text2)
                BigButton("닫기", t, filled = false) { c.closeAnswer() }
            }
        }
    }

    // 자막: 마지막으로 말한 문장
    c.caption?.let { if (c.answer == null) T("🔊 $it", t.small, Text2) }
    // 소리가 안 나면 여기서 바로 알 수 있게
    if (!c.ttsOk) T("🔇 ${c.ttsStatus} — 테스트 신호 목록 > 음성 점검", t.small, Color(0xFFFFD23F))
    VoiceLogView(c, lines = 4)

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BigButton("방금 뭐 켜졌어?", t, Modifier.weight(1f)) { c.askWhatsOn() }
        BigButton(if (c.listening) "듣는 중…" else "🎤 말하기", t, Modifier.weight(1f), filled = false) { micClick(c, act) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BigButton("사고 났어", t, Modifier.weight(1f), color = Color(0xFFFF4D5E), filled = false) { c.startAccident() }
        BigButton(if (s.stopped) "설정" else "설정 (정차 후)", t, Modifier.weight(1f), filled = false) { c.openSettings() }
    }
    BigButton(if (simShown) "테스트 신호 목록 닫기" else "테스트 신호 목록", t, Modifier.fillMaxWidth(), color = Text2, filled = false) { toggleSim() }
}

@Composable
private fun AlertCard(c: AppController, t: Type, a: Alert) {
    val col = levelColor(a.level, c.profile)
    val elder = c.profile == Profile.ELDER
    Card(border = col) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ShapeIcon(a.level.shape, col, if (t.compact) 52.dp else if (elder) 110.dp else 84.dp)
                Column {
                    T(a.level.label, t.small, col, bold = true)
                    T(if (elder) a.shortTitle else a.title, t.title, bold = true)
                }
            }
            if (elder) T(a.title, t.body, Text2) else T(a.detail, t.body, Text2)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (a.action == AlertAction.ADD_STATION_WAYPOINT) {
                    BigButton("경유지 추가", t, Modifier.weight(1f), color = col) { c.onAction(AlertAction.ADD_STATION_WAYPOINT) }
                }
                BigButton("알겠어요", t, Modifier.weight(1f), color = col, filled = false) { c.dismiss() }
            }
        }
    }
}

@Composable
private fun IdleCard(c: AppController, t: Type, s: CarState) {
    val col = levelColor(Level.INFO, c.profile)
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ShapeIcon(Level.INFO.shape, col, if (t.compact) 44.dp else 72.dp)
                T("지금은 괜찮아요", t.title, bold = true)
            }
            T("문제가 생기면 말로 먼저 알려드릴게요.", t.body, Text2)
            val bits = buildList {
                add(if (s.hazard) "비상등 켜짐" else if (s.turnOn) "깜빡이 켜짐" else "깜빡이 꺼짐")
                add(if (s.highBeam) "상향등" else if (s.lowBeam) "전조등" else "라이트 꺼짐")
                add(if (s.openDoors.isEmpty() && !s.trunkOpen) "문 닫힘" else "문 열림")
                s.rangeKm?.let { add("주행 가능 ${it.toInt()}km") }
            }
            T(bits.joinToString("  ·  "), t.small, Text2)
        }
    }
}

private fun gearText(g: Gear) = if (g == Gear.UNKNOWN) "-" else g.name

private fun micClick(c: AppController, act: Actions) {
    c.refreshChecks()
    if (c.sttStatus.startsWith("마이크 권한")) act.requestMic()
    c.listen()
}

/**
 * Pleos 가로 창(가로로 길고 납작함)용 주행 화면.
 * 왼쪽: 지금 상태/알림 · 오른쪽: 버튼 2줄. 스크롤 없이 한눈에.
 */
@Composable
private fun DriveStrip(c: AppController, t: Type, act: Actions) {
    val s = c.state
    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Signal Lens", t.small, Cyan, bold = true)
                Pill(c.profile.label, Cyan)
                Pill(if (c.simOn) "테스트 신호" else "차 신호", if (c.simOn) Color(0xFFFFD23F) else Text2)
                Spacer(Modifier.weight(1f))
                T("${s.speedKmh.toInt()} km/h · ${gearText(s.gear)}", t.small, Text2, bold = true)
            }
            val a = c.current
            if (a != null) AlertCard(c, t, a) else IdleCard(c, t, s)
            c.answer?.let { ans ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("💬 $ans", t.body, Cyan, modifier = Modifier.weight(1f))
                    BigButton("닫기", t, filled = false) { c.closeAnswer() }
                }
            }
            if (c.answer == null) c.caption?.let { T("🔊 $it", t.small, Text2) }
            if (!c.ttsOk) T("🔇 ${c.ttsStatus}", t.small, Color(0xFFFFD23F))
            VoiceLogView(c, lines = 3)
        }
        Column(Modifier.width(620.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("방금 뭐 켜졌어?", t, Modifier.weight(1.3f)) { c.askWhatsOn() }
                BigButton(if (c.listening) "듣는 중…" else "🎤 말하기", t, Modifier.weight(1f), filled = false) { micClick(c, act) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("사고 났어", t, Modifier.weight(1f), color = Color(0xFFFF4D5E), filled = false) { c.startAccident() }
                BigButton("테스트", t, Modifier.weight(1f), color = Text2, filled = false) { c.screen = Screen.TEST }
                BigButton(if (s.stopped) "설정" else "설정 (정차 후)", t, Modifier.weight(1f), color = Text2, filled = false) { c.openSettings() }
            }
        }
    }
}

/** 테스트 신호 목록 (납작한 창에서는 화면 하나를 통째로 쓴다) */
@Composable
fun TestScreen(c: AppController, t: Type, act: Actions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton("← 돌아가기", t) { c.screen = Screen.DRIVE }
            val a = c.current
            T(
                if (a != null) "${a.level.label} · ${a.title}" else (c.caption?.let { "🔊 $it" } ?: "알림 없음"),
                t.body, if (a != null) levelColor(a.level, c.profile) else Text2, bold = a != null,
                modifier = Modifier.weight(1f),
            )
        }
        SimPanel(c, act)
    }
}

// ═══════════════════ 3. 테스트 신호 목록 (가상 차 신호 · 음성 점검) ═══════════════════

@Composable
fun SimPanel(c: AppController, act: Actions) {
    val s = c.sim
    fun set(n: CarState) {
        c.sim = n
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(24.dp))
            .border(2.dp, Line, RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── 음성 점검: 소리 · 마이크 · 지도 위 카드가 되는지
        SimLabel("음성 점검")
        CheckLine(c.ttsOk, "말하기(스피커)", c.ttsStatus)
        CheckLine(c.sttStatus.startsWith("음성 인식 준비"), "듣기(마이크)", c.sttStatus)
        CheckLine(c.overlayOk, "지도 위 알림 카드", if (c.overlayOk) "켜짐" else "'다른 앱 위에 표시' 권한이 꺼져 있어요 (README의 adb 명령)")
        VoiceLogView(c, lines = 8)
        ChipGrid(
            listOf(
                Triple("소리 테스트", false) { c.testVoice() },
                Triple("마이크 권한", false) { act.requestMic() },
                Triple(if (c.overlayOk) "카드 미리보기" else "카드 권한 켜기", false) {
                    if (c.overlayOk) c.previewFloat() else act.openOverlaySettings()
                },
            )
        )
        Text("카드 미리보기를 누르고 지도 화면으로 가 보세요. 지도에서는 알림이 왼쪽 위에 떠요.", color = Text2, fontSize = 20.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SimLabel("테스트 신호 쓰기")
                Text(
                    if (c.simOn) "아래에서 고른 가짜 차 신호로 동작해요" else "실제 차(가상 콘솔) 신호로 동작해요 · ${c.sourceInfo} · 받은 신호 ${c.realSignalCount}",
                    color = Text2, fontSize = 20.sp,
                )
            }
            Switch(checked = c.simOn, onCheckedChange = { c.simOn = it })
        }

        SimLabel("상황 바로 만들기")
        Text("누르면 그 상황의 차 신호가 한 번에 들어가요. 몇 초 뒤 알림이 나와요.", color = Text2, fontSize = 20.sp)
        ChipGrid(
            listOf(
                Triple("깜빡이 켠 채로 주행", false) { c.scenario("turn") },
                Triple("낮인데 상향등", false) { c.scenario("high") },
                Triple("밤인데 라이트 꺼짐", false) { c.scenario("dark") },
                Triple("뒷문 열린 채 출발", false) { c.scenario("door") },
                Triple("기름이 목적지까지 부족", false) { c.scenario("fuel") },
                Triple("창문 열고 주차 직전 → P 누르기", false) { c.scenario("park") },
            ),
            perRow = 2,
        )

        SimLabel("신호 하나씩 바꾸기")

        SimLabel("속도 ${s.speedKmh.toInt()} km/h")
        Slider(value = s.speedKmh, onValueChange = { set(s.copy(speedKmh = it)) }, valueRange = 0f..120f)

        SimLabel("기어")
        ChipGrid(Gear.entries.filter { it != Gear.UNKNOWN }.map { g -> Triple(g.name, s.gear == g) { set(s.copy(gear = g)) } }, perRow = 4)

        SimLabel("깜빡이")
        ChipGrid(
            listOf(
                Triple("왼쪽", s.turnLeft && !s.hazard) { set(s.copy(turnLeft = !s.turnLeft, turnRight = false, hazard = false)) },
                Triple("오른쪽", s.turnRight && !s.hazard) { set(s.copy(turnRight = !s.turnRight, turnLeft = false, hazard = false)) },
                Triple("비상등", s.hazard) { set(s.copy(hazard = !s.hazard, turnLeft = false, turnRight = false)) },
            )
        )

        SimLabel("라이트 · 주변")
        ChipGrid(
            listOf(
                Triple("전조등", s.lowBeam) { set(s.copy(lowBeam = !s.lowBeam)) },
                Triple("상향등", s.highBeam) { set(s.copy(highBeam = !s.highBeam)) },
                Triple(if (s.isNight == true) "밤" else "낮", s.isNight == true) { set(s.copy(isNight = s.isNight != true)) },
            )
        )

        SimLabel("문")
        val doors = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")
        ChipGrid(
            doors.map { d ->
                Triple(areaName(d), d in s.openDoors) {
                    set(s.copy(openDoors = if (d in s.openDoors) s.openDoors - d else s.openDoors + d))
                }
            } + Triple("트렁크", s.trunkOpen) { set(s.copy(trunkOpen = !s.trunkOpen)) }
        )

        SimLabel("창문")
        ChipGrid(
            doors.map { d ->
                Triple(areaName(d), d in s.openWindows) {
                    set(s.copy(openWindows = if (d in s.openWindows) s.openWindows - d else s.openWindows + d))
                }
            },
            perRow = 2,
        )

        SimLabel("주행 가능 거리 ${s.rangeKm?.toInt() ?: 0} km")
        Slider(value = s.rangeKm ?: 0f, onValueChange = { set(s.copy(rangeKm = it)) }, valueRange = 0f..600f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimLabel(
                if (s.routeRemainingKm == null) "내비 경로 없음" else "목적지까지 ${s.routeRemainingKm.toInt()} km",
                Modifier.weight(1f),
            )
            Switch(
                checked = s.routeRemainingKm != null,
                onCheckedChange = { set(s.copy(routeRemainingKm = if (it) 140f else null)) },
            )
        }
        s.routeRemainingKm?.let { r ->
            Slider(value = r, onValueChange = { set(s.copy(routeRemainingKm = it)) }, valueRange = 0f..400f)
        }

        SimLabel("내일 날씨 [예시]")
        ChipGrid(
            listOf(
                Triple("비 예보", c.rain) { c.setRainForecast(!c.rain) },
                Triple("눈 예보", c.snow) { c.setSnowForecast(!c.snow) },
            ),
            perRow = 2,
        )

        SimLabel("브레이크 · ${c.brakeText}")
        ChipGrid(
            listOf(
                Triple("부드럽게 정지", false) { c.simStop(StopKind.SMOOTH) },
                Triple("급정거", false) { c.simStop(StopKind.HARD) },
                Triple("늦게 꾹", false) { c.simStop(StopKind.LATE) },
                Triple("여러 번 밟기", false) { c.simStop(StopKind.PUMPING) },
                Triple("브레이크 밟음", s.brakePressed) { set(s.copy(brakePressed = !s.brakePressed)) },
                Triple("출발", false) { c.simGo() },
            )
        )
        Text("코칭은 기준 횟수(테스트 모드 2회) 이상 쌓이고, 완전히 멈춘 뒤 2초가 지나야 나와요.", color = Text2, fontSize = 20.sp)
    }
}

/** 🎤 실시간 로그: 마이크 소리 크기 막대 + 들은 말 + 오류 */
@Composable
fun VoiceLogView(c: AppController, lines: Int) {
    if (!c.listening && c.voiceLog.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF07112A), RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (c.listening) "🎤 듣는 중" else "🎤 음성 로그", color = Cyan, fontSize = 20.sp)
            Box(
                Modifier
                    .weight(1f)
                    .height(12.dp)
                    .background(Panel, RoundedCornerShape(6.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(c.micLevel.coerceIn(0f, 1f))
                        .height(12.dp)
                        .background(Cyan, RoundedCornerShape(6.dp))
                )
            }
        }
        c.voiceLog.take(lines).forEach { Text(it, color = Text2, fontSize = 18.sp) }
    }
}

@Composable
private fun CheckLine(ok: Boolean, what: String, detail: String) {
    Text(
        (if (ok) "✓ " else "✕ ") + "$what · $detail",
        color = if (ok) Cyan else Color(0xFFFFD23F),
        fontSize = 22.sp,
    )
}

@Composable
private fun SimLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Text1, fontSize = 26.sp, style = TextStyle(), modifier = modifier)
}

// ═══════════════════ 4. 사고 났어 ═══════════════════

@Composable
fun AccidentScreen(c: AppController, t: Type) {
    val step = c.accidentStep ?: return
    val red = Color(0xFFFF4D5E)
    val s = c.state
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(t.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("사고 가이드 · ${c.accidentStartText} 시작", red)
            Spacer(Modifier.weight(1f))
            if (step != Step.START) BigButton("이전", t, filled = false, color = Text2) { c.accidentBack() }
            BigButton("끝내기", t, filled = false, color = Text2) { c.endAccident() }
        }

        // 진행 표시: 1 비상등 · 2 트렁크 · 3 대피 · 4 연락
        val names = listOf("비상등", "트렁크", "대피", "연락", "정리")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            names.forEachIndexed { i, n ->
                val idx = i + 1
                val p = when (step) {
                    Step.START -> 0
                    Step.HAZARD -> 1
                    Step.TRUNK -> 2
                    Step.EVACUATE -> 3
                    Step.CONTACT -> 4
                    Step.AFTER -> 5
                    Step.DONE -> 6
                }
                val done = idx < p
                val now = idx == p
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (now) PanelHi else Panel, RoundedCornerShape(12.dp))
                        .border(2.dp, if (now) red else if (done) Cyan else Line, RoundedCornerShape(12.dp))
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text((if (done) "✓ " else "$idx ") + n, color = if (now) Text1 else Text2, fontSize = 22.sp)
                }
            }
        }

        T(step.title, t.hero, bold = true)
        T(step.body, t.body, Text2)

        step.check?.let { what ->
            Card(border = if (c.accidentConfirmed) Cyan else Line) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T(
                        if (c.accidentConfirmed) "✓ 차에서 확인했어요 ($what)" else "차 신호로 확인하는 중… ($what)",
                        t.body, if (c.accidentConfirmed) Cyan else Text2, bold = true,
                    )
                    BigButton("했어요", t, Modifier.fillMaxWidth(), color = red) { c.accidentNext() }
                }
            }
        }

        when (step) {
            Step.START -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("다친 사람 있어요", t, Modifier.weight(1f), color = red) { c.answerInjury(true) }
                BigButton("없어요", t, Modifier.weight(1f), filled = false) { c.answerInjury(false) }
            }
            Step.CONTACT -> {
                BigButton("119  구급 · 화재", t, Modifier.fillMaxWidth(), color = red) { c.dial("119") }
                BigButton("1588-2504  고속도로 긴급견인 (무료)", t, Modifier.fillMaxWidth(), filled = false) { c.dial("15882504") }
                if (c.insurerPhone.isNotBlank()) {
                    BigButton("${c.insurerName.ifBlank { "보험사" }}  ${c.insurerPhone}", t, Modifier.fillMaxWidth(), filled = false) { c.dial(c.insurerPhone) }
                } else {
                    T("보험사 번호는 설정에서 미리 넣어두면 여기 버튼이 생겨요.", t.small, Text2)
                }
                BigButton("다음", t, Modifier.fillMaxWidth(), color = Text2, filled = false) { c.accidentNext() }
            }
            Step.AFTER -> {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("현장 전체 · 번호판 · 파손 부위 사진", "상대 연락처 · 보험사", "블랙박스 영상 보존", "과실은 현장에서 정하지 않기").forEach {
                            T("□  $it", t.body)
                        }
                    }
                }
                BigButton("다 챙겼어요", t, Modifier.fillMaxWidth()) { c.accidentNext() }
            }
            Step.DONE -> {
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        T("기록", t.small, Cyan, bold = true)
                        c.accidentLog.forEach { T(it, t.body) }
                    }
                }
                BigButton("닫기", t, Modifier.fillMaxWidth()) { c.endAccident() }
            }
            else -> {}
        }

        // 시연할 때: 차 신호를 여기서 바로 바꿔 자동 확인을 보여준다
        if (c.simOn) {
            Text("테스트: 차 신호 바꾸기", color = Text2, fontSize = 22.sp)
            ChipGrid(
                listOf(
                    Triple("비상등", s.hazard) { c.sim = c.sim.copy(hazard = !c.sim.hazard, turnLeft = false, turnRight = false) },
                    Triple("트렁크", s.trunkOpen) { c.sim = c.sim.copy(trunkOpen = !c.sim.trunkOpen) },
                    Triple("기어 P", s.gear == Gear.P) { c.sim = c.sim.copy(gear = Gear.P, speedKmh = 0f) },
                    Triple("운전석 문", s.driverDoorOpen) {
                        val d = "ROW_1_LEFT"
                        c.sim = c.sim.copy(openDoors = if (d in c.sim.openDoors) c.sim.openDoors - d else c.sim.openDoors + d)
                    },
                ),
                perRow = 4,
            )
        }
        Text(
            "에어백이 터질 만큼 큰 사고라면 차량의 긴급 구조(SOS) 버튼을 먼저 쓰세요. 이 가이드는 과실이나 법적 판단을 하지 않아요.",
            color = Text2, fontSize = 20.sp,
        )
    }
}

// ═══════════════════ 5. 주차 요약 ═══════════════════

@Composable
fun ParkingScreen(c: AppController, t: Type) {
    val p = c.parking ?: return
    val ok = levelColor(Level.INFO, c.profile)
    val warn = levelColor(Level.CAUTION, c.profile)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(t.gap),
    ) {
        T("주차 완료", t.hero, bold = true)
        p.weatherLine?.let { w ->
            Card(border = warn) {
                Column {
                    T(if (p.weatherIsDemo) "내일 날씨 [예시]" else "내일 날씨", t.small, warn, bold = true)
                    T(w, t.body)
                    if (p.openWindows.isNotEmpty()) T("${p.openWindows.joinToString(", ")} 창문이 열려 있어요. 닫고 내리세요.", t.body, warn, bold = true)
                }
            }
        }
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                p.items.forEach { (name, problem) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ShapeIcon(if (problem) Level.CAUTION.shape else Level.INFO.shape, if (problem) warn else ok, 44.dp)
                        T(name, t.body, if (problem) Text1 else Text2, bold = problem)
                    }
                }
            }
        }
        p.brakeTip?.let {
            Card(border = ok) {
                Column {
                    T("오늘 브레이크 한마디", t.small, ok, bold = true)
                    T(it, t.body)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton("다시 듣기", t, Modifier.weight(1f), filled = false) {
                c.say(if (p.hasAnything) p.speech else "수고하셨어요. 창문과 문이 모두 닫혀 있어요.")
            }
            BigButton("가족에게 요약 보내기", t, Modifier.weight(1f), filled = false) { c.shareFamily() }
        }
        T("가족 요약에는 위치가 들어가지 않아요. 보내기 전에 내용을 확인할 수 있어요.", t.small, Text2)
        BigButton("닫기", t, Modifier.fillMaxWidth()) { c.closeParking() }
    }
}

// ═══════════════════ 6. 설정 (정차 중에만) ═══════════════════

@Composable
fun SettingsScreen(c: AppController, t: Type) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(t.gap),
    ) {
        T("설정", t.hero, bold = true)
        if (c.state.moving) {
            T("안전을 위해 설정은 차가 멈췄을 때만 바꿀 수 있어요.", t.body, Text2)
            BigButton("돌아가기", t, Modifier.fillMaxWidth()) { c.screen = Screen.DRIVE }
        } else {
            SettingsBody(c, t)
        }
    }
}

@Composable
private fun SettingsBody(c: AppController, t: Type) {
    var name by remember { mutableStateOf(c.insurerName) }
    var phone by remember { mutableStateOf(c.insurerPhone) }
    Column(verticalArrangement = Arrangement.spacedBy(t.gap)) {
        T("운전자 프로필", t.title, bold = true)
        Profile.entries.forEach { p ->
            BigButton(
                (if (c.profile == p) "✓ " else "") + p.label, t, Modifier.fillMaxWidth(),
                filled = c.profile == p,
            ) { c.chooseProfile(p) }
        }
        BigButton("색 구분 테스트 다시 하기", t, Modifier.fillMaxWidth(), color = Text2, filled = false) {
            c.prefs.onboarded = false
            c.screen = Screen.ONBOARD
        }

        T("보험사 (사고 가이드에서 바로 전화)", t.title, bold = true)
        val fieldStyle = TextStyle(fontSize = t.body, color = Text1)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), textStyle = fieldStyle, label = { Text("보험사 이름", fontSize = 20.sp) })
        OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '-' } }, Modifier.fillMaxWidth(), textStyle = fieldStyle, label = { Text("긴급출동 번호", fontSize = 20.sp) })
        BigButton("저장", t, Modifier.fillMaxWidth()) { c.saveInsurer(name.trim(), phone.trim()) }

        T("테스트", t.title, bold = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("테스트 모드 (알림 빨리 보기)", t.body, bold = true)
                T("기준 시간을 짧게 (깜빡이 10초 · 상향등 20초 · 브레이크 2회)", t.small, Text2)
            }
            Switch(checked = c.demoMode, onCheckedChange = { c.changeDemoMode(it) })
        }
        BigButton("브레이크 기록 지우기", t, Modifier.fillMaxWidth(), color = Text2, filled = false) { c.resetBrake() }
        T("연결 상태: ${c.sourceInfo} · 받은 신호 ${c.realSignalCount}건", t.small, Text2)
        T("Logcat에서 SignalLens 태그로 구독한 함수와 들어온 값을 볼 수 있어요.", t.small, Text2)
        Spacer(Modifier.height(8.dp))
        BigButton("돌아가기", t, Modifier.fillMaxWidth()) { c.screen = Screen.DRIVE }
    }
}
