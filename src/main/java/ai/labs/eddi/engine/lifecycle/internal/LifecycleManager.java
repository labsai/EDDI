package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;

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
import static ai.labs.eddi.utils.LifecycleUtilities.createComponentKey;
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
    public void executeLifecycle(final IConversationMemory conversationMemory, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException {

        checkNotNull(conversationMemory, "conversationMemory");

        var eventSink = conversationMemory.getEventSink();

        // Determine which tasks to execute
        List<ILifecycleTask> lifecycleTasks;
        if (isNullOrEmpty(lifecycleTaskTypes)) {
            // Execute all tasks
            lifecycleTasks = this.lifecycleTasks;
        } else {
            // Execute only tasks starting from specified type
            lifecycleTasks = getLifecycleTasks(lifecycleTaskTypes);
        }

        // Resolve memory policy once (null-safe)
        var memoryPolicy = conversationMemory.getMemoryPolicy();
        boolean strictWriteEnabled = memoryPolicy != null && memoryPolicy.isEffectivelyEnabled();

        // Execute each task in sequence
        for (int index = 0; index < lifecycleTasks.size(); index++) {
            ILifecycleTask task = lifecycleTasks.get(index);

            // Check if execution should be interrupted (graceful shutdown)
            if (Thread.currentThread().isInterrupted()) {
                throw new LifecycleException.LifecycleInterruptedException("Execution was interrupted!");
            }

            // Snapshot state before task execution (for rollback on failure)
            var currentStep = conversationMemory.getCurrentStep();
            Map<String, IData<?>> dataIdentitiesBefore = Map.of();
            Set<String> outputKeysBefore = Set.of();
            List<String> actionsBefore = List.of();
            if (strictWriteEnabled && currentStep instanceof ConversationStep cs) {
                dataIdentitiesBefore = cs.snapshotDataIdentities();
                outputKeysBefore = cs.snapshotOutputKeys();
                // Capture pre-failure actions for Bug 3 fix
                IData<List<String>> preActionData = currentStep.getLatestData(ACTIONS);
                if (preActionData != null && preActionData.getResult() != null) {
                    actionsBefore = List.copyOf(preActionData.getResult());
                }
            }

            // === OpenTelemetry: create span per task ===
            Span taskSpan = getTracer().spanBuilder("eddi.pipeline.task")
                    .setAttribute("eddi.task.id", task.getId())
                    .setAttribute("eddi.task.type", task.getType() != null ? task.getType() : "unknown")
                    .setAttribute("eddi.task.index", (long) index)
                    .setAttribute("eddi.conversation.id",
                            conversationMemory.getConversationId() != null ? conversationMemory.getConversationId() : "unknown")
                    .setAttribute("eddi.agent.id", conversationMemory.getAgentId() != null ? conversationMemory.getAgentId() : "unknown")
                    .startSpan();

            try (Scope ignored = taskSpan.makeCurrent()) {
                // Retrieve task's component from cache
                // Component contains task-specific configuration loaded during agent
                // initialization
                var components = componentCache.getComponentMap(task.getId());
                var componentKey = createComponentKey(workflowId.getId(), workflowId.getVersion(), index);
                var component = components.getOrDefault(componentKey, null);

                // Emit task_start event if streaming
                if (eventSink != null) {
                    eventSink.onTaskStart(task.getId(), task.getType(), index);
                }

                long taskStartTime = System.nanoTime();

                // Execute the task, transforming the conversation memory
                task.execute(conversationMemory, component);

                // Emit task_complete event if streaming
                long durationMs = (System.nanoTime() - taskStartTime) / 1_000_000;
                Map<String, Object> summary = buildTaskSummary(conversationMemory, task);

                // Record Micrometer timer for dashboards & alerting
                String taskId = task.getId() != null ? task.getId() : "unknown";
                String taskType = task.getType() != null ? task.getType() : "unknown";
                String meterKey = taskId + "|" + taskType;
                TASK_TIMERS.computeIfAbsent(meterKey, k -> Timer.builder("eddi.pipeline.task.duration")
                        .tag("task.id", taskId)
                        .tag("task.type", taskType)
                        .description("Pipeline task execution duration")
                        .register(Metrics.globalRegistry)).record(java.time.Duration.ofMillis(durationMs));

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

            } catch (LifecycleException | RuntimeException e) {
                taskSpan.setStatus(StatusCode.ERROR, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                taskSpan.recordException(e);

                // Record error counter for dashboards & alerting
                String errTaskId = task.getId() != null ? task.getId() : "unknown";
                String errTaskType = task.getType() != null ? task.getType() : "unknown";
                String errMeterKey = errTaskId + "|" + errTaskType;
                TASK_ERROR_COUNTERS.computeIfAbsent(errMeterKey, k -> Counter.builder("eddi.pipeline.task.errors")
                        .tag("task.id", errTaskId)
                        .tag("task.type", errTaskType)
                        .description("Pipeline task execution errors")
                        .register(Metrics.globalRegistry)).increment();

                if (strictWriteEnabled && currentStep instanceof ConversationStep cs) {
                    // === Strict Write Discipline: handle task failure ===
                    String onFailureMode = resolveOnFailureMode(memoryPolicy);
                    handleTaskFailure(cs, task, e, dataIdentitiesBefore,
                            outputKeysBefore, actionsBefore, onFailureMode);
                }

                // Re-throw — pipeline stops (current behavior preserved).
                // The error digest and task_failed action are stored in the
                // conversation output for the next turn's behavior rules.
                if (e instanceof LifecycleException le) {
                    throw new LifecycleException("Error while executing lifecycle!", le);
                }
                throw new LifecycleException("Error while executing lifecycle!", e);
            } finally {
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
        // Tool execution trace (for LLM tasks) — enables live tool call display in UI
        IData<?> traceData = conversationMemory.getCurrentStep().getLatestData("langchain:trace:" + task.getId());
        if (traceData != null && traceData.getResult() != null) {
            summary.put("toolTrace", traceData.getResult());
        }
        // Cascade confidence (when model cascade is active)
        IData<Double> confidenceData = conversationMemory.getCurrentStep().getLatestData("audit:confidence");
        if (confidenceData != null && confidenceData.getResult() != null) {
            summary.put("confidence", confidenceData.getResult());
        }
        return summary;
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

        // Collect LLM details (if present)
        Map<String, Object> llmDetail = null;
        IData<String> promptData = currentStep.getLatestData("audit:compiled_prompt");
        if (promptData != null && promptData.getResult() != null) {
            llmDetail = new LinkedHashMap<>();
            llmDetail.put("compiledPrompt", promptData.getResult());
            IData<String> responseData = currentStep.getLatestData("audit:model_response");
            if (responseData != null)
                llmDetail.put("modelResponse", responseData.getResult());
            IData<String> modelData = currentStep.getLatestData("audit:model_name");
            if (modelData != null)
                llmDetail.put("modelName", modelData.getResult());
            IData<Map<String, Object>> tokenData = currentStep.getLatestData("audit:token_usage");
            if (tokenData != null)
                llmDetail.put("tokenUsage", tokenData.getResult());
        }

        // Actions
        @SuppressWarnings("unchecked")
        List<String> actions = summary.containsKey("actions") ? (List<String>) summary.get("actions") : null;

        return new AuditEntry(UUID.randomUUID().toString(), memory.getConversationId(), memory.getAgentId(), memory.getAgentVersion(),
                memory.getUserId(), null, // environment is set by ConversationService
                stepIndex, task.getId(), task.getType(), taskIndex, durationMs, input.isEmpty() ? null : input, output.isEmpty() ? null : output,
                llmDetail, null, // toolCalls — set by LlmTask in memory
                actions, 0.0, // cost — set by ToolCostTracker integration
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
    private List<ILifecycleTask> getLifecycleTasks(List<String> lifecycleTaskTypes) {
        List<ILifecycleTask> ret = new LinkedList<>();

        // Find the first task that matches any of the specified types
        for (int i = 0; i < this.lifecycleTasks.size(); i++) {
            ILifecycleTask task = this.lifecycleTasks.get(i);

            // Check if this task's type matches any of the requested types (prefix match)
            if (lifecycleTaskTypes.stream().anyMatch(type -> task.getType().startsWith(type))) {
                // Include this task and all subsequent tasks
                ret.addAll(this.lifecycleTasks.subList(i, this.lifecycleTasks.size()));
                break;
            }
        }

        return ret;
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
        errorOutput.put("taskId", task.getId());
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
        String failureAction = "task_failed_" + task.getId();

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
}
