/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_CASCADE_MODEL;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_COMPILED_PROMPT;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_CONFIDENCE;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_COST;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_MODEL_NAME;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_MODEL_RESPONSE;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_TOKEN_USAGE;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_TOOL_CALLS;
import static ai.labs.eddi.engine.memory.MemoryKeys.LANGCHAIN_TRACE_PREFIX;
import static ai.labs.eddi.engine.memory.MemoryKeys.TASK_TYPE_LANGCHAIN;
import static ai.labs.eddi.utils.LifecycleUtilities.createComponentKey;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

import org.jboss.logging.Logger;

/**
 * Executes the Lifecycle Workflow - EDDI's core processing engine.
 *
 * <p>
 * The LifecycleManager is responsible for executing an agent's configured
 * sequence of {@link ILifecycleTask} components, passing conversation state
 * through each task in order.
 * </p>
 *
 * <h2>Lifecycle Workflow Concept</h2>
 * <p>
 * Instead of hard-coded Agent logic, EDDI processes conversations through a
 * configurable workflow of tasks:
 * </p>
 *
 * <pre>
 * User Input → Parser → Behavior Rules → API Calls → LLM → Output Generation
 * </pre>
 *
 * <p>
 * Each task transforms the {@link IConversationMemory} object, building up the
 * conversation state step by step.
 * </p>
 *
 * <h2>Key Features</h2>
 * <ul>
 * <li><strong>Sequential Execution</strong>: Tasks execute in order, each
 * building on previous results</li>
 * <li><strong>Stateless Design</strong>: Tasks don't maintain state; all state
 * is in the memory object</li>
 * <li><strong>Interruptible</strong>: Workflow can stop early if
 * STOP_CONVERSATION action is triggered</li>
 * <li><strong>Selective Execution</strong>: Can execute only a subset of tasks
 * starting from a specific type</li>
 * <li><strong>Component-Based</strong>: Each task has an associated component
 * (config/resource) loaded from the workflow</li>
 * </ul>
 *
 * <h2>Example Flow</h2>
 *
 * <pre>{@code
 * // 1. User sends message
 * memory.getCurrentStep().storeData("input", "What's the weather?");
 *
 * // 2. Parser task extracts entities
 * memory.getCurrentStep().storeData("expressions", ["question(what)", "entity(weather)"]);
 *
 * // 3. Behavior rules evaluate conditions and trigger actions
 * memory.getCurrentStep().storeData("actions", ["httpcall(weather-api)"]);
 *
 * // 4. HTTP Calls task executes API call
 * memory.getCurrentStep().storeData("weatherData", {temp: 75, condition: "sunny"});
 *
 * // 5. Output task generates response
 * memory.getCurrentStep().storeData("output", ["The weather is sunny with 75°F"]);
 * }</pre>
 *
 * @author ginccc
 * @see ILifecycleTask
 * @see IConversationMemory
 * @see ai.labs.eddi.engine.runtime.internal.ConversationCoordinator
 */
public class LifecycleManager implements ILifecycleManager {

    private static final Logger LOGGER = Logger.getLogger(LifecycleManager.class);

    /** Maximum length for error digest message shown to the LLM. */
    private static final int MAX_ERROR_DIGEST_LENGTH = 200;

    // Cached Micrometer meters keyed by "taskId|taskType" to avoid per-invocation
    // builder allocation on the hot path.
    private static final ConcurrentHashMap<String, Timer> TASK_TIMERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Counter> TASK_ERROR_COUNTERS = new ConcurrentHashMap<>();

    /**
     * The ordered list of lifecycle tasks to execute. Tasks are added during Agent
     * initialization based on package configuration.
     */
    private final List<ILifecycleTask> lifecycleTasks;

    /**
     * Cache of task-specific components (configurations, resources, etc.).
     * Components are loaded once and reused across executions for performance.
     */
    private final IComponentCache componentCache;

    /**
     * Identifier of the workflow this lifecycle manager belongs to. Used for
     * component cache lookups.
     */
    private final IResourceStore.IResourceId workflowId;

    public LifecycleManager(IComponentCache componentCache, IResourceStore.IResourceId workflowId) {
        this.componentCache = componentCache;
        this.workflowId = workflowId;

        lifecycleTasks = new LinkedList<>();
    }

    /**
     * Executes the lifecycle workflow, transforming the conversation memory.
     *
     * <p>
     * This method iterates through the configured lifecycle tasks, executing each
     * one in sequence. Each task reads from and writes to the conversation memory,
     * building up the conversation state progressively.
     * </p>
     *
     * <h3>Execution Flow</h3>
     * <ol>
     * <li>Determine which tasks to execute (all or filtered by type)</li>
     * <li>For each task:
     * <ul>
     * <li>Check if thread has been interrupted (allows graceful shutdown)</li>
     * <li>Retrieve task's component from cache</li>
     * <li>Execute task with memory and component</li>
     * <li>Check if STOP_CONVERSATION action was triggered</li>
     * </ul>
     * </li>
     * <li>If STOP_CONVERSATION action found, throw ConversationStopException</li>
     * </ol>
     *
     * <h3>Selective Execution</h3>
     * <p>
     * If {@code lifecycleTaskTypes} is provided, only tasks starting from the first
     * matching type will be executed. This allows partial workflow execution,
     * useful for debugging or specialized processing.
     * </p>
     *
     * <h3>Example</h3>
     *
     * <pre>{@code
     * // Execute full workflow
     * lifecycleManager.executeLifecycle(memory, null);
     *
     * // Execute only from behavior rules onward
     * lifecycleManager.executeLifecycle(memory, List.of("behavior_rules"));
     * }</pre>
     *
     * @param conversationMemory
     *            the conversation state to transform
     * @param lifecycleTaskTypes
     *            optional filter to execute only specific task types
     * @throws LifecycleException
     *             if any task execution fails
     * @throws ConversationStopException
     *             if STOP_CONVERSATION action is triggered
     */
    @SuppressWarnings("null") // Objects.requireNonNullElse guarantees non-null but Eclipse JDT doesn't track
                              // it
    public void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException, ConversationPauseException {

        checkNotNull(conversationMemory, "conversationMemory");

        if (isNullOrEmpty(lifecycleTaskTypes)) {
            // Execute all tasks — loop index is already absolute (offset 0).
            executeTaskRange(conversationMemory, this.lifecycleTasks, 0, 0);
        } else {
            // Selective execution: run the suffix of the pipeline starting at the
            // first task whose type matches. The sublist is passed (preserving the
            // component-cache/telemetry index base), but the absolute offset is
            // threaded through so a HITL pause records an ABSOLUTE task index that
            // resume can re-enter against the full task list.
            int startAbsolute = getLifecycleStartIndex(lifecycleTaskTypes);
            if (startAbsolute < 0) {
                return; // no task matches the requested types — nothing to execute
            }
            List<ILifecycleTask> tasks = this.lifecycleTasks.subList(startAbsolute, this.lifecycleTasks.size());
            executeTaskRange(conversationMemory, tasks, 0, startAbsolute);
        }
    }

    /**
     * Resume lifecycle execution from an absolute task index. Used by the HITL
     * framework to continue the pipeline from where it was paused.
     */
    @Override
    public void executeLifecycleFromIndex(IConversationMemory conversationMemory, int startFromAbsoluteIndex)
            throws LifecycleException, ConversationStopException, ConversationPauseException {

        checkNotNull(conversationMemory, "conversationMemory");
        // The index comes from a persisted HITL bookmark — validate it before use.
        // A negative index is a corrupt bookmark; an index STRICTLY past the end
        // means the workflow was redeployed with fewer tasks (a bookmark of exactly
        // size() is valid: it means "pause was on the last task", zero remaining).
        if (startFromAbsoluteIndex < 0) {
            throw new LifecycleException("HITL resume index cannot be negative: " + startFromAbsoluteIndex);
        }
        if (startFromAbsoluteIndex > this.lifecycleTasks.size()) {
            LOGGER.warnf("HITL resume index %d exceeds task count %d (workflow may have been redeployed) — "
                    + "skipping remaining tasks of this workflow", startFromAbsoluteIndex, this.lifecycleTasks.size());
            return;
        }
        executeTaskRange(conversationMemory, this.lifecycleTasks, startFromAbsoluteIndex, 0);
    }

    /**
     * Shared task execution loop used by both executeLifecycle and
     * executeLifecycleFromIndex. Iterates tasks from startIndex, applying
     * cancel/interrupt checks, action snapshots, tracing, metrics, strict-write
     * discipline, and HITL pause detection for each task.
     */
    private void executeTaskRange(IConversationMemory conversationMemory,
                                  List<ILifecycleTask> tasks, int startIndex, int indexOffset)
            throws LifecycleException, ConversationStopException, ConversationPauseException {

        var eventSink = conversationMemory.getEventSink();

        // Resolve memory policy once (null-safe)
        var memoryPolicy = conversationMemory.getMemoryPolicy();
        boolean strictWriteEnabled = memoryPolicy != null && memoryPolicy.isEffectivelyEnabled();

        // Execute each task in sequence
        for (int index = startIndex; index < tasks.size(); index++) {
            ILifecycleTask task = tasks.get(index);

            // Cancel check (Wave 0)
            if (conversationMemory.isCancelled()) {
                throw new ConversationStopException();
            }

            // Fail-fast: every task must have a non-null TaskId
            if (task.getId() == null) {
                throw new LifecycleException("Lifecycle task returned null TaskId: " + task.getClass().getName());
            }

            // Check if execution should be interrupted (graceful shutdown)
            if (Thread.currentThread().isInterrupted()) {
                throw new LifecycleException.LifecycleInterruptedException("Execution was interrupted!");
            }

            // Snapshot actions before task execution — always captured for
            // delta-based PAUSE_CONVERSATION detection (Blocker #1 fix).
            // Also used by strict-write rollback when enabled.
            var currentStep = conversationMemory.getCurrentStep();
            IData<List<String>> preActionData = currentStep.getLatestData(ACTIONS);
            List<String> actionsBefore = (preActionData != null && preActionData.getResult() != null)
                    ? List.copyOf(preActionData.getResult())
                    : List.of();

            Map<String, IData<?>> dataIdentitiesBefore = Map.of();
            Set<String> outputKeysBefore = Set.of();
            if (strictWriteEnabled && currentStep instanceof ConversationStep cs) {
                dataIdentitiesBefore = cs.snapshotDataIdentities();
                outputKeysBefore = cs.snapshotOutputKeys();
            }

            // === OpenTelemetry: create span per task ===
            Span taskSpan = getTracer().spanBuilder("eddi.pipeline.task")
                    .setAttribute("eddi.task.id", task.getId().name())
                    .setAttribute("eddi.task.type", Objects.requireNonNullElse(task.getType(), "unknown"))
                    .setAttribute("eddi.task.index", (long) index)
                    .setAttribute("eddi.conversation.id",
                            Objects.requireNonNullElse(conversationMemory.getConversationId(), "unknown"))
                    .setAttribute("eddi.agent.id",
                            Objects.requireNonNullElse(conversationMemory.getAgentId(), "unknown"))
                    .startSpan();

            long taskStartTime = System.nanoTime();

            try (Scope ignored = taskSpan.makeCurrent()) {
                // Retrieve task's component from cache
                // Component contains task-specific configuration loaded during agent
                // initialization
                var components = componentCache.getComponentMap(task.getId().name());
                var componentKey = createComponentKey(workflowId.getId(), workflowId.getVersion(), index);
                var component = components.getOrDefault(componentKey, null);

                // Emit task_start event if streaming
                if (eventSink != null) {
                    eventSink.onTaskStart(task.getId(), task.getType(), index);
                }

                // Execute the task, transforming the conversation memory
                task.execute(conversationMemory, component);

                // Emit task_complete event if streaming
                long durationMs = (System.nanoTime() - taskStartTime) / 1_000_000;
                Map<String, Object> summary = buildTaskSummary(conversationMemory, task);

                if (eventSink != null) {
                    eventSink.onTaskComplete(task.getId(), task.getType(), durationMs, summary);
                }

                // Emit audit entry if audit collector is set
                var auditCollector = conversationMemory.getAuditCollector();
                if (auditCollector != null) {
                    AuditEntry auditEntry = buildAuditEntry(conversationMemory, task, index, durationMs, summary);
                    auditCollector.collect(auditEntry);
                }

                // Check if task triggered a STOP_CONVERSATION action
                checkIfStopConversationAction(conversationMemory);
                // The pause bookmark must be ABSOLUTE (offset + loop index) so resume
                // re-enters the full task list at the right place, even when this is a
                // selective (sublist) execution where the loop index is offset-relative.
                checkIfPauseConversationAction(conversationMemory, indexOffset + index, actionsBefore);

            } catch (LifecycleException | RuntimeException e) {
                // HITL tool pause: a gated LLM tool call is NOT a task failure. Convert
                // it to a ConversationPauseException(TOOL_CALL) BEFORE the error counter
                // and strict-write rollback — the partially-executed step data (incl. the
                // pending batch just written to memory) must survive into the pause
                // snapshot, exactly like the rule-based PAUSE_CONVERSATION path.
                if (e instanceof ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException tare) {
                    taskSpan.setAttribute("eddi.hitl.pause", "tool_call");
                    throw new ConversationPauseException(workflowId.getId(), indexOffset + index,
                            tare.getPauseReason(), ConversationPauseException.PauseOrigin.TOOL_CALL);
                }

                taskSpan.setStatus(StatusCode.ERROR, Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
                taskSpan.recordException(e);

                // Classify error for metrics, audit, and admin dashboards
                String errorType = classifyError(e);
                String errorSummary = summarizeForAudit(e);
                long failDurationMs = (System.nanoTime() - taskStartTime) / 1_000_000;

                // Record error counter for dashboards & alerting (tagged by error.type)
                String errTaskId = task.getId().name();
                String errTaskType = task.getType() != null ? task.getType() : "unknown";
                String errMeterKey = errTaskId + "|" + errTaskType + "|" + errorType;
                TASK_ERROR_COUNTERS.computeIfAbsent(errMeterKey, k -> Counter.builder("eddi.pipeline.task.errors")
                        .tag("task.id", errTaskId)
                        .tag("task.type", errTaskType)
                        .tag("error.type", errorType)
                        .description("Pipeline task execution errors")
                        .register(Metrics.globalRegistry)).increment();

                // Emit SSE task_failed event for real-time admin monitoring
                var eventSinkRef = conversationMemory.getEventSink();
                if (eventSinkRef != null) {
                    try {
                        eventSinkRef.onTaskFailed(task.getId(), task.getType(),
                                failDurationMs, errorType, errorSummary);
                    } catch (Exception sseEx) {
                        LOGGER.warnf(sseEx, "SSE task_failed emission failed for conversation '%s', task '%s'",
                                sanitize(conversationMemory.getConversationId()), errTaskId);
                    }
                }

                // Strict-write recovery runs before reporting: it is integrity-critical
                // and must not be skipped by an audit failure.
                if (strictWriteEnabled && currentStep instanceof ConversationStep cs) {
                    // === Strict Write Discipline: handle task failure ===
                    String onFailureMode = resolveOnFailureMode(memoryPolicy);
                    handleTaskFailure(cs, task, e, dataIdentitiesBefore,
                            outputKeysBefore, actionsBefore, onFailureMode);
                }

                // Collect failure audit entry. Best-effort: an audit failure must not
                // replace the original task exception, so it is attached as suppressed.
                var auditCollector = conversationMemory.getAuditCollector();
                if (auditCollector != null) {
                    try {
                        var failureOutput = new LinkedHashMap<String, Object>();
                        failureOutput.put("status", "TASK_FAILED");
                        failureOutput.put("errorType", errorType);
                        failureOutput.put("errorMessage", errorSummary);
                        failureOutput.put("strictWriteApplied", strictWriteEnabled);

                        AuditEntry failureEntry = new AuditEntry(
                                UUID.randomUUID().toString(), conversationMemory.getConversationId(),
                                conversationMemory.getAgentId(), conversationMemory.getAgentVersion(),
                                conversationMemory.getUserId(), null, conversationMemory.size() - 1,
                                errTaskId, errTaskType, index, failDurationMs,
                                null, failureOutput, null, null, null, 0.0,
                                Instant.now(), null, null);
                        auditCollector.collect(failureEntry);
                    } catch (Exception auditEx) {
                        LOGGER.warnf(auditEx, "Failure audit collection failed for conversation '%s', task '%s'",
                                sanitize(conversationMemory.getConversationId()), errTaskId);
                        e.addSuppressed(auditEx);
                    }
                }

                // Re-throw — pipeline stops (current behavior preserved).
                // The error digest and task_failed action are stored in the
                // conversation output for the next turn's behavior rules.
                if (e instanceof LifecycleException le) {
                    throw new LifecycleException("Error while executing lifecycle!", le);
                }
                throw new LifecycleException("Error while executing lifecycle!", e);
            } finally {
                // Record Micrometer timer for dashboards & alerting — in finally so
                // both successful and failed task executions contribute to the
                // duration histogram (failing tasks with long timeouts are especially
                // important for latency monitoring during incidents).
                long durationMs = (System.nanoTime() - taskStartTime) / 1_000_000;
                String taskId = task.getId().name();
                String taskType = task.getType() != null ? task.getType() : "unknown";
                String meterKey = taskId + "|" + taskType;
                TASK_TIMERS.computeIfAbsent(meterKey, k -> Timer.builder("eddi.pipeline.task.duration")
                        .tag("task.id", taskId)
                        .tag("task.type", taskType)
                        .description("Pipeline task execution duration")
                        .publishPercentileHistogram()
                        .register(Metrics.globalRegistry)).record(java.time.Duration.ofMillis(durationMs));

                taskSpan.end();
            }
        }
    }

    /**
     * Build a summary map for the task_complete event. Includes emitted actions,
     * tool execution traces, and cascade confidence for the Manager UI.
     */
    private Map<String, Object> buildTaskSummary(IConversationMemory conversationMemory, ILifecycleTask task) {
        var summary = new HashMap<String, Object>();
        // If the task produced actions, include them in the summary
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(ACTIONS);
        if (actionData != null && actionData.getResult() != null) {
            summary.put("actions", actionData.getResult());
        }
        // Tool execution trace (LLM tasks only) — enables live tool call display in UI.
        // One LlmTask execution iterates the LLM config's tasks and writes one
        // "langchain:trace:<modelType>:<configTaskId>" key PER config task, so the
        // trace has to be aggregated. getLatestData() cannot be used: it reverses the
        // element list and returns only the LAST prefix match. The task-type gate is
        // load-bearing — step data survives across tasks, so an ungated prefix scan
        // would report the LLM's trace on every task that runs after it in this step.
        if (isLlmTask(task)) {
            List<Object> toolTrace = collectToolTrace(conversationMemory.getCurrentStep());
            if (!toolTrace.isEmpty()) {
                // Redact before it leaves the process. This summary feeds the SSE
                // task_complete frame, and tool arguments/results are LLM- and
                // user-controlled, so they can carry secrets (API keys, bearer
                // tokens). The audit-ledger path already scrubs the same content via
                // AuditLedgerService; this brings the live-display path to parity.
                summary.put("toolTrace", redactToolTrace(toolTrace));
            }
            // Cascade confidence (when model cascade is active) — written as a Double by
            // LlmTask's cascade branch. It used to write a String under a different key
            // ("audit:cascade_confidence"), so this slot was never populated at all.
            // Same gate as the trace above: it is an LLM-only signal that lingers in the
            // step, so an ungated read reports it for every later task too.
            IData<Double> confidenceData = conversationMemory.getCurrentStep().getLatestData(AUDIT_CONFIDENCE);
            if (confidenceData != null && confidenceData.getResult() != null) {
                summary.put("confidence", confidenceData.getResult());
            }
        }
        return summary;
    }

    /**
     * Whether this task is the LLM task, i.e. the only writer of the
     * {@code audit:*} and {@code langchain:trace:*} step keys.
     * <p>
     * Both {@link #buildTaskSummary} and {@link #buildAuditEntry} read those keys,
     * and {@code ConversationStep}'s data is never cleared between tasks — so an
     * ungated read attributes the LLM's evidence to every task that runs after it
     * in the same step. Kept as one shared predicate so the two readers cannot
     * drift apart again.
     */
    private boolean isLlmTask(ILifecycleTask task) {
        return TASK_TYPE_LANGCHAIN.equals(task.getType());
    }

    /**
     * Aggregates every {@code langchain:trace:*} entry of the current step, in
     * write order. {@link IConversationMemory.IConversationStep#getAllElements()}
     * returns an insertion-ordered defensive copy, so no reordering is needed.
     * <p>
     * The caller must gate on the task type — this method deliberately does not, so
     * it stays a pure read over the step.
     */
    private List<Object> collectToolTrace(IConversationMemory.IConversationStep currentStep) {
        List<Object> aggregated = new ArrayList<>();
        for (IData<?> element : currentStep.getAllElements()) {
            if (element != null && element.getKey() != null
                    && element.getKey().startsWith(LANGCHAIN_TRACE_PREFIX)
                    && element.getResult() instanceof List<?> entries) {
                aggregated.addAll(entries);
            }
        }
        return aggregated;
    }

    /**
     * Deep-redacts a collected tool trace before it is placed on the SSE
     * {@code task_complete} summary. Each entry is a {@code Map} whose
     * {@code arguments}/{@code result} strings are LLM- or user-controlled and may
     * contain secrets. Mirrors {@code AuditLedgerService}'s scrub so the two
     * outward-facing channels redact the same way. Returns a fresh structure — the
     * trace stored in conversation memory is left intact for the owner-scoped
     * {@code RestToolHistory} endpoint.
     */
    private static List<Object> redactToolTrace(List<Object> trace) {
        List<Object> redacted = new ArrayList<>(trace.size());
        for (Object entry : trace) {
            redacted.add(redactTraceValue(entry));
        }
        return redacted;
    }

    @SuppressWarnings("unchecked")
    private static Object redactTraceValue(Object value) {
        if (value instanceof String s) {
            return SecretRedactionFilter.redact(s);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> scrubbed = new LinkedHashMap<>(map.size());
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                scrubbed.put(e.getKey(), redactTraceValue(e.getValue()));
            }
            return scrubbed;
        } else if (value instanceof List<?> list) {
            return list.stream().map(LifecycleManager::redactTraceValue).toList();
        }
        return value;
    }

    /**
     * Build an audit entry capturing a task's execution data. Maps memory data into
     * input/output/llmDetail/toolCalls fields.
     */
    private AuditEntry buildAuditEntry(IConversationMemory memory, ILifecycleTask task, int taskIndex, long durationMs, Map<String, Object> summary) {
        var currentStep = memory.getCurrentStep();
        int stepIndex = memory.size() - 1; // 0-based

        // Collect input data (what the task read)
        Map<String, Object> input = new LinkedHashMap<>();
        IData<String> inputData = currentStep.getLatestData("input");
        if (inputData != null && inputData.getResult() != null) {
            input.put("userInput", inputData.getResult());
        }

        // Collect output data (what the task wrote)
        Map<String, Object> output = new LinkedHashMap<>();
        IData<List<String>> outputData = currentStep.getLatestData("output");
        if (outputData != null && outputData.getResult() != null) {
            output.put("output", outputData.getResult());
        }

        // Everything below is written by LlmTask (executeTask and executeResume) and
        // only when an audit collector is attached. It is gated on the task type for
        // the same reason buildTaskSummary gates the tool trace: the step's data
        // survives across tasks, so an ungated read makes every task running after the
        // LLM task in this step append a ledger row carrying the LLM's prompt, tokens,
        // tool calls and — worst of all — its dollar cost. The ledger is append-only,
        // so an auditor summing cost over the turn would read a multiple of the truth
        // with no way to correct it after the fact.
        boolean llmTask = isLlmTask(task);

        // Collect LLM details (if present).
        Map<String, Object> llmDetail = null;
        IData<String> promptData = llmTask ? currentStep.getLatestData(AUDIT_COMPILED_PROMPT) : null;
        if (promptData != null && promptData.getResult() != null) {
            llmDetail = new LinkedHashMap<>();
            llmDetail.put("compiledPrompt", promptData.getResult());
            IData<String> responseData = currentStep.getLatestData(AUDIT_MODEL_RESPONSE);
            if (responseData != null)
                llmDetail.put("modelResponse", responseData.getResult());
            IData<String> modelData = currentStep.getLatestData(AUDIT_MODEL_NAME);
            if (modelData != null)
                llmDetail.put("modelName", modelData.getResult());
            // Stricter than the two above on purpose: those have always produced values
            // and their looser guard is grandfathered, whereas the keys below never had
            // a writer at all, so nothing depends on a null result reaching llmDetail.
            IData<Map<String, Object>> tokenData = currentStep.getLatestData(AUDIT_TOKEN_USAGE);
            if (tokenData != null && tokenData.getResult() != null)
                llmDetail.put("tokenUsage", tokenData.getResult());
            IData<String> cascadeModelData = currentStep.getLatestData(AUDIT_CASCADE_MODEL);
            if (cascadeModelData != null && cascadeModelData.getResult() != null)
                llmDetail.put("cascadeModel", cascadeModelData.getResult());
            IData<Double> confidenceData = currentStep.getLatestData(AUDIT_CONFIDENCE);
            if (confidenceData != null && confidenceData.getResult() != null)
                llmDetail.put("confidence", confidenceData.getResult());
        }

        // Tool execution evidence, accumulated by LlmTask across the whole turn.
        Map<String, Object> toolCalls = null;
        IData<Map<String, Object>> toolCallData = llmTask ? currentStep.getLatestData(AUDIT_TOOL_CALLS) : null;
        if (toolCallData != null && toolCallData.getResult() != null && !toolCallData.getResult().isEmpty()) {
            toolCalls = toolCallData.getResult();
        }

        // Dollar cost of this task: configured cascade LLM pricing plus tracked tool
        // cost. Absent means nothing priced ran, which is a genuine 0.0.
        double cost = 0.0;
        IData<Double> costData = llmTask ? currentStep.getLatestData(AUDIT_COST) : null;
        if (costData != null && costData.getResult() != null) {
            cost = costData.getResult();
        }

        // Actions
        @SuppressWarnings("unchecked")
        List<String> actions = summary.containsKey("actions") ? (List<String>) summary.get("actions") : null;

        return new AuditEntry(UUID.randomUUID().toString(), memory.getConversationId(), memory.getAgentId(),
                memory.getAgentVersion(),
                memory.getUserId(), null, // environment is set by ConversationService
                stepIndex, task.getId().name(), task.getType(), taskIndex, durationMs, input.isEmpty() ? null : input,
                output.isEmpty() ? null : output,
                llmDetail, toolCalls,
                actions, cost,
                Instant.now(), null // HMAC computed by AuditLedgerService
                , null);
    }

    /**
     * Filters lifecycle tasks to execute only those starting from specified types.
     *
     * <p>
     * This enables partial workflow execution. For example, if you specify
     * ["behavior_rules"], it will execute behavior_rules and all subsequent tasks,
     * but skip earlier tasks like parsing.
     * </p>
     *
     * @param lifecycleTaskTypes
     *            list of task type prefixes to match
     * @return filtered list of tasks to execute
     */
    /**
     * Returns the ABSOLUTE index of the first task whose type matches any of the
     * requested types (prefix match); selective execution runs that task and all
     * subsequent ones. Returns -1 when no task matches.
     */
    private int getLifecycleStartIndex(List<String> lifecycleTaskTypes) {
        for (int i = 0; i < this.lifecycleTasks.size(); i++) {
            ILifecycleTask task = this.lifecycleTasks.get(i);
            if (lifecycleTaskTypes.stream().anyMatch(type -> task.getType().startsWith(type))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks if the current step contains a STOP_CONVERSATION action.
     *
     * <p>
     * STOP_CONVERSATION is a special action that can be triggered by behavior rules
     * to immediately halt the lifecycle workflow and end the conversation. This is
     * useful for scenarios like:
     * </p>
     * <ul>
     * <li>User explicitly says "goodbye" or "end conversation"</li>
     * <li>Maximum conversation turns reached</li>
     * <li>Error conditions that should terminate the conversation</li>
     * <li>Business logic determines conversation should end</li>
     * </ul>
     *
     * @param conversationMemory
     *            the conversation memory to check
     * @throws ConversationStopException
     *             if STOP_CONVERSATION action is found
     */
    private void checkIfStopConversationAction(IConversationMemory conversationMemory) throws ConversationStopException {
        // Retrieve actions from current step
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(ACTIONS);
        if (actionData != null) {
            var result = actionData.getResult();

            // Check if STOP_CONVERSATION is in the actions list
            if (result != null && result.contains(IConversation.STOP_CONVERSATION)) {
                throw new ConversationStopException();
            }
        }
    }

    /**
     * Checks if the current step contains a PAUSE_CONVERSATION action that was
     * <em>newly added</em> by the just-executed task. This delta-based check
     * prevents the re-pause loop (Blocker #1): on resume, the stale
     * PAUSE_CONVERSATION action from the prior turn is already in the step's
     * ACTIONS data, but it must not re-trigger the pause.
     *
     * @param actionsBeforeTask
     *            actions snapshot taken before the task executed; if
     *            PAUSE_CONVERSATION was already present, the task did not add it
     *            and no pause is thrown.
     */
    private void checkIfPauseConversationAction(IConversationMemory conversationMemory,
                                                int absoluteTaskIndex,
                                                List<String> actionsBeforeTask)
            throws ConversationPauseException {
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(ACTIONS);
        if (actionData == null)
            return;
        List<String> actions = actionData.getResult();
        if (actions != null
                && actions.contains(IConversation.PAUSE_CONVERSATION)
                && !actionsBeforeTask.contains(IConversation.PAUSE_CONVERSATION)) {
            throw new ConversationPauseException(workflowId.getId(), absoluteTaskIndex, "PAUSE_CONVERSATION action");
        }
    }

    /**
     * Adds a lifecycle task to this manager's execution workflow.
     *
     * <p>
     * Tasks are executed in the order they are added. This method is typically
     * called during Agent initialization, when the agent's package configuration is
     * being loaded.
     * </p>
     *
     * <p>
     * <strong>Important:</strong> Tasks should be added in the correct order to
     * ensure proper workflow flow. A typical order is:
     * </p>
     * <ol>
     * <li>Input normalization/parsing</li>
     * <li>Semantic parsing (dictionaries)</li>
     * <li>Behavior rules</li>
     * <li>Property extraction</li>
     * <li>HTTP calls / LangChain</li>
     * <li>Output generation</li>
     * </ol>
     *
     * @param lifecycleTask
     *            the task to add to the workflow
     * @throws IllegalArgumentException
     *             if lifecycleTask is null
     */
    @Override
    public void addLifecycleTask(ILifecycleTask lifecycleTask) {
        checkNotNull(lifecycleTask, "lifecycleTask");
        lifecycleTasks.add(lifecycleTask);
    }

    // === Strict Write Discipline: failure handling ===

    /** Valid onFailure modes. */
    private static final Set<String> VALID_ON_FAILURE_MODES = Set.of("digest", "exclude_all", "keep_all");

    /**
     * Resolve and validate the onFailure mode from the memory policy. Logs a
     * warning and defaults to "digest" for unknown values.
     */
    private String resolveOnFailureMode(AgentConfiguration.MemoryPolicy memoryPolicy) {
        var swd = memoryPolicy != null ? memoryPolicy.getStrictWriteDiscipline() : null;
        String mode = swd != null ? swd.getOnFailure() : "digest";
        if (mode == null || !VALID_ON_FAILURE_MODES.contains(mode)) {
            LOGGER.warnf("[STRICT_WRITE] Unknown onFailure mode '%s', defaulting to 'digest'", mode);
            return "digest";
        }
        return mode;
    }

    /**
     * Handle a task failure under strict write discipline. Performs three
     * operations:
     * <ol>
     * <li>Marks all IData entries added or overwritten by the failed task as
     * uncommitted (using identity comparison against pre-execution snapshot)</li>
     * <li>Rolls back ConversationOutput entries added by the failed task</li>
     * <li>Injects an error digest (if mode is "digest") under a separate
     * "taskErrors" key, and a {@code task_failed_<taskId>} action rebuilt from the
     * pre-failure action state</li>
     * </ol>
     */
    private void handleTaskFailure(ConversationStep step, ILifecycleTask task, Exception exception,
                                   Map<String, IData<?>> dataIdentitiesBefore,
                                   Set<String> outputKeysBefore, List<String> actionsBefore,
                                   String onFailureMode) {

        LOGGER.warnf("[STRICT_WRITE] Task '%s' (type=%s) failed — applying write discipline (mode=%s): %s",
                task.getId(), task.getType(), onFailureMode, exception.getMessage());

        // 1. Mark new or overwritten IData entries as uncommitted.
        // An entry is "dirty" if its key didn't exist before OR if the
        // IData reference changed (task overwrote an existing key).
        for (var entry : step.getAllElements()) {
            IData<?> before = dataIdentitiesBefore.get(entry.getKey());
            if (before == null || before != entry) {
                // New key or different IData instance → written by failed task
                entry.setCommitted(false);
            }
        }

        // 2. Rollback ConversationOutput entries added by the failed task
        var output = step.getConversationOutput();
        Set<String> currentKeys = new LinkedHashSet<>(output.keySet());
        for (String key : currentKeys) {
            if (!outputKeysBefore.contains(key)) {
                output.remove(key);
            }
        }

        // 3. Inject error digest and task_failed action
        if ("digest".equals(onFailureMode)) {
            injectErrorDigest(step, task, exception);
        }
        injectFailureAction(step, task, actionsBefore);
    }

    /**
     * Injects a compact error digest into the conversation output under the
     * dedicated "taskErrors" key (NOT mixed into "output"). This ensures
     * {@link ConversationLogGenerator} doesn't concatenate error text with regular
     * assistant output. The UI can render taskErrors with distinct styling (warning
     * icon, different color).
     */
    private void injectErrorDigest(ConversationStep step, ILifecycleTask task, Exception exception) {
        String summary = summarizeException(exception);
        String digestText = String.format("Task '%s' failed: %s", task.getId(), summary);

        // Store under separate "taskErrors" key — never mixed into "output"
        var errorOutput = new LinkedHashMap<String, Object>();
        errorOutput.put("type", "errorDigest");
        errorOutput.put("taskId", task.getId().name());
        errorOutput.put("taskType", task.getType());
        errorOutput.put("text", digestText);
        step.addConversationOutputList("taskErrors", List.of(errorOutput));

        // Also store as committed IData so it's visible in memory
        Data<String> digestData = new Data<>("taskError:" + task.getId(), digestText);
        digestData.setPublic(true);
        digestData.setCommitted(true);
        step.storeData(digestData);
    }

    /**
     * Injects a {@code task_failed_<taskId>} action into the conversation step.
     * Rebuilds the actions list from the pre-failure snapshot to avoid including
     * actions that the failed task may have added.
     *
     * @param actionsBefore
     *            the actions list captured before the failed task ran
     */
    private void injectFailureAction(ConversationStep step, ILifecycleTask task,
                                     List<String> actionsBefore) {
        String failureAction = "task_failed_" + task.getId().name();

        // Rebuild from pre-failure state + failure action
        List<String> actions = new ArrayList<>(actionsBefore);
        actions.add(failureAction);

        // Store as fresh committed IData (replaces any uncommitted actions data)
        step.set(ACTIONS, actions);
        step.addConversationOutputList(ACTIONS.key(), List.of(failureAction));
    }

    /**
     * Creates a concise, sanitized error summary suitable for LLM consumption.
     * Strips stack traces, internal URLs, and class names to avoid context
     * pollution.
     */
    private String summarizeException(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }

        // Strip common noise patterns
        msg = msg.replaceAll("https?://[^\\s]+", "[url]");
        msg = msg.replaceAll("at [a-zA-Z0-9.$]+\\([^)]+\\)", "");

        if (msg.length() > MAX_ERROR_DIGEST_LENGTH) {
            msg = msg.substring(0, MAX_ERROR_DIGEST_LENGTH) + "...";
        }
        return msg.trim();
    }

    // ==================== OpenTelemetry ====================

    /**
     * Lazy-initialized OTel tracer. Returns a no-op tracer when OTel SDK is
     * disabled ({@code quarkus.otel.sdk.disabled=true}), ensuring zero overhead in
     * dev/test.
     *
     * <p>
     * Uses {@link GlobalOpenTelemetry} because {@code LifecycleManager} is not
     * CDI-managed — it is instantiated via {@code new} in
     * {@link ai.labs.eddi.engine.runtime.client.workflows.WorkflowStoreClientLibrary}.
     * </p>
     */
    private static volatile Tracer tracer;

    private static Tracer getTracer() {
        if (tracer == null) {
            tracer = GlobalOpenTelemetry.getTracer("eddi.pipeline");
        }
        return tracer;
    }

    // ========================== Error Classification ==========================

    /**
     * Classifies the root cause of an error for metrics, audit, and dashboards.
     * Typed causes are matched across the whole chain first — they are
     * authoritative, so a wrapper's message can never outrank them. Message
     * heuristics are only consulted when no typed cause matches.
     *
     * @return one of: "timeout", "transport", "rate_limit", "content_filter",
     *         "unknown"
     */
    static String classifyError(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return "timeout";
            }
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException) {
                return "transport";
            }
        }
        // Substring matching is easily fooled (e.g. "failed after 429ms"), so it only
        // runs once the chain is known to hold no typed cause.
        for (Throwable current = e; current != null; current = current.getCause()) {
            String msg = current.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase();
            if (lower.contains("rate limit") || lower.contains("429") || lower.contains("too many")) {
                return "rate_limit";
            }
            if (lower.contains("content_filter") || lower.contains("content filter")) {
                return "content_filter";
            }
        }
        return "unknown";
    }

    /**
     * Creates a redacted, truncated summary of an exception for audit and SSE
     * events. Unlike {@link #summarizeException(Exception)} (which sanitizes for
     * LLM consumption at 200 chars), this preserves full detail at 500 chars for
     * admin visibility — URLs and class names are deliberately kept, since the
     * audience is privileged and needs them to diagnose. Credentials are not: they
     * are scrubbed via {@link SecretRedactionFilter}.
     */
    static String summarizeForAudit(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        // Redact before truncating — cutting first can split a secret so the
        // pattern no longer matches, leaving a fragment behind.
        msg = SecretRedactionFilter.redact(msg);
        // Truncate to 500 chars for safe embedding in JSON/SSE payloads
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
