package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
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

import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.memory.ContextUtilities.storeContextLanguageInLongTermMemory;
import static ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.engine.memory.MemoryKeys.INPUT;
import static ai.labs.eddi.engine.memory.MemoryKeys.INPUT_INITIAL;
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
    }

    private void postConversationLifecycleTasks() throws IResourceStore.ResourceStoreException {
        removeOldInvalidProperties();
        storePropertiesPermanently();
    }

    private void startNextStep() {
        ((ConversationMemory) conversationMemory).startNextStep();
    }

    private List<IData<?>> prepareLifecycleData(String message, Map<String, Context> contexts, List<String> taskTypeResultsToBeRemoved) {

        List<IData<Context>> contextData = createContextData(contexts);
        List<IData<?>> lifecycleData = new LinkedList<>(contextData);

        storeContextLanguageInLongTermMemory(contexts, conversationMemory);

        IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        addContextToConversationOutput(currentStep, contextData);
        removedTaskTypeResultsFromPreviousRuns(currentStep, taskTypeResultsToBeRemoved);

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

    private void executeConversationStep(List<IData<?>> lifecycleData, List<String> lifecycleTaskTypes) throws LifecycleException {
        try {
            executeWorkflows(lifecycleData, lifecycleTaskTypes);
        } catch (ConversationStopException unused) {
            endConversation();
        }

        try {
            postConversationLifecycleTasks();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
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
                contextData.add(new Data<>(KEY_CONTEXT + ":" + key, context.get(key)));

            }
        }
        return contextData;
    }

    private void executeWorkflows(List<IData<?>> data, List<String> lifecycleTaskTypes) throws LifecycleException, ConversationStopException {
        for (IExecutableWorkflow executableWorkflow : executableWorkflows) {
            conversationMemory.getCurrentStep().setCurrentWorkflowId(executableWorkflow.getWorkflowId());
            data.stream().filter(Objects::nonNull).forEach(datum -> conversationMemory.getCurrentStep().storeData(datum));
            ILifecycleManager lifecycleManager = executableWorkflow.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory, lifecycleTaskTypes);
        }
    }
}
