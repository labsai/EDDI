/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditKeyring;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightFixtures;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("sampled audit branches")
class SampledAuditRecorderBranchesTest {

    private static final Map<String, String> SOURCES = Map.of("createNote", "http");
    private static final Map<String, String> ENDPOINTS = Map.of("createNote", "post:/crm/notes");
    private static final byte[] KEY = "a-sampling-key-for-tests-only-32".getBytes(StandardCharsets.UTF_8);

    private OversightConfigLookup configLookup;
    private IToolQuotaStore quotaStore;
    private IAuditSampleStore sampleStore;
    private OversightMetrics metrics;
    private IConversationMemory memory;
    private SampledAuditConfig audit;
    private OversightConfig oversight;
    private final List<Runnable> queued = new ArrayList<>();
    private Executor writer = queued::add;

    @BeforeEach
    void setUp() {
        configLookup = mock(OversightConfigLookup.class);
        quotaStore = mock(IToolQuotaStore.class);
        sampleStore = mock(IAuditSampleStore.class);
        metrics = mock(OversightMetrics.class);
        memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getAgentVersion()).thenReturn(3);
        when(memory.getConversationId()).thenReturn("conv-1");
        when(memory.getUserId()).thenReturn("user-1");
        when(memory.size()).thenReturn(4);
        audit = OversightFixtures.audit("http.post:/crm/notes", 1.0);
        oversight = new OversightConfig();
        oversight.setSampledAudit(audit);
        when(configLookup.oversight(eq(AGENT), any())).thenAnswer(inv -> oversight);
        when(quotaStore.tryAcquire(any(), anyInt(), any())).thenReturn(new IToolQuotaStore.Acquisition(true, 1));
    }

    private SampledAuditRecorder recorder() {
        var settings = new OversightSettings(Duration.ofDays(400), Duration.ofDays(90), true, Duration.ofDays(180), Duration.ofHours(1),
                Duration.ofSeconds(15), 500);
        return new SampledAuditRecorder(settings, configLookup, new SamplingKey(KEY), quotaStore, sampleStore, metrics, writer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ToolExecutionRequest call(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }

    private static List<Map<String, Object>> result(Object value) {
        Map<String, Object> step = new HashMap<>();
        step.put("type", "tool_result");
        step.put("tool", "createNote");
        step.put("result", value);
        return List.of(step);
    }

    private AuditSample consider(ToolExecutionRequest request, List<Map<String, Object>> trace) {
        return recorder().consider(memory, request, SOURCES, ENDPOINTS, null, trace);
    }

    @Nested
    @DisplayName("what is never considered")
    class NeverConsidered {

        @Test
        @DisplayName("no memory, no request, a nameless request, and a missing or blank call id")
        void incompleteInput() {
            var r = recorder();
            assertNull(r.consider(null, call("c", "createNote", "{}"), SOURCES, ENDPOINTS, null, result("ok")));
            assertNull(r.consider(memory, null, SOURCES, ENDPOINTS, null, result("ok")));
            assertNull(r.consider(memory, call("c", null, "{}"), SOURCES, ENDPOINTS, null, result("ok")));
            assertNull(r.consider(memory, call(null, "createNote", "{}"), SOURCES, ENDPOINTS, null, result("ok")));
            assertNull(r.consider(memory, call("  ", "createNote", "{}"), SOURCES, ENDPOINTS, null, result("ok")));
            verify(configLookup, never()).oversight(any(), any());
        }

        @Test
        @DisplayName("an agent with no oversight, no sampled audit, or no rules")
        void nothingConfigured() {
            oversight = null;
            assertNull(consider(call("c", "createNote", "{}"), result("ok")));

            oversight = new OversightConfig();
            assertNull(consider(call("c", "createNote", "{}"), result("ok")));

            oversight.setSampledAudit(new SampledAuditConfig());
            assertNull(consider(call("c", "createNote", "{}"), result("ok")));

            var empty = new SampledAuditConfig();
            empty.setRules(List.of());
            oversight.setSampledAudit(empty);
            assertNull(consider(call("c", "createNote", "{}"), result("ok")));
            verify(quotaStore, never()).tryAcquire(any(), anyInt(), any());
        }

        @Test
        @DisplayName("a call no rule matches, or whose rule has no rate or a zero rate")
        void noEffectiveRule() {
            assertNull(consider(call("c", "otherTool", "{}"), result("ok")));

            audit.getRules().get(0).setRate(null);
            assertNull(consider(call("c", "createNote", "{}"), result("ok")));

            audit.getRules().get(0).setRate(0.0);
            assertNull(consider(call("c", "createNote", "{}"), result("ok")));
            verify(metrics, never()).sample(any());
        }

        @Test
        @DisplayName("a call with no result in the trace, or no trace at all, is counted as skipped")
        void noResult() {
            assertNull(consider(call("c", "createNote", "{}"), null));
            assertNull(consider(call("c", "createNote", "{}"), List.of()));
            verify(metrics, times(2)).sample("skipped");
        }

        @Test
        @DisplayName("a group member turn is skipped")
        void groupTurn() {
            var step = mock(IConversationMemory.IWritableConversationStep.class);
            doReturn(new Data<>("context:groupId", "g1")).when(step).getData("context:groupId");
            when(memory.getCurrentStep()).thenReturn(step);

            assertNull(consider(call("c", "createNote", "{}"), result("ok")));
            verify(metrics).sample("skipped");
        }

        @Test
        @DisplayName("a call below the sampling fraction is not sampled and takes no quota")
        void notChosen() {
            audit.getRules().get(0).setRate(1.0e-12);

            assertNull(consider(call("c", "createNote", "{}"), result("ok")));

            verify(quotaStore, never()).tryAcquire(any(), anyInt(), any());
            verify(metrics, never()).sample("sampled");
        }

        @Test
        @DisplayName("a category over its daily cap is counted as capped")
        void capped() {
            when(quotaStore.tryAcquire(any(), anyInt(), any())).thenReturn(new IToolQuotaStore.Acquisition(false, 50));

            assertNull(consider(call("c", "createNote", "{}"), result("ok")));

            verify(metrics).sample("capped");
        }

        @Test
        @DisplayName("an error while considering is swallowed by afterAllowed")
        void errorSwallowed() {
            when(configLookup.oversight(any(), any())).thenThrow(new IllegalStateException("boom"));
            recorder().afterAllowed(memory, call("c", "createNote", "{}"), SOURCES, ENDPOINTS, null, result("ok"));
            verify(metrics, never()).sample(any());
        }
    }

    @Nested
    @DisplayName("what is stored")
    class Stored {

        @Test
        @DisplayName("null source and endpoint maps still match by tool name, and null arguments are stored as empty")
        void nullMaps() {
            audit.getRules().get(0).setMatch("createNote");
            AuditSample sample = recorder().consider(memory, call("c", "createNote", null), null, null, null, result("ok"));

            assertNotNull(sample);
            assertNull(sample.source());
            assertNull(sample.endpoint());
            assertEquals("", sample.argumentsRedacted());
            assertNull(sample.exemptPattern());
        }

        @Test
        @DisplayName("includeResult stores a redacted result; a missing result stores none")
        void includeResult() {
            audit.setIncludeResult(true);
            assertEquals("done", consider(call("c1", "createNote", "{}"), result("done")).resultRedacted());
            assertNull(consider(call("c2", "createNote", "{}"), result(null)).resultRedacted());

            audit.setIncludeResult(false);
            assertNull(consider(call("c3", "createNote", "{}"), result("done")).resultRedacted());
        }

        @Test
        @DisplayName("the queued write inserts the sample; a failing insert is counted as dropped")
        void writeAndFailure() {
            AuditSample sample = consider(call("c1", "createNote", "{}"), result("ok"));
            queued.get(0).run();
            verify(sampleStore).insert(sample);

            doThrow(new IllegalStateException("db down")).when(sampleStore).insert(any());
            queued.get(0).run();
            verify(metrics).sample("dropped");
        }

        @Test
        @DisplayName("a full queue drops samples and warns at most once a minute")
        void fullQueue() {
            writer = command -> {
                throw new RejectedExecutionException("full");
            };
            var r = recorder();

            assertNull(r.consider(memory, call("c1", "createNote", "{}"), SOURCES, ENDPOINTS, null, result("ok")));
            assertNull(r.consider(memory, call("c2", "createNote", "{}"), SOURCES, ENDPOINTS, null, result("ok")));

            verify(metrics, times(2)).sample("dropped");
        }

        @Test
        @DisplayName("shutdown stops a real writer and ignores an injected one")
        void shutdown() {
            var settings = OversightSettings.defaults();
            var real = new SampledAuditRecorder(settings, configLookup, new SamplingKey(KEY), quotaStore, sampleStore, metrics);
            real.shutdown();
            recorder().shutdown();
        }
    }

    @Nested
    @DisplayName("static helpers")
    class Helpers {

        private SampledAuditConfig.Rule rule(String match) {
            var rule = new SampledAuditConfig.Rule();
            rule.setMatch(match);
            rule.setRate(1.0);
            return rule;
        }

        @Test
        @DisplayName("matchingRule prefers fewer wildcards, then the longer pattern, and skips null and blank rules")
        void matchingRule() {
            var broad = rule("http.*");
            var narrow = rule("http.post:/crm/notes");
            var middle = rule("http.post:*");
            List<SampledAuditConfig.Rule> rules = Arrays.asList(null, rule(" "), rule(null), broad, middle, narrow);
            List<String> addresses = List.of("http.post:/crm/notes");

            assertSame(narrow, SampledAuditRecorder.matchingRule(rules, addresses));
            assertSame(middle, SampledAuditRecorder.matchingRule(List.of(broad, middle), addresses));
            assertSame(narrow, SampledAuditRecorder.matchingRule(List.of(middle, narrow), addresses));
            assertNull(SampledAuditRecorder.matchingRule(List.of(rule("mcp:*")), addresses));
            assertNull(SampledAuditRecorder.matchingRule(List.of(), addresses));
        }

        @Test
        @DisplayName("resultOf reads the call's own last entry, skipping a result guardrail note and entries of other tools")
        void resultOf() {
            Map<String, Object> call = Map.of("type", "tool_call", "tool", "t");
            Map<String, Object> result = Map.of("type", "tool_result", "tool", "t", "result", "r");
            Map<String, Object> guardrail = Map.of("type", "tool_result_guardrail", "tool", "t");
            Map<String, Object> other = Map.of("type", "tool_result", "tool", "other");

            assertNull(SampledAuditRecorder.resultOf(null, "t"));
            assertNull(SampledAuditRecorder.resultOf(List.of(), "t"));
            assertSame(result, SampledAuditRecorder.resultOf(List.of(call, result), "t"));
            assertSame(result, SampledAuditRecorder.resultOf(List.of(call, result, guardrail, other), "t"));
            assertNull(SampledAuditRecorder.resultOf(List.of(result, call), "t"), "the last entry of the call is not a result");
            assertNull(SampledAuditRecorder.resultOf(List.of(other), "t"));
            assertSame(result, SampledAuditRecorder.resultOf(Arrays.asList(result, null), "t"));
        }

        @Test
        @DisplayName("capUtf8 keeps short text, drops null, and never cuts inside a multi-byte character")
        void capUtf8() {
            assertNull(SampledAuditRecorder.capUtf8(null, 10));
            assertEquals("abc", SampledAuditRecorder.capUtf8("abc", 3));
            assertEquals("ab", SampledAuditRecorder.capUtf8("abc", 2));
            assertEquals("éé", SampledAuditRecorder.capUtf8("ééé", 5));
            assertEquals("éé", SampledAuditRecorder.capUtf8("ééé", 4));
            assertEquals("", SampledAuditRecorder.capUtf8("é", 1));
        }
    }

    @Nested
    @DisplayName("SamplingKey")
    class Key {

        @SuppressWarnings("unchecked")
        private Instance<AuditKeyring> keyring(boolean resolvable, AuditKeyring ring) {
            Instance<AuditKeyring> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(resolvable);
            when(instance.get()).thenReturn(ring);
            return instance;
        }

        @Test
        @DisplayName("the fraction is deterministic, in [0, 1), and depends on every input")
        void fraction() {
            var key = new SamplingKey(KEY);
            double u = key.fraction("a", "c", 1, "call", "tool");

            assertEquals(u, key.fraction("a", "c", 1, "call", "tool"));
            assertTrue(u >= 0.0 && u < 1.0);
            assertNotEquals(u, key.fraction("b", "c", 1, "call", "tool"));
            assertNotEquals(u, key.fraction("a", "c", 2, "call", "tool"));
            assertNotEquals(u, new SamplingKey("another-key-for-tests-only-32by".getBytes(StandardCharsets.UTF_8)).fraction("a", "c", 1, "call",
                    "tool"));
        }

        @Test
        @DisplayName("sampled compares the fraction strictly below the rate")
        void sampled() {
            assertTrue(SamplingKey.sampled(0.1, 0.5));
            assertFalse(SamplingKey.sampled(0.5, 0.5));
            assertFalse(SamplingKey.sampled(0.9, 0.5));
        }

        @Test
        @DisplayName("a key derived from the audit signing key is stable across instances and cached")
        void derivedFromAuditKey() {
            var ring = mock(AuditKeyring.class);
            when(ring.signingKey()).thenReturn(AuditHmac.signingKey("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));

            var first = new SamplingKey(keyring(true, ring));
            var second = new SamplingKey(keyring(true, ring));

            double u = first.fraction("a", "c", 1, "call", "tool");
            assertEquals(u, second.fraction("a", "c", 1, "call", "tool"), "reproducible by an auditor holding the key");
            assertEquals(u, first.fraction("a", "c", 1, "call", "tool"));
            verify(ring, times(2)).signingKey(); // once per instance, then cached
        }

        @Test
        @DisplayName("without a usable audit key a random per-node key is used")
        void randomFallbacks() {
            var noSigning = mock(AuditKeyring.class);
            when(noSigning.signingKey()).thenReturn(null);
            var noHmac = mock(AuditKeyring.class);
            when(noHmac.signingKey()).thenReturn(new AuditHmac.SigningKey("id", null, null));
            var failing = mock(AuditKeyring.class);
            when(failing.signingKey()).thenThrow(new IllegalStateException("vault down"));

            for (Instance<AuditKeyring> instance : List.of(keyring(false, null), keyring(true, noSigning), keyring(true, noHmac),
                    keyring(true, failing))) {
                double u = new SamplingKey(instance).fraction("a", "c", 1, "call", "tool");
                assertTrue(u >= 0.0 && u < 1.0);
            }
        }
    }
}
