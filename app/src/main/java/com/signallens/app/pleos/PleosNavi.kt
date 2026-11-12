package com.signallens.app.pleos

import android.content.Context
import android.util.Log

/**
 * Pleos NaviHelper SDK: 목적지까지 남은 거리, 현재 위치, 경유지 추가.
 * 값은 리스너로 돌아온다 (getDestinationInfo() → onDestinationInfo).
 */
class PleosNavi(
    context: Context,
    /** 목적지까지 남은 거리(km). 경로가 없으면 null */
    private val onRemainKm: (Float?) -> Unit,
    private val onLocation: (Double, Double) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private var navi: Any? = null
    private var listener: Any? = null
    @Volatile var ready = false
        private set

    init {
        try {
            val n = Reflect.create("ai.pleos.playground.navi.helper.NaviHelper", context.applicationContext)
            if (n == null) {
                onLog("NaviHelper SDK 없음")
            } else {
                navi = n
                Reflect.call(n, "initialize")
                val add = Reflect.listenerMethod(n, "addListener")
                if (add != null) {
                    val l = Reflect.proxy(add.parameterTypes[0], "SignalLensNavi", ::onEvent)
                    listener = l
                    add.invoke(n, l)
                }
                ready = true
                onLog("NaviHelper 연결")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper 사용 불가", e)
            onLog("NaviHelper 사용 불가: ${e.cause?.message ?: e.message}")
        }
    }

    private fun onEvent(name: String, args: Array<Any?>) {
        when (name) {
            "onDestinationInfo" -> {
                val info = args.firstOrNull()
                val meters = (Reflect.read(info, "remainDistance") as? Number)?.toFloat()
                Log.d(TAG, "Navi 목적지: ${Reflect.read(info, "name")} 남은 ${meters}m")
                onRemainKm(meters?.takeIf { it > 0f }?.div(1000f))
            }
            "onCurrentLocationInfo" -> {
                val info = args.firstOrNull()
                val lat = (Reflect.read(info, "latitude") as? Number)?.toDouble()
                val lon = (Reflect.read(info, "longitude") as? Number)?.toDouble()
                if (lat != null && lon != null && lat != 0.0) onLocation(lat, lon)
            }
            "onRouteEnded", "onRouteCancelled", "onDestinationArrived" -> onRemainKm(null)
            "onRouteStarted", "onRoutingCompleted", "onReRouteEnded", "onDestinationChanged", "onWaypointChanged" -> poll()
            "onError" -> Log.d(TAG, "Navi 오류: ${args.firstOrNull()}")
        }
    }

    /** 몇 초마다 불러 최신 값을 요청한다 */
    fun poll() {
        val n = navi ?: return
        runCatching { Reflect.call(n, "getDestinationInfo") }
        runCatching { Reflect.call(n, "getCurrentLocationInfo") }
    }

    /** 경유지 추가. 성공하면 true */
    fun addWaypoint(lat: Double, lon: Double, name: String): Boolean {
        val n = navi ?: return false
        return try {
            val first = Reflect.enumOf("ai.pleos.playground.navi.constants.WaypointIndex", "FIRST")
            val req = Reflect.create(
                "ai.pleos.playground.navi.data.RequestWaypointInfo",
                lat, lon, name, first, "", "", "", "",
            )
            if (req == null) {
                onLog("경유지 요청 형식을 못 찾음 (RequestWaypointInfo)")
                false
            } else {
                val ok = Reflect.call(n, "addWaypoint", req)
                onLog(if (ok) "경유지 추가 요청: $name" else "addWaypoint 함수를 못 찾음")
                ok
            }
        } catch (e: Throwable) {
            onLog("경유지 추가 실패: ${e.cause?.message ?: e.message}")
            false
        }
    }

    fun release() {
        val n = navi ?: return
        runCatching { listener?.let { l -> Reflect.listenerMethod(n, "removeListener")?.invoke(n, l) } }
        runCatching { Reflect.call(n, "release") }
        navi = null
        ready = false
    }

    companion object {
        const val TAG = "SignalLens"
    }
}
