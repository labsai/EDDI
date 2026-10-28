/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.List;
import java.util.Map;

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

    /**
     * What replaces user input that must not be persisted: a client-flagged secret
     * input in the echoed output, and the raw input once a {@code scope: "secret"}
     * property has vaulted it. {@code input:initial} holding exactly this value is
     * how later stages (the audit ledger) know the turn's input was a secret.
     */
    public static final String SECRET_INPUT_PLACEHOLDER = "<secret input>";

    /**
     * What replaces a context value the client marked {@code "secret": true} once
     * its turn has run — in the stored context entry, and wherever the value was
     * copied to during the turn (step data, conversation output, properties, audit
     * entries). Distinct from {@link #SECRET_INPUT_PLACEHOLDER} because that one on
     * {@code input:initial} tells the audit ledger the INPUT was a secret.
     */
    public static final String SECRET_CONTEXT_PLACEHOLDER = "<secret context>";

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
     * Why the model stopped, when it stopped abnormally: {@code "length"} (cut off
     * at the output-token limit) or {@code "content_filter"} (withheld by the
     * provider). Written by LlmTask only in those two cases; read by the
     * OpenAI-compatible adapter as the response's {@code finish_reason}. Not an
     * {@code audit:} key on purpose — those are withheld from the detailed snapshot
     * the adapter reads.
     */
    public static final String LLM_FINISH_REASON = "llm:finish_reason";

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
     * How much of this step's {@link #AUDIT_COST} has already been charged to the
     * tenant's monthly cost counter ({@code Double}). Written by
     * {@code TurnCostMeter} at every turn-settle point, so a step that settles more
     * than once — a HITL pause settles it part-way, the resume continues
     * accumulating into the same step — is charged only the part not yet metered.
     * <p>
     * Deliberately NOT {@code "audit:cost:..."}: step lookups by key are prefix
     * matches, so a key starting with {@code audit:cost} would be returned by
     * {@code getLatestData(AUDIT_COST)} and read back as the step's cost.
     *
     * @since 6.6.0
     */
    public static final String AUDIT_METERED_COST = "audit:metered_cost";

    /**
     * A tenant cost charge ({@code Double}) that could not be recorded because the
     * quota store was unavailable when the step settled, carried forward until a
     * later settle records it ({@code 0.0} afterwards). Written by
     * {@code TurnCostMeter} on the newest step only — say turns are persisted as
     * appends, so an earlier step would never be rewritten. Undo and redo copy the
     * value of the step they move aside onto the step that becomes current
     * ({@code ConversationMemory.carryPendingCost}), so neither can lose it or
     * revive an older one.
     *
     * @since 6.6.0
     */
    public static final String AUDIT_PENDING_COST = "audit:pending_cost";

    /**
     * Priced spend ({@code Double}) of steps that an undo moved to the redo cache
     * and a new turn then discarded, carried onto that new turn's step by
     * {@code ConversationMemory.startNextStep()}. The money was spent, so the
     * conversation's total — what {@code maxCostPerConversationUsd} is compared
     * against — counts it with the steps' {@link #AUDIT_COST}; without it, say →
     * undo → say reset the conversation's spend.
     * <p>
     * Deliberately NOT {@code "audit:cost:..."}: step lookups by key are prefix
     * matches (see {@link #AUDIT_METERED_COST}).
     *
     * @since 6.7.0
     */
    public static final String AUDIT_DISCARDED_COST = "audit:discarded_cost";

    /**
     * Prefix of the guardrail evidence keys,
     * {@code audit:guardrail:<phase>:<type>}: a {@code List} of {@code {phase,
     * type, action, reason, retryCount, taskId}} records, one per time that
     * guardrail fired this turn. Written by LlmTask, collected by LifecycleManager
     * into {@code AuditEntry.output.guardrails}. Absent when no guardrail is
     * configured or none fired.
     *
     * @since 6.6.0
     */
    public static final String AUDIT_GUARDRAIL_PREFIX = "audit:guardrail:";

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

    // ---- LLM task failure handling ----

    /**
     * Prefix of the step flag {@code llm:fallback:<taskId>} = {@code true}, set
     * when the LLM task served its configured fallback instead of a model answer.
     * The conversation history built for the model skips the assistant turn of a
     * step carrying it, so the model never learns from its own apology.
     */
    public static final String LLM_FALLBACK_PREFIX = "llm:fallback:";

    /**
     * Prefix of the step flag {@code llm:input_withheld:<taskId>} = {@code true},
     * set when an input guardrail of the LLM task blocked the turn's message, or
     * redacted it with a guardrail that does not re-screen history (a
     * {@code classifier}). Every history the engine builds for a model — the
     * message window, the rolling summary's input and the recall tool — replaces
     * that turn's user message (text, attachment extracts and input files) with a
     * placeholder, so a refused message never reaches a model on a later turn.
     * Conversation memory itself keeps the message as the user sent it.
     *
     * @since 6.6.0
     */
    public static final String LLM_INPUT_WITHHELD_PREFIX = "llm:input_withheld:";

    /**
     * Prefix of {@code llm:spend_refused:<taskId>} = {@code {reason, detail}},
     * recorded when the LLM task skipped its model call because a spend limit was
     * already reached ({@code LlmSpendGate}) and served its fallback answer
     * instead. {@code reason} is {@code tenant_budget},
     * {@code tenant_accounting_unavailable} or {@code conversation_ceiling}.
     *
     * @since 6.6.0
     */
    public static final String LLM_SPEND_REFUSED_PREFIX = "llm:spend_refused:";

    /**
     * Prefix of {@code llm:error:<taskId>} = {@code {class, message}} (redacted,
     * truncated), recorded when {@code onError: fallback} absorbed a failure.
     */
    public static final String LLM_ERROR_PREFIX = "llm:error:";

    // ---- Output ----

    /**
     * Output prefix key. Used for dynamic output keys like "output:text:action".
     */
    public static final String OUTPUT_PREFIX = "output";

    /**
     * Quick replies prefix key. Used for dynamic keys like "quickReplies:action".
     */
    public static final String QUICK_REPLIES_PREFIX = "quickReplies";

    /**
     * Output key under which a failed task is reported to the caller —
     * {@code [{type: "errorDigest", taskId, taskType, text}]}. Written by
     * {@code LifecycleManager}; one of the keys a non-detailed response keeps.
     */
    public static final String TASK_ERRORS = "taskErrors";

    // ---- ApiCalls ----

    /** ApiCalls prefix key. Used for dynamic keys like "httpCalls:callName". */
    public static final String HTTP_CALLS_PREFIX = "httpCalls";

    // ---- Agent version ----

    /**
     * The agent version that ran this step. Written by {@code Conversation} at the
     * end of every step, so a conversation that followed its agent across versions
     * can say which version produced each answer — with or without the audit
     * ledger. At the end, not the start: detailed snapshots list a step's data in
     * insertion order and clients read it by position.
     *
     * @since 6.5.0
     */
    public static final MemoryKey<Integer> AGENT_VERSION = MemoryKey.of("agent:version");

    /**
     * {@code {"from": n, "to": m}} on the first step a conversation ran after
     * moving to another, compatible version of its agent. Absent on every other
     * step.
     * <p>
     * Deliberately not {@code agent:version…}: step lookups match keys by PREFIX
     * ({@code ConversationStep#getLatestData}), so a key starting with
     * {@link #AGENT_VERSION}'s would be returned — and removed — in its place.
     *
     * @since 6.5.0
     */
    public static final MemoryKey<Map<String, Integer>> AGENT_VERSION_CHANGE = MemoryKey.of("agent:switch");

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

    // ---- Dynamic agents ----

    /**
     * Agent ids created by {@code create_sub_agent} during this conversation,
     * cumulative across turns. Written by {@code DynamicAgentToolsProvider}, read
     * back by its own seeding pass (so {@code maxCreatedAgentsPerDiscussion} bounds
     * the conversation rather than a single turn) and by
     * {@code GroupLifecycleOps#propagateDynamicAgentTracking}, which folds it into
     * the group's tracking for ephemeral cleanup.
     */
    public static final String DYNAMIC_CREATED_AGENT_IDS = "dynamic:created_agent_ids";

    /**
     * Agent ids the creating model asked to keep past the discussion
     * ({@code retain=true}). Exempt from ephemeral cleanup under the
     * {@code AGENT_DECIDES} lifecycle policy.
     */
    public static final String DYNAMIC_RETAINED_AGENT_IDS = "dynamic:retained_agent_ids";

    /**
     * Agent ids torn down by {@code teardown_agent} during this conversation.
     * Subtracted from {@link #DYNAMIC_CREATED_AGENT_IDS} when seeding, so a
     * teardown frees a {@code maxCreatedAgentsPerDiscussion} slot instead of the id
     * reappearing on the next turn's seed, and applied to the group's tracking by
     * {@code GroupLifecycleOps#propagateDynamicAgentTracking}.
     * <p>
     * A plain {@code String} rather than a {@code MemoryKey}: these three are read
     * positionally out of a serialized snapshot's step data by the group layer,
     * which sees keys as strings and never resolves a typed {@code MemoryKey}.
     */
    public static final String DYNAMIC_TORN_DOWN_AGENT_IDS = "dynamic:torn_down_agent_ids";
}
