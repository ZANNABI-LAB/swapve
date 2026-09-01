package dev.swapve.javacompat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.swapve.ocpp.session.InboundResponse;
import dev.swapve.ocpp.session.MessageDirection;
import dev.swapve.ocpp.session.OcppCall;
import dev.swapve.ocpp.session.OcppEventRecord;
import dev.swapve.ocpp.session.OcppEventSink;
import dev.swapve.ocpp.session.OcppResult;
import dev.swapve.ocpp.session.OcppSessionAsync;
import dev.swapve.ocpp.session.OcppSessionsAsync;
import dev.swapve.ocpp.session.TransmitOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 세션을 <b>Java 에서만</b> 쓴다 (docs/LAYERS.md §4).
 *
 * <p>이 파일에 리플렉션이 <b>한 줄도 없다</b>는 것이 판정의 절반이다. 나머지 절반은
 * {@code kotlin.*} import 가 하나도 없다는 것이다 — {@code Continuation} 도,
 * {@code Result} 도, {@code Function2} 도 소비자 코드에 나타나지 않는다.
 * 그것이 {@link OcppSessionsAsync} 가 존재하는 이유 전부다.
 *
 * <p>{@link RawSessionIsKotlinOnlyTest} 는 그 옆에서 반대쪽을 고정한다 — 진입점을 거치지
 * 않은 {@code OcppSession} 은 여전히 Kotlin 전용이다.
 */
class SessionFromJavaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final RecordingSink sink = new RecordingSink();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("Java 가 들어온 CALL 을 처리하고 CALLRESULT 를 내보낸다")
    void handlesAnInboundCall() throws Exception {
        BlockingQueue<String> sent = new ArrayBlockingQueue<>(8);

        try (OcppSessionsAsync sessions = OcppSessionsAsync.using(executor, Clock.systemUTC(), sink)) {
            OcppSessionAsync session = sessions.open(
                    "ST-JAVA-01",
                    text -> {
                        sent.add(text);
                        return CompletableFuture.completedFuture(TransmitOutcome.Delivered.INSTANCE);
                    },
                    (stationId, call) ->
                            CompletableFuture.completedFuture(new InboundResponse.Respond(payload())));

            session.receive("[2,\"19223201\",\"Heartbeat\",{}]").get(5, TimeUnit.SECONDS);

            String reply = sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(reply, "CALLRESULT 가 나가지 않았다");
            assertTrue(reply.startsWith("[3,\"19223201\","), reply);
            assertEquals(2, sink.records.size(), "인바운드 · 아웃바운드 두 건이 기록돼야 한다");
        }
    }

    @Test
    @DisplayName("Java 가 CALL 을 보내고 CompletableFuture 로 응답을 받는다")
    void sendsACallAndCompletesWithTheResult() throws Exception {
        BlockingQueue<String> sent = new ArrayBlockingQueue<>(8);

        try (OcppSessionsAsync sessions = OcppSessionsAsync.using(executor, Clock.systemUTC(), sink)) {
            OcppSessionAsync session = sessions.open(
                    "ST-JAVA-01",
                    text -> {
                        sent.add(text);
                        return CompletableFuture.completedFuture(TransmitOutcome.Delivered.INSTANCE);
                    },
                    (stationId, call) ->
                            CompletableFuture.completedFuture(new InboundResponse.Respond(payload())));

            CompletableFuture<OcppResult> pending = session.call(new OcppCall("Heartbeat", payload()));

            String outbound = sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(outbound, "CALL 이 나가지 않았다");
            String messageId = mapper.readTree(outbound).get(1).asText();

            session.receive("[3,\"" + messageId + "\",{\"currentTime\":\"2026-09-01T00:00:00Z\"}]")
                    .get(5, TimeUnit.SECONDS);

            OcppResult.Accepted accepted =
                    assertInstanceOf(OcppResult.Accepted.class, pending.get(5, TimeUnit.SECONDS));
            assertEquals(messageId, accepted.getMessageId());
        }
    }

    @Test
    @DisplayName("프로토콜 결과는 예외가 아니라 값으로 온다 — 응답이 없으면 TimedOut")
    void aTimeoutIsAValueNotAnException() throws Exception {
        try (OcppSessionsAsync sessions = OcppSessionsAsync.using(
                executor, Clock.systemUTC(), sink, Duration.ofMillis(200))) {

            OcppSessionAsync session = sessions.open(
                    "ST-JAVA-01",
                    text -> CompletableFuture.completedFuture(TransmitOutcome.Delivered.INSTANCE),
                    (stationId, call) ->
                            CompletableFuture.completedFuture(new InboundResponse.Respond(payload())));

            OcppResult result = session.call(new OcppCall("Heartbeat", payload())).get(5, TimeUnit.SECONDS);

            assertInstanceOf(OcppResult.TimedOut.class, result);
        }
    }

    @Test
    @DisplayName("receive 는 넘긴 순서대로 처리된다 — 순서를 소비자가 지키지 않아도 된다")
    void receiveKeepsArrivalOrder() throws Exception {
        List<String> handled = new CopyOnWriteArrayList<>();

        try (OcppSessionsAsync sessions = OcppSessionsAsync.using(executor, Clock.systemUTC(), sink)) {
            OcppSessionAsync session = sessions.open(
                    "ST-JAVA-01",
                    text -> CompletableFuture.completedFuture(TransmitOutcome.Delivered.INSTANCE),
                    (stationId, call) -> {
                        handled.add(call.getPayload().get("data").asText());
                        return CompletableFuture.completedFuture(
                                new InboundResponse.Respond(accepted()));
                    });

            CompletableFuture<?> last = null;
            for (int n = 0; n < 25; n++) {
                // 순서를 실어 보낼 자리가 필요하다. DataTransfer 의 data 는 스펙이 형식을
                // 정하지 않은 유일한 필드다 — Heartbeat 는 빈 객체만 받는다.
                last = session.receive(
                        "[2,\"m" + n + "\",\"DataTransfer\","
                                + "{\"vendorId\":\"swapve\",\"data\":\"" + n + "\"}]");
            }
            assertNotNull(last, "receive 가 한 번도 불리지 않았다");
            last.get(5, TimeUnit.SECONDS);

            assertEquals(25, handled.size());
            for (int n = 0; n < 25; n++) {
                assertEquals(String.valueOf(n), handled.get(n), "순서가 어긋났다");
            }
        }
    }

    /**
     * ★ 회귀 시험. 처음 쓸 때 [receive] 가 <b>모든</b> 프레임을 하나의 체인에 묶었다.
     * 그러면 응답(CALLRESULT)이 느린 요청 핸들러 뒤에 줄을 서고, 상대가 100ms 만에 답했는데도
     * 우리 쪽 {@code call} 이 타임아웃으로 끝난다 — 와이어에서 일어나지 않은 타임아웃이다.
     *
     * <p>{@code OcppSession.handleCallResult} 의 KDoc 이 바로 그것을 피하려고
     * <i>"스테이션 직렬화를 거치지 않는다"</i> 고 적어 두었는데, 진입점이 그 회피를 도로
     * 무효로 만들고 있었다. 이제 응답은 체인을 건너뛴다.
     */
    @Test
    @DisplayName("느린 요청 핸들러가 응답을 붙잡지 않는다")
    void aSlowHandlerDoesNotHoldUpAResponse() throws Exception {
        BlockingQueue<String> sent = new ArrayBlockingQueue<>(8);
        CountDownLatch handlerEntered = new CountDownLatch(1);

        try (OcppSessionsAsync sessions = OcppSessionsAsync.using(
                executor, Clock.systemUTC(), sink, Duration.ofMillis(700))) {

            OcppSessionAsync session = sessions.open(
                    "ST-JAVA-01",
                    text -> {
                        sent.add(text);
                        return CompletableFuture.completedFuture(TransmitOutcome.Delivered.INSTANCE);
                    },
                    (stationId, call) -> {
                        handlerEntered.countDown();
                        // 요청 핸들러가 call 의 타임아웃보다 오래 걸린다. 응답이 이 뒤에 줄을
                        // 서면 call 은 상대가 제때 답했는데도 TimedOut 으로 끝난다.
                        return CompletableFuture.supplyAsync(
                                () -> (InboundResponse) new InboundResponse.Respond(accepted()),
                                CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS, executor));
                    });

            CompletableFuture<OcppResult> pending = session.call(new OcppCall("Heartbeat", payload()));
            String outbound = sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(outbound, "CALL 이 나가지 않았다");
            String messageId = mapper.readTree(outbound).get(1).asText();

            // 느린 요청을 하나 태우고, 그 뒤에 응답을 넣는다.
            session.receive("[2,\"inbound-1\",\"DataTransfer\",{\"vendorId\":\"swapve\"}]");
            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "핸들러가 시작되지 않았다");
            session.receive("[3,\"" + messageId + "\",{\"currentTime\":\"2026-09-01T00:00:00Z\"}]");

            // 체인에 묶여 있었다면 여기서 TimedOut 이 온다.
            assertInstanceOf(OcppResult.Accepted.class, pending.get(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 핸들러 future 가 끝내 완료되지 않으면 그 뒤의 요청이 영원히 줄을 선다. 세션을 닫는 것이
     * 그 줄을 푸는 유일한 손잡이다 — Kotlin 호출자라면 부모 job 이 죽으면서 풀리는 자리다.
     */
    @Test
    @DisplayName("close 는 끝나지 않는 핸들러에 묶인 요청 줄을 푼다")
    void closeReleasesAStuckRequestChain() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);

        try (OcppSessionsAsync sessions = OcppSessionsAsync.using(executor, Clock.systemUTC(), sink)) {
            OcppSessionAsync session = sessions.open(
                    "ST-JAVA-01",
                    text -> CompletableFuture.completedFuture(TransmitOutcome.Delivered.INSTANCE),
                    (stationId, call) -> {
                        handlerEntered.countDown();
                        return new CompletableFuture<>();   // 아무도 완료시키지 않는다
                    });

            CompletableFuture<Void> stuck = session.receive(
                    "[2,\"inbound-1\",\"DataTransfer\",{\"vendorId\":\"swapve\"}]");
            CompletableFuture<Void> behind = session.receive(
                    "[2,\"inbound-2\",\"DataTransfer\",{\"vendorId\":\"swapve\"}]");
            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "핸들러가 시작되지 않았다");
            assertFalse(behind.isDone(), "앞이 막혔는데 뒤가 끝났다");

            session.close();

            // 완료되기만 하면 된다 — 취소든 예외든, 영원히 매달려 있지 않다는 것이 요점이다.
            assertThrows(ExecutionException.class, () -> stuck.get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> behind.get(5, TimeUnit.SECONDS));
        }
    }

    private ObjectNode payload() {
        return mapper.createObjectNode();
    }

    private ObjectNode accepted() {
        return mapper.createObjectNode().put("status", "Accepted");
    }

    private static final class RecordingSink implements OcppEventSink {
        private final List<OcppEventRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public OcppEventRecord append(String stationId, MessageDirection direction,
                                      String action, String messageId, String payload, Instant at) {
            OcppEventRecord record =
                    new OcppEventRecord(records.size() + 1L, stationId, direction, action, messageId, payload, at);
            records.add(record);
            return record;
        }
    }
}
