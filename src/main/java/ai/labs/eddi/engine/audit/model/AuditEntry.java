package ai.labs.eddi.engine.audit.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable record representing one pipeline task's audit data for a single conversation step.
 * <p>
 * Each entry captures the complete context of a lifecycle task execution:
 * what was read (input), what was produced (output), LLM-specific details,
 * tool calls, actions emitted, cost, and timing.
 * <p>
 * Entries are <strong>write-once</strong>: once persisted to the audit ledger,
 * they must never be modified or deleted. The {@code hmac} field provides
 * tamper detection — if any field is altered after storage, the HMAC will
 * no longer verify.
 * <p>
 * This record implements Tier 3 ("Telemetry Ledger") of the EDDI 3-Tier
 * CQRS architecture and satisfies EU AI Act Articles 17/19 requirements
 * for immutable decision traceability.
 *
 * @param id             Auto-generated UUID
 * @param conversationId The conversation this entry belongs to
 * @param botId          Bot identifier
 * @param botVersion     Bot version
 * @param userId         User identifier
 * @param environment    Deployment environment (e.g. "production")
 * @param stepIndex      0-based step position in the conversation
 * @param taskId         Lifecycle task ID (e.g. "ai.labs.parser")
 * @param taskType       Task type (e.g. "expressions", "langchain")
 * @param taskIndex      0-based task position in the pipeline
 * @param durationMs     Task execution time in milliseconds
 * @param input          Key data read by the task (user input, actions)
 * @param output         Key data written by the task (output text, tool results)
 * @param llmDetail      LLM-specific data: compiled prompt, model response, token usage (null for non-LLM tasks)
 * @param toolCalls      Tool execution data: name, args, result, cost (null if no tools called)
 * @param actions        Actions emitted by this task
 * @param cost           Monetary cost of this step (0.0 if free)
 * @param timestamp      When the task completed
 * @param hmac           HMAC-SHA256 integrity hash of all fields above
 * @author ginccc
 * @since 6.0.0
 */
public record AuditEntry(
        String id,
        String conversationId,
        String botId,
        Integer botVersion,
        String userId,
        String environment,
        int stepIndex,
        String taskId,
        String taskType,
        int taskIndex,
        long durationMs,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> llmDetail,
        Map<String, Object> toolCalls,
        List<String> actions,
        double cost,
        Instant timestamp,
        String hmac
) {

    /**
     * Return a copy of this entry with the environment field set.
     * Used by ConversationService to enrich entries built by LifecycleManager.
     */
    public AuditEntry withEnvironment(String env) {
        return new AuditEntry(
                id, conversationId, botId, botVersion, userId, env,
                stepIndex, taskId, taskType, taskIndex, durationMs,
                input, output, llmDetail, toolCalls, actions,
                cost, timestamp, hmac);
    }

    /**
     * Return a copy of this entry with the HMAC integrity hash set.
     * Used by AuditLedgerService after computing the HMAC.
     */
    public AuditEntry withHmac(String hmacValue) {
        return new AuditEntry(
                id, conversationId, botId, botVersion, userId, environment,
                stepIndex, taskId, taskType, taskIndex, durationMs,
                input, output, llmDetail, toolCalls, actions,
                cost, timestamp, hmacValue);
    }
}
