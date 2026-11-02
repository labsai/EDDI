/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import java.time.Duration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ToolQuotaService")
class ToolQuotaServiceTest {

    static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";
    static final String CONVERSATION = "bbbbbbbbbbbbbbbbbbbbbbbb";

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void set(String iso) {
            this.now = Instant.parse(iso);
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
            return now;
        }
    }

    private InMemoryToolQuotaStore store;
    private ITemplatingEngine templating;
    private IMemoryItemConverter converter;
    private SimpleMeterRegistry meters;
    private MutableClock clock;
    private ToolQuotaService service;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() {
        store = new InMemoryToolQuotaStore();
        templating = mock(ITemplatingEngine.class);
        converter = mock(IMemoryItemConverter.class);
        when(converter.convert(any())).thenReturn(Map.of());
        meters = new SimpleMeterRegistry();
        clock = new MutableClock("2026-11-02T10:00:00Z");
        service = new ToolQuotaService(store, templating, converter, meters, clock);
        memory = memoryOf("user-1");
    }

    static IConversationMemory memoryOf(String userId) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getUserId()).thenReturn(userId);
        when(memory.getConversationId()).thenReturn(CONVERSATION);
        return memory;
    }

    private ToolQuotaGate gate(ToolQuotaConfig cfg, String tool, IConversationMemory mem) {
        return service.gateFor(cfg, ToolInvocation.of(tool), mem, r -> r.startsWith("Error"));
    }

    private ToolQuotaGate gate(ToolQuotaConfig cfg, String tool) {
        return gate(cfg, tool, memory);
    }

    private double decisions(String tool, String outcome) {
        var counter = meters.find("eddi.tool.quota.decisions").tag("tool", tool).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("no toolQuotas, or no rule for the tool: no gate and the store is never read")
    void absentConfigTouchesNothing() {
        assertNull(gate(null, "updateAction"));
        assertNull(gate(config("other", rule(1, "P1D", null)), "updateAction"));
        assertTrue(service.precheck(null, "updateAction", "updateAction", memory).isEmpty());
        assertTrue(service.precheck(config("other", rule(1, "P1D", null)), "updateAction", "updateAction", memory).isEmpty());
        assertEquals(0, store.calls.get());
        verifyNoInteractions(templating);
    }

    @Test
    @DisplayName("a call is counted when it is reserved, and the limit then refuses")
    void countsAndRefuses() {
        var cfg = config("updateAction", rule(2, "P1D", null));
        assertTrue(gate(cfg, "updateAction").reserve().isEmpty());
        assertTrue(gate(cfg, "updateAction").reserve().isEmpty());
        var third = gate(cfg, "updateAction");
        Optional<String> refusal = third.reserve();
        assertTrue(refusal.isPresent());
        assertTrue(third.refused());
        assertEquals(2, store.total());
        assertEquals(2, decisions("updateAction", "allowed"));
        assertEquals(1, decisions("updateAction", "refused"));
    }

    @Test
    @DisplayName("the refusal is a final NOT_EXECUTED result that names the limit and the reset time")
    void refusalWording() {
        var cfg = config("updateAction", rule(1, "P1D", null));
        gate(cfg, "updateAction").reserve();
        String refusal = gate(cfg, "updateAction").reserve().orElseThrow();
        assertTrue(refusal.contains("\"status\":\"NOT_EXECUTED\""), refusal);
        assertTrue(refusal.contains("Not run: the daily limit for this tool (1) is reached"), refusal);
        assertTrue(refusal.contains("it resets at 2026-11-03T00:00:00Z"), refusal);
        assertTrue(refusal.contains("do not call this tool again"), refusal);
        assertFalse(refusal.contains("try again"), "the model must not be invited to retry");
    }

    @Test
    @DisplayName("the default subject is the conversation's user id: another user has their own count")
    void defaultSubjectIsTheUser() {
        var cfg = config("updateAction", rule(1, "P1D", null));
        assertTrue(gate(cfg, "updateAction", memoryOf("user-1")).reserve().isEmpty());
        assertTrue(gate(cfg, "updateAction", memoryOf("user-2")).reserve().isEmpty(), "user-2 is not charged for user-1");
        assertTrue(gate(cfg, "updateAction", memoryOf("user-1")).reserve().isPresent());
    }

    @Test
    @DisplayName("a subject template counts per person across composite managed-conversation user ids")
    void subjectTemplateCountsPerPerson() throws Exception {
        var cfg = config("updateAction", rule(1, "P1D", null));
        cfg.setSubject("{context.userInfo.userIds[0]}");
        when(templating.processTemplate(anyString(), any())).thenReturn("person-9");
        // two programs of one person: composite ids differ, the rendered subject does
        // not
        assertTrue(gate(cfg, "updateAction", memoryOf("person-9-acme-program-a")).reserve().isEmpty());
        assertTrue(gate(cfg, "updateAction", memoryOf("person-9-acme-program-b")).reserve().isPresent(),
                "the second program draws on the same person's allowance");
        var key = new ToolQuotaKey(AGENT, "tool:updateAction", "person-9", ToolQuotaRules.windowStart(clock.instant(), Duration.ofDays(1)));
        assertEquals(1, store.peek(key));
    }

    @Test
    @DisplayName("a subject template that renders blank falls back to the conversation's user id")
    void blankSubjectFallsBack() throws Exception {
        var cfg = config("updateAction", rule(1, "P1D", null));
        cfg.setSubject("{context.missing}");
        when(templating.processTemplate(anyString(), any())).thenReturn("   ");
        assertTrue(gate(cfg, "updateAction", memoryOf("user-1")).reserve().isEmpty());
        var key = new ToolQuotaKey(AGENT, "tool:updateAction", "user-1", ToolQuotaRules.windowStart(clock.instant(), Duration.ofDays(1)));
        assertEquals(1, store.peek(key));
    }

    @Test
    @DisplayName("without any user id the caller is capped per conversation, not refused and not pooled with strangers")
    void anonymousCallerIsCappedPerConversation() {
        var cfg = config("updateAction", rule(1, "P1D", null));
        assertTrue(gate(cfg, "updateAction", memoryOf(null)).reserve().isEmpty());
        var key = new ToolQuotaKey(AGENT, "tool:updateAction", "conversation:" + CONVERSATION,
                ToolQuotaRules.windowStart(clock.instant(), Duration.ofDays(1)));
        assertEquals(1, store.peek(key));
    }

    @Test
    @DisplayName("a subject template that fails to render fails closed instead of counting under a guess")
    void brokenTemplateFailsClosed() throws Exception {
        var cfg = config("updateAction", rule(5, "P1D", null));
        cfg.setSubject("{#if}");
        when(templating.processTemplate(anyString(), any())).thenThrow(new ITemplatingEngine.TemplateEngineException("bad", null));
        var gate = gate(cfg, "updateAction");
        String refusal = gate.reserve().orElseThrow();
        assertTrue(gate.refused());
        assertTrue(refusal.contains("could not be checked"), refusal);
        assertEquals(0, store.total());
    }

    @Test
    @DisplayName("an over-long subject is shortened to a bounded prefix plus digest, and two long subjects stay distinct")
    void longSubjectIsCappedAndStaysDistinct() {
        String a = "x".repeat(300) + "A";
        String b = "x".repeat(300) + "B";
        String capA = ToolQuotaService.capSubject(a);
        String capB = ToolQuotaService.capSubject(b);
        assertTrue(capA.length() <= ToolQuotaConfig.MAX_SUBJECT_LENGTH, "" + capA.length());
        assertNotEquals(capA, capB);
        assertEquals("short", ToolQuotaService.capSubject("short"));
    }

    @Test
    @DisplayName("a failed call gives its reservation back; a successful one keeps it")
    void releaseOnFailure() {
        var cfg = config("updateAction", rule(1, "P1D", null));
        var failing = gate(cfg, "updateAction");
        assertTrue(failing.reserve().isEmpty());
        failing.settle(null, new IllegalStateException("boom"));
        assertEquals(0, store.total(), "an exception releases");
        assertEquals(1, decisions("updateAction", "released"));

        var errorResult = gate(cfg, "updateAction");
        assertTrue(errorResult.reserve().isEmpty());
        errorResult.settle("Error: the tool failed", null);
        assertEquals(0, store.total(), "a failure result releases");

        var ok = gate(cfg, "updateAction");
        assertTrue(ok.reserve().isEmpty());
        ok.settle("{\"httpCode\":200}", null);
        assertEquals(1, store.total(), "a success keeps the count");
        assertTrue(gate(cfg, "updateAction").reserve().isPresent());
    }

    @Test
    @DisplayName("settling twice, or after a refusal, never releases someone else's count")
    void settleIsIdempotent() {
        var cfg = config("updateAction", rule(1, "P1D", null));
        var first = gate(cfg, "updateAction");
        first.reserve();
        var refused = gate(cfg, "updateAction");
        refused.reserve();
        refused.settle(null, new RuntimeException("n/a"));
        assertEquals(1, store.total(), "a refused gate holds nothing to give back");
        first.settle(null, new RuntimeException("x"));
        first.settle(null, new RuntimeException("x"));
        assertEquals(0, store.total());
    }

    @Test
    @DisplayName("tools of one pool draw on one counter")
    void poolSharing() {
        var cfg = config("updateAction", rule(3, "P1D", "improvements"), "updateSession", rule(3, "P1D", "improvements"), "generateActionImage",
                rule(1, "P1D", null));
        assertTrue(gate(cfg, "updateAction").reserve().isEmpty());
        assertTrue(gate(cfg, "updateSession").reserve().isEmpty());
        assertTrue(gate(cfg, "updateAction").reserve().isEmpty());
        assertTrue(gate(cfg, "updateSession").reserve().isPresent(), "the pool's 3 are spent across both tools");
        assertTrue(gate(cfg, "updateAction").reserve().isPresent());
        assertTrue(gate(cfg, "generateActionImage").reserve().isEmpty(), "a tool outside the pool has its own counter");
    }

    @Test
    @DisplayName("the window is fixed on the UTC epoch: spent at 23:59:59, free again at 00:00:00")
    void windowBoundary() {
        var cfg = config("updateAction", rule(1, "P1D", null));
        clock.set("2026-11-02T23:59:59Z");
        assertTrue(gate(cfg, "updateAction").reserve().isEmpty());
        String refusal = gate(cfg, "updateAction").reserve().orElseThrow();
        assertTrue(refusal.contains("2026-11-03T00:00:00Z"), refusal);
        clock.set("2026-11-03T00:00:00Z");
        assertTrue(gate(cfg, "updateAction").reserve().isEmpty(), "a new window starts with a fresh count");
    }

    @Test
    @DisplayName("fail closed: a store outage refuses the call, counts the failure and never throws")
    void failsClosed() {
        var cfg = config("updateAction", rule(5, "P1D", null));
        store.failing = true;
        var gate = gate(cfg, "updateAction");
        String refusal = gate.reserve().orElseThrow();
        assertTrue(gate.refused());
        assertTrue(refusal.contains("\"status\":\"NOT_EXECUTED\"") && refusal.contains("could not be checked"), refusal);
        assertEquals(1.0, meters.find("eddi.tool.quota.store.failures").tag("operation", "reserve").counter().count());
        assertEquals(1, decisions("updateAction", "unavailable"));
        // a second failure inside the minute is still counted
        gate(cfg, "updateAction").reserve();
        assertEquals(2.0, meters.find("eddi.tool.quota.store.failures").tag("operation", "reserve").counter().count());
    }

    @Test
    @DisplayName("a stored rule that cannot be applied refuses the call instead of running it unbounded")
    void unusableRuleFailsClosed() {
        var cfg = config("updateAction", rule(0, "P1D", null));
        var gate = gate(cfg, "updateAction");
        assertTrue(gate.reserve().orElseThrow().contains("could not be checked"));
        assertTrue(service.precheck(cfg, "updateAction", "updateAction", memory).orElseThrow().contains("could not be checked"));
    }

    @Test
    @DisplayName("precheck refuses only an already-exhausted tool, and counts nothing")
    void precheck() {
        var cfg = config("updateAction", rule(2, "P1D", null));
        assertTrue(service.precheck(cfg, "updateAction", "updateAction", memory).isEmpty());
        gate(cfg, "updateAction").reserve();
        assertTrue(service.precheck(cfg, "updateAction", "updateAction", memory).isEmpty(), "one left");
        gate(cfg, "updateAction").reserve();
        assertTrue(service.precheck(cfg, "updateAction", "updateAction", memory).orElseThrow().contains("Not run: the daily limit"));
        assertEquals(2, store.total(), "a pre-check never increments");
        assertEquals(1, decisions("updateAction", "precheck_refused"));
    }

    @Test
    @DisplayName("precheck fails closed too")
    void precheckFailsClosed() {
        var cfg = config("updateAction", rule(2, "P1D", null));
        store.failing = true;
        assertTrue(service.precheck(cfg, "updateAction", "updateAction", memory).orElseThrow().contains("could not be checked"));
    }

    @Test
    @DisplayName("a batch of 5 parallel calls with 3 left runs exactly 3; the other 2 are refused")
    void parallelBatchNeverOvershoots() throws Exception {
        var cfg = config("updateAction", rule(10, "P1D", null));
        for (int i = 0; i < 7; i++) {
            assertTrue(gate(cfg, "updateAction").reserve().isEmpty());
        }
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                var g = service.gateFor(cfg, ToolInvocation.of("updateAction"), memory, (Predicate<String>) r -> false);
                start.await();
                return g.reserve().isEmpty();
            }));
        }
        start.countDown();
        int granted = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                granted++;
            }
        }
        pool.shutdown();
        assertEquals(3, granted);
        assertEquals(10, store.total());
    }
}
