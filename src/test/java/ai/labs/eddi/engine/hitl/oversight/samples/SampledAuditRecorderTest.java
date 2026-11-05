/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightFixtures;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SampledAuditRecorder")
class SampledAuditRecorderTest {

    private static final Map<String, String> SOURCES = Map.of("createNote", "http");
    private static final Map<String, String> ENDPOINTS = Map.of("createNote", "post:/crm/notes");
    private static final byte[] KEY = "a-sampling-key-for-tests-only-32".getBytes(StandardCharsets.UTF_8);

    private OversightConfigLookup configLookup;
    private IToolQuotaStore quotaStore;
    private IAuditSampleStore sampleStore;
    private OversightMetrics metrics;
    private IConversationMemory memory;
    private SampledAuditConfig audit;
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
        var oversight = new OversightConfig();
        oversight.setSampledAudit(audit);
        when(configLookup.oversight(eq(AGENT), any())).thenReturn(oversight);
        when(quotaStore.tryAcquire(any(), anyInt(), any())).thenReturn(new IToolQuotaStore.Acquisition(true, 1));
    }

    private SampledAuditRecorder recorder(boolean enabled) {
        var settings = new OversightSettings(Duration.ofDays(400), Duration.ofDays(90), enabled, Duration.ofDays(180), Duration.ofHours(1),
                Duration.ofSeconds(15), 500);
        return new SampledAuditRecorder(settings, configLookup, new SamplingKey(KEY), quotaStore, sampleStore, metrics, writer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ToolExecutionRequest call(String id, String args) {
        return ToolExecutionRequest.builder().id(id).name("createNote").arguments(args).build();
    }

    private static List<Map<String, Object>> traceWithResult(Object result, boolean mocked) {
        Map<String, Object> step = new HashMap<>();
        step.put("type", "tool_result");
        step.put("tool", "createNote");
        step.put("result", result);
        if (mocked) {
            step.put("mocked", true);
        }
        return List.of(Map.of("type", "tool_call", "tool", "createNote"), step);
    }

    private ToolApprovalsConfig exempting() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        cfg.setExempt(List.of("http.post:/crm/notes"));
        return cfg;
    }

    @Test
    @DisplayName("rate 1 samples the call: redacted, digested, PENDING, due after reviewWithin, written off-thread")
    void sampled() {
        AuditSample sample = recorder(true).consider(memory, call("c1", "{\"text\":\"hello\"}"), SOURCES, ENDPOINTS, exempting(),
                traceWithResult("ok", false));

        assertNotNull(sample);
        assertEquals("tool:http.post:/crm/notes", sample.category());
        assertEquals("http.post:/crm/notes", sample.rulePattern());
        assertEquals("http.post:/crm/notes", sample.exemptPattern());
        assertEquals(AuditSample.Status.PENDING, sample.status());
        assertEquals(NOW.plus(Duration.ofDays(3)), sample.reviewDueAt());
        assertEquals(64, sample.argsDigest().length());
        assertNull(sample.resultRedacted(), "no result unless includeResult");
        verify(sampleStore, never()).insert(any());
        queued.getFirst().run();
        verify(sampleStore).insert(sample);
        verify(metrics).sample("sampled");
    }

    @Test
    @DisplayName("rate 0 never samples")
    void rateZero() {
        audit.getRules().getFirst().setRate(0.0);
        assertNull(recorder(true).consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", false)));
    }

    @Test
    @DisplayName("the decision is deterministic for the same inputs and differs across keys")
    void deterministic() {
        var key = new SamplingKey(KEY);
        double a = key.fraction(AGENT, "conv-1", 4, "c1", "createNote");
        assertEquals(a, key.fraction(AGENT, "conv-1", 4, "c1", "createNote"));
        assertNotEquals(a, new SamplingKey("another-key-for-tests-only-32-b!".getBytes(StandardCharsets.UTF_8)).fraction(AGENT, "conv-1", 4,
                "c1", "createNote"));
        assertTrue(a >= 0.0 && a < 1.0);
        assertFalse(SamplingKey.sampled(a, a), "u < rate: a call at exactly the rate is not sampled");
        assertTrue(SamplingKey.sampled(a, Math.nextUp(a)));
    }

    @Test
    @DisplayName("roughly the configured share of distinct calls is sampled")
    void share() {
        var key = new SamplingKey(KEY);
        int sampled = 0;
        for (int i = 0; i < 10_000; i++) {
            if (SamplingKey.sampled(key.fraction(AGENT, "conv-" + i, i % 7, "c" + i, "createNote"), 0.1)) {
                sampled++;
            }
        }
        assertTrue(sampled > 850 && sampled < 1150, "expected ~1000, got " + sampled);
    }

    @Test
    @DisplayName("over the per-category daily cap the call is counted as capped, not stored")
    void capped() {
        when(quotaStore.tryAcquire(any(), anyInt(), any())).thenReturn(new IToolQuotaStore.Acquisition(false, 50));

        assertNull(recorder(true).consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", false)));

        var key = ArgumentCaptor.forClass(ToolQuotaKey.class);
        verify(quotaStore).tryAcquire(key.capture(), eq(50), any());
        assertEquals("sample:tool:http.post:/crm/notes", key.getValue().counter());
        verify(metrics).sample("capped");
        assertTrue(queued.isEmpty());
    }

    @Test
    @DisplayName("mocked calls, calls without a result and group member turns are skipped")
    void skipped() {
        var r = recorder(true);
        assertNull(r.consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", true)));
        assertNull(r.consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(),
                List.of(Map.of("type", "tool_error", "tool", "createNote", "error", "rate_limited"))));
        assertNull(r.consider(memory, call(null, "{}"), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", false)));
        verify(quotaStore, never()).tryAcquire(any(), anyInt(), any());
    }

    @Test
    @DisplayName("the switch eddi.hitl.samples.enabled=false samples nothing")
    void disabled() {
        assertNull(recorder(false).consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", false)));
    }

    @Test
    @DisplayName("arguments are secret-redacted and capped at 32 KB; never stored raw")
    void redactedAndCapped() {
        String secret = "sk-" + "a".repeat(48);
        String big = "{\"key\":\"" + secret + "\",\"pad\":\"" + "x".repeat(40_000) + "\"}";

        AuditSample sample = recorder(true).consider(memory, call("c1", big), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", false));

        assertFalse(sample.argumentsRedacted().contains(secret), "the credential must not be stored");
        assertTrue(sample.argumentsRedacted().getBytes(StandardCharsets.UTF_8).length <= PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES);
    }

    @Test
    @DisplayName("with includeResult the redacted result is stored, capped at 4 KB")
    void includeResult() {
        audit.setIncludeResult(true);

        AuditSample sample = recorder(true).consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(),
                traceWithResult("y".repeat(10_000), false));

        assertEquals(SampledAuditRecorder.RESULT_MAX_BYTES, sample.resultRedacted().length());
    }

    @Test
    @DisplayName("a full write queue drops the sample without throwing")
    void fullQueue() {
        writer = command -> {
            throw new RejectedExecutionException("full");
        };

        assertDoesNotThrow(() -> recorder(true).afterAllowed(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(),
                traceWithResult("ok", false)));
        verify(metrics).sample("dropped");
    }

    @Test
    @DisplayName("the most specific matching rule wins")
    void mostSpecificRule() {
        var broad = new SampledAuditConfig.Rule();
        broad.setMatch("http.post:*");
        broad.setRate(1.0);
        broad.setCategory("all-posts");
        audit.getRules().addFirst(broad);

        AuditSample sample = recorder(true).consider(memory, call("c1", "{}"), SOURCES, ENDPOINTS, exempting(), traceWithResult("ok", false));

        assertEquals("tool:http.post:/crm/notes", sample.category());
    }
}
