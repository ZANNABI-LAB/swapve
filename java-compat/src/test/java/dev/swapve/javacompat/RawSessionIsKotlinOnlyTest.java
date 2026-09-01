package dev.swapve.javacompat;

import dev.swapve.ocpp.rpc.OcppFrameCodec;
import dev.swapve.ocpp.schema.OcppPayloadValidator;
import dev.swapve.ocpp.session.OcppSession;
import dev.swapve.ocpp.session.OcppSessions;
import kotlin.jvm.internal.DefaultConstructorMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 진입점을 거치지 않은 세션은 <b>여전히 Kotlin 전용이다</b> — 짐작이 아니라 측정이다
 * (docs/LAYERS.md §4). Java 소비자의 길은
 * {@link dev.swapve.ocpp.session.OcppSessionsAsync} 이고,
 * {@link SessionFromJavaTest} 가 그 길을 리플렉션 없이 밟는다.
 *
 * <h2>벽이 무엇인지 한 번 틀리게 적었다</h2>
 *
 * <p>처음에는 <i>"Kotlin 의 기본 인자 때문에 컴파일러 내부용 생성자만 노출된다"</i> 고
 * 적었다. <b>그 진단은 틀렸다.</b> 기본 인자뿐이었다면 전체-인자 생성자가
 * {@code public} 으로 남아 Java 가 그대로 불렀을 것이다.
 *
 * <p>실제 원인은 {@code callTimeout} 의 타입인 {@code kotlin.time.Duration} 이
 * <b>value class</b> 라는 것이다. value class 를 받는 함수는 이름을 맹글링해 시그니처
 * 충돌을 피하는데 <b>생성자는 이름이 {@code <init>} 이라 맹글링할 수가 없다.</b> 그래서
 * Kotlin 은 전체-인자 생성자를 {@code private} 으로 내리고
 * {@code DefaultConstructorMarker} 가 붙은 것만 노출한다. 아래 두 시험이 그 두 사실을
 * 각각 고정한다.
 *
 * <p><b>{@code suspend} 는 벽이 아니었다.</b> 생성자만 우회하면 {@code open} ·
 * {@code call} · {@code receive} 는 Java 가 그대로 부를 수 있다. 그래서 진입점이 감춰야
 * 했던 것은 {@code suspend} 자체가 아니라 호출 쪽 비용이었다 — {@code Continuation}
 * 구현, {@code COROUTINE_SUSPENDED} 판별, {@code kotlin.Result.Failure} 해제.
 */
class RawSessionIsKotlinOnlyTest {

    @Test
    @DisplayName("공개 생성자는 전부 DefaultConstructorMarker 를 요구한다")
    void noPublicConstructorIsJavaCallable() {
        for (Class<?> type : new Class<?>[] { OcppSession.class, OcppSessions.class }) {
            Constructor<?>[] constructors = type.getConstructors();

            assertTrue(constructors.length > 0, "생성자 자체는 있어야 한다: " + type);
            for (Constructor<?> constructor : constructors) {
                assertTrue(
                        Arrays.asList(constructor.getParameterTypes()).contains(DefaultConstructorMarker.class),
                        "Java 가 부를 수 있는 생성자가 생겼다면 docs/LAYERS.md §4 를 갱신하라: " + constructor);
            }
        }
    }

    @Test
    @DisplayName("원인은 value class 다 — 전체-인자 생성자가 private 으로 내려가 있고 Duration 자리가 long 이다")
    void theCauseIsTheValueClassParameter() throws Exception {
        Constructor<?> full = OcppSessions.class.getDeclaredConstructor(
                Clock.class,
                dev.swapve.ocpp.session.OcppEventSink.class,
                long.class,
                dev.swapve.ocpp.session.InboundCallLedger.class,
                dev.swapve.ocpp.session.StationSerializer.class,
                OcppFrameCodec.class,
                OcppPayloadValidator.class);

        assertTrue(Modifier.isPrivate(full.getModifiers()),
                "전체-인자 생성자가 public 이 되었다면 벽이 사라진 것이다: " + full);

        // kotlin.time.Duration 이 평탄화된 자리. 여기가 long 이 아니게 되면 진단을 다시 써야 한다.
        assertEquals(long.class, full.getParameterTypes()[2]);
    }

    @Test
    @DisplayName("DEFAULT_CALL_TIMEOUT 은 이름이 맹글링돼 Java 문법으로는 부를 수 없다")
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
