package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;

import java.time.Instant;
import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.utils.LifecycleUtilities.createComponentKey;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Executes the Lifecycle Workflow - EDDI's core processing engine.
 *
 * <p>
 * The LifecycleManager is responsible for executing an agent's configured
 * sequence
 * of
 * {@link ILifecycleTask} components, passing conversation state through each
 * task in order.
 * </p>
 *
 * <h2>Lifecycle Workflow Concept</h2>
 * <p>
 * Instead of hard-coded Agent logic, EDDI processes conversations through a
 * configurable
 * workflow of tasks:
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
 * building on
 * previous results</li>
 * <li><strong>Stateless Design</strong>: Tasks don't maintain state; all state
 * is in
 * the memory object</li>
 * <li><strong>Interruptible</strong>: Workflow can stop early if
 * STOP_CONVERSATION
 * action is triggered</li>
 * <li><strong>Selective Execution</strong>: Can execute only a subset of tasks
 * starting
 * from a specific type</li>
 * <li><strong>Component-Based</strong>: Each task has an associated component
 * (config/resource)
 * loaded from the workflow</li>
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

    /**
     * The ordered list of lifecycle tasks to execute.
     * Tasks are added during Agent initialization based on package configuration.
     */
    private final List<ILifecycleTask> lifecycleTasks;

    /**
     * Cache of task-specific components (configurations, resources, etc.).
     * Components are loaded once and reused across executions for performance.
     */
    private final IComponentCache componentCache;

    /**
     * Identifier of the workflow this lifecycle manager belongs to.
     * Used for component cache lookups.
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
     * one
     * in sequence. Each task reads from and writes to the conversation memory,
     * building
     * up the conversation state progressively.
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
     * useful for
     * debugging or specialized processing.
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
     * @param conversationMemory the conversation state to transform
     * @param lifecycleTaskTypes optional filter to execute only specific task types
     * @throws LifecycleException        if any task execution fails
     * @throws ConversationStopException if STOP_CONVERSATION action is triggered
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

        // Execute each task in sequence
        for (int index = 0; index < lifecycleTasks.size(); index++) {
            ILifecycleTask task = lifecycleTasks.get(index);

            // Check if execution should be interrupted (graceful shutdown)
            if (Thread.currentThread().isInterrupted()) {
                throw new LifecycleException.LifecycleInterruptedException("Execution was interrupted!");
            }

            try {
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

                if (eventSink != null) {
                    eventSink.onTaskComplete(task.getId(), task.getType(), durationMs, summary);
                }

                // Emit audit entry if audit collector is set
                var auditCollector = conversationMemory.getAuditCollector();
                if (auditCollector != null) {
                    AuditEntry auditEntry = buildAuditEntry(
                            conversationMemory, task, index, durationMs, summary);
                    auditCollector.collect(auditEntry);
                }

                // Check if task triggered a STOP_CONVERSATION action
                checkIfStopConversationAction(conversationMemory);
            } catch (LifecycleException e) {
                throw new LifecycleException("Error while executing lifecycle!", e);
            }
        }
    }

    /**
     * Build a summary map for the task_complete event.
     * Includes emitted actions for behavior tasks so the Manager UI can display
     * them.
     */
    private Map<String, Object> buildTaskSummary(IConversationMemory conversationMemory,
            ILifecycleTask task) {
        var summary = new HashMap<String, Object>();
        // If the task produced actions, include them in the summary
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(ACTIONS);
        if (actionData != null && actionData.getResult() != null) {
            summary.put("actions", actionData.getResult());
        }
        return summary;
    }

    /**
     * Build an audit entry capturing a task's execution data.
     * Maps memory data into input/output/llmDetail/toolCalls fields.
     */
    private AuditEntry buildAuditEntry(IConversationMemory memory, ILifecycleTask task,
            int taskIndex, long durationMs,
            Map<String, Object> summary) {
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
        List<String> actions = summary.containsKey("actions")
                ? (List<String>) summary.get("actions")
                : null;

        return new AuditEntry(
                UUID.randomUUID().toString(),
                memory.getConversationId(),
                memory.getAgentId(),
                memory.getAgentVersion(),
                memory.getUserId(),
                null, // environment is set by ConversationService
                stepIndex,
                task.getId(),
                task.getType(),
                taskIndex,
                durationMs,
                input.isEmpty() ? null : input,
                output.isEmpty() ? null : output,
                llmDetail,
                null, // toolCalls — set by LangchainTask in memory
                actions,
                0.0, // cost — set by ToolCostTracker integration
                Instant.now(),
                null // HMAC computed by AuditLedgerService
        );
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
     * @param lifecycleTaskTypes list of task type prefixes to match
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
     * useful
     * for scenarios like:
     * </p>
     * <ul>
     * <li>User explicitly says "goodbye" or "end conversation"</li>
     * <li>Maximum conversation turns reached</li>
     * <li>Error conditions that should terminate the conversation</li>
     * <li>Business logic determines conversation should end</li>
     * </ul>
     *
     * @param conversationMemory the conversation memory to check
     * @throws ConversationStopException if STOP_CONVERSATION action is found
     */
    private void checkIfStopConversationAction(IConversationMemory conversationMemory)
            throws ConversationStopException {
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
     * called
     * during Agent initialization, when the agent's package configuration is being
     * loaded.
     * </p>
     *
     * <p>
     * <strong>Important:</strong> Tasks should be added in the correct order to
     * ensure
     * proper workflow flow. A typical order is:
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
     * @param lifecycleTask the task to add to the workflow
     * @throws IllegalArgumentException if lifecycleTask is null
     */
    @Override
    public void addLifecycleTask(ILifecycleTask lifecycleTask) {
        checkNotNull(lifecycleTask, "lifecycleTask");
        lifecycleTasks.add(lifecycleTask);
    }
}
