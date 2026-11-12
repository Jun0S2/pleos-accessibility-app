package com.signallens.app.vehicle

import android.content.Context
import android.util.Log
import com.signallens.app.engine.RawSignal
import java.lang.reflect.Proxy

/**
 * 안드로이드 오토모티브(AAOS) 표준 차량 속성을 읽는다.
 * Pleos FAQ: "클러스터와 같은 속도를 쓰려면 CarPropertyManager로 PERF_VEHICLE_SPEED를 읽는다"
 *
 * android.car 라이브러리를 빌드에 넣지 않고 리플렉션으로 쓴다 (없는 기기에서도 앱이 죽지 않게).
 */
class CarPropertySource(
    private val context: Context,
    private val onSignal: (RawSignal) -> Unit,
    private val log: (String) -> Unit,
) {
    private var car: Any? = null
    private var manager: Any? = null
    private var callback: Any? = null

    /** 속성 id → 이름. 이름은 SignalMapper가 해석에 쓴다. */
    private val props = linkedMapOf(
        0x11600207 to ("PERF_VEHICLE_SPEED" to 5f), // m/s, 초당 5회
        0x11400401 to ("CURRENT_GEAR" to 0f),
        0x11400400 to ("GEAR_SELECTION" to 0f),
        0x11200407 to ("NIGHT_MODE" to 0f),         // 조도 기반 주야간
        0x11600308 to ("RANGE_REMAINING" to 0f),    // m
    )

    fun start(): Int {
        return try {
            val carCls = Class.forName("android.car.Car")
            val c = carCls.getMethod("createCar", Context::class.java).invoke(null, context)
            car = c
            val mgr = carCls.getMethod("getCarManager", String::class.java).invoke(c, "property")
            manager = mgr
            val cbCls = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val cb = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { proxy, m, a ->
                when (m.name) {
                    "equals" -> proxy === a?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "SignalLensCarProperty"
                    "onChangeEvent" -> {
                        val v = a?.firstOrNull() ?: return@newProxyInstance null
                        val id = v.javaClass.getMethod("getPropertyId").invoke(v) as Int
                        val value = v.javaClass.getMethod("getValue").invoke(v)
                        val name = props[id]?.first ?: "PROP_$id"
                        Log.d(TAG, "$name = $value")
                        onSignal(RawSignal(name, null, value))
                        null
                    }
                    else -> null
                }
            }
            callback = cb
            val reg = mgr!!.javaClass.getMethod(
                "registerCallback", cbCls, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            var ok = 0
            props.forEach { (id, pair) ->
                val (name, rate) = pair
                try {
                    if (reg.invoke(mgr, cb, id, rate) == true) ok++ else Log.d(TAG, "$name 구독 거부")
                } catch (e: Throwable) {
                    Log.d(TAG, "$name 구독 실패: ${e.cause?.message ?: e.message}")
                }
            }
            log("AAOS 속성 구독 ${ok}건")
            ok
        } catch (e: Throwable) {
            log("AAOS 속성 사용 불가: ${e.javaClass.simpleName}")
            0
        }
    }

    fun stop() {
        runCatching {
            val cbCls = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            manager?.javaClass?.getMethod("unregisterCallback", cbCls)?.invoke(manager, callback)
        }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        car = null
        manager = null
        callback = null
    }

    companion object {
        const val TAG = "SignalLens"
    }
}
