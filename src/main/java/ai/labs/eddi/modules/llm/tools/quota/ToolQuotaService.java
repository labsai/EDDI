/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Per-subject, per-window caps on tool calls ({@code toolQuotas}).
 * <p>
 * The service resolves which rule governs a call, who the call is counted
 * against, and which window it falls in; the {@link IToolQuotaStore} does the
 * atomic counting. It holds no per-call state: each call that is subject to a
 * rule gets its own {@link ToolQuotaGate}.
 * <p>
 * Two entry points, deliberately separate:
 * <ul>
 * <li>{@link #gateFor} — the reservation made immediately before a tool runs.
 * It is the only place a call is counted, so a cache hit, a rate-limited or
 * over-budget call, a rejected approval and a call that never ran are never
 * counted. {@code ToolExecutionService} owns the moment; every tool source,
 * live and HITL-resumed alike, passes through it.</li>
 * <li>{@link #precheck} — a read, no increment, that lets the approval gate
 * refuse an exhausted call instead of asking a human to approve something that
 * will then be refused.</li>
 * </ul>
 * <p>
 * Fails closed. When the counter store cannot answer, the call is not run and
 * the model is told the limit could not be checked.
 */
@ApplicationScoped
public class ToolQuotaService {

    private static final Logger LOGGER = Logger.getLogger(ToolQuotaService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * At most one store-failure WARN per this long; every failure is still counted.
     */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final IToolQuotaStore store;
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AtomicLong lastWarnMs = new AtomicLong(Long.MIN_VALUE);

    @Inject
    public ToolQuotaService(IToolQuotaStore store, ITemplatingEngine templatingEngine, IMemoryItemConverter memoryItemConverter,
            MeterRegistry meterRegistry) {
        this(store, templatingEngine, memoryItemConverter, meterRegistry, Clock.systemUTC());
    }

    /** With an injectable clock, for tests of the window boundary. */
    public ToolQuotaService(IToolQuotaStore store, ITemplatingEngine templatingEngine, IMemoryItemConverter memoryItemConverter,
            MeterRegistry meterRegistry, Clock clock) {
        this.store = store;
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * The gate for one tool call, or {@code null} when no quota applies to it — no
     * {@code toolQuotas}, or no rule for this tool. In that case the store is never
     * read.
     *
     * @param failureDetector
     *            decides, from the tool's raw result, whether the call failed and
     *            its reservation is to be given back (an HTTP tool's non-2xx or
     *            transport failure, a tool-reported error)
     */
    public ToolQuotaGate gateFor(ToolQuotaConfig config, ToolInvocation invocation, IConversationMemory memory,
                                 Predicate<String> failureDetector) {
        ToolQuotaRules.Resolved rule;
        try {
            rule = ToolQuotaRules.resolve(config, invocation.dispatchName(), invocation.canonicalName());
        } catch (IllegalStateException unusable) {
            // A stored rule that cannot be applied must not run unbounded.
            warnUnavailable(invocation.dispatchName(), memory, "unusable rule", unusable);
            return ToolQuotaGate.unusable(this, invocation.dispatchName());
        }
        if (rule == null) {
            return null;
        }
        return new ToolQuotaGate(this, config, rule, invocation.dispatchName(), memory, failureDetector);
    }

    /**
     * Whether a call is already over its quota, read without counting it.
     *
     * @return the refusal to hand the model, or empty when the call may go ahead
     *         (including when no rule applies)
     */
    public Optional<String> precheck(ToolQuotaConfig config, String dispatchName, String canonicalName, IConversationMemory memory) {
        ToolQuotaRules.Resolved rule;
        try {
            rule = ToolQuotaRules.resolve(config, dispatchName, canonicalName);
        } catch (IllegalStateException unusable) {
            warnUnavailable(dispatchName, memory, "unusable rule", unusable);
            count(dispatchName, "unavailable");
            return Optional.of(unavailableRefusal());
        }
        if (rule == null) {
            return Optional.empty();
        }
        try {
            ToolQuotaKey key = keyFor(rule, config, memory);
            if (store.used(key) >= rule.limit()) {
                count(dispatchName, "precheck_refused");
                return Optional.of(overLimitRefusal(rule, resetAt(key, rule)));
            }
            return Optional.empty();
        } catch (RuntimeException | ITemplatingEngine.TemplateEngineException e) {
            failedToCheck(dispatchName, memory, "precheck", e);
            return Optional.of(unavailableRefusal());
        }
    }

    // === Used by the gate ===

    ToolQuotaKey keyFor(ToolQuotaRules.Resolved rule, ToolQuotaConfig config, IConversationMemory memory)
            throws ITemplatingEngine.TemplateEngineException {
        return new ToolQuotaKey(memory.getAgentId(), rule.counter(), subjectFor(config, memory),
                ToolQuotaRules.windowStart(clock.instant(), rule.window()));
    }

    /**
     * Who a call is counted against: the rendered {@code subject} template, else
     * the conversation's user id, else (an unauthenticated caller) the conversation
     * itself — so an anonymous caller is capped per conversation instead of every
     * anonymous caller sharing one counter, or being refused outright.
     */
    String subjectFor(ToolQuotaConfig config, IConversationMemory memory) throws ITemplatingEngine.TemplateEngineException {
        String subject = null;
        String template = config.getSubject();
        if (template != null && !template.isBlank()) {
            Map<String, Object> data = memoryItemConverter.convert(memory);
            String rendered = templatingEngine.processTemplate(template, data);
            subject = rendered == null ? null : rendered.strip();
        }
        if (subject == null || subject.isEmpty()) {
            subject = userSubject(memory.getUserId());
        }
        if (subject == null || subject.isEmpty()) {
            subject = "conversation:" + memory.getConversationId();
        }
        return capSubject(subject);
    }

    /**
     * A user id in the form counters store it as a subject — stripped and capped —
     * so GDPR erasure and export find the same rows; {@code null} for a blank id.
     */
    public static String userSubject(String userId) {
        if (userId == null) {
            return null;
        }
        String subject = userId.strip();
        return subject.isEmpty() ? null : capSubject(subject);
    }

    /**
     * Keeps a subject within {@link ToolQuotaConfig#MAX_SUBJECT_LENGTH}: a longer
     * one becomes a prefix plus a digest of the whole, so two long subjects that
     * share a prefix still count separately.
     */
    static String capSubject(String subject) {
        if (subject.length() <= ToolQuotaConfig.MAX_SUBJECT_LENGTH) {
            return subject;
        }
        return subject.substring(0, 200) + "#" + digest(subject).substring(0, 32);
    }

    Instant expiresAt(ToolQuotaKey key, ToolQuotaRules.Resolved rule) {
        return resetAt(key, rule).plus(ToolQuotaRules.RETENTION_GRACE);
    }

    Instant resetAt(ToolQuotaKey key, ToolQuotaRules.Resolved rule) {
        return ToolQuotaRules.windowEnd(key.windowStart(), rule.window());
    }

    IToolQuotaStore store() {
        return store;
    }

    void count(String tool, String outcome) {
        meterRegistry.counter("eddi.tool.quota.decisions", "tool", tool == null ? "unknown" : tool, "outcome", outcome).increment();
    }

    void countStoreFailure(String operation) {
        meterRegistry.counter("eddi.tool.quota.store.failures", "operation", operation).increment();
    }

    /**
     * Records that the limit could not be checked: counted every time, logged at
     * WARN at most once a minute. The subject is only ever logged as a short
     * digest, never raw.
     */
    void failedToCheck(String tool, IConversationMemory memory, String operation, Throwable cause) {
        countStoreFailure(operation);
        count(tool, "unavailable");
        warnUnavailable(tool, memory, operation, cause);
    }

    private void warnUnavailable(String tool, IConversationMemory memory, String operation, Throwable cause) {
        long now = clock.millis();
        long last = lastWarnMs.get();
        boolean due = last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS;
        if (!due || !lastWarnMs.compareAndSet(last, now)) {
            LOGGER.debugf(cause, "Tool quota %s failed for tool '%s' (already reported)", operation, sanitize(tool));
            return;
        }
        String user = memory == null || memory.getUserId() == null ? "none" : digest(memory.getUserId()).substring(0, 8);
        LOGGER.warnf(cause,
                "Tool quota %s could not be answered; tool '%s' is refused (fail closed). agent=%s conversation=%s user#%s. "
                        + "Further failures in the next minute are counted in eddi.tool.quota.store.failures and not logged.",
                operation, sanitize(tool), memory == null ? null : sanitize(memory.getAgentId()),
                memory == null ? null : sanitize(memory.getConversationId()), user);
    }

    // === Refusal wording ===

    /**
     * What the model is told when the quota is spent. A {@code NOT_EXECUTED}
     * envelope like the call caps, so the repeat guard and the outcome classifier
     * read it as "the call never ran" and not as a failure to retry.
     */
    String overLimitRefusal(ToolQuotaRules.Resolved rule, Instant resetAt) {
        return envelope("Not run: the " + windowLabel(rule.window()) + " limit for this tool (" + rule.limit() + ") is reached; it resets at "
                + resetAt + ". This is final: do not call this tool again in this conversation until then, and tell the user the limit is "
                + "reached.");
    }

    static String unavailableRefusal() {
        return envelope("Not run: the usage limit for this tool could not be checked right now, so the call was not made. Do not retry it "
                + "in this turn; tell the user to try again later.");
    }

    private static String envelope(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "NOT_EXECUTED");
        body.put("reason", reason);
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"status\":\"NOT_EXECUTED\",\"reason\":\"Not run: usage limit.\"}";
        }
    }

    private static String windowLabel(Duration window) {
        if (window.equals(Duration.ofDays(1))) {
            return "daily";
        }
        if (window.equals(Duration.ofHours(1))) {
            return "hourly";
        }
        if (window.equals(Duration.ofDays(7))) {
            return "weekly";
        }
        return window.toString();
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
