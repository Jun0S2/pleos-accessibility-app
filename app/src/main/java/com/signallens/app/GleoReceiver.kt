package com.signallens.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Gleo Interface: "글레오, 운전비서 방금 뭐 켜졌어?" 처럼 글레오에게 말하면
 * 글레오가 assets/schema.json의 함수를 골라 이 리시버로 방송을 보낸다.
 * 앱은 APP_ACTION_RESPONSE 방송으로 답 문장을 돌려주고, 글레오가 그 문장을 읽어 준다.
 *
 * Gleo 없이 시험: adb shell am broadcast -a WHAT_IS_ON -n com.signallens.app/.GleoReceiver
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val name = action.substringAfterLast('.') // "com.signallens.app.WHAT_IS_ON" 형태로 와도 받는다
        val controller = (context.applicationContext as? SignalLensApp)?.controller
        val message = controller?.gleo(name)
        val response = JSONObject()
            .put("functionName", name)
            .put("status", if (message != null) "success" else "fail")
            .put("message", message ?: "지금은 답할 수 없어요.")
        Log.i("SignalLens", "[Gleo] $action → $response")
        context.sendBroadcast(
            Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString())
        )
    }
}
