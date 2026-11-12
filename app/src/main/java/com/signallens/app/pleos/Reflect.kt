package com.signallens.app.pleos

import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pleos SDK(TextToSpeech · SpeechToText · NaviHelper)를 리플렉션으로 부르는 도우미.
 *
 * 왜 리플렉션인가: 이 SDK들은 Pleos 전용 저장소에만 있고, 리스너 인터페이스의 함수 구성이 버전마다
 * 조금씩 다르다. 이름으로 연결하면 함수가 하나 늘거나 빠져도 빌드가 깨지지 않고,
 * SDK가 없는 기기에서도 앱이 죽지 않는다 (Vehicle SDK를 붙인 방식과 같다).
 */
internal object Reflect {

    fun cls(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    /** enum 상수 (예: Mode.ON_DEVICE) */
    fun enumOf(className: String, constant: String): Any? =
        cls(className)?.enumConstants?.firstOrNull { (it as Enum<*>).name == constant }

    /** 인자 개수와 타입이 맞는 생성자로 객체 생성 */
    fun create(className: String, vararg args: Any?): Any? {
        val c = cls(className) ?: return null
        val ctor = c.constructors.firstOrNull { k ->
            k.parameterTypes.size == args.size && k.parameterTypes.zip(args).all { (t, a) -> fits(t, a) }
        } ?: return null
        return ctor.newInstance(*args)
    }

    /** 이름과 인자가 맞는 함수 찾기 */
    fun method(target: Any, name: String, vararg args: Any?): Method? =
        target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                m.parameterTypes.zip(args).all { (t, a) -> fits(t, a) }
        }

    /** 함수 호출. 없으면 예외 대신 false. */
    fun call(target: Any, name: String, vararg args: Any?): Boolean {
        val m = method(target, name, *args) ?: return false
        m.invoke(target, *args)
        return true
    }

    /** 이름이 [name]이고 인자가 1개이며 그 타입이 인터페이스인 함수 (리스너 등록용) */
    fun listenerMethod(target: Any, name: String): Method? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface }

    /** 인터페이스 구현체를 만든다. 어떤 함수가 불리든 [handler](함수 이름, 인자들)로 들어온다. */
    fun proxy(iface: Class<*>, label: String, handler: (String, Array<Any?>) -> Unit): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, m, a ->
            when (m.name) {
                "equals" -> p === a?.firstOrNull()
                "hashCode" -> System.identityHashCode(p)
                "toString" -> label
                else -> {
                    runCatching { handler(m.name, a ?: emptyArray()) }
                    defaultReturn(m.returnType)
                }
            }
        }

    /** 객체의 필드/게터 값 읽기 (예: DestinationInfo.remainDistance) */
    fun read(obj: Any?, name: String): Any? {
        if (obj == null) return null
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        obj.javaClass.methods.firstOrNull { (it.name == getter || it.name == name) && it.parameterTypes.isEmpty() }
            ?.let { return runCatching { it.invoke(obj) }.getOrNull() }
        return runCatching { obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj) }.getOrNull()
    }

    private fun fits(type: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !type.isPrimitive
        val boxed = when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            else -> type
        }
        return boxed.isInstance(arg)
    }

    private fun defaultReturn(t: Class<*>): Any? = when (t) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        else -> null
    }
}
