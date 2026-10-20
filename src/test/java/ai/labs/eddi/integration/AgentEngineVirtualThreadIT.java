/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.internal.ConversationService;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turns ({@code say}, {@code rerun}) are dispatched onto virtual threads and
 * conversation starts deliberately stay on the worker pool; a turn arriving
 * over HTTP carries its arrival time and the moment its caller gives up.
 * <p>
 * In a burst, starts held all ~200 worker threads in database waits, and the
 * turns queued behind them twice: before their 60 s timeout was armed, and
 * again to have the finished answer written. Turns now leave that pool. Starts
 * do not: moved to virtual threads in the same burst, unbounded CPU-heavy
 * starts starved the event loops until no connection was accepted at all, so
 * the worker pool remains their concurrency limit. Only a booted HTTP layer
 * shows which thread Quarkus dispatches to.
 * <p>
 * The conversation service is a Mockito stand-in that records
 * {@link Thread#isVirtual()} per call and answers at once, so no agent or LLM
 * is involved.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class AgentEngineVirtualThreadIT {

    private static final String AGENT_ID = "6a0f5d783ab1ce8997018cb1";
    private static final String CONVERSATION_ID = "6a0f5d783ab1ce8997018cb2";

    /** Service method name → whether the call ran on a virtual thread. */
    private final Map<String, Boolean> threadOfCall = new ConcurrentHashMap<>();
    /** The input of the last turn the service received. */
    private final Map<String, InputData> inputOfCall = new ConcurrentHashMap<>();

    @BeforeEach
    void installRecordingService() {
        ConversationService recording = Mockito.mock(ConversationService.class, invocation -> {
            threadOfCall.put(invocation.getMethod().getName(), Thread.currentThread().isVirtual());
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof InputData inputData) {
                    inputOfCall.put(invocation.getMethod().getName(), inputData);
                }
                if (argument instanceof ConversationResponseHandler handler) {
                    handler.onComplete(new SimpleConversationMemorySnapshot());
                }
            }
            if (invocation.getMethod().getReturnType() == ConversationResult.class) {
                return new ConversationResult(CONVERSATION_ID, URI.create("/agents/" + CONVERSATION_ID));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        QuarkusMock.installMockForType(recording, IConversationService.class);
    }

    private void assertRanOnWorkerThread(String serviceMethod) {
        assertEquals(Boolean.FALSE, threadOfCall.get(serviceMethod),
                serviceMethod + " must stay on the worker pool (null = never called, true = a virtual thread)");
    }

    private void assertRanOnVirtualThread(String serviceMethod) {
        assertEquals(Boolean.TRUE, threadOfCall.get(serviceMethod),
                serviceMethod + " must be reached on a virtual thread (null = never called, false = a worker thread)");
    }

    @Test
    @DisplayName("POST /agents/{agentId}/start stays on the worker pool, its concurrency limit")
    void start() {
        given().post("/agents/" + AGENT_ID + "/start").then().statusCode(lessThan(300));
        assertRanOnWorkerThread("startConversation");
    }

    @Test
    @DisplayName("POST /agents/{agentId}/start with a context body stays on the worker pool")
    void startWithContext() {
        given().contentType(ContentType.JSON).body(Map.of()).post("/agents/" + AGENT_ID + "/start").then().statusCode(lessThan(300));
        assertRanOnWorkerThread("startConversation");
    }

    @Test
    @DisplayName("POST /agents/{conversationId} (text) runs on a virtual thread")
    void say() {
        given().contentType(ContentType.TEXT).body("hello").post("/agents/" + CONVERSATION_ID).then().statusCode(lessThan(300));
        assertRanOnVirtualThread("say");
    }

    @Test
    @DisplayName("POST /agents/{conversationId} (JSON) runs on a virtual thread, and the turn knows when its caller gives up")
    void sayWithinContext() {
        long before = System.currentTimeMillis();
        given().contentType(ContentType.JSON).body(Map.of("input", "hello")).post("/agents/" + CONVERSATION_ID).then()
                .statusCode(lessThan(300));
        assertRanOnVirtualThread("say");

        InputData input = inputOfCall.get("say");
        assertNotNull(input.getRequestArrivalEpochMs(), "the router's first route must have stamped the arrival");
        assertTrue(input.getRequestArrivalEpochMs() >= before);
        assertNotNull(input.getCallerGivesUpAtEpochMs());
        assertTrue(input.getCallerGivesUpAtEpochMs() > input.getRequestArrivalEpochMs());
    }

    @Test
    @DisplayName("POST /agents/{conversationId}/rerun runs on a virtual thread")
    void rerun() {
        given().post("/agents/" + CONVERSATION_ID + "/rerun").then().statusCode(lessThan(300));
        assertRanOnVirtualThread("say");
    }

    @Test
    @DisplayName("GET /q/metrics still answers in both formats with the dedicated scrape route in front of Quarkus' handler")
    void metricsScrape() {
        given().accept("text/plain").get("/q/metrics").then().statusCode(200)
                .header("Content-Type", containsString("text/plain"))
                .body(containsString("jvm_threads_live_threads"));
        given().get("/q/metrics").then().statusCode(200).header("Content-Type", containsString("openmetrics"));
    }
}
