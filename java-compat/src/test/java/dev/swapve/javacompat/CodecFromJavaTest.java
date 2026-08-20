package dev.swapve.javacompat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.swapve.ocpp.rpc.DecodeOutcome;
import dev.swapve.ocpp.rpc.OcppFrame;
import dev.swapve.ocpp.rpc.OcppFrameCodec;
import dev.swapve.ocpp.rpc.RpcErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 프레이밍 층을 <b>Java 에서만</b> 호출한다 (docs/LAYERS.md §4).
 *
 * <p>이 시험이 확인하는 것은 "값이 맞는가"가 아니다 — 그건 {@code ocpp-core} 의 Kotlin
 * 시험이 이미 한다. 여기서 확인하는 것은 <b>Java 소비자가 이 API 를 부를 수 있는가</b> 다.
 * 그러므로 이 파일이 <b>컴파일된다는 사실 자체가 판정의 절반</b>이다.
 */
class CodecFromJavaTest {

    private static final String BOOT_PAYLOAD =
            "{\"reason\":\"PowerUp\",\"chargingStation\":"
                    + "{\"model\":\"SingleSocketCharger\",\"vendorName\":\"VendorX\"}}";

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode payload(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }

    @Test
    @DisplayName("Java 에서 코덱을 만들고 CALL 을 인코딩한다")
    void encodesCallFromJava() throws Exception {
        // 마찰 1 — Kotlin 의 기본 인자는 Java 에 보이지 않는다.
        // Kotlin: OcppFrameCodec()   Java: ObjectMapper 를 직접 넘겨야 한다.
        OcppFrameCodec codec = new OcppFrameCodec(mapper);

        String line = codec.encode(new OcppFrame.Call("19223201", "BootNotification", payload(BOOT_PAYLOAD)));

        assertTrue(line.startsWith("[2,\"19223201\",\"BootNotification\","), line);
    }

    @Test
    @DisplayName("Java 에서 디코딩하고 sealed 계층을 instanceof 로 가른다")
    void decodesCallFromJava() {
        OcppFrameCodec codec = new OcppFrameCodec(mapper);

        DecodeOutcome outcome = codec.decode(
                "[2,\"19223201\",\"BootNotification\"," + BOOT_PAYLOAD + "]");

        // 마찰 없음 — sealed interface 는 Java 에서 그냥 인터페이스이고,
        // data class 는 그냥 클래스다. JDK 17 의 instanceof 패턴이 그대로 듣는다.
        DecodeOutcome.Decoded decoded = assertInstanceOf(DecodeOutcome.Decoded.class, outcome);
        OcppFrame.Call call = assertInstanceOf(OcppFrame.Call.class, decoded.getFrame());

        assertEquals("19223201", call.getMessageId());
        assertEquals("BootNotification", call.getAction());
    }

    @Test
    @DisplayName("깨진 프레임이 예외가 아니라 값으로 온다 — Java 에서도 try/catch 가 필요 없다")
    void malformedIsAValueNotAnException() {
        OcppFrameCodec codec = new OcppFrameCodec(mapper);

        DecodeOutcome outcome = codec.decode("this is not JSON");

        // 마찰 2 — Kotlin enum 상수는 이름 그대로 온다. UPPER_SNAKE 로 바뀌지 않는다.
        DecodeOutcome.Malformed malformed = assertInstanceOf(DecodeOutcome.Malformed.class, outcome);
        assertEquals(RpcErrorCode.RpcFrameworkError, malformed.getErrorCode());
        assertEquals(OcppFrameCodec.UNREADABLE_MESSAGE_ID, malformed.getMessageId());
    }

    @Test
    @DisplayName("모르는 메시지 타입은 Ignored 로 온다 (errata 2026-06 §4.1)")
    void unknownMessageTypeIsIgnored() {
        OcppFrameCodec codec = new OcppFrameCodec(mapper);

        DecodeOutcome outcome = codec.decode("[9,\"19223201\",\"Whatever\",{}]");

        assertInstanceOf(DecodeOutcome.Ignored.class, outcome);
    }

    @Test
    @DisplayName("왕복 — Java 에서 인코딩한 것을 Java 에서 다시 읽는다")
    void roundTripFromJava() throws Exception {
        OcppFrameCodec codec = new OcppFrameCodec(mapper);
        OcppFrame.CallResult original = new OcppFrame.CallResult("19223201", payload("{\"status\":\"Accepted\"}"));

        DecodeOutcome outcome = codec.decode(codec.encode(original));

        DecodeOutcome.Decoded decoded = assertInstanceOf(DecodeOutcome.Decoded.class, outcome);
        assertEquals(original, decoded.getFrame());
    }
}
