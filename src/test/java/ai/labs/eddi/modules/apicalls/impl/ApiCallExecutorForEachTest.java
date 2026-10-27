/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.BatchRequestBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.ForEachInstruction;
import ai.labs.eddi.configs.apicalls.model.HttpPostResponse;
import ai.labs.eddi.configs.apicalls.model.HttpPreRequest;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.apicalls.model.RetryApiCallInstruction;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code forEach} on an httpcall: one request per element, every element's
 * result collected in order, bounded in items, concurrency, time and stored
 * size, and a failing item never taking its siblings with it.
 * <p>
 * Runs against a hand-written {@link IRequest} double — no network — and the
 * real templating engine, so what an element does to a request is what the
 * engine does.
 */
@DisplayName("ApiCallExecutor — forEach")
class ApiCallExecutorForEachTest {

    private static final String SERVER = "http://api.example.com";

    private IHttpClient httpClient;
    private ApiCallExecutor executor;
    private IConversationMemory memory;

    /** Every request the executor built, in the order it built them. */
    private final List<FakeRequest> built = new CopyOnWriteArrayList<>();

    /**
     * Decides each request's response; may throw to simulate a transport failure.
     */
    private volatile Responder responder = request -> json(200, "{\"ok\":true}");

    @FunctionalInterface
    interface Responder {
        IResponse respond(FakeRequest request) throws IRequest.HttpRequestException;
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(IHttpClient.class);
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        var jsonSerialization = new JsonSerialization(new ObjectMapper());
        var engine = Engine.builder().addDefaults().strictRendering(false).build();
        var prePostUtils = new PrePostUtils(jsonSerialization, mock(IMemoryItemConverter.class), new TemplatingEngine(engine),
                mock(IDataFactory.class), mock(SecretPropertyVault.class));

        when(httpClient.newRequest(any(URI.class), any())).thenAnswer(inv -> {
            var request = new FakeRequest(inv.getArgument(0));
            built.add(request);
            return request;
        });

        executor = new ApiCallExecutor(httpClient, jsonSerialization, mock(IRuntime.class), prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, new CallerIdentityContext(null, null), new RequestRedactor(callerIdentityResolver),
                mock(ConnectionResolver.class), false, 30_000L, 2_000_000);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
    }

    // ─── Backward compatibility ────────────────────────────────────────────────

    @Nested
    @DisplayName("a call without forEach")
    class WithoutForEach {

        @Test
        @DisplayName("sends exactly one request and stores the response as before")
        void singleCallUnchanged() throws Exception {
            ApiCall call = call("/items", null);

            Map<String, Object> templateData = dataWithItems(List.of("a", "b", "c"));
            Map<String, Object> result = executor.execute(call, memory, templateData, SERVER);

            assertEquals(1, built.size());
            assertEquals(Map.of("ok", true), templateData.get("results"), "the body is stored as the object itself, not wrapped in an array");
            assertEquals(200, result.get("httpCode"));
        }

        @Test
        @DisplayName("batchRequests on a call that waits for its response still sends exactly one request")
        void batchRequestsWithoutFireAndForgetStillOneRequest() throws Exception {
            ApiCall call = call("/items", null);
            var batch = new BatchRequestBuildingInstruction();
            batch.setPathToTargetArray("properties.items");
            batch.setIterationObjectName("item");
            var preRequest = new HttpPreRequest();
            preRequest.setBatchRequests(batch);
            call.setPreRequest(preRequest);

            executor.execute(call, memory, dataWithItems(List.of("a", "b", "c")), SERVER);

            assertEquals(1, built.size());
        }

        @Test
        @DisplayName("a stored config without the key deserializes with forEach unset")
        void oldJsonHasNoForEach() throws Exception {
            ApiCall call = new ObjectMapper().readValue("{\"name\":\"x\",\"saveResponse\":true,\"responseObjectName\":\"r\"}", ApiCall.class);
            assertNull(call.getForEach());
        }
    }

    // ─── Results ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("results")
    class Results {

        @Test
        @DisplayName("one request per element, each element bound as data, results in source order whatever order they finish in")
        void orderedResults() throws Exception {
            // Earlier elements answer later, so completion order is the reverse.
            responder = request -> {
                String id = lastSegment(request.uri);
                sleep(10L * (5 - Integer.parseInt(id)));
                return json(200, "{\"id\":" + id + "}");
            };
            ApiCall call = call("/items/{item.id}", forEach(4));
            List<Object> items = List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3), Map.of("id", 4));

            Map<String, Object> templateData = dataWithItems(items);
            Map<String, Object> result = executor.execute(call, memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals(4, stored.size());
            for (int i = 0; i < 4; i++) {
                assertEquals(i, stored.get(i).get("index"));
                assertEquals(true, stored.get(i).get("ok"));
                assertEquals(200, stored.get(i).get("httpCode"));
                assertEquals(Map.of("id", i + 1), stored.get(i).get("body"));
                assertEquals(1, stored.get(i).get("attempts"));
            }
            assertEquals(200, result.get("httpCode"));
            assertEquals(stored, result.get("body"));
        }

        @Test
        @DisplayName("templateFilterExpression skips elements; index stays the source position")
        void filterKeepsSourceIndex() throws Exception {
            ApiCall call = call("/items/{item.id}", forEach(1));
            call.getForEach().setTemplateFilterExpression("item.active");
            List<Object> items = List.of(Map.of("id", 1, "active", false), Map.of("id", 2, "active", true), Map.of("id", 3, "active", true));

            Map<String, Object> templateData = dataWithItems(items);
            executor.execute(call, memory, templateData, SERVER);

            assertEquals(List.of("2", "3"), built.stream().map(r -> lastSegment(r.uri)).toList());
            assertEquals(List.of(1, 2), stored(templateData).stream().map(m -> m.get("index")).toList());
        }

        @Test
        @DisplayName("a path that resolves to nothing is an empty loop: nothing is sent, an empty array is stored")
        void missingArrayIsEmpty() throws Exception {
            Map<String, Object> templateData = new HashMap<>();
            templateData.put("properties", new HashMap<>());

            Map<String, Object> result = executor.execute(call("/items", forEach(1)), memory, templateData, SERVER);

            assertTrue(built.isEmpty());
            assertEquals(List.of(), templateData.get("results"));
            assertEquals(200, result.get("httpCode"));
        }

        @Test
        @DisplayName("a path that resolves to something other than an array fails the call and names the path")
        void nonArrayIsRefused() {
            Map<String, Object> templateData = new HashMap<>();
            templateData.put("properties", Map.of("items", "not-a-list"));

            LifecycleException e = assertThrows(LifecycleException.class,
                    () -> executor.execute(call("/items", forEach(1)), memory, templateData, SERVER));

            assertTrue(e.getMessage().contains("pathToTargetArray"), e.getMessage());
            assertTrue(built.isEmpty());
        }

        @Test
        @DisplayName("saveResponse=false stores nothing but still reports the step's status")
        void noSaveResponse() throws Exception {
            ApiCall call = call("/items/{item}", forEach(1));
            call.setSaveResponse(false);
            Map<String, Object> templateData = dataWithItems(List.of("a", "b"));

            Map<String, Object> result = executor.execute(call, memory, templateData, SERVER);

            assertFalse(templateData.containsKey("results"));
            assertFalse(result.containsKey("body"));
            assertEquals(200, result.get("httpCode"));
            assertEquals(2, built.size());
        }
    }

    // ─── Concurrency ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        @BeforeEach
        void trackInFlight() {
            responder = request -> {
                int now = inFlight.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                sleep(30);
                inFlight.decrementAndGet();
                return json(200, "{}");
            };
        }

        @Test
        @DisplayName("unset maxConcurrency runs one item at a time")
        void defaultIsSequential() throws Exception {
            executor.execute(call("/items/{item}", forEach(null)), memory, dataWithItems(numbers(6)), SERVER);

            assertEquals(1, peak.get());
            assertEquals(6, built.size());
        }

        @Test
        @DisplayName("maxConcurrency is honoured exactly: never more in flight, and the cap is reached")
        void capHonoured() throws Exception {
            executor.execute(call("/items/{item}", forEach(3)), memory, dataWithItems(numbers(12)), SERVER);

            assertEquals(3, peak.get());
        }

        @Test
        @DisplayName("a stored value above the ceiling runs at the ceiling")
        void storedValueAboveCeilingIsCapped() throws Exception {
            executor.execute(call("/items/{item}", forEach(500)), memory, dataWithItems(numbers(40)), SERVER);

            assertTrue(peak.get() <= ForEachInstruction.MAX_CONCURRENCY_CEILING, "peak " + peak.get());
        }
    }

    // ─── Failures ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("failures")
    class Failures {

        @BeforeEach
        void mixedOutcomes() {
            responder = request -> switch (lastSegment(request.uri)) {
                case "b" -> json(500, "{\"error\":\"boom\"}");
                case "c" -> throw new IRequest.HttpRequestException("connection reset");
                default -> json(200, "{\"fine\":true}");
            };
        }

        @Test
        @DisplayName("a failing item does not cancel its siblings; each records its own error; the step continues with 207")
        void partialFailureContinues() throws Exception {
            Map<String, Object> templateData = dataWithItems(List.of("a", "b", "c", "d"));

            Map<String, Object> result = executor.execute(call("/items/{item}", forEach(2)), memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals(List.of(true, false, false, true), stored.stream().map(m -> m.get("ok")).toList());
            assertEquals(500, stored.get(1).get("httpCode"));
            assertEquals("the call returned http 500", stored.get(1).get("error"));
            assertTrue(String.valueOf(stored.get(1).get("body")).contains("boom"), "the error body is kept for the author");
            assertNull(stored.get(2).get("httpCode"));
            assertTrue(String.valueOf(stored.get(2).get("error")).contains("connection reset"));
            assertEquals(ApiCallExecutor.FOR_EACH_PARTIAL_HTTP_CODE, result.get("httpCode"));
            assertEquals("2 of 4 forEach items failed", templateData.get("resultsError"));
            assertEquals(4, built.size());
        }

        @Test
        @DisplayName("onItemFailure=fail fails the step — after every item ran and the results were stored")
        void failPolicyThrowsAfterStoring() {
            ApiCall call = call("/items/{item}", forEach(1));
            call.getForEach().setOnItemFailure("fail");
            Map<String, Object> templateData = dataWithItems(List.of("a", "b", "c", "d"));

            LifecycleException e = assertThrows(LifecycleException.class, () -> executor.execute(call, memory, templateData, SERVER));

            assertTrue(e.getMessage().contains("2 of 4"), e.getMessage());
            assertEquals(4, built.size(), "every item was still sent");
            assertEquals(4, stored(templateData).size(), "the results were stored before the step failed");
        }

        @Test
        @DisplayName("an item whose request cannot be built is not sent; the others are")
        void unbuildableItemIsNotSent() throws Exception {
            responder = request -> json(200, "{}");
            ApiCall call = call("/items", forEach(1));
            call.getRequest().setMethod("POST");
            call.getRequest().setBody("v={item}");
            Map<String, Object> templateData = dataWithItems(List.of("a", MemoryKeys.SECRET_CONTEXT_PLACEHOLDER, "c"));

            executor.execute(call, memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals(List.of(true, false, true), stored.stream().map(m -> m.get("ok")).toList());
            assertEquals(false, stored.get(1).get("sent"));
            assertTrue(String.valueOf(stored.get(1).get("error")).startsWith("request not built"), String.valueOf(stored.get(1).get("error")));
            assertEquals(2, built.stream().filter(r -> r.sends.get() > 0).count());
        }
    }

    // ─── Retries ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retries")
    class Retries {

        private final Map<String, AtomicInteger> sendsPerItem = new ConcurrentHashMap<>();

        @Test
        @DisplayName("a timed-out item is never retried, even with a retry policy; it is marked timedOut")
        void timeoutIsNotRetried() throws Exception {
            responder = request -> {
                sendsPerItem.computeIfAbsent(lastSegment(request.uri), k -> new AtomicInteger()).incrementAndGet();
                if ("slow".equals(lastSegment(request.uri))) {
                    throw new IRequest.HttpRequestException("Request timed out while waiting for response", new TimeoutException());
                }
                return json(200, "{}");
            };
            ApiCall call = withRetry(call("/items/{item}", forEach(1)), List.of(502, 503, 504));
            Map<String, Object> templateData = dataWithItems(List.of("fast", "slow"));

            executor.execute(call, memory, templateData, SERVER);

            assertEquals(1, sendsPerItem.get("slow").get(), "a request that may have been processed must not be sent again");
            Map<String, Object> slow = stored(templateData).get(1);
            assertEquals(true, slow.get("timedOut"));
            assertEquals(1, slow.get("attempts"));
        }

        @Test
        @DisplayName("a retryable response code is retried per item, as for a single call")
        void retryableCodeIsRetried() throws Exception {
            responder = request -> {
                int n = sendsPerItem.computeIfAbsent(lastSegment(request.uri), k -> new AtomicInteger()).incrementAndGet();
                return "flaky".equals(lastSegment(request.uri)) && n == 1 ? json(503, "{}") : json(200, "{\"n\":" + n + "}");
            };
            ApiCall call = withRetry(call("/items/{item}", forEach(2)), List.of(503));
            Map<String, Object> templateData = dataWithItems(List.of("steady", "flaky"));

            executor.execute(call, memory, templateData, SERVER);

            Map<String, Object> flaky = stored(templateData).get(1);
            assertEquals(2, flaky.get("attempts"));
            assertEquals(true, flaky.get("ok"));
            assertEquals(1, sendsPerItem.get("steady").get());
        }

        @Test
        @DisplayName("isTimeout recognises a timeout anywhere in the cause chain, and nothing else")
        void timeoutClassification() {
            assertTrue(ForEachItems.isTimeout(new IRequest.HttpRequestException("x", new TimeoutException())));
            assertTrue(ForEachItems.isTimeout(new RuntimeException(new HttpTimeoutException("t"))));
            assertTrue(ForEachItems.isTimeout(new IRequest.HttpRequestException("x", new NoStackTraceTimeoutException())));
            assertFalse(ForEachItems.isTimeout(new IRequest.HttpRequestException("connection reset")));
        }
    }

    /** Stand-in for the transport's own timeout type, recognised by its name. */
    static final class NoStackTraceTimeoutException extends RuntimeException {
    }

    // ─── Bounds ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("more elements than maxItems refuses the call as a whole — nothing is built or sent")
        void overMaxItemsRefused() throws Exception {
            ApiCall call = call("/items/{item}", forEach(1));
            call.getForEach().setMaxItems(3);

            LifecycleException e = assertThrows(LifecycleException.class,
                    () -> executor.execute(call, memory, dataWithItems(numbers(4)), SERVER));

            assertTrue(e.getMessage().contains("forEach.maxItems"), e.getMessage());
            assertTrue(built.isEmpty());
        }

        @Test
        @DisplayName("unset maxItems uses the batch default")
        void defaultMaxItems() {
            assertThrows(LifecycleException.class,
                    () -> executor.execute(call("/items/{item}", forEach(1)), memory,
                            dataWithItems(numbers(ApiCallExecutor.DEFAULT_MAX_BATCH_SIZE + 1)),
                            SERVER));
            assertTrue(built.isEmpty());
        }

        @Test
        @DisplayName("the total size budget keeps bodies in order and drops the rest, keeping their status")
        void totalSizeCap() throws Exception {
            responder = request -> text(200, "x".repeat(40));
            ApiCall call = call("/items/{item}", forEach(1));
            call.getForEach().setMaxTotalResponseSizeInBytes(100);
            Map<String, Object> templateData = dataWithItems(List.of("a", "b", "c"));

            executor.execute(call, memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals("x".repeat(40), stored.get(0).get("body"));
            assertEquals("x".repeat(40), stored.get(1).get("body"));
            assertNull(stored.get(2).get("body"));
            assertEquals(true, stored.get(2).get("bodyDropped"));
            assertEquals(200, stored.get(2).get("httpCode"));
        }

        @Test
        @DisplayName("the total budget is measured in UTF-8 bytes, not characters")
        void totalSizeCapCountsUtf8Bytes() throws Exception {
            // 30 characters, 60 UTF-8 bytes: by characters both bodies (60) fit 100, by
            // bytes (120) only the first does.
            String body = "é".repeat(30);
            responder = request -> text(200, body);
            ApiCall call = call("/items/{item}", forEach(1));
            call.getForEach().setMaxTotalResponseSizeInBytes(100);
            Map<String, Object> templateData = dataWithItems(List.of("a", "b"));

            executor.execute(call, memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals(body, stored.get(0).get("body"));
            assertNull(stored.get(1).get("body"));
            assertEquals(true, stored.get(1).get("bodyDropped"));
        }

        @Test
        @DisplayName("without a total budget, the call's maxResponseSizeInBytes applies to the array as a whole")
        void totalBudgetDefaultsToCallCap() throws Exception {
            responder = request -> text(200, "y".repeat(40));
            ApiCall call = call("/items/{item}", forEach(1));
            call.setMaxResponseSizeInBytes(50);
            Map<String, Object> templateData = dataWithItems(List.of("a", "b"));

            executor.execute(call, memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals("y".repeat(40), stored.get(0).get("body"));
            assertEquals(true, stored.get(1).get("bodyDropped"));
        }
    }

    // ─── Turn deadline ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("turn deadline")
    class Deadline {

        private final MutableClock clock = new MutableClock(1_000_000L);

        @Test
        @DisplayName("an expired deadline fails the call before anything is sent")
        void expiredBeforeStart() {
            when(memory.getTurnDeadline()).thenReturn(TurnDeadline.of(clock, 0L, 10_000L, 0L));

            assertThrows(LifecycleException.class,
                    () -> executor.execute(call("/items/{item}", forEach(1)), memory, dataWithItems(numbers(3)), SERVER));
            assertTrue(built.isEmpty());
        }

        @Test
        @DisplayName("items whose turn comes after the deadline are not sent; the ones already sent are kept")
        void deadlinePassesMidRun() throws Exception {
            when(memory.getTurnDeadline()).thenReturn(TurnDeadline.of(clock, clock.millis(), 20_000L, 0L));
            responder = request -> {
                clock.advance(15_000L);
                return json(200, "{}");
            };
            Map<String, Object> templateData = dataWithItems(numbers(4));

            executor.execute(call("/items/{item}", forEach(1)), memory, templateData, SERVER);

            List<Map<String, Object>> stored = stored(templateData);
            assertEquals(List.of(true, true, false, false), stored.stream().map(m -> m.get("ok")).toList());
            assertEquals(false, stored.get(2).get("sent"));
            assertTrue(String.valueOf(stored.get(2).get("error")).contains("deadline"));
            assertEquals(2, built.stream().filter(r -> r.sends.get() > 0).count());
        }

        @Test
        @DisplayName("each item's timeout is clamped to what is left of the turn")
        void timeoutClamped() throws Exception {
            when(memory.getTurnDeadline()).thenReturn(TurnDeadline.of(clock, clock.millis(), 5_000L, 0L));

            executor.execute(call("/items/{item}", forEach(1)), memory, dataWithItems(numbers(2)), SERVER);

            for (FakeRequest request : built) {
                assertTrue(request.timeoutMs.get() <= 5_000L, "timeout " + request.timeoutMs.get());
            }
        }
    }

    // ─── Template safety ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("template safety")
    class TemplateSafety {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("an element is data: template syntax inside it is sent literally, never rendered")
        void elementIsNeverTemplateSource() throws Exception {
            ApiCall call = call("/items", forEach(1));
            call.getRequest().setMethod("POST");
            call.getRequest().setBody("q={item}");
            Map<String, Object> templateData = dataWithItems(List.of("{properties.secret}"));
            ((Map<String, Object>) templateData.get("properties")).put("secret", "TOP-SECRET-VALUE");

            executor.execute(call, memory, templateData, SERVER);

            assertEquals("q={properties.secret}", built.getFirst().body);
        }

        @Test
        @DisplayName("an element substituted into the path is one encoded segment: it cannot traverse or add a query")
        void elementInPathIsEncoded() throws Exception {
            Map<String, Object> templateData = dataWithItems(List.of(Map.of("id", "../admin?x=1")));

            executor.execute(call("/items/{item.id}", forEach(1)), memory, templateData, SERVER);

            String uri = built.getFirst().uri.toString();
            assertFalse(uri.contains("../"), uri);
            assertFalse(uri.contains("?x=1"), uri);
            assertTrue(uri.endsWith("/items/%2E%2E%2Fadmin%3Fx%3D1"), uri);
        }

        @Test
        @DisplayName("a forEach call cannot be pinned: resolve() returns no fingerprint")
        void resolveIsUnpinned() throws Exception {
            ResolvedRequest resolved = executor.resolve(call("/items/{item}", forEach(1)), memory, dataWithItems(List.of("a")), SERVER);

            assertNull(resolved.fingerprint());
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static ForEachInstruction forEach(Integer maxConcurrency) {
        var forEach = new ForEachInstruction();
        forEach.setPathToTargetArray("properties.items");
        forEach.setIterationObjectName("item");
        forEach.setMaxConcurrency(maxConcurrency);
        return forEach;
    }

    private static ApiCall call(String path, ForEachInstruction forEach) {
        ApiCall call = new ApiCall();
        call.setName("items-call");
        call.setSaveResponse(true);
        call.setResponseObjectName("results");
        Request request = new Request();
        request.setPath(path);
        request.setMethod("GET");
        call.setRequest(request);
        call.setForEach(forEach);
        return call;
    }

    private static ApiCall withRetry(ApiCall call, List<Integer> codes) {
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(3);
        retry.setExponentialBackoffDelayInMillis(1);
        retry.setRetryOnHttpCodes(codes);
        var postResponse = new HttpPostResponse();
        postResponse.setRetryApiCallInstruction(retry);
        call.setPostResponse(postResponse);
        return call;
    }

    private static Map<String, Object> dataWithItems(List<?> items) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("items", new ArrayList<>(items));
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("properties", properties);
        return templateData;
    }

    private static List<Object> numbers(int count) {
        List<Object> numbers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            numbers.add(i);
        }
        return numbers;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stored(Map<String, Object> templateData) {
        return (List<Map<String, Object>>) templateData.get("results");
    }

    private static String lastSegment(URI uri) {
        String path = uri.getRawPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static IResponse json(int code, String body) {
        return new FakeResponse(code, body, "application/json");
    }

    static IResponse text(int code, String body) {
        return new FakeResponse(code, body, "text/plain");
    }

    record FakeResponse(int code, String body, String contentType) implements IResponse {
        @Override
        public String getContentAsString() {
            return body;
        }

        @Override
        public int getHttpCode() {
            return code;
        }

        @Override
        public String getHttpCodeMessage() {
            return "status " + code;
        }

        @Override
        public Map<String, String> getHttpHeader() {
            return Map.of("Content-Type", contentType);
        }
    }

    /**
     * A request that records what was set on it and answers through
     * {@link #responder}.
     */
    final class FakeRequest implements IRequest {
        final URI uri;
        volatile String body;
        final AtomicLong timeoutMs = new AtomicLong();
        final AtomicInteger sends = new AtomicInteger();
        private final Map<String, String> headers = new LinkedHashMap<>();

        FakeRequest(URI uri) {
            this.uri = uri;
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
            return this;
        }

        @Override
        public IRequest setQueryParam(String key, String value) {
            return this;
        }

        @Override
        public IRequest setUserAgent(String userAgent) {
            return this;
        }

        @Override
        public IRequest setHttpHeader(String headerName, String value) {
            headers.put(headerName, value);
            return this;
        }

        @Override
        public IRequest setBodyEntity(String content, String encoding, String contentType) {
            this.body = content;
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int byteSize) {
            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            timeoutMs.set(timeUnit.toMillis(timeout));
            return this;
        }

        @Override
        public IRequest setFollowRedirects(boolean follow) {
            return this;
        }

        @Override
        public IResponse send() throws HttpRequestException {
            sends.incrementAndGet();
            return responder.respond(this);
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put(KEY_URI, uri.toString());
            map.put(KEY_METHOD, "GET");
            map.put(KEY_HEADERS, new HashMap<>(headers));
            if (body != null) {
                map.put(KEY_BODY, body);
            }
            return map;
        }

        @Override
        public void send(ICompleteListener completeListener) {
            throw new UnsupportedOperationException("forEach never sends fire-and-forget");
        }
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long start) {
            this.millis = new AtomicLong(start);
        }

        void advance(long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }
}
