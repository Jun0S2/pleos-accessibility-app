package com.signallens.app.pleos

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Pleos TextToSpeech SDK (Gleo AI 음성). 차량·에뮬레이터의 한국어 음성은 안드로이드 기본 TTS가 아니라
 * 이 SDK로 나온다. ON_DEVICE 모드라 계정 키가 필요 없다.
 *
 * 순서 주의 (에뮬레이터 v2.1.0): initialize()가 성공한 뒤에 addEventListener()를 불러야 한다.
 */
class PleosSpeaker(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onReady: (Boolean, String) -> Unit,
    /** 한 문장을 다 말했을 때 */
    private val onDone: () -> Unit = {},
) {
    private var tts: Any? = null
    private var events: Any? = null
    @Volatile var ready = false
        private set

    init {
        try {
            val mode = Reflect.enumOf("ai.pleos.playground.tts.constant.Mode", "ON_DEVICE")
            val t = Reflect.create("ai.pleos.playground.tts.TextToSpeech", context.applicationContext, mode)
            if (t == null) {
                onReady(false, "Pleos TTS SDK 없음")
            } else {
                tts = t
                val init = Reflect.listenerMethod(t, "initialize")
                if (init != null) {
                    init.invoke(t, Reflect.proxy(init.parameterTypes[0], "SignalLensTtsInit") { name, args ->
                        when (name) {
                            "onSuccess" -> afterInit(t)
                            "onFailure" -> onReady(false, "Pleos TTS 초기화 실패: ${args.firstOrNull()}")
                        }
                    })
                } else {
                    Reflect.call(t, "initialize")
                    afterInit(t)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos TTS 사용 불가", e)
            onReady(false, "Pleos TTS 사용 불가: ${e.cause?.message ?: e.message}")
        }
    }

    private fun afterInit(t: Any) {
        runCatching {
            val add = Reflect.listenerMethod(t, "addEventListener")
            if (add != null) {
                val l = Reflect.proxy(add.parameterTypes[0], "SignalLensTtsEvents") { name, args ->
                    when (name) {
                        "onStart" -> onLog("🔊 말하기 시작 (Pleos)")
                        "onDone" -> {
                            onLog("🔊 말하기 끝")
                            onDone()
                        }
                        "onError" -> onLog("🔊 말하기 실패: ${args.firstOrNull()}")
                    }
                }
                events = l
                add.invoke(t, l)
            }
        }.onFailure { Log.w(TAG, "addEventListener 실패", it) }
        runCatching { Reflect.call(t, "setLocale", Locale.KOREA) }
        ready = true
        onReady(true, "음성 준비됨 · Pleos TTS (한국어)")
    }

    fun speak(text: String): Boolean {
        val t = tts ?: return false
        if (!ready || text.isBlank()) return false
        return try {
            // speak(text, isFinal): true = 한 번에 끝나는 문장
            val ok = Reflect.call(t, "speak", text, true) || Reflect.call(t, "speak", text)
            onLog(if (ok) "🔊 요청(Pleos): $text" else "🔊 Pleos speak 함수를 못 찾음")
            ok
        } catch (e: Throwable) {
            onLog("🔊 Pleos speak 오류: ${e.cause?.message ?: e.message}")
            false
        }
    }

    fun stop() {
        tts?.let { runCatching { Reflect.call(it, "stop") } }
    }

    fun release() {
        val t = tts ?: return
        runCatching { events?.let { e -> Reflect.listenerMethod(t, "removeEventListener")?.invoke(t, e) } }
        runCatching { Reflect.call(t, "release") }
        tts = null
        ready = false
    }

    companion object {
        const val TAG = "SignalLens"
    }
}

/**
 * Pleos SpeechToText SDK (Gleo AI 음성 인식).
 * 안드로이드 기본 SpeechRecognizer는 에뮬레이터에서 응답하지 않았다(ai.umos.ambientai, 오류 5).
 * 차량의 음성 인식은 이 SDK로만 열린다.
 */
class PleosListener(
    context: Context,
    private val onText: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onLog: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onReady: (Boolean, String) -> Unit,
) {
    private var stt: Any? = null
    private var listener: Any? = null
    @Volatile var ready = false
        private set
    @Volatile private var active = false
    private var gotFinal = false
    private var lastPartial = ""

    init {
        try {
            val mode = Reflect.enumOf("ai.pleos.playground.stt.constant.Mode", "ON_DEVICE")
            val s = Reflect.create("ai.pleos.playground.stt.SpeechToText", context.applicationContext, mode)
            if (s == null) {
                onReady(false, "Pleos STT SDK 없음")
            } else {
                stt = s
                val add = Reflect.listenerMethod(s, "addListener")
                if (add != null) {
                    val l = Reflect.proxy(add.parameterTypes[0], "SignalLensSttEvents", ::onEvent)
                    listener = l
                    add.invoke(s, l)
                }
                val init = Reflect.listenerMethod(s, "initialize")
                if (init != null) {
                    init.invoke(s, Reflect.proxy(init.parameterTypes[0], "SignalLensSttInit") { name, args ->
                        when (name) {
                            "onSuccess" -> afterInit(s)
                            "onFailure" -> onReady(false, "Pleos STT 초기화 실패: ${args.firstOrNull()}")
                        }
                    })
                } else {
                    Reflect.call(s, "initialize")
                    afterInit(s)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos STT 사용 불가", e)
            onReady(false, "Pleos STT 사용 불가: ${e.cause?.message ?: e.message}")
        }
    }

    private fun afterInit(s: Any) {
        runCatching { Reflect.call(s, "setLocale", Locale.KOREA) }
        ready = true
        onReady(true, "음성 인식 준비됨 · Pleos STT (한국어)")
    }

    private fun onEvent(name: String, args: Array<Any?>) {
        // 원본 그대로 Logcat에 남긴다 (소리 크기 값은 너무 많아서 제외)
        if (name != "onUpdatedRms") Log.i(TAG, "[STT 원본] $name ${args.joinToString(prefix = "(", postfix = ")")}")
        when (name) {
            "onReady" -> onLog("준비됨 — 지금 말하세요 (Pleos STT)")
            "onStartedRecognition" -> onLog("말소리 감지 시작")
            "onUpdatedRms" -> {
                val v = (args.firstOrNull() as? Number)?.toFloat() ?: 0f
                // 값의 범위가 문서에 없어 0~1, 0~100 둘 다 받아들인다
                onLevel((if (v > 1f) v / 100f else v).coerceIn(0f, 1f))
            }
            "onUpdated" -> {
                val text = (args.getOrNull(0) as? String).orEmpty().trim()
                val final = args.getOrNull(1) as? Boolean ?: false
                if (final) {
                    gotFinal = true
                    finish()
                    if (text.isBlank()) {
                        onLog("들은 말 없음")
                        onProblem("잘 못 들었어요. 다시 말씀해 주세요.")
                    } else {
                        onLog("들은 말: $text")
                        onText(text)
                    }
                } else if (text.isNotBlank() && text != lastPartial) {
                    lastPartial = text
                    onLog("… $text")
                }
            }
            "onEndedRecognition" -> {
                if (active && !gotFinal) {
                    finish()
                    val heard = lastPartial
                    if (heard.isNotBlank()) {
                        onLog("들은 말: $heard")
                        onText(heard)
                    } else {
                        onLog("인식 끝 — 들은 말 없음")
                        onProblem("잘 못 들었어요. 다시 말씀해 주세요.")
                    }
                }
            }
            "onError" -> {
                onLog("Pleos STT 오류: ${args.firstOrNull()}")
                if (active) {
                    finish()
                    onProblem("음성 인식 오류가 났어요. 다시 눌러 주세요.")
                }
            }
        }
    }

    private fun finish() {
        active = false
        onLevel(0f)
        onListening(false)
    }

    fun listen(): Boolean {
        val s = stt ?: return false
        if (!ready) return false
        if (active) return true
        gotFinal = false
        lastPartial = ""
        return try {
            if (!Reflect.call(s, "request")) return false
            active = true
            onListening(true)
            onLog("듣기 시작 (Pleos STT)")
            true
        } catch (e: Throwable) {
            onLog("Pleos STT request 오류: ${e.cause?.message ?: e.message}")
            false
        }
    }

    /** 응답이 없을 때 호출 (컨트롤러의 시간 초과) */
    fun cancel() {
        val s = stt ?: return
        runCatching { Reflect.call(s, "stop") }
        if (active) finish()
    }

    val isActive: Boolean get() = active

    fun release() {
        val s = stt ?: return
        runCatching { listener?.let { l -> Reflect.listenerMethod(s, "removeListener")?.invoke(s, l) } }
        runCatching { Reflect.call(s, "release") }
        stt = null
        ready = false
    }

    companion object {
        const val TAG = "SignalLens"
    }
}
