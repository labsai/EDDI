/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.memory.ContextUtilities.storeContextLanguageInLongTermMemory;
import static ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.engine.memory.MemoryKeys.INPUT;
import static ai.labs.eddi.engine.memory.MemoryKeys.INPUT_INITIAL;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
public class Conversation implements IConversation {
    private static final Logger LOGGER = Logger.getLogger(Conversation.class);
    private static final String KEY_USER_INFO = "userInfo";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_SECRET_INPUT = "secretInput";
    private static final String SECRET_INPUT_PLACEHOLDER = "<secret input>";
    private static final String CONVERSATION_START = "CONVERSATION_START";
    private static final String CONVERSATION_END = "CONVERSATION_END";

    // Default recall settings for agents without UserMemoryConfig
    private static final String DEFAULT_RECALL_ORDER = "most_recent";
    private static final int DEFAULT_MAX_RECALL_ENTRIES = 1000;

    private final List<IExecutableWorkflow> executableWorkflows;
    private final IConversationMemory conversationMemory;
    private final IPropertiesHandler propertiesHandler;
    private final IConversation.IConversationOutputRenderer outputProvider;

    Conversation(List<IExecutableWorkflow> executableWorkflows, IConversationMemory conversationMemory, IPropertiesHandler propertiesHandler,
            IConversationOutputRenderer outputProvider) {
        this.executableWorkflows = executableWorkflows;
        this.conversationMemory = conversationMemory;
        this.propertiesHandler = propertiesHandler;
        this.outputProvider = outputProvider;
    }

    @Override
    public boolean isEnded() {
        return getConversationState() == ConversationState.ENDED;
    }

    @Override
    public void endConversation() {
        setConversationState(ConversationState.ENDED);
    }

    @Override
    public IConversationMemory getConversationMemory() {
        return conversationMemory;
    }

    @Override
    public void init(Map<String, Context> context) throws LifecycleException {
        setConversationState(ConversationState.READY);

        addConversationStartAction(conversationMemory.getCurrentStep());

        // Set UserMemoryConfig on the memory (if advanced tools are enabled)
        AgentConfiguration.UserMemoryConfig memoryConfig = propertiesHandler.getUserMemoryConfig();
        if (memoryConfig != null) {
            conversationMemory.setUserMemoryConfig(memoryConfig);
        }

        // Load all user properties from usermemories (always, regardless of
        // enableMemoryTools)
        loadUserProperties(conversationMemory, context);

        try {
            var lifecycleData = prepareLifecycleData("", context, null);
            executeConversationStep(lifecycleData, null);
        } finally {
            checkActionsForConversationEnd();
        }
    }

    private void setConversationState(ConversationState conversationState) {
        this.conversationMemory.setConversationState(conversationState);
    }

    private static void addConversationStartAction(IWritableConversationStep currentStep) {
        List<String> conversationEndArray = Collections.singletonList(CONVERSATION_START);
        currentStep.set(ACTIONS, conversationEndArray);
        currentStep.addConversationOutputList(ACTIONS.key(), conversationEndArray);
    }

    /**
     * Loads user properties from the {@code usermemories} collection. This replaces
     * both the old {@code loadLongTermProperties()} (which read from the legacy
     * {@code properties} collection) and the old {@code loadUserMemories()}.
     * <p>
     * Uses {@link IUserMemoryStore#getVisibleEntries} which handles visibility
     * scoping (self + group + global). Recall order and max entries come from
     * {@link AgentConfiguration.UserMemoryConfig} if available, or sensible
     * defaults.
     */
    private void loadUserProperties(IConversationMemory memory, Map<String, Context> context) throws LifecycleException {
        IUserMemoryStore store = propertiesHandler.getUserMemoryStore();
        if (store == null)
            return;

        try {
            String userId = memory.getUserId();
            String agentId = memory.getAgentId();
            List<String> groupIds = extractGroupIds(context);

            // Use config-specific recall settings if available, else defaults
            AgentConfiguration.UserMemoryConfig config = memory.getUserMemoryConfig();
            String recallOrder = config != null ? config.getRecallOrder() : DEFAULT_RECALL_ORDER;
            int maxEntries = config != null ? config.getMaxRecallEntries() : DEFAULT_MAX_RECALL_ENTRIES;

            List<UserMemoryEntry> entries = store.getVisibleEntries(userId, agentId, groupIds, recallOrder, maxEntries);

            for (UserMemoryEntry entry : entries) {
                Property prop = entryToProperty(entry);

                // Special handling for userInfo (needs to be stored as conversation-scoped map)
                if (KEY_USER_INFO.equals(entry.key()) && entry.value() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    var userInfoMap = (Map<String, Object>) entry.value();
                    memory.getConversationProperties().put(KEY_USER_INFO, new Property(KEY_USER_INFO, userInfoMap, Scope.conversation));
                } else {
                    memory.getConversationProperties().put(entry.key(), prop);
                }
            }

            if (!entries.isEmpty()) {
                LOGGER.debugf("[MEMORY] Loaded %d user properties for user='%s', agent='%s'", entries.size(), userId, agentId);
            }
        } catch (IResourceStore.ResourceStoreException e) {
            throw new LifecycleException("Failed to load user properties: " + e.getLocalizedMessage(), e);
        }
    }

    private ConversationState getConversationState() {
        return this.conversationMemory.getConversationState();
    }

    @Override
    public void rerun(final Map<String, Context> contexts) throws ConversationNotReadyException, LifecycleException {
        runStep("", contexts, false, Arrays.asList("output", "quickReplies"));
    }

    @Override
    public void say(final String message, final Map<String, Context> contexts) throws LifecycleException, ConversationNotReadyException {
        runStep(message, contexts, true, new LinkedList<>());
    }

    private void runStep(String message, Map<String, Context> contexts, boolean startNewStep, List<String> lifecycleTaskTypes)
            throws ConversationNotReadyException, LifecycleException {

        // Auto-recover from transient interrupted state
        if (getConversationState() == ConversationState.EXECUTION_INTERRUPTED) {
            LOGGER.infof("Auto-recovering conversation %s from EXECUTION_INTERRUPTED",
                    conversationMemory.getConversationId());
            setConversationState(ConversationState.READY);
        }

        checkIfConversationInProgress();

        try {
            setConversationState(ConversationState.IN_PROGRESS);

            if (startNewStep) {
                startNextStep();
            }

            var lifecycleData = prepareLifecycleData(message, contexts, lifecycleTaskTypes);
            executeConversationStep(lifecycleData, lifecycleTaskTypes);

        } catch (LifecycleException.LifecycleInterruptedException e) {
            setConversationState(ConversationState.EXECUTION_INTERRUPTED);
            throw e;
        } catch (Exception e) {
            setConversationState(ConversationState.ERROR);
            throw new LifecycleException(e.getLocalizedMessage(), e);
        } finally {
            checkActionsForConversationEnd();

            if (getConversationState() == ConversationState.IN_PROGRESS) {
                setConversationState(ConversationState.READY);
            }

            if (outputProvider != null) {
                outputProvider.renderOutput(conversationMemory);
            }
        }
    }

    private void checkIfConversationInProgress() throws ConversationNotReadyException {
        if (getConversationState() == ConversationState.IN_PROGRESS) {
            throw new ConversationNotReadyException("Conversation is currently IN_PROGRESS! Please try again later!");
        }
        if (getConversationState() == ConversationState.AWAITING_HUMAN) {
            throw new ConversationNotReadyException("Conversation is AWAITING_HUMAN approval. Use the /resume endpoint.");
        }
    }

    private void postConversationLifecycleTasks() throws IResourceStore.ResourceStoreException {
        removeOldInvalidProperties();
        storePropertiesPermanently();
    }

    private void startNextStep() {
        clearStaleToolPauseState();
        ((ConversationMemory) conversationMemory).startNextStep();
    }

    /**
     * Stale-batch hygiene (Step 6): at the start of each fresh turn, defensively
     * clear any leftover tool-pause state that is not backed by an active
     * AWAITING_HUMAN pause. Guards against a crash that persisted a pending batch
     * without the pause committing, and against future code paths that error after
     * the gate trips. WARN only when a stale batch is actually dropped.
     */
    private void clearStaleToolPauseState() {
        if (getConversationState() == ConversationState.AWAITING_HUMAN) {
            return; // a legitimate pause owns this state
        }
        if (conversationMemory.getHitlPendingToolCalls() != null || conversationMemory.getHitlPauseType() != null) {
            LOGGER.warnf("Clearing stale HITL tool-pause state at turn start for conversation %s (not AWAITING_HUMAN)",
                    sanitize(conversationMemory.getConversationId()));
            conversationMemory.setHitlPendingToolCalls(null);
            conversationMemory.setHitlPauseType(null);
            conversationMemory.setHitlResumeDecision(null);
        }
    }

    private List<IData<?>> prepareLifecycleData(String message, Map<String, Context> contexts, List<String> taskTypeResultsToBeRemoved) {

        List<IData<Context>> contextData = createContextData(contexts);
        List<IData<?>> lifecycleData = new LinkedList<>(contextData);

        storeContextLanguageInLongTermMemory(contexts, conversationMemory);

        IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        addContextToConversationOutput(currentStep, contextData);
        removedTaskTypeResultsFromPreviousRuns(currentStep, taskTypeResultsToBeRemoved);

        // Extract attachments from context (attachment_0, attachment_1, etc.),
        // resolve stored-blob metadata (owner/grant authorized) and enforce the
        // per-turn cap. Failures surface as attachments:errors — never silent.
        var parsedAttachments = AttachmentContextExtractor.extractAttachments(contexts);
        if (!parsedAttachments.isEmpty()) {
            var extraction = AttachmentContextExtractor.resolveAndGuard(
                    parsedAttachments, propertiesHandler.getAttachmentStore(),
                    conversationMemory.getConversationId(), propertiesHandler.getMaxAttachmentsPerTurn());

            if (!extraction.errors().isEmpty()) {
                extraction.errors().forEach(err -> LOGGER.warnv("Attachment issue in conversation {0}: {1}",
                        conversationMemory.getConversationId(), err));
                var errorData = new Data<>(MemoryKeys.ATTACHMENT_ERRORS.key(), extraction.errors());
                errorData.setPublic(false);
                currentStep.storeData(errorData);
            }
            if (!extraction.attachments().isEmpty()) {
                var data = new Data<>(MemoryKeys.ATTACHMENTS.key(), extraction.attachments());
                data.setPublic(true);
                currentStep.storeData(data);
            }
        }

        boolean isSecretInput = isSecretInputFlagged(contexts);
        storeUserInputInMemory(message, lifecycleData, isSecretInput);
        return lifecycleData;
    }

    private void removedTaskTypeResultsFromPreviousRuns(IWritableConversationStep currentStep, List<String> taskTypeResultsToBeRemoved) {

        if (!isNullOrEmpty(taskTypeResultsToBeRemoved)) {
            taskTypeResultsToBeRemoved.forEach(type -> {
                currentStep.removeData(type);
                currentStep.resetConversationOutput(type);
            });
        }
    }

    private void addContextToConversationOutput(IWritableConversationStep currentStep, List<IData<Context>> contextData) {

        if (!contextData.isEmpty()) {
            var context = ConversationMemoryUtilities.prepareContext(contextData);
            currentStep.addConversationOutputMap(KEY_CONTEXT, context);
        }
    }

    /**
     * Check if the client flagged this input as a secret via the context map. The
     * client sends: {@code { "secretInput": { "type": "string", "value": "true" }
     * }}
     */
    private static boolean isSecretInputFlagged(Map<String, Context> contexts) {
        if (contexts == null || !contexts.containsKey(KEY_SECRET_INPUT)) {
            return false;
        }
        Context secretCtx = contexts.get(KEY_SECRET_INPUT);
        return secretCtx != null && "true".equals(String.valueOf(secretCtx.getValue()));
    }

    private void storeUserInputInMemory(String message, List<IData<?>> lifecycleData, boolean isSecretInput) {
        IData<?> initialData;
        IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        if (!"".equals(message.trim())) {
            // The actual plaintext flows through lifecycle data so PropertySetterTask
            // can vault it. But the conversation output (persisted + returned to client)
            // is scrubbed when the client marks the input as secret.
            initialData = new Data<>(INPUT_INITIAL.key(), message);
            initialData.setPublic(true);
            lifecycleData.add(initialData);

            String displayValue = isSecretInput ? SECRET_INPUT_PLACEHOLDER : message;
            currentStep.addConversationOutputString(INPUT.key(), displayValue);
        }
    }

    private void executeConversationStep(List<IData<?>> lifecycleData, List<String> lifecycleTaskTypes)
            throws LifecycleException {
        boolean paused = false;
        try {
            executeWorkflows(lifecycleData, lifecycleTaskTypes);
        } catch (ConversationStopException unused) {
            endConversation();
        } catch (ConversationPauseException e) {
            if (conversationMemory.isCancelled()) {
                // A cancel that landed while the pausing task ran wins over the
                // pause — the caller was told CANCELLED; committing a pause here
                // would park the conversation AWAITING_HUMAN despite the cancel.
                endConversation();
            } else {
                pauseConversation(e);
                paused = true;
            }
        }
        if (!paused) {
            try {
                postConversationLifecycleTasks();
            } catch (IResourceStore.ResourceStoreException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            }
        }
    }

    private void checkActionsForConversationEnd() {
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(ACTIONS);
        if (actionData != null) {
            List<String> result = actionData.getResult();
            if (result != null && result.contains(CONVERSATION_END)) {
                endConversation();
            }
        }
    }

    private void removeOldInvalidProperties() {
        IConversationProperties conversationProperties = conversationMemory.getConversationProperties();
        Map<String, Property> filteredConversationProperties = conversationProperties.entrySet().stream()
                .filter(property -> property.getValue().getScope() != Scope.step).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        conversationProperties.clear();
        conversationProperties.putAll(filteredConversationProperties);
    }

    /**
     * Persists all longTerm properties to the {@code usermemories} collection.
     * <p>
     * Visibility is applied at the persistence boundary:
     * <ul>
     * <li>If the property has explicit visibility → use it</li>
     * <li>If the property has no visibility (null) → default to {@code global}
     * (matches legacy flat properties behavior)</li>
     * </ul>
     */
    private void storePropertiesPermanently() throws IResourceStore.ResourceStoreException {
        IUserMemoryStore store = propertiesHandler.getUserMemoryStore();
        if (store == null)
            return;

        String userId = conversationMemory.getUserId();
        String agentId = conversationMemory.getAgentId();
        String conversationId = conversationMemory.getConversationId();

        // Determine the agent's configured default visibility (from UserMemoryConfig).
        // Falls back to global if no config (matches legacy unscoped behavior).
        AgentConfiguration.UserMemoryConfig config = conversationMemory.getUserMemoryConfig();
        Visibility configDefault = Visibility.global;
        if (config != null) {
            try {
                configDefault = Visibility.valueOf(config.getDefaultVisibility());
            } catch (IllegalArgumentException e) {
                configDefault = Visibility.global;
            }
        }

        for (Property property : conversationMemory.getConversationProperties().values()) {
            if (property.getScope() == Scope.longTerm) {
                // Apply visibility at persistence boundary only
                Visibility vis = property.getVisibility() != null ? property.getVisibility() : configDefault;
                UserMemoryEntry entry = UserMemoryEntry.fromProperty(property, userId, agentId, conversationId, vis);
                store.upsert(entry);
            }
        }
    }

    /**
     * Extracts groupId(s) from the conversation context map.
     * GroupConversationService puts "groupId" in the context when creating member
     * conversations.
     */
    private static List<String> extractGroupIds(Map<String, Context> context) {
        if (context == null)
            return List.of();
        Context groupCtx = context.get("groupId");
        if (groupCtx != null && groupCtx.getValue() != null) {
            return List.of(String.valueOf(groupCtx.getValue()));
        }
        return List.of();
    }

    private static Property entryToProperty(UserMemoryEntry entry) {
        Object value = entry.value();
        Property prop;
        if (value instanceof String s) {
            prop = new Property(entry.key(), s, Scope.longTerm);
        } else if (value instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            prop = new Property(entry.key(), map, Scope.longTerm);
        } else if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> objectList = (List<Object>) list;
            prop = new Property(entry.key(), objectList, Scope.longTerm);
        } else if (value instanceof Integer i) {
            prop = new Property(entry.key(), i, Scope.longTerm);
        } else if (value instanceof Float f) {
            prop = new Property(entry.key(), f, Scope.longTerm);
        } else if (value instanceof Boolean b) {
            prop = new Property(entry.key(), b, Scope.longTerm);
        } else {
            prop = new Property(entry.key(), value != null ? value.toString() : null, Scope.longTerm);
        }
        prop.setVisibility(entry.visibility());
        return prop;
    }

    private List<IData<Context>> createContextData(Map<String, Context> context) {
        List<IData<Context>> contextData = new LinkedList<>();
        if (context != null) {
            for (String key : context.keySet()) {
                // Persisted copy is scrubbed of inline base64 payloads; the live payload
                // has already been captured into ATTACHMENTS memory for this turn.
                Context persistedCopy = AttachmentContextExtractor.scrubInlinePayload(key, context.get(key));
                contextData.add(new Data<>(KEY_CONTEXT + ":" + key, persistedCopy));
            }
        }
        return contextData;
    }

    private void executeWorkflows(List<IData<?>> data, List<String> lifecycleTaskTypes)
            throws LifecycleException, ConversationStopException, ConversationPauseException {
        for (IExecutableWorkflow executableWorkflow : executableWorkflows) {
            conversationMemory.getCurrentStep().setCurrentWorkflowId(executableWorkflow.getWorkflowId());
            data.stream().filter(Objects::nonNull).forEach(datum -> conversationMemory.getCurrentStep().storeData(datum));
            ILifecycleManager lifecycleManager = executableWorkflow.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory, lifecycleTaskTypes);
        }
    }

    /**
     * Public output key + value for the transient RULE-pause awaiting-approval
     * marker.
     */
    private static final String HITL_STATUS_OUTPUT_KEY = "hitl:status";
    private static final String HITL_STATUS_AWAITING = "awaiting_approval";

    /**
     * Removes the transient {@code hitl:status=awaiting_approval} marker (both the
     * step Data and the conversation-output entry) on resume, so a resolved turn no
     * longer advertises "awaiting approval" to state-aware clients. Only RULE
     * pauses ever write it, so this is a no-op for TOOL_CALL pauses.
     */
    private void clearHitlStatusMarker(IWritableConversationStep currentStep) {
        currentStep.removeData(HITL_STATUS_OUTPUT_KEY);
        currentStep.removeConversationOutput(HITL_STATUS_OUTPUT_KEY);
    }

    private void pauseConversation(ConversationPauseException e) {
        setConversationState(ConversationState.AWAITING_HUMAN);
        conversationMemory.setHitlPausedWorkflowId(e.getPausedWorkflowId());
        conversationMemory.setHitlPausedAbsoluteTaskIndex(e.getPausedAbsoluteTaskIndex());
        conversationMemory.setHitlPausedAt(Instant.now());
        conversationMemory.setHitlPauseReason(e.getPauseReason());
        conversationMemory.setHitlPauseType(e.getPauseOrigin().name());
        if (e.getPauseOrigin() == ConversationPauseException.PauseOrigin.TOOL_CALL) {
            // End-user visibility: a tool pause aborts the turn BEFORE the output /
            // templating tasks run, so without this the chat client renders NOTHING
            // for the paused turn. Mirror the REJECTED path's Data/output pattern.
            String pending = resolvePendingMessage(conversationMemory);
            var pendingData = new Data<>(MemoryKeys.OUTPUT_PREFIX, List.of(pending));
            pendingData.setPublic(true);
            conversationMemory.getCurrentStep().storeData(pendingData);
            conversationMemory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(pending));
        } else {
            // A RULE pause must never carry a stale tool batch (e.g. the gate tripped
            // earlier in the same turn on a path that recovered) — belt and braces.
            clearToolPauseState();
            // A RULE pause aborts the turn BEFORE the output/templating tasks run, so
            // the paused step would otherwise commit an EMPTY conversationOutput and a
            // client that renders turns from the output list shows a blank bubble.
            // Emit a public hitl:status marker (framework plan §6.4) so state-aware
            // clients can render an "awaiting approval" indicator. It is removed on
            // resume (clearHitlStatusMarker) so the resolved turn no longer advertises
            // "awaiting approval" — the marker is transient to the paused state.
            var statusData = new Data<>(HITL_STATUS_OUTPUT_KEY, HITL_STATUS_AWAITING);
            statusData.setPublic(true);
            conversationMemory.getCurrentStep().storeData(statusData);
            conversationMemory.getCurrentStep().addConversationOutputString(HITL_STATUS_OUTPUT_KEY, HITL_STATUS_AWAITING);
        }
    }

    /**
     * Default end-user pending message used when the agent config does not supply a
     * {@code toolApprovals.pendingMessage}.
     */
    private static final String DEFAULT_PENDING_MESSAGE = "This action requires human approval before it can proceed. "
            + "You will receive the result once a reviewer decides.";

    /**
     * Resolves the end-user-facing pending message for a tool pause:
     * {@code toolApprovals.pendingMessage} with {@code {toolNames}} substituted
     * from the pending batch's gated call names, falling back to a generic default.
     */
    private String resolvePendingMessage(IConversationMemory memory) {
        String template = null;
        var batch = memory.getHitlPendingToolCalls();
        // Fix #1: prefer the task-scoped effective config that ACTUALLY gated this
        // batch (persisted by AgentOrchestrator.buildPendingBatch) over the agent-level
        // default. A legacy batch (null effective config) or a null batch falls back to
        // the agent-level config exactly as before.
        var cfg = batch != null && batch.getEffectiveToolApprovals() != null
                ? batch.getEffectiveToolApprovals()
                : memory.getAgentToolApprovalsConfig();
        if (cfg != null && !isNullOrEmpty(cfg.getPendingMessage())) {
            template = cfg.getPendingMessage();
        }
        if (template == null) {
            template = DEFAULT_PENDING_MESSAGE;
        }
        String names = "";
        if (batch != null && batch.getCalls() != null) {
            names = batch.getCalls().stream()
                    .map(c -> c.getToolName())
                    .filter(Objects::nonNull)
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }
        return template.replace("{toolNames}", names);
    }

    /**
     * Removes the pending-approval placeholder that {@link #pauseConversation}
     * added to the current step on a TOOL_CALL pause, so the resumed step renders
     * ONLY the final answer.
     * <p>
     * Robust identification without dropping legitimate earlier output: the
     * placeholder is the exact string produced by {@link #resolvePendingMessage},
     * which is deterministic from the still-present pending batch (its effective
     * tool-approvals config and gated call names survive on memory until LlmTask
     * consumes them). We recompute that string and remove ONLY that value from the
     * {@code "output"} conversation-output list — an earlier task's output in a
     * multi-task step (e.g. {@code [earlierOutput, placeholder]}) keeps its entry.
     * <p>
     * We also blank the mirror public step-{@code Data<>} that pauseConversation
     * stored under the bare {@code "output"} key (surfaced in detailed step-data
     * snapshots via {@code startsWith("output")}) when it still holds exactly the
     * placeholder — otherwise a client reading the detailed view would see the
     * stale placeholder a second time. We overwrite that EXACT key with an empty
     * list (not {@code removeData}, whose {@code startsWith} semantics would also
     * wipe an earlier task's {@code output:text:*} data in a multi-task step) and
     * only when it is untouched (equals {@code [placeholder]}); if some other
     * writer replaced it we leave it alone.
     */
    private void dropPendingApprovalPlaceholder(IWritableConversationStep currentStep) {
        String pending = resolvePendingMessage(conversationMemory);
        currentStep.removeConversationOutputListItem(MemoryKeys.OUTPUT_PREFIX, pending);

        IData<?> outputData = currentStep.getData(MemoryKeys.OUTPUT_PREFIX);
        if (outputData != null && List.of(pending).equals(outputData.getResult())) {
            var blanked = new Data<>(MemoryKeys.OUTPUT_PREFIX, new ArrayList<>());
            blanked.setPublic(true);
            currentStep.storeData(blanked);
        }
    }

    /**
     * Nulls the transient tool-pause state on the live memory (pause type, pending
     * batch, in-JVM resume decision). Distinct from {@link #clearHitlBookmark()},
     * which owns only the six bookmark fields — this must NOT touch those.
     */
    private void clearToolPauseState() {
        conversationMemory.setHitlPauseType(null);
        conversationMemory.setHitlPendingToolCalls(null);
        conversationMemory.setHitlResumeDecision(null);
    }

    @Override
    public void resume(HitlDecision decision)
            throws LifecycleException, ConversationNotReadyException {
        if (getConversationState() != ConversationState.AWAITING_HUMAN) {
            throw new ConversationNotReadyException("Not in AWAITING_HUMAN state");
        }
        try {
            setConversationState(ConversationState.IN_PROGRESS);

            // Store decision in step data (internal — not template-visible via colon keys)
            conversationMemory.getCurrentStep().storeData(new Data<>("hitl:decision_verdict", decision.getVerdict().name()));
            if (decision.getNote() != null)
                conversationMemory.getCurrentStep().storeData(new Data<>("hitl:decision_note", decision.getNote()));
            if (decision.getDecidedBy() != null)
                conversationMemory.getCurrentStep().storeData(new Data<>("hitl:decision_by", decision.getDecidedBy()));

            // Decision visibility (Phase 1b): make verdict accessible in templates
            // and behavior rules. ConversationOutput → {memory.current.hitlDecision},
            // property → {properties.hitlVerdict} for next-turn behavior rule matching.
            var currentStep = conversationMemory.getCurrentStep();
            currentStep.addConversationOutputString("hitlDecision", decision.getVerdict().name());
            if (decision.getNote() != null) {
                currentStep.addConversationOutputString("hitlDecisionNote", decision.getNote());
            }
            var props = conversationMemory.getConversationProperties();
            props.put("hitlVerdict",
                    new Property("hitlVerdict", decision.getVerdict().name(), Scope.conversation));

            // TOOL_CALL pauses re-enter the SAME task (same index) on BOTH verdicts:
            // APPROVED runs the approved calls, and REJECTED is turned by LlmTask into
            // synthetic tool-rejection results so the model can still answer the user
            // gracefully (Task 9). Only RULE pauses short-circuit on REJECTED.
            boolean toolPause = "TOOL_CALL".equals(conversationMemory.getHitlPauseType());

            if (decision.getVerdict() == HitlDecision.HitlVerdict.REJECTED && !toolPause) {
                String rejectionMessage = "This action was rejected by a human reviewer."
                        + (decision.getNote() != null ? " Reason: " + decision.getNote() : "");
                // Public step data for snapshot consumers + ConversationOutput so
                // log generation and UIs (which read conversationOutputs["output"])
                // actually render the rejection feedback.
                var rejectionData = new Data<>(MemoryKeys.OUTPUT_PREFIX, List.of(rejectionMessage));
                rejectionData.setPublic(true);
                currentStep.storeData(rejectionData);
                currentStep.addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(rejectionMessage));
                clearHitlBookmark();
                // The step's ACTIONS data still contains PAUSE_CONVERSATION from the
                // paused turn — strip it (as the APPROVED path does) so a later
                // rerun/undo of this step can never re-trigger the gate from stale
                // action data.
                stripPauseAction(currentStep);
                return;
            }

            String pausedWorkflowId = conversationMemory.getHitlPausedWorkflowId();
            // RULE pauses resume AFTER the paused task (the rule already evaluated;
            // +1 = backward compat). TOOL_CALL pauses resume AT the paused task so
            // LlmTask re-enters the same LLM turn and consumes the pending batch.
            int resumeFromIndex = conversationMemory.getHitlPausedAbsoluteTaskIndex() + (toolPause ? 0 : 1);
            // Stash the decision on the live memory BEFORE clearing the bookmark so
            // LlmTask.executeResume (reached at the same index) can consume it. The
            // pending batch + pauseType survive clearHitlBookmark() — that method only
            // owns the six bookmark fields — and are cleared by LlmTask after
            // consumption (Task 9) or by the finally safety-net below.
            if (toolPause) {
                conversationMemory.setHitlResumeDecision(decision);
                // Fix #2: a TOOL_CALL resume re-enters the SAME step at the SAME task
                // index, so LlmTask.executeResume APPENDS the final answer to the same
                // "output" conversation-output list that pauseConversation seeded with
                // the pending-approval placeholder. addConversationOutputList never
                // replaces, so without this the turn would render
                // [placeholder, finalAnswer]. Drop the placeholder now — BEFORE the
                // pipeline re-enters — so the resumed step renders ONLY the final answer
                // (on both the APPROVED and the REJECTED-tool graceful-answer paths).
                // The placeholder is still shown while AWAITING_HUMAN; only this resume
                // removes it. We recompute the exact same string via resolvePendingMessage
                // (deterministic: the batch + its effective config are still on memory,
                // see PendingToolCallBatch#effectiveToolApprovals) and remove ONLY that
                // value, so an earlier task's legitimate output in a multi-task step is
                // preserved.
                dropPendingApprovalPlaceholder(currentStep);
            }
            // Drop the transient RULE-pause awaiting-approval marker so the resolved
            // turn does not keep advertising "awaiting approval". No-op for TOOL_CALL
            // pauses (which never write it); if the resume re-pauses, pauseConversation
            // re-adds it.
            clearHitlStatusMarker(currentStep);
            clearHitlBookmark();

            // Belt-and-braces for Blocker #1: strip PAUSE_CONVERSATION from the
            // step's ACTIONS data before re-entering the pipeline. The primary
            // fix is the delta-based check in LifecycleManager; this ensures
            // stale actions can never re-trigger even if the delta check is
            // bypassed by a code path that doesn't snapshot actionsBefore.
            stripPauseAction(currentStep);

            boolean foundPaused = false;
            for (IExecutableWorkflow workflow : executableWorkflows) {
                String wfId = workflow.getWorkflowId();
                conversationMemory.getCurrentStep().setCurrentWorkflowId(wfId);
                if (!foundPaused) {
                    if (wfId.equals(pausedWorkflowId)) {
                        foundPaused = true;
                        workflow.getLifecycleManager().executeLifecycleFromIndex(conversationMemory, resumeFromIndex);
                    }
                } else {
                    workflow.getLifecycleManager().executeLifecycle(conversationMemory, null);
                }
            }
            if (!foundPaused) {
                LOGGER.warnf("Resume: pausedWorkflowId '%s' not found in executable workflows", pausedWorkflowId);
                setConversationState(ConversationState.ERROR);
                throw new LifecycleException("Paused workflow '" + pausedWorkflowId + "' no longer exists (config drift)");
            }
        } catch (ConversationStopException unused) {
            endConversation();
        } catch (ConversationPauseException e) {
            if (conversationMemory.isCancelled()) {
                endConversation(); // cancel wins over a re-pause (see executeConversationStep)
            } else {
                pauseConversation(e);
            }
        } catch (Exception e) {
            setConversationState(ConversationState.ERROR);
            throw new LifecycleException(e.getLocalizedMessage(), e);
        } finally {
            checkActionsForConversationEnd();
            ConversationState finalState = getConversationState();
            if (finalState == ConversationState.IN_PROGRESS)
                setConversationState(ConversationState.READY);
            // Tool-pause safety-net: the batch normally survives clearHitlBookmark()
            // until LlmTask consumes it and clears it. But on any exit where LlmTask
            // did NOT consume it — config drift, a degraded path, an error, or simply
            // a lifecycle that never reached the paused LlmTask — a stale batch would
            // linger on memory and poison the next turn's resume-mode detection. If the
            // batch is still present and this was NOT a fresh re-pause (AWAITING_HUMAN),
            // clear it. A fresh re-pause legitimately re-arms the batch, so leave it.
            if (conversationMemory.getHitlPendingToolCalls() != null && finalState != ConversationState.AWAITING_HUMAN) {
                clearToolPauseState();
            }
            // Persist long-term properties only on a clean outcome. Skip on a
            // re-pause (AWAITING_HUMAN — the pause is not the end of the turn) and
            // on ERROR — mirroring the say path (executeConversationStep only runs
            // post-tasks when execution did not throw), so a failed resume does not
            // upsert partial/inconsistent property state into the user memory store.
            if (finalState != ConversationState.AWAITING_HUMAN && finalState != ConversationState.ERROR) {
                try {
                    postConversationLifecycleTasks();
                } catch (IResourceStore.ResourceStoreException ex) {
                    LOGGER.error("post-conversation tasks on resume failed", ex);
                }
            }
            if (outputProvider != null)
                outputProvider.renderOutput(conversationMemory);
        }
    }

    private void clearHitlBookmark() {
        conversationMemory.setHitlPausedWorkflowId(null);
        conversationMemory.setHitlPausedAbsoluteTaskIndex(-1);
        conversationMemory.setHitlPausedAt(null);
        conversationMemory.setHitlPauseReason(null);
        conversationMemory.setHitlTimeoutPolicy(null);
        conversationMemory.setHitlApprovalTimeout(null);
    }

    /**
     * Strips the PAUSE_CONVERSATION action from the step's ACTIONS data.
     * Belt-and-braces for Blocker #1: even if the delta-based check in
     * LifecycleManager is bypassed, the stale action is no longer present.
     */
    private void stripPauseAction(IConversationMemory.IWritableConversationStep step) {
        IData<List<String>> actionData = step.getLatestData(ACTIONS.key());
        if (actionData == null)
            return;
        List<String> actions = actionData.getResult();
        if (actions != null && actions.contains(IConversation.PAUSE_CONVERSATION)) {
            List<String> cleaned = new java.util.ArrayList<>(actions);
            cleaned.remove(IConversation.PAUSE_CONVERSATION);
            IData<List<String>> replacement = new Data<>(ACTIONS.key(), cleaned);
            step.storeData(replacement);
        }
    }
}
