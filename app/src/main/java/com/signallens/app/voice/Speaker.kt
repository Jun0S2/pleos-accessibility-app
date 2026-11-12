package com.signallens.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 한국어 음성 안내. Pleos TextToSpeech SDK 대신 안드로이드 기본 TTS를 쓴다.
 *
 * 소리가 안 날 때 원인을 화면에 보여주려고 상태(status)를 콜백으로 알린다.
 *  - 음성 엔진이 아예 없음 → 에뮬레이터에 TTS 앱 설치 필요
 *  - 엔진은 있는데 한국어 없음 → 한국어 음성 데이터 설치 필요
 * 음성이 안 되더라도 알림음(띵)은 따로 울린다.
 */
class Speaker(context: Context, private val onStatus: (String, Boolean) -> Unit) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var korean = false
    private var pending: String? = null
    private var rate = 1.0f
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS 초기화 실패: $status")
            onStatus("음성 엔진이 없어요 (TTS 초기화 실패)", false)
            return
        }
        val engines = tts.engines.joinToString { it.name }
        // 차에서는 '내비 안내 음성'으로 내보내야 음악 위로 들린다
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        korean = pickKorean()
        if (!korean) runCatching { tts.setLanguage(Locale.US) } // 한국어가 없으면 영어로라도 말한다
        tts.setSpeechRate(rate)
        ready = true
        Log.i(TAG, "TTS 엔진: $engines, 기본: ${tts.defaultEngine}, 한국어: $korean")
        if (korean) onStatus("음성 준비됨 · ${tts.defaultEngine}", true)
        else onStatus("한국어 음성이 없어 영어로 말해요 (${tts.defaultEngine})", false)
        pending?.let { speak(it, pendingEn) }
        pending = null
    }

    /**
     * 엔진마다 한국어 이름이 달라서(ko, ko_KR, kor) 하나씩 시도하고,
     * 그래도 안 되면 엔진이 가진 목소리 목록에서 한국어를 직접 찾는다.
     */
    private fun pickKorean(): Boolean {
        fun ok(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        for (loc in listOf(Locale.KOREA, Locale.KOREAN, Locale("kor"), Locale("ko", "KR"))) {
            if (runCatching { ok(tts.setLanguage(loc)) }.getOrDefault(false)) return true
        }
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        Log.i(TAG, "목소리 목록: " + voices.joinToString { "${it.name}(${it.locale})" })
        val ko = voices.firstOrNull {
            val l = it.locale.language.lowercase()
            l == "ko" || l == "kor" || it.name.lowercase().startsWith("ko")
        } ?: return false
        return runCatching { tts.setVoice(ko) == TextToSpeech.SUCCESS }.getOrDefault(false)
    }

    fun setRate(r: Float) {
        rate = r
        if (ready) tts.setSpeechRate(r)
    }

    /** 주의 · 경고 앞에 짧은 알림음. TTS가 없어도 소리는 난다. */
    fun chime(urgent: Boolean) {
        runCatching {
            tone?.startTone(if (urgent) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK, 250)
        }
    }

    /** @param en 한국어 음성이 없을 때 대신 읽을 영어 문장 */
    fun speak(text: String, en: String? = null) {
        if (text.isBlank()) return
        val say = if (korean || !ready) text else (en ?: "Signal Lens. Please check the screen.")
        if (!ready) {
            pending = text
            pendingEn = en
            return
        }
        tts.speak(say, TextToSpeech.QUEUE_FLUSH, null, "sl_${System.currentTimeMillis()}")
    }

    private var pendingEn: String? = null

    fun stop() {
        tts.stop()
    }

    fun release() {
        tts.stop()
        tts.shutdown()
        tone?.release()
    }

    companion object {
        const val TAG = "SignalLens"
    }
}
