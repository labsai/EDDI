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
import java.util.regex.Pattern;
import java.util.ArrayList;

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
    private static final String KEY_PROPERTIES = "properties";
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

    /**
     * The longTerm properties as they stood when this turn started — the baseline
     * {@link #storePropertiesPermanently()} diffs against so untouched memories are
     * not rewritten on every turn. A new {@code Conversation} is built per turn
     * ({@code Agent#continueConversation}), so the constructor snapshot is exactly
     * "state at the start of this turn"; {@code init()} re-captures after the store
     * load.
     * <p>
     * <strong>The baseline alone is NOT proof of persistence.</strong> It is taken
     * from the conversation properties, which are restored from the conversation
     * document — and that document also contains longTerm values a previous turn
     * set but never wrote (HITL pause, error, cancel). Those are tracked separately
     * as {@link IConversationMemory#getPendingLongTermWrites() deferred writes} and
     * are persisted regardless of the diff.
     */
    private final Map<String, Property> longTermBaseline = new HashMap<>();

    Conversation(List<IExecutableWorkflow> executableWorkflows, IConversationMemory conversationMemory, IPropertiesHandler propertiesHandler,
            IConversationOutputRenderer outputProvider) {
        this.executableWorkflows = executableWorkflows;
        this.conversationMemory = conversationMemory;
        this.propertiesHandler = propertiesHandler;
        this.outputProvider = outputProvider;
        applyUserMemoryConfig();
        captureRestoredLongTermBaseline();
    }

    /**
     * Carries the agent's user-memory configuration onto this turn's memory object.
     * <p>
     * This has to happen per turn, not per conversation. The field is not part of
     * the persisted snapshot, and every request rebuilds memory from the store — so
     * setting it only in {@link #init()} meant it was present for the
     * CONVERSATION_START turn and null for every turn after it. The gate in
     * {@code ContextualToolsProvider.addUserMemoryToolIfEnabled} reads exactly that
     * field, so the {@code UserMemoryTool} was never assembled on a turn a user
     * could actually talk to. An agent with memory fully enabled could not write a
     * single memory, silently: no error, no log line, the store simply stayed at
     * zero — and the model, handed no tool, went on to state that it had saved
     * things it had not, once leaking raw tool-call syntax into user-visible
     * output. The constructor is the one point every path goes through
     * ({@code Agent#continueConversation} builds a Conversation for say, resume and
     * rerun alike).
     */
    private void applyUserMemoryConfig() {
        AgentConfiguration.UserMemoryConfig memoryConfig = propertiesHandler.getUserMemoryConfig();
        if (memoryConfig != null) {
            conversationMemory.setUserMemoryConfig(memoryConfig);
        }
    }

    /**
     * Constructor-time baseline capture, i.e. over memory hydrated from the
     * conversation document.
     * <p>
     * The baseline must model what the USER MEMORY STORE holds, not what the
     * conversation document holds — and those two diverge whenever a turn ends
     * without {@link #postConversationLifecycleTasks()} running. A HITL pause is
     * the canonical case: {@code executeConversationStep} skips the post-tasks (so
     * nothing is upserted to {@code usermemories}), yet the AWAITING_HUMAN snapshot
     * still writes every conversation property into the conversation document. If
     * the next {@code Conversation} (built by {@code Agent#continueConversation} to
     * serve the resume) captured that document as its baseline, the diff would
     * report "unchanged" and the property would never be persisted — silently and
     * permanently lost. The same holds for a turn that ended in ERROR or was
     * interrupted.
     * <p>
     * So the document is only trusted as a persisted baseline when the previous
     * turn demonstrably completed (READY / ENDED), or when there is no previous
     * turn at all (state still {@code null} on a freshly created memory). Otherwise
     * the baseline stays empty and every longTerm property is written — the
     * pre-diff behaviour, which is safe because {@code upsert} is idempotent.
     */
    private void captureRestoredLongTermBaseline() {
        longTermBaseline.clear();
        if (conversationMemory == null) {
            return;
        }
        ConversationState restoredState = conversationMemory.getConversationState();
        if (!priorTurnPersistedLongTermProperties(restoredState)) {
            LOGGER.debugf("[MEMORY] Conversation %s restored in state %s — the previous turn never ran "
                    + "storePropertiesPermanently(), so its longTerm properties are treated as unpersisted.",
                    sanitize(conversationMemory.getConversationId()), restoredState);
            return;
        }
        captureLongTermBaseline();
    }

    private static boolean priorTurnPersistedLongTermProperties(ConversationState restoredState) {
        return restoredState == null
                || restoredState == ConversationState.READY
                || restoredState == ConversationState.ENDED;
    }

    /**
     * Records the current longTerm properties as the persisted baseline for this
     * turn.
     */
    private void captureLongTermBaseline() {
        longTermBaseline.clear();
        if (conversationMemory == null) {
            return;
        }
        IConversationProperties properties = conversationMemory.getConversationProperties();
        if (properties == null) {
            return;
        }
        properties.forEach((key, property) -> {
            if (property != null && property.getScope() == Scope.longTerm) {
                longTermBaseline.put(key, property);
            }
        });
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

        // The config is applied in the constructor, which every turn goes through —
        // init() is only the first of them. Re-applying is harmless but pointless.

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

            // Everything just loaded came FROM the store — it is by definition already
            // persisted and must not be re-upserted at the end of this turn.
            captureLongTermBaseline();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new LifecycleException("Failed to load user properties: " + e.getLocalizedMessage(), e);
        }
    }

    private ConversationState getConversationState() {
        return this.conversationMemory.getConversationState();
    }

    /**
     * The step results a rerun discards before re-executing: the rendered answer
     * and its quick replies.
     */
    private static final List<String> RERUN_CLEARED_RESULT_TYPES = List.of("output", "quickReplies");

    /**
     * Where a rerun restarts the pipeline — the earliest task type that can
     * <em>produce</em> what {@link #RERUN_CLEARED_RESULT_TYPES} discards.
     * <p>
     * Selective execution runs the suffix of the pipeline from the first task
     * matching any of these types, so this set and the cleared set have to agree: a
     * rerun must never clear a result it will not regenerate. Restarting at
     * {@code output} alone did exactly that on any LLM agent. The model's answer is
     * stored under {@code output} but is written by the {@code langchain} task,
     * which sits <em>before</em> the output task — so the answer was wiped,
     * {@code ai.labs.llm} never re-ran, and the output task alone had nothing left
     * to render. The turn came back 200 with {@code conversationOutputs[n].output}
     * an empty array, destroying the reply instead of retrying it — and taking it
     * out of the model's own history with it.
     * <p>
     * Restarting at {@code langchain} re-runs only the model and everything after
     * it. Tasks before it — parser, behavior rules, property setters, HTTP calls —
     * keep their results, so a retry does not re-fire external side effects. A
     * rule-based agent has no {@code langchain} task, so it still restarts at
     * {@code output} and behaves exactly as before.
     */
    private static final List<String> RERUN_RESTART_TASK_TYPES = List.of("langchain", "output", "quickReplies");

    @Override
    public void rerun(final Map<String, Context> contexts) throws ConversationNotReadyException, LifecycleException {
        runStep("", contexts, false, RERUN_CLEARED_RESULT_TYPES, RERUN_RESTART_TASK_TYPES);
    }

    @Override
    public void say(final String message, final Map<String, Context> contexts) throws LifecycleException, ConversationNotReadyException {
        runStep(message, contexts, true, new LinkedList<>(), new LinkedList<>());
    }

    /**
     * @param clearedResultTypes
     *            task-type results to drop from the current step before executing
     * @param restartTaskTypes
     *            the pipeline restarts at the first task matching one of these;
     *            empty means "run every task"
     */
    private void runStep(String message, Map<String, Context> contexts, boolean startNewStep,
                         List<String> clearedResultTypes, List<String> restartTaskTypes)
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

            var lifecycleData = prepareLifecycleData(message, contexts, clearedResultTypes);
            executeConversationStep(lifecycleData, restartTaskTypes);

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
        } finally {
            // BEFORE the persist decision below, and on every exit including the
            // exception path: note which longTerm properties this turn changed. If the
            // turn does not reach storePropertiesPermanently (pause / error / cancel)
            // the marker travels with the conversation document and the next completed
            // turn writes them. Without it the write is lost forever — the next turn's
            // baseline comes from the same document and therefore already contains the
            // un-persisted value.
            recordPendingLongTermWrites();
        }
        // A turn whose outcome is discarded must not commit its side effects.
        // ConversationService discards the snapshot of a cancelled or abandoned turn,
        // but storePropertiesPermanently() writes straight to the user-memory store,
        // so without this guard such a turn still upserts whatever longTerm properties
        // it managed to set before stopping — the same "a failed turn must not persist
        // partial state" rule the ERROR path already follows.
        if (!paused && !isTurnDiscarded()) {
            try {
                postConversationLifecycleTasks();
            } catch (IResourceStore.ResourceStoreException e) {
                throw new LifecycleException(e.getLocalizedMessage(), e);
            }
        }
    }

    /**
     * True when this turn's result will be thrown away, so its side effects outside
     * the conversation snapshot must not be committed either.
     * <p>
     * Two independent abort signals reach a running turn, and only one of them is a
     * flag on the memory:
     * <ul>
     * <li>a cooperative <em>cancel</em> ({@code /cancel}, client disconnect) sets
     * {@code conversationMemory.setCancelled(true)}; the pipeline observes it and
     * the snapshot is discarded;</li>
     * <li>the runtime watchdog <em>abandons</em> a turn that exceeded
     * {@code agentTimeoutInSeconds} by interrupting the pipeline thread —
     * {@code BaseRuntime.AbandonableFuture#cancel} only sets its own
     * {@code abandoned} flag and interrupts, it never sets the memory's cancel flag
     * — and routes the late completion to {@code onFailure}, so the conversation
     * document is never stored.</li>
     * </ul>
     * Only the first was checked here, so an interrupt that landed during the last
     * task of the last workflow (a short in-memory task such as output or
     * templating; interruptibly-blocking tasks throw instead) left the pipeline
     * returning normally and this turn's changed longTerm properties upserted into
     * {@code usermemories} for a turn whose conversation document was then thrown
     * away. Narrow race, pre-existing; the interrupt flag is only read here, never
     * cleared, so the runtime still sees it.
     */
    private boolean isTurnDiscarded() {
        return conversationMemory.isCancelled() || Thread.currentThread().isInterrupted();
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

    /**
     * Drops the {@code step}-scoped properties at the end of a turn.
     * <p>
     * Removes in place rather than {@code clear()} + {@code putAll(surviving)}: the
     * re-put mirrored every surviving property into the step data and conversation
     * output AGAIN, once per turn, which is the quadratic term in a long
     * conversation's document growth. In-place removal is only safe because
     * {@code ConversationProperties#toMap()} is derived from the map instead of
     * being maintained as a parallel structure.
     */
    private void removeOldInvalidProperties() {
        IConversationProperties conversationProperties = conversationMemory.getConversationProperties();
        IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        conversationProperties.entrySet().removeIf(entry -> {
            Property property = entry.getValue();
            if (property == null) {
                return true;
            }
            if (property.getScope() != Scope.step) {
                return false;
            }
            dropMirroredProperty(currentStep, entry.getKey(), property.getName());
            return true;
        });
    }

    /**
     * Drops a step-scoped property's mirrored conversation-output entry.
     * <p>
     * {@code ConversationProperties} mirrors step-scoped properties into the
     * current step's conversation output so {@code {memory.current.properties.X}}
     * resolves DURING the turn that set them. Removing the mirror here — before the
     * step is persisted — keeps them out of the conversation document and out of
     * the next turn's {@code {memory.last.properties.X}}, which is what "cleared at
     * the end of the turn" has to mean for the projection as well as for the live
     * map.
     */
    private static void dropMirroredProperty(IWritableConversationStep currentStep, String key, String propertyName) {
        if (currentStep == null || currentStep.getConversationOutput() == null) {
            return;
        }
        Object mirrored = currentStep.getConversationOutput().get(KEY_PROPERTIES);
        if (mirrored instanceof Map<?, ?> mirroredProperties) {
            // The mirror is keyed on the property NAME; the map key is removed as
            // well because the two can differ.
            if (propertyName != null) {
                mirroredProperties.remove(propertyName);
            }
            if (key != null) {
                mirroredProperties.remove(key);
            }
        }
    }

    /**
     * Persists the longTerm properties that actually CHANGED during this turn to
     * the {@code usermemories} collection.
     * <p>
     * Visibility is applied at the persistence boundary:
     * <ul>
     * <li>If the property has explicit visibility → use it</li>
     * <li>If the property has no visibility (null) → default to {@code global}
     * (matches legacy flat properties behavior)</li>
     * </ul>
     * <p>
     * <strong>Why the diff matters:</strong> every entry recalled at conversation
     * init is tagged {@code longTerm}, so upserting all of them each turn refreshed
     * {@code updatedAt} on memories nobody touched. That degenerated
     * {@code recallOrder: "most_recent"} into "everything is recent" and made
     * {@code deleteOlderThan} retention unable to ever expire anything for an
     * active user. Only differing values are written now.
     * <p>
     * A property set during a turn that FAILS before the post-conversation tasks
     * run is not persisted by THAT turn — the same deliberate choice the resume
     * path already makes (it skips this method on ERROR so a failed turn cannot
     * upsert partial state). The write is not lost though: the failed turn records
     * the key in {@link IConversationMemory#getPendingLongTermWrites()}, which
     * travels with the conversation document, and the next turn that completes
     * cleanly writes it even though the value now equals its baseline.
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

        // Writes owed by an earlier turn that never reached this method. They are
        // indistinguishable from "unchanged" by value, so they are driven by the
        // marker instead. Entries are removed one by one as the store accepts them,
        // and whatever is left is written back — a store failure halfway through the
        // loop therefore retries only the keys that were not written.
        Set<String> pending = new LinkedHashSet<>(conversationMemory.getPendingLongTermWrites());
        try {
            for (Map.Entry<String, Property> propertyEntry : conversationMemory.getConversationProperties().entrySet()) {
                Property property = propertyEntry.getValue();
                if (property == null || property.getScope() != Scope.longTerm) {
                    continue;
                }
                boolean writeOwed = pending.contains(propertyEntry.getKey());
                if (!writeOwed && property.equals(longTermBaseline.get(propertyEntry.getKey()))) {
                    // Unchanged since the turn started AND nothing owed — already
                    // persisted, skip the write.
                    continue;
                }
                // Apply visibility at persistence boundary only
                Visibility vis = property.getVisibility() != null ? property.getVisibility() : configDefault;
                UserMemoryEntry entry = UserMemoryEntry.fromProperty(property, userId, agentId, conversationId, vis);
                store.upsert(entry);
                pending.remove(propertyEntry.getKey());
            }
            // Every live longTerm property has been considered, so a leftover marker
            // refers to a property that no longer exists — there is nothing to write.
            pending.clear();
        } finally {
            conversationMemory.setPendingLongTermWrites(pending);
        }

        // The turn's writes are now the new baseline — a resume that persists twice
        // within the same Conversation must not rewrite them a second time.
        captureLongTermBaseline();
    }

    /**
     * Marks every longTerm property that differs from this turn's baseline as a
     * write the user-memory store is still owed, unioned with whatever was already
     * owed. Called on EVERY exit of a turn (see
     * {@link #executeConversationStep}/{@link #resume}) — on the clean path
     * {@link #storePropertiesPermanently()} immediately consumes and clears the
     * marker; on a pause/error/cancel it survives on the conversation document and
     * the next completed turn honours it.
     */
    private void recordPendingLongTermWrites() {
        if (conversationMemory == null) {
            return;
        }
        IConversationProperties properties = conversationMemory.getConversationProperties();
        if (properties == null) {
            return;
        }
        Set<String> pending = new LinkedHashSet<>(conversationMemory.getPendingLongTermWrites());
        properties.forEach((key, property) -> {
            if (property != null && property.getScope() == Scope.longTerm && !property.equals(longTermBaseline.get(key))) {
                pending.add(key);
            }
        });
        conversationMemory.setPendingLongTermWrites(pending);
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
            // The model's own narration of what it is about to do goes AHEAD of the
            // placeholder — see PendingToolCallBatch#interimText. Without it a resumed
            // (non-streamed) turn's second approval arrived with no explanation at all,
            // and even a streamed one lost the explanation on reload.
            var outputs = new ArrayList<String>(2);
            var batch = conversationMemory.getHitlPendingToolCalls();
            String interim = batch != null ? batch.getInterimText() : null;
            if (interim != null && !interim.isBlank() && !interim.equals(pending)) {
                outputs.add(interim);
            }
            outputs.add(pending);
            var step = conversationMemory.getCurrentStep();
            // The Data twin must ACCUMULATE, exactly as the output list does: a turn
            // may pause several times, and each pause's explanation is a permanent
            // part of the record — retrospectively, the admin must be able to see
            // everything the model said it was doing. Replacing the twin (as a bare
            // storeData would) kept only the latest pause's text in the detailed
            // step view while the output list kept them all, and two channels that
            // disagree is exactly the class of bug the surgical placeholder drop
            // exists to prevent.
            var accumulated = new ArrayList<Object>();
            IData<?> previous = step.getData(MemoryKeys.OUTPUT_PREFIX);
            if (previous != null && previous.getResult() instanceof List<?> prior) {
                accumulated.addAll(prior);
            }
            accumulated.addAll(outputs);
            var pendingData = new Data<>(MemoryKeys.OUTPUT_PREFIX, accumulated);
            pendingData.setPublic(true);
            step.storeData(pendingData);
            step.addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.copyOf(outputs));
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
     * {@code toolApprovals.pendingMessage} <b>and</b> the gated call names are
     * unknown (a legacy batch, or one whose calls carry no tool name).
     */
    private static final String DEFAULT_PENDING_MESSAGE = "This action requires human approval before it can proceed. "
            + "You will receive the result once a reviewer decides.";

    /** Matches a rendered default carrying the repeat-pause ordinal suffix. */
    private static final Pattern ORDINAL_SUFFIX = Pattern.compile(
            "^(.*) \\(approval \\d+ this turn\\)$", Pattern.DOTALL);

    /**
     * Default pending message when the gated call names ARE known — the normal
     * case.
     * <p>
     * Naming them is what makes a multi-pause turn legible. A turn may pause up to
     * {@code maxPausesPerTurn} times (default 3), and every pause writes this
     * message into the same step's output while the previous one is dropped on
     * resume. With a constant sentence, deciding a batch and landing on the very
     * next pause re-rendered a bubble with byte-identical text — which reads as "I
     * approved it and nothing happened". The gated tool is the thing that actually
     * changed between the two, so it is the thing the message says.
     */
    private static final String DEFAULT_PENDING_MESSAGE_WITH_TOOLS = "I need your approval before I can run {toolNames}. "
            + "You will receive the result once a reviewer decides.";

    /**
     * Resolves the end-user-facing pending message for a tool pause: the governing
     * {@code toolApprovals.rules} entry's {@code pendingMessage} if it set one,
     * else {@code toolApprovals.pendingMessage}, with {@code {toolNames}}
     * substituted from the pending batch's gated call names, falling back to
     * {@link #DEFAULT_PENDING_MESSAGE_WITH_TOOLS} (or, with no names to show,
     * {@link #DEFAULT_PENDING_MESSAGE}).
     * <p>
     * Must stay deterministic from the persisted batch alone —
     * {@code dropPendingApprovalPlaceholder} recomputes this exact string on resume
     * to remove it, so a value that varied between the two calls would leave the
     * placeholder stranded in the resolved turn's output.
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
        var rule = batch != null ? batch.getEffectiveRule() : null;
        // isBlank, not isNullOrEmpty (which is isEmpty-only): a whitespace-only
        // pendingMessage would otherwise be used verbatim and render as an empty
        // bubble instead of falling back to the scalar or the default.
        if (rule != null && rule.getPendingMessage() != null && !rule.getPendingMessage().isBlank()) {
            template = rule.getPendingMessage();
        } else if (cfg != null && !isNullOrEmpty(cfg.getPendingMessage())) {
            template = cfg.getPendingMessage();
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
        // Resolved AFTER the names, because which default applies depends on whether
        // there are any. A configured template is used as-is either way — an operator
        // who wrote their own wording keeps it, with or without {toolNames} in it.
        boolean usedDefault = template == null;
        if (usedDefault) {
            template = names.isBlank() ? DEFAULT_PENDING_MESSAGE : DEFAULT_PENDING_MESSAGE_WITH_TOOLS;
        }
        String rendered = template.replace("{toolNames}", names);
        // Naming the tool made pauses on DIFFERENT tools distinguishable; a turn
        // that pauses twice on the SAME tool (approve → the call fails → the model
        // retries with fixed arguments) still rendered byte-identical text, which
        // reads as a duplicated bubble — or a dead Approve button. The batch's own
        // pause ordinal disambiguates. It is persisted WITH the batch, so the
        // resume-time recomputation in dropPendingApprovalPlaceholder reads the
        // identical value — the determinism that placeholder-dropping requires.
        // Only the built-in default gains the suffix: a configured template is the
        // operator's wording, kept verbatim. First pauses (and legacy batches,
        // whose ordinal is 0) stay clean.
        int pauseOrdinal = batch != null ? batch.getPauseCountThisTurn() : 0;
        if (usedDefault && pauseOrdinal >= 2) {
            rendered += " (approval " + pauseOrdinal + " this turn)";
        }
        return rendered;
    }

    /**
     * Every string {@link #pauseConversation} may have written as the pending
     * placeholder for the CURRENT batch — under this build or a previous one. First
     * entry is what this build writes; the rest are legacy renderings.
     * <p>
     * Exists because the determinism argument ("recompute the exact string on
     * resume") silently assumed pause and resume run the same build. Two releases
     * changed the DEFAULT wording — the tool-named default, then the repeat-pause
     * ordinal — so a conversation paused under the previous build recomputes a
     * string that is not in its output list, the removal no-ops, and the resolved
     * turn renders [stale placeholder, answer]: the exact artifact those changes
     * exist to kill, once per in-flight pause on the first post-upgrade resume.
     * There is no schema migration for conversation output, so the resume path
     * itself must recognise its predecessors' wording.
     * <p>
     * Only DEFAULT renderings accumulate variants — a configured
     * {@code pendingMessage} has never been rewritten by a release, so it stays a
     * single candidate. (An operator editing their template between pause and
     * resume strands the placeholder exactly as before; that is config drift, not a
     * version boundary, and predates all of this.)
     */
    private List<String> pendingPlaceholderCandidates(IConversationMemory memory) {
        String current = resolvePendingMessage(memory);
        var candidates = new ArrayList<String>();
        candidates.add(current);

        var batch = memory.getHitlPendingToolCalls();
        var cfg = batch != null && batch.getEffectiveToolApprovals() != null
                ? batch.getEffectiveToolApprovals()
                : memory.getAgentToolApprovalsConfig();
        var rule = batch != null ? batch.getEffectiveRule() : null;
        boolean configured = (rule != null && rule.getPendingMessage() != null && !rule.getPendingMessage().isBlank())
                || (cfg != null && !isNullOrEmpty(cfg.getPendingMessage()));
        if (!configured) {
            // Pre-ordinal build: the tool-named default without the suffix. The
            // ordinal itself predates the suffix (it was persisted for cap
            // enforcement), so a repeat pause persisted by that build re-reads
            // its ordinal today and gains a suffix the stored text never had.
            var suffix = ORDINAL_SUFFIX.matcher(current);
            if (suffix.matches()) {
                candidates.add(suffix.group(1));
            }
            // Pre-tool-named build: the constant, regardless of names.
            if (!candidates.contains(DEFAULT_PENDING_MESSAGE)) {
                candidates.add(DEFAULT_PENDING_MESSAGE);
            }
        }
        return candidates;
    }

    /**
     * Removes the pending-approval placeholder that {@link #pauseConversation}
     * added to the current step on a TOOL_CALL pause — and ONLY the placeholder, so
     * the resumed step keeps everything the model said it was doing (the interim
     * narration written ahead of the placeholder, and every earlier pause's
     * narration in a multi-pause turn) and gains the final answer.
     * <p>
     * Identification: the placeholder is the exact string produced by
     * {@link #resolvePendingMessage} (or a previous build's rendering — see
     * {@link #pendingPlaceholderCandidates}), deterministic from the still-present
     * pending batch. It is always the TRAILING element the latest pause appended,
     * so the LAST occurrence of a candidate is the one removed — never the first,
     * which on a mixed list could be an earlier output or a piece of narration that
     * happens to equal a candidate string. Applied identically to both channels:
     * the {@code "output"} conversation-output list and its mirror public
     * step-{@code Data<>} under the bare {@code "output"} key (surfaced in detailed
     * step-data snapshots via {@code startsWith("output")}). The Data twin is
     * overwritten with the stripped list on that EXACT key — not
     * {@code removeData}, whose {@code startsWith} semantics would also wipe an
     * earlier task's {@code output:text:*} data in a multi-task step.
     */
    private void dropPendingApprovalPlaceholder(IWritableConversationStep currentStep) {
        List<String> candidates = pendingPlaceholderCandidates(conversationMemory);

        // Output list: drop the LAST candidate occurrence only.
        Object rawList = currentStep.getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX);
        if (rawList instanceof List<?> outputList) {
            int idx = lastCandidateIndex(outputList, candidates);
            if (idx >= 0) {
                outputList.remove(idx);
            }
        }

        // Data twin: same rule, written back on the exact key.
        IData<?> outputData = currentStep.getData(MemoryKeys.OUTPUT_PREFIX);
        if (outputData != null && outputData.getResult() instanceof List<?> stored) {
            int idx = lastCandidateIndex(stored, candidates);
            if (idx >= 0) {
                var kept = new ArrayList<Object>(stored);
                kept.remove(idx);
                var stripped = new Data<>(MemoryKeys.OUTPUT_PREFIX, kept);
                stripped.setPublic(true);
                currentStep.storeData(stripped);
            }
        }
    }

    /**
     * Index of the last element that IS one of the candidate strings, or -1. Object
     * equality on purpose: the placeholder is stored as a plain String, and a
     * rendered {@code TextOutputItem} whose text merely reads the same must not be
     * mistaken for it.
     */
    private static int lastCandidateIndex(List<?> items, List<String> candidates) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Object item = items.get(i);
            if (item instanceof String text && candidates.contains(text)) {
                return i;
            }
        }
        return -1;
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
            // Same contract as the say path: note the owed writes BEFORE deciding
            // whether to persist, so a re-pause / ERROR / cancel carries them to the
            // next turn instead of dropping them.
            recordPendingLongTermWrites();
            // Persist long-term properties only on a clean outcome. Skip on a
            // re-pause (AWAITING_HUMAN — the pause is not the end of the turn), on
            // ERROR, and on a discarded turn (cancelled, or abandoned by the watchdog
            // that guards the resume exactly like the say path) — mirroring
            // executeConversationStep, so a failed, cancelled or abandoned resume does
            // not upsert partial/inconsistent property state into the user memory
            // store.
            if (finalState != ConversationState.AWAITING_HUMAN && finalState != ConversationState.ERROR
                    && !isTurnDiscarded()) {
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
            List<String> cleaned = new ArrayList<>(actions);
            cleaned.remove(IConversation.PAUSE_CONVERSATION);
            IData<List<String>> replacement = new Data<>(ACTIONS.key(), cleaned);
            step.storeData(replacement);
        }
    }
}
