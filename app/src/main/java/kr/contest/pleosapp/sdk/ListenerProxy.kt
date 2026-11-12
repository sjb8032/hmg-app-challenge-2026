package kr.contest.pleosapp.sdk

import java.lang.reflect.Proxy

/**
 * SDK 리스너 인터페이스를 동적 프록시로 만든다.
 *
 * Pleos SDK 리스너는 매개변수의 null 허용 여부가 공개 문서에 없어, Kotlin에서 직접 override하면
 * 버전에 따라 컴파일이 깨질 수 있다. 공식 Vehicle 샘플 앱(VehicleApiExplorer)도 같은 방식을 쓴다.
 *
 * @param onEvent 호출된 메서드 이름과 인자를 받는다. 반환값은 쓰지 않는다 (리스너 메서드는 모두 void).
 */
inline fun <reified T : Any> listenerProxy(crossinline onEvent: (method: String, args: Array<out Any?>) -> Unit): T {
    val type = T::class.java
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RestMateListener(${type.simpleName})"
            else -> {
                onEvent(method.name, args ?: emptyArray())
                null
            }
        }
    } as T
}
