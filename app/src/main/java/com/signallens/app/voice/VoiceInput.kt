package com.signallens.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * "방금 뭐 켜졌어?", "사고 났어" 같은 짧은 말을 알아듣는다.
 * Pleos SpeechToText SDK 대신 안드로이드 기본 음성 인식을 쓴다.
 * 안 될 때는 이유를 문장으로 돌려준다 (onProblem).
 */
class VoiceInput(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    /** 실시간 로그 한 줄 (화면 + Logcat) */
    private val onDebug: (String) -> Unit = {},
    /** 마이크 소리 크기 0~1 */
    private val onLevel: (Float) -> Unit = {},
) {
    private var maxRms = -100f
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    /** 이 기기에 깔린 음성 인식 서비스 (패키지 이름) */
    private fun services(): String = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .joinToString { it.serviceInfo.packageName }
    }.getOrDefault("")

    private fun onDevice(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || onDevice()

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun status(): String = when {
        !hasMicPermission() -> "마이크 권한이 꺼져 있어요"
        SpeechRecognizer.isRecognitionAvailable(context) -> "음성 인식 준비됨 · ${services()}"
        onDevice() -> "음성 인식 준비됨 (기기 내)"
        else -> "이 기기에 음성 인식 엔진이 없어요"
    }

    fun listen() {
        if (!hasMicPermission()) {
            onProblem("마이크 권한을 허용해 주세요.")
            return
        }
        val r = recognizer ?: when {
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            onDevice() && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> {
                onProblem("이 기기에는 음성 인식 엔진이 없어요. 화면 버튼으로도 똑같이 쓸 수 있어요.")
                return
            }
        }.also { recognizer = it }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onDebug("준비됨 — 지금 말하세요")
            override fun onBeginningOfSpeech() = onDebug("말소리 감지 시작")
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > maxRms) maxRms = rmsdB
                onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onDebug("말 끝 감지 — 인식 중…")
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { onDebug("… $it") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                onDebug("오류 코드 $error (최대 소리 ${"%.1f".format(maxRms)}dB)")
                onLevel(0f)
                clearTimeout()
                onListening(false)
                onProblem(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한을 허용해 주세요."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "잘 못 들었어요. 다시 말씀해 주세요."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "인터넷 연결이 필요해요."
                        SpeechRecognizer.ERROR_AUDIO -> "마이크 소리가 안 들어와요. 에뮬레이터 마이크 설정을 확인해 주세요."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "잠시 뒤에 다시 눌러 주세요."
                        else -> "음성 인식 오류($error)가 났어요."
                    }
                )
            }
            override fun onResults(results: Bundle?) {
                clearTimeout()
                onListening(false)
                onLevel(0f)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onDebug("들은 말: ${text ?: "(없음)"}")
                if (text.isNullOrBlank()) onProblem("잘 못 들었어요. 다시 말씀해 주세요.") else onText(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        maxRms = -100f
        onDebug("듣기 시작 (${services().ifBlank { "기기 내 인식" }})")
        onListening(true)
        r.startListening(intent)
        // 10초 동안 아무 응답이 없으면 멈추고 이유를 알려준다 ("듣는 중…"에 멈춰 있지 않게)
        clearTimeout()
        val task = Runnable {
            onDebug("10초 동안 응답 없음 (최대 소리 ${"%.1f".format(maxRms)}dB${if (maxRms < 0f) " → 마이크로 소리가 안 들어옴" else ""})")
            onLevel(0f)
            runCatching { r.cancel() }
            onListening(false)
            onProblem("소리가 안 들어와요. 에뮬레이터 마이크 설정을 확인해 주세요.")
        }
        timeout = task
        main.postDelayed(task, 10_000)
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
