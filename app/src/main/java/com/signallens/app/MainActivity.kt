package com.signallens.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.signallens.app.ui.App

class MainActivity : ComponentActivity() {

    private val controller: AppController get() = (application as SignalLensApp).controller

    /** 음성 인식 + AAOS 속도/기어 권한. 거절돼도 앱은 버튼과 테스트 패널로 동작한다. */
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.startCarProperty()
        controller.refreshChecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()
        setContent {
            App(
                c = controller,
                requestMic = { askPermissions() },
                openOverlaySettings = { openOverlaySettings() },
            )
        }
    }

    // 앱 화면이 실제로 맨 앞에 있는지. Pleos는 창을 나눠 쓰기 때문에 내비로 넘어가도
    // 액티비티가 '정지'되지 않을 수 있다. 그래서 정지 여부와 창 포커스를 함께 본다.
    private var started = false
    private var focused = false

    private fun updateVisible() {
        controller.appVisible = started && focused
    }

    override fun onStart() {
        super.onStart()
        started = true
        updateVisible()
    }

    override fun onStop() {
        started = false
        updateVisible()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        updateVisible()
    }

    private fun askPermissions() {
        permissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                "pleos.car.permission.TTS_SERVICE",
                "pleos.car.permission.STT_SERVICE",
                "pleos.car.permission.NAVI_ROUTE",
                "pleos.car.permission.NAVI_ROUTE_SEARCH",
                "android.car.permission.CAR_SPEED",
                "android.car.permission.CAR_POWERTRAIN",
                "android.car.permission.CAR_ENERGY",
                "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            )
        )
    }

    /** '다른 앱 위에 표시' 권한 화면. 차 화면에 이 설정이 없으면 adb 명령으로 켠다 (README 참고). */
    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            controller.say("이 화면에서는 권한 설정을 열 수 없어요. 안내된 명령어로 켜주세요.")
        }
    }
}
