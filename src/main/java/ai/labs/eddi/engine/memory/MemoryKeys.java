/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.List;

/**
 * Central registry of well-known memory keys used across the EDDI lifecycle
 * workflow.
 * <p>
 * Tasks should import these constants instead of declaring local string
 * constants. Keys with a dynamic suffix (e.g.
 * "langchain:trace:&lt;type&gt;:&lt;id&gt;") are registered here as plain
 * {@code String} prefixes rather than {@code MemoryKey<T>} constants, because
 * the full key is only known at runtime.
 *
 * @see MemoryKey
 * @see IConversationMemory.IConversationStep
 */
public final class MemoryKeys {

    private MemoryKeys() {
        // non-instantiable
    }

    // ---- Engine / Conversation ----

    /** User input text. Written by the engine, read by InputParserTask. */
    public static final MemoryKey<String> INPUT = MemoryKey.ofPublic("input");

    /** Original (un-normalized) user input. Written by Conversation. */
    public static final MemoryKey<String> INPUT_INITIAL = MemoryKey.of("input:initial");

    /** Normalized user input (after normalizers). Written by InputParserTask. */
    public static final MemoryKey<String> INPUT_NORMALIZED = MemoryKey.of("input:normalized");

    // ---- Parser ----

    /**
     * Parsed expression string. Written by InputParserTask, read by
     * RulesEvaluationTask.
     */
    public static final MemoryKey<String> EXPRESSIONS_PARSED = MemoryKey.of("expressions:parsed");

    /**
     * Input-to-expression matches. Written by InputParserTask. Maps each matched
     * user input word to the expression it triggered. Useful for debugging which
     * input produced which expression.
     */
    public static final MemoryKey<List<String>> EXPRESSIONS_MATCHES = MemoryKey.of("expressions:matches");

    /** Extracted intent names. Written by InputParserTask. */
    public static final MemoryKey<List<String>> INTENTS = MemoryKey.ofPublic("intents");

    // ---- Behavior Rules ----

    /** Action strings emitted by behavior rules. Read by most downstream tasks. */
    public static final MemoryKey<List<String>> ACTIONS = MemoryKey.ofPublic("actions");

    // ---- Langchain ----

    /** LLM response text. Written by LlmTask. */
    public static final MemoryKey<String> LANGCHAIN = MemoryKey.ofPublic("langchain");

    /** System message for the LLM. Written by LlmTask. */
    public static final MemoryKey<String> SYSTEM_MESSAGE = MemoryKey.of("systemMessage");

    /** Prompt sent to the LLM. Written by LlmTask. */
    public static final MemoryKey<String> PROMPT = MemoryKey.of("prompt");

    /**
     * Prefix for LLM tool-execution trace keys. The full key shape is
     * {@code langchain:trace:<modelType>:<configTaskId>} — LlmTask writes one such
     * key <em>per LLM config task</em> it executes, so consumers must aggregate
     * over all matching keys rather than taking the latest one.
     * <p>
     * Written by LlmTask (executeTask / executeResume); read by LifecycleManager
     * (the {@code task_complete} SSE summary) and by RestToolHistory (replay of
     * persisted conversation snapshots). The literal value is part of the persisted
     * snapshot format and must not be changed.
     * <p>
     * Deliberately does <em>not</em> match the sibling keys
     * {@code langchain:cascade:trace:}, {@code rag:trace:} and
     * {@code rag:httpcall:trace:}.
     *
     * @since 6.1.0
     */
    public static final String LANGCHAIN_TRACE_PREFIX = "langchain:trace:";

    /**
     * Lifecycle task type reported by LlmTask. Used to gate reads of
     * {@link #LANGCHAIN_TRACE_PREFIX} keys, which linger in the conversation step
     * and would otherwise be attributed to every task that runs after the LLM task
     * in the same step.
     *
     * @since 6.1.0
     */
    public static final String TASK_TYPE_LANGCHAIN = "langchain";

    // ---- Audit ledger ----

    /**
     * Fully rendered system message + prompt of the last LLM call in this step.
     * Written by LlmTask (executeTask and executeResume) only when an audit
     * collector is attached; read by LifecycleManager, which gates the ENTIRE
     * {@code llmDetail} block of the audit entry on its presence — a path that
     * calls the model without writing this key produces an audit entry with no LLM
     * evidence at all.
     *
     * @since 6.1.0
     */
    public static final String AUDIT_COMPILED_PROMPT = "audit:compiled_prompt";

    /** Final LLM response text for the audit ledger. Written by LlmTask. */
    public static final String AUDIT_MODEL_RESPONSE = "audit:model_response";

    /**
     * Model that produced the response (the cascade winner when a cascade ran).
     * Written by LlmTask.
     */
    public static final String AUDIT_MODEL_NAME = "audit:model_name";

    /**
     * Turn-total token usage as {@code {inputTokens, outputTokens, totalTokens}}.
     * Accumulated by LlmTask across every LLM call of the step (sub-tasks, cascade
     * steps, tool-loop iterations) — {@code getLatestData} is last-write-wins, so
     * every contributor must read-modify-write. Read by LifecycleManager into
     * {@code llmDetail.tokenUsage}.
     *
     * @since 6.1.0
     */
    public static final String AUDIT_TOKEN_USAGE = "audit:token_usage";

    /**
     * Turn-total tool-execution evidence, shaped {@code {"calls": [ … ]}} where
     * each entry is a tool-trace record augmented with its originating
     * {@code llmTaskId}. Accumulated by LlmTask, read by LifecycleManager into
     * {@code AuditEntry.toolCalls}.
     *
     * @since 6.1.0
     */
    public static final String AUDIT_TOOL_CALLS = "audit:tool_calls";

    /**
     * Turn-total dollar cost ({@code Double}) — configured cascade LLM pricing plus
     * tracked tool cost. Accumulated by LlmTask, read by LifecycleManager into
     * {@code AuditEntry.cost}. Absent when nothing priced ran, which the reader
     * treats as {@code 0.0}.
     *
     * @since 6.1.0
     */
    public static final String AUDIT_COST = "audit:cost";

    /**
     * Confidence of the accepted cascade step, as a {@code Double}. Written by
     * LlmTask when a model cascade ran; read by LifecycleManager for both the
     * {@code task_complete} SSE summary and {@code llmDetail.confidence}.
     *
     * @since 6.1.0
     */
    public static final String AUDIT_CONFIDENCE = "audit:confidence";

    /**
     * Human-readable description of the winning cascade step
     * ({@code provider/model (step N)}). Written by LlmTask, read by
     * LifecycleManager into {@code llmDetail.cascadeModel}.
     *
     * @since 6.1.0
     */
    public static final String AUDIT_CASCADE_MODEL = "audit:cascade_model";

    // ---- Output ----

    /**
     * Output prefix key. Used for dynamic output keys like "output:text:action".
     */
    public static final String OUTPUT_PREFIX = "output";

    /**
     * Quick replies prefix key. Used for dynamic keys like "quickReplies:action".
     */
    public static final String QUICK_REPLIES_PREFIX = "quickReplies";

    // ---- ApiCalls ----

    /** ApiCalls prefix key. Used for dynamic keys like "httpCalls:callName". */
    public static final String HTTP_CALLS_PREFIX = "httpCalls";

    // ---- Properties ----

    /** Context prefix key. Used for dynamic keys like "context:output". */
    public static final String CONTEXT_PREFIX = "context";

    /** Extracted properties. Written by PropertySetterTask. */
    public static final MemoryKey<List<?>> PROPERTIES_EXTRACTED = MemoryKey.ofPublic("properties:extracted");

    // ---- Attachments (Multimodal) ----

    /**
     * Binary attachment references for the current step. Stored as
     * {@code List<Attachment>} metadata references — never inline base64. Written
     * by the REST layer on multipart upload, read by LlmTask for multimodal LLM
     * forwarding and by ContentTypeMatcher for routing.
     *
     * @since 6.0.0
     */
    public static final MemoryKey<List<?>> ATTACHMENTS = MemoryKey.ofPublic("attachments");

    /**
     * Human-readable notes for attachments that were dropped, skipped, or failed to
     * resolve/forward this turn (unresolvable stored ref, per-turn cap reached,
     * capability gate, oversize). Non-public — surfaced to the LLM as a note and
     * available for audit, never silently discarded.
     *
     * @since 6.1.0
     */
    public static final MemoryKey<List<String>> ATTACHMENT_ERRORS = MemoryKey.of("attachments:errors");

    /**
     * Text extracted from attachments this turn (PDF text-fallback, inlined text
     * documents), one entry per attachment as {@code "fileName: <text>"}.
     * Non-public — stitched into that turn's user message when history is rebuilt
     * so later turns retain the content, while the visible transcript stays clean.
     *
     * @since 6.1.0
     */
    public static final MemoryKey<List<String>> ATTACHMENT_EXTRACTS = MemoryKey.of("attachments:extracts");
}
