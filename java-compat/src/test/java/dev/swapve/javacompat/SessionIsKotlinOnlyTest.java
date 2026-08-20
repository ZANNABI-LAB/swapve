package dev.swapve.javacompat;

import dev.swapve.ocpp.rpc.OcppFrameCodec;
import dev.swapve.ocpp.schema.OcppPayloadValidator;
import dev.swapve.ocpp.session.OcppSession;
import kotlin.jvm.internal.DefaultConstructorMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 세션 층은 <b>Kotlin 전용이다</b> — 짐작이 아니라 측정이다 (docs/LAYERS.md §4).
 *
 * <p>처음에는 Java 로 세션을 세워 CALL 을 주고받는 시험을 쓰려 했다. <b>컴파일되지 않았다.</b>
 * 이유는 코루틴이 아니라 그 앞에 있었다 —
 *
 * <ul>
 *   <li>{@code OcppSession} 의 <b>모든 생성자가 {@code DefaultConstructorMarker} 를 요구한다.</b>
 *       Kotlin 의 기본 인자 때문에 생기는 컴파일러 내부용 생성자만 노출되고,
 *       Java 가 부를 수 있는 공개 생성자는 하나도 없다.</li>
 *   <li>{@code DEFAULT_CALL_TIMEOUT} 의 접근자 이름이 {@code getDEFAULT_CALL_TIMEOUT-UwyO8pc}
 *       로 <b>맹글링</b>돼 있다. {@code kotlin.time.Duration} 이 value class 라서다.
 *       하이픈은 Java 식별자에 쓸 수 없으므로 <b>부를 방법이 없다.</b></li>
 * </ul>
 *
 * <p>그래서 이 시험은 "Java 에서 된다"가 아니라 <b>"Java 에서 안 된다"를 고정</b>한다.
 * 나중에 {@code @JvmOverloads} 나 {@code java.time.Duration} 오버로드를 넣어 이 벽이
 * 사라지면 이 시험이 빨개진다. 그때 고칠 것은 코드가 아니라
 * <b>{@code docs/LAYERS.md} §4 의 판정</b>이다.
 */
class SessionIsKotlinOnlyTest {

    @Test
    @DisplayName("OcppSession 은 Java 에서 생성할 수 없다 — 공개 생성자가 없다")
    void sessionHasNoJavaCallableConstructor() {
        Constructor<?>[] constructors = OcppSession.class.getConstructors();

        assertTrue(constructors.length > 0, "생성자 자체는 있어야 한다");
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameters = constructor.getParameterTypes();
            assertTrue(
                    Arrays.asList(parameters).contains(DefaultConstructorMarker.class),
                    "Java 에서 부를 수 있는 생성자가 생겼다면 docs/LAYERS.md §4 를 갱신하라: "
                            + constructor);
        }
    }

    @Test
    @DisplayName("DEFAULT_CALL_TIMEOUT 은 이름이 맹글링돼 Java 에서 부를 수 없다")
    void defaultTimeoutAccessorIsMangled() {
        boolean mangled = Arrays.stream(OcppSession.Companion.getClass().getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.startsWith("getDEFAULT_CALL_TIMEOUT") && name.contains("-"));

        assertTrue(mangled, "맹글링이 사라졌다면 docs/LAYERS.md §4 를 갱신하라");
    }

    @Test
    @DisplayName("반면 L1·L2 는 Java 가 부를 수 있는 생성자를 가진다")
    void codecAndValidatorAreJavaCallable() {
        assertTrue(hasJavaCallableConstructor(OcppFrameCodec.class), "OcppFrameCodec");
        assertTrue(hasJavaCallableConstructor(OcppPayloadValidator.class), "OcppPayloadValidator");
    }

    private static boolean hasJavaCallableConstructor(Class<?> type) {
        return Arrays.stream(type.getConstructors())
                .anyMatch(c -> !Arrays.asList(c.getParameterTypes()).contains(DefaultConstructorMarker.class));
    }
}
