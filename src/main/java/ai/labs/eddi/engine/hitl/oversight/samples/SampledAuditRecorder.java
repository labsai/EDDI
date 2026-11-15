/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.audit.EditMetrics;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.hitl.tools.AllowedToolCallObserver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import ai.labs.eddi.engine.memory.ConversationGroups;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The sampled audit: after an <em>allowed</em> tool call executed, decides
 * whether it is copied into the review queue.
 * <p>
 * A call is considered when the agent's {@code oversight.sampledAudit} has a
 * rule matching it (most specific rule wins) and it actually produced a result.
 * Skipped: calls with no id, refused, rate-limited or quota-refused calls (no
 * result), calls answered by an agent-test mock, group member turns, and
 * everything when {@code eddi.hitl.samples.enabled=false}.
 * <p>
 * The choice is deterministic ({@link SamplingKey}); a per-(agent, category,
 * UTC day) cap keeps one busy category from crowding out the others. The write
 * goes to a bounded single-worker queue — it never blocks the turn, and a full
 * queue drops the sample (counted as {@code dropped}).
 * <p>
 * Privacy: arguments are stored as the approval card shows them — secret
 * redaction, capped at 32 KB — plus the SHA-256 of the raw arguments; the
 * result only with {@code includeResult}, redacted and capped at 4 KB.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class SampledAuditRecorder implements AllowedToolCallObserver {

    private static final Logger LOGGER = Logger.getLogger(SampledAuditRecorder.class);

    /** Cap of a stored result. */
    public static final int RESULT_MAX_BYTES = 4096;
    /** Queue bound of pending sample writes. */
    static final int QUEUE_CAPACITY = 10_000;
    /** Quota-store subject of the sample caps. */
    static final String CAP_SUBJECT = "__audit_sample__";

    private final OversightSettings settings;
    private final OversightConfigLookup configLookup;
    private final SamplingKey samplingKey;
    private final IToolQuotaStore quotaStore;
    private final IAuditSampleStore sampleStore;
    private final OversightMetrics metrics;
    private final Executor writer;
    private final Clock clock;
    private final AtomicLong lastDropWarning = new AtomicLong();

    @Inject
    public SampledAuditRecorder(OversightSettings settings, OversightConfigLookup configLookup, SamplingKey samplingKey,
            IToolQuotaStore quotaStore, IAuditSampleStore sampleStore, OversightMetrics metrics) {
        this(settings, configLookup, samplingKey, quotaStore, sampleStore, metrics, newWriter(), Clock.systemUTC());
    }

    /** Test seam: an explicit writer and clock. */
    public SampledAuditRecorder(OversightSettings settings, OversightConfigLookup configLookup, SamplingKey samplingKey,
            IToolQuotaStore quotaStore, IAuditSampleStore sampleStore, OversightMetrics metrics, Executor writer, Clock clock) {
        this.settings = settings;
        this.configLookup = configLookup;
        this.samplingKey = samplingKey;
        this.quotaStore = quotaStore;
        this.sampleStore = sampleStore;
        this.metrics = metrics;
        this.writer = writer;
        this.clock = clock;
    }

    private static ThreadPoolExecutor newWriter() {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
            Thread thread = new Thread(runnable, "eddi-audit-sample-writer");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    @PreDestroy
    void shutdown() {
        if (writer instanceof ThreadPoolExecutor executor) {
            executor.shutdown();
        }
    }

    @Override
    public void afterAllowed(IConversationMemory memory, ToolExecutionRequest request, Map<String, String> toolSources,
                             Map<String, String> toolEndpoints, ToolApprovalsConfig effectiveToolApprovals,
                             List<Map<String, Object>> trace) {
        try {
            consider(memory, request, toolSources, toolEndpoints, effectiveToolApprovals, trace);
        } catch (RuntimeException e) {
            LOGGER.debugf("Sampled audit skipped a call after an error: %s", e.getMessage());
        }
    }

    /**
     * The decision for one call; returns the sample handed to the writer, or null.
     */
    AuditSample consider(IConversationMemory memory, ToolExecutionRequest request, Map<String, String> toolSources,
                         Map<String, String> toolEndpoints, ToolApprovalsConfig effectiveToolApprovals, List<Map<String, Object>> trace) {
        if (!settings.samplesEnabled() || memory == null || request == null || request.name() == null || request.id() == null
                || request.id().isBlank()) {
            return null;
        }
        String agentId = memory.getAgentId();
        OversightConfig oversight = configLookup.oversight(agentId, memory.getAgentVersion());
        SampledAuditConfig audit = oversight != null ? oversight.getSampledAudit() : null;
        if (audit == null || audit.getRules() == null || audit.getRules().isEmpty()) {
            return null;
        }
        Map<String, String> sources = toolSources != null ? toolSources : Map.of();
        Map<String, String> endpoints = toolEndpoints != null ? toolEndpoints : Map.of();
        List<String> addresses = ToolApprovalGate.addressesOf(request.name(), sources, endpoints);
        SampledAuditConfig.Rule rule = matchingRule(audit.getRules(), addresses);
        if (rule == null || rule.getRate() == null || rule.getRate() <= 0.0) {
            return null;
        }
        Map<String, Object> result = resultOf(trace, request.name());
        if (result == null || Boolean.TRUE.equals(result.get("mocked")) || !ConversationGroups.resolveGroupIds(memory).isEmpty()) {
            metrics.sample("skipped");
            return null;
        }
        double u = samplingKey.fraction(agentId, memory.getConversationId(), memory.size(), request.id(), request.name());
        if (!SamplingKey.sampled(u, rule.getRate())) {
            return null;
        }
        String category = rule.effectiveCategory();
        Instant now = clock.instant();
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        IToolQuotaStore.Acquisition acquisition = quotaStore.tryAcquire(
                new ToolQuotaKey(agentId, "sample:" + category, CAP_SUBJECT, dayStart.toEpochMilli()),
                audit.effectiveMaxSamplesPerCategoryPerDay(), dayStart.plus(Duration.ofDays(2)));
        if (!acquisition.granted()) {
            metrics.sample("capped");
            return null;
        }
        String raw = request.arguments() != null ? request.arguments() : "";
        String resultText = audit.effectiveIncludeResult() && result.get("result") != null
                ? capUtf8(SecretRedactionFilter.redact(String.valueOf(result.get("result"))), RESULT_MAX_BYTES)
                : null;
        AuditSample sample = new AuditSample(UUID.randomUUID().toString(), agentId, memory.getAgentVersion(), memory.getConversationId(),
                memory.getUserId(), request.id(), request.name(), sources.get(request.name()), endpoints.get(request.name()), category,
                rule.getMatch(), ToolApprovalGate.firstExemptMatch(request.name(), sources, endpoints, effectiveToolApprovals),
                capUtf8(SecretRedactionFilter.redact(raw), PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES), EditMetrics.sha256Hex(raw),
                resultText, now, now.plus(audit.effectiveReviewWithin()), AuditSample.Status.PENDING, null, null, null, null, 0L);
        try {
            writer.execute(() -> write(sample));
            metrics.sample("sampled");
            return sample;
        } catch (RejectedExecutionException full) {
            metrics.sample("dropped");
            long last = lastDropWarning.get();
            long nowMs = System.currentTimeMillis();
            if (nowMs - last > 60_000 && lastDropWarning.compareAndSet(last, nowMs)) {
                LOGGER.warn("The audit-sample write queue is full; samples are being dropped (eddi_audit_sample_total{outcome=dropped})");
            }
            return null;
        }
    }

    private void write(AuditSample sample) {
        try {
            sampleStore.insert(sample);
        } catch (RuntimeException e) {
            metrics.sample("dropped");
            LOGGER.warnf("Could not store an audit sample of agent %s: %s", sanitize(sample.agentId()), e.getMessage());
        }
    }

    /** The most specific rule matching the call: fewest wildcards, then longest. */
    static SampledAuditConfig.Rule matchingRule(List<SampledAuditConfig.Rule> rules, List<String> addresses) {
        return rules.stream().filter(r -> r != null && r.getMatch() != null && !r.getMatch().isBlank())
                .filter(r -> addresses.stream().anyMatch(a -> ToolApprovalPatterns.matches(r.getMatch(), a)))
                .max(Comparator.comparingInt(SampledAuditRecorder::specificity)).orElse(null);
    }

    private static int specificity(SampledAuditConfig.Rule rule) {
        String match = rule.getMatch();
        int wildcards = (int) match.chars().filter(c -> c == '*').count();
        return -wildcards * 1024 + match.length();
    }

    /**
     * The call's own last trace entry when it is a result; null when the call
     * produced none (refused, rate-limited, errored) — those are not sampled.
     */
    static Map<String, Object> resultOf(List<Map<String, Object>> trace, String toolName) {
        if (trace == null) {
            return null;
        }
        for (int i = trace.size() - 1; i >= 0; i--) {
            Map<String, Object> step = trace.get(i);
            if (step != null && toolName.equals(step.get("tool"))) {
                if ("tool_result_guardrail".equals(step.get("type"))) {
                    // A result guardrail's note follows the result it inspected.
                    continue;
                }
                return "tool_result".equals(step.get("type")) ? step : null;
            }
        }
        return null;
    }

    static String capUtf8(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        // Do not cut inside a multi-byte sequence.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
