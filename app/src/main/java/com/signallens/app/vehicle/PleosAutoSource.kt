package com.signallens.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import com.signallens.app.engine.RawSignal
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pleos Vehicle SDK의 register 함수들을 자동으로 찾아 구독한다.
 *
 * 왜 이렇게 하나요?
 *  공개 문서에서 리스너 이름과 반환 타입을 다 확인할 수 없어서,
 *  실행 중에 함수 시그니처를 보고 연결한다. (공식 샘플 앱의 VehicleApiExplorer와 같은 방식)
 *  - registerXxx(listener)            → 바로 구독
 *  - registerXxx(area, listener)      → 영역(문 · 창문 등) 값마다 구독
 *  들어온 값은 RawSignal로 넘기고, 해석은 engine/SignalMapper가 한다.
 *  Logcat 태그 "SignalLens"에 구독한 함수와 들어온 값이 모두 찍힌다.
 */
class PleosAutoSource(
    context: Context,
    private val onSignal: (RawSignal) -> Unit,
    private val log: (String) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false

    private data class Reg(val domain: Any, val unregister: Method?, val args: Array<Any?>)
    private val regs = mutableListOf<Reg>()

    /** 구독할 도메인과 함수 이름 필터 */
    private val targets: List<Pair<String, (String) -> Boolean>> = listOf(
        "getTurnSignal" to { _ -> true },
        "getLight" to { n -> n.contains("HighBeam") || n.contains("LowBeam") },
        "getDoor" to { n -> !n.contains("Lock") && !n.contains("Glove") },
        "getWindow" to { n -> n.contains("Position") || n.contains("Open") },
        "getDrivingMode" to { _ -> true },
        "getFuel" to { _ -> true },
        "getEvBattery" to { n -> n.contains("Range") || n.contains("StateOfCharge") || n.contains("Level") },
        "getBrake" to { _ -> true },
    )

    /** @return 구독에 성공한 개수 */
    fun start(): Int {
        try {
            vehicle.initialize()
            initialized = true
        } catch (e: Throwable) {
            log("Vehicle SDK 초기화 실패: ${e.message}")
            return 0
        }
        var count = 0
        for ((getter, filter) in targets) {
            val domain = runCatching { vehicle.javaClass.getMethod(getter).invoke(vehicle) }.getOrNull()
            if (domain == null) {
                log("$getter 없음")
                continue
            }
            domain.javaClass.methods
                .filter { it.name.startsWith("register") && !it.isSynthetic && !it.name.contains('$') && filter(it.name) }
                .forEach { m -> count += subscribe(domain, m) }
        }
        log("Pleos 구독 ${count}건")
        return count
    }

    private fun subscribe(domain: Any, m: Method): Int {
        val p = m.parameterTypes
        val listenerIdx = p.indexOfFirst { it.isInterface }
        if (listenerIdx < 0) return 0
        return when (p.size) {
            1 -> if (register(domain, m, arrayOfNulls(1), listenerIdx, null)) 1 else 0
            2 -> {
                val areaIdx = 1 - listenerIdx
                val areas = areaValues(p[areaIdx])
                if (areas.isEmpty()) {
                    Log.d(TAG, "건너뜀 (영역 타입 모름): ${m.toGenericString()}")
                    return 0
                }
                areas.count { (name, areaObj) ->
                    val args = arrayOfNulls<Any>(2).also { it[areaIdx] = areaObj }
                    register(domain, m, args, listenerIdx, name)
                }
            }
            else -> {
                Log.d(TAG, "건너뜀 (인자 ${p.size}개): ${m.toGenericString()}")
                0
            }
        }
    }

    /** DoorArea(DoorValue.X) 처럼 enum 하나를 받는 영역 클래스면 모든 값을 만든다 */
    private fun areaValues(areaCls: Class<*>): List<Pair<String, Any>> {
        val ctor = areaCls.constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0].isEnum }
            ?: return emptyList()
        val enums = ctor.parameterTypes[0].enumConstants ?: return emptyList()
        return enums.mapNotNull { e ->
            runCatching { (e as Enum<*>).name to ctor.newInstance(e) }.getOrNull()
        }
    }

    private fun register(domain: Any, m: Method, args: Array<Any?>, listenerIdx: Int, area: String?): Boolean {
        val listenerType = m.parameterTypes[listenerIdx]
        val label = if (area != null) "${m.name}[$area]" else m.name
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, a ->
            when (method.name) {
                "equals" -> proxy === a?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SignalLens($label)"
                "onFailed" -> {
                    Log.d(TAG, "$label 실패: ${(a?.firstOrNull() as? Throwable)?.message}")
                    null
                }
                else -> {
                    val value = when {
                        a == null || a.isEmpty() -> null
                        a.size == 1 -> a[0]
                        else -> a.last()
                    }
                    Log.d(TAG, "$label.${method.name} = $value")
                    onSignal(RawSignal(m.name, area, value))
                    null
                }
            }
        }
        args[listenerIdx] = listener
        return try {
            m.invoke(domain, *args)
            val unregister = runCatching {
                domain.javaClass.getMethod(m.name.replaceFirst("register", "unregister"), *m.parameterTypes)
            }.getOrNull()
            regs += Reg(domain, unregister, args.copyOf())
            Log.i(TAG, "구독: $label (${listenerType.simpleName})")
            true
        } catch (e: Throwable) {
            Log.d(TAG, "구독 실패 $label: ${e.cause?.message ?: e.message}")
            false
        }
    }

    fun stop() {
        regs.forEach { r -> runCatching { r.unregister?.invoke(r.domain, *r.args) } }
        regs.clear()
        if (initialized) {
            runCatching { vehicle.release() }
            initialized = false
        }
    }

    companion object {
        const val TAG = "SignalLens"
    }
}
