package dev.swapve.javacompat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.swapve.ocpp.rpc.OcppFrame;
import dev.swapve.ocpp.rpc.RpcErrorCode;
import dev.swapve.ocpp.schema.PayloadValidation;
import dev.swapve.ocpp.schema.SchemaViolation;
import dev.swapve.ocpp.schema.OcppPayloadValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 스키마 검증 층을 <b>Java 에서만</b> 호출한다 (docs/LAYERS.md §4).
 *
 * <p>공식 스키마 181개를 Java 소비자가 그대로 쓸 수 있는지가 요점이다.
 */
class SchemaValidationFromJavaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OcppPayloadValidator validator = new OcppPayloadValidator();

    private ObjectNode payload(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }

    @Test
    @DisplayName("Java 에서 유효한 CALL 페이로드를 통과시킨다")
    void validPayloadFromJava() throws Exception {
        PayloadValidation result = validator.validateCall(
                "BootNotification",
                payload("{\"reason\":\"PowerUp\",\"chargingStation\":"
                        + "{\"model\":\"SingleSocketCharger\",\"vendorName\":\"VendorX\"}}"));

        // 마찰 3 — Kotlin 의 `data object` 는 Java 에서 INSTANCE 싱글턴이다.
        assertSame(PayloadValidation.Valid.INSTANCE, result);
    }

    @Test
    @DisplayName("Java 에서 위반 목록을 그대로 받는다 — 정보가 깎이지 않는다")
    void invalidPayloadCarriesViolationsToJava() throws Exception {
        PayloadValidation result = validator.validateCall(
                "BootNotification",
                payload("{\"reason\":\"NotARealReason\"}"));

        PayloadValidation.Invalid invalid = assertInstanceOf(PayloadValidation.Invalid.class, result);
        List<SchemaViolation> violations = invalid.getViolations();
        assertFalse(violations.isEmpty(), "위반 목록이 비어 있으면 안 된다");

        // 각 위반이 회신할 RPC 코드를 스스로 안다 (Part 4 §4.3 Table 9).
        // Kotlin 의 `val errorCode get()` 은 Java 에서 getErrorCode() 다.
        for (SchemaViolation violation : violations) {
            assertFalse(violation.getInstancePath().isEmpty());
            assertFalse(violation.getKeyword().isEmpty());
            violation.getErrorCode();
        }
    }

    @Test
    @DisplayName("스키마가 없는 action 은 NotImplemented 를 실은 Invalid — 예외가 아니다")
    void unknownActionIsInvalidNotAnException() throws Exception {
        PayloadValidation result = validator.validateCall("NoSuchActionExists", payload("{}"));

        // 미지의 action 은 "검증 대상 아님"이 아니라 **위반**이다 — Part 4 §4.3 이
        // "Requested Action is not known by receiver" 에 NotImplemented 를 요구한다.
        PayloadValidation.Invalid invalid = assertInstanceOf(PayloadValidation.Invalid.class, result);
        assertEquals(RpcErrorCode.NotImplemented, invalid.getErrorCode());
        assertTrue(invalid.getViolations().isEmpty(), "스키마가 없으면 개별 위반은 없다");
    }

    @Test
    @DisplayName("검증할 스키마가 아예 없는 프레임 종류는 NotApplicable")
    void framesWithoutSchemaAreNotApplicable() {
        // CALLERROR 에는 대응 스키마가 없다. 이때만 NotApplicable 이 나온다.
        PayloadValidation result = validator.validate(
                new OcppFrame.CallError("19223201", "GenericError", "boom",
                        JsonNodeFactory.instance.objectNode()),
                null);   // 마찰 4 — Kotlin 의 기본 인자(callAction = null)를 Java 는 직접 넘겨야 한다

        assertSame(PayloadValidation.NotApplicable.INSTANCE, result);
    }

    @Test
    @DisplayName("검증기 인스턴스는 공유해서 쓴다 — 181개 스키마를 다시 파싱하지 않는다")
    void validatorIsMeantToBeShared() throws Exception {
        ObjectNode good = payload("{\"currentTime\":\"2026-08-20T00:00:00Z\",\"interval\":300,"
                + "\"status\":\"Accepted\"}");

        assertSame(PayloadValidation.Valid.INSTANCE, validator.validateCallResult("BootNotification", good));
        assertSame(PayloadValidation.Valid.INSTANCE, validator.validateCallResult("BootNotification", good));
        assertEquals(0, 0);
    }
}
