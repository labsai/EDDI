/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable record representing one workflow task's audit data for a single
 * conversation step.
 * <p>
 * Each entry captures the complete context of a lifecycle task execution: what
 * was read (input), what was produced (output), LLM-specific details, tool
 * calls, actions emitted, cost, and timing.
 * <p>
 * Entries are <strong>write-once</strong>: once persisted to the audit ledger,
 * they must never be modified or deleted. The {@code hmac} field provides
 * tamper detection — if any field is altered after storage, the HMAC will no
 * longer verify.
 * <p>
 * This record implements Tier 3 ("Telemetry Ledger") of the EDDI 3-Tier CQRS
 * architecture and satisfies EU AI Act Articles 17/19 requirements for
 * immutable decision traceability.
 *
 * @param id
 *            Auto-generated UUID
 * @param conversationId
 *            The conversation this entry belongs to
 * @param agentId
 *            Agent identifier
 * @param agentVersion
 *            Agent version
 * @param userId
 *            User identifier
 * @param environment
 *            Deployment environment (e.g. "production")
 * @param stepIndex
 *            0-based step position in the conversation
 * @param taskId
 *            Lifecycle task ID (e.g. "ai.labs.parser")
 * @param taskType
 *            Task type (e.g. "expressions", "langchain")
 * @param taskIndex
 *            0-based task position in the workflow
 * @param durationMs
 *            Task execution time in milliseconds
 * @param input
 *            Key data read by the task (user input, actions)
 * @param output
 *            Key data written by the task (output text, tool results)
 * @param llmDetail
 *            LLM-specific data: compiled prompt, model response, token usage
 *            (null for non-LLM tasks)
 * @param toolCalls
 *            Tool execution data: name, args, result, cost (null if no tools
 *            called)
 * @param actions
 *            Actions emitted by this task
 * @param cost
 *            Monetary cost of this step (0.0 if free)
 * @param timestamp
 *            When the task completed
 * @param hmac
 *            HMAC-SHA256 integrity hash of all fields above
 * @param agentSignature
 *            Optional cryptographic agent signature for tamper-evident identity
 *            proof (null when signing not configured)
 * @param sequence
 *            0-based, gap-free position of this entry within its conversation,
 *            or {@link #UNSEQUENCED} when the backing store cannot persist it.
 *            The value is part of the signed payload, so an entry cannot be
 *            renumbered to close the gap left by a deleted predecessor — which
 *            is what makes DELETION and REORDERING detectable at all (a
 *            per-entry HMAC alone only catches in-place field edits).
 * @author ginccc
 * @since 6.0.0
 */
public record AuditEntry(String id, String conversationId, String agentId, Integer agentVersion, String userId, String environment, int stepIndex,
        String taskId, String taskType, int taskIndex, long durationMs, Map<String, Object> input, Map<String, Object> output,
        Map<String, Object> llmDetail, Map<String, Object> toolCalls, List<String> actions, double cost, Instant timestamp, String hmac,
        String agentSignature, long sequence) {

    /**
     * Sentinel for {@link #sequence}: this entry does not participate in a
     * conversation chain. Used for entries with no conversation (compliance events)
     * and by stores that do not persist the sequence — a store that dropped a real
     * sequence on write would make every one of its rows read as tampered, so the
     * ledger only assigns one when the store advertises support for it.
     */
    public static final long UNSEQUENCED = -1L;

    /**
     * Compatibility constructor for the pre-sequence 20-component shape. Produces
     * an {@link #UNSEQUENCED} entry — every existing call site (pipeline
     * collection, store deserialization, tests) keeps compiling and keeps its
     * previous behaviour.
     */
    public AuditEntry(String id, String conversationId, String agentId, Integer agentVersion, String userId, String environment, int stepIndex,
            String taskId, String taskType, int taskIndex, long durationMs, Map<String, Object> input, Map<String, Object> output,
            Map<String, Object> llmDetail, Map<String, Object> toolCalls, List<String> actions, double cost, Instant timestamp, String hmac,
            String agentSignature) {
        this(id, conversationId, agentId, agentVersion, userId, environment, stepIndex, taskId, taskType, taskIndex, durationMs, input, output,
                llmDetail, toolCalls, actions, cost, timestamp, hmac, agentSignature, UNSEQUENCED);
    }

    /**
     * Return a copy of this entry with the environment field set. Used by
     * ConversationService to enrich entries built by LifecycleManager.
     */
    public AuditEntry withEnvironment(String env) {
        return new AuditEntry(id, conversationId, agentId, agentVersion, userId, env, stepIndex, taskId, taskType, taskIndex, durationMs, input,
                output, llmDetail, toolCalls, actions, cost, timestamp, hmac, agentSignature, sequence);
    }

    /**
     * Return a copy of this entry with the HMAC integrity hash set. Used by
     * AuditLedgerService after computing the HMAC.
     */
    public AuditEntry withHmac(String hmacValue) {
        return new AuditEntry(id, conversationId, agentId, agentVersion, userId, environment, stepIndex, taskId, taskType, taskIndex, durationMs,
                input, output, llmDetail, toolCalls, actions, cost, timestamp, hmacValue, agentSignature, sequence);
    }

    /**
     * Return a copy of this entry with the agent signature set. Used by
     * AuditLedgerService when agent signing is configured.
     */
    public AuditEntry withAgentSignature(String signature) {
        return new AuditEntry(id, conversationId, agentId, agentVersion, userId, environment, stepIndex, taskId, taskType, taskIndex, durationMs,
                input, output, llmDetail, toolCalls, actions, cost, timestamp, hmac, signature, sequence);
    }

    /**
     * Return a copy of this entry with its position in the conversation chain set.
     * Applied by AuditLedgerService <em>before</em> the HMAC is computed, so the
     * sequence is covered by the signature.
     */
    public AuditEntry withSequence(long sequenceValue) {
        return new AuditEntry(id, conversationId, agentId, agentVersion, userId, environment, stepIndex, taskId, taskType, taskIndex, durationMs,
                input, output, llmDetail, toolCalls, actions, cost, timestamp, hmac, agentSignature, sequenceValue);
    }

    /**
     * Return a copy of this entry with the user identifier replaced. The sole
     * permitted content mutation (GDPR Art. 17(3)(e) pseudonymisation).
     */
    public AuditEntry withUserId(String newUserId) {
        return new AuditEntry(id, conversationId, agentId, agentVersion, newUserId, environment, stepIndex, taskId, taskType, taskIndex, durationMs,
                input, output, llmDetail, toolCalls, actions, cost, timestamp, hmac, agentSignature, sequence);
    }
}
