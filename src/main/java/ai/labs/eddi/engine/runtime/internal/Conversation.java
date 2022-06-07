package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.Property;
import ai.labs.eddi.models.Property.Scope;

import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.memory.ContextUtilities.storeContextLanguageInLongTermMemory;
import static ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
public class Conversation implements IConversation {
    private static final String KEY_USER_INFO = "userInfo";
    private static final String KEY_INPUT = "input";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_ACTIONS = "actions";
    private final List<IExecutablePackage> executablePackages;
    private final IConversationMemory conversationMemory;
    private final IPropertiesHandler propertiesHandler;
    private final IConversation.IConversationOutputRenderer outputProvider;

    Conversation(List<IExecutablePackage> executablePackages,
                 IConversationMemory conversationMemory,
                 IPropertiesHandler propertiesHandler,
                 IConversationOutputRenderer outputProvider) {
        this.executablePackages = executablePackages;
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
        loadLongTermProperties(conversationMemory);

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
        currentStep.storeData(new Data<>(KEY_ACTIONS, conversationEndArray));
        currentStep.addConversationOutputList(KEY_ACTIONS, conversationEndArray);
    }

    private void loadLongTermProperties(IConversationMemory conversationMemory) throws LifecycleException {
        try {
            Properties properties = propertiesHandler.loadProperties();

            if (properties.containsKey(KEY_USER_INFO)) {
                Object userInfo = properties.get(KEY_USER_INFO);
                if (userInfo instanceof Map userInfoMap) {
                    conversationMemory.getConversationProperties().
                            put(KEY_USER_INFO, new Property(KEY_USER_INFO, userInfoMap, Scope.conversation));
                }
            }

            conversationMemory.getConversationProperties().putAll(convertProperties(properties));

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
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
    public void say(final String message, final Map<String, Context> contexts)
            throws LifecycleException, ConversationNotReadyException {
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
            String errorMessage = "Conversation is currently IN_PROGRESS! Please try again later!";
            errorMessage = String.format(errorMessage, getConversationState());
            throw new ConversationNotReadyException(errorMessage);
        }
    }

    private void postConversationLifecycleTasks() throws IResourceStore.ResourceStoreException {
        removeOldInvalidProperties();
        storePropertiesPermanently();
    }

    private void startNextStep() {
        ((ConversationMemory) conversationMemory).startNextStep();
    }

    private List<IData> prepareLifecycleData(String message, Map<String, Context> contexts,
                                             List<String> taskTypeResultsToBeRemoved) {

        List<IData<Context>> contextData = createContextData(contexts);
        List<IData> lifecycleData = new LinkedList<>(contextData);

        storeContextLanguageInLongTermMemory(contexts, conversationMemory);

        IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        addContextToConversationOutput(currentStep, contextData);
        removedTaskTypeResultsFromPreviousRuns(currentStep, taskTypeResultsToBeRemoved);

        storeUserInputInMemory(message, lifecycleData);
        return lifecycleData;
    }

    private void removedTaskTypeResultsFromPreviousRuns(IWritableConversationStep currentStep,
                                                        List<String> taskTypeResultsToBeRemoved) {

        if (!isNullOrEmpty(taskTypeResultsToBeRemoved)) {
            taskTypeResultsToBeRemoved.forEach(type -> {
                currentStep.removeData(type);
                currentStep.resetConversationOutput(type);
            });
        }
    }

    private void addContextToConversationOutput(IWritableConversationStep currentStep,
                                                List<IData<Context>> contextData) {

        if (!contextData.isEmpty()) {
            var context = ConversationMemoryUtilities.prepareContext(contextData);
            currentStep.addConversationOutputMap(KEY_CONTEXT, context);
        }
    }

    private void storeUserInputInMemory(String message, List<IData> lifecycleData) {
        IData initialData;
        IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        if (!"".equals(message.trim())) {
            initialData = new Data<>(KEY_INPUT + ":initial", message);
            initialData.setPublic(true);
            lifecycleData.add(initialData);
            currentStep.addConversationOutputString(KEY_INPUT, message);
        }
    }

    private void executeConversationStep(List<IData> lifecycleData, List<String> lifecycleTaskTypes) throws LifecycleException {
        try {
            executePackages(lifecycleData, lifecycleTaskTypes);
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
        IData<List<String>> actionData = conversationMemory.getCurrentStep().getLatestData(KEY_ACTIONS);
        if (actionData != null) {
            List<String> result = actionData.getResult();
            if (result != null && result.contains(CONVERSATION_END)) {
                endConversation();
            }
        }
    }

    private Map<String, Property> convertProperties(Properties properties) {
        return properties.keySet().stream().collect(
                Collectors.toMap(name -> name,
                        name -> {
                            Object value = properties.get(name);
                            if (value instanceof String) {
                                return new Property(name, value.toString(), Scope.longTerm);
                            } else if (value instanceof Map valueMap) {
                                return new Property(name, valueMap, Scope.longTerm);
                            } else if (value instanceof Integer valueInt) {
                                return new Property(name, valueInt, Scope.longTerm);
                            } else if (value instanceof Float valueFloat) {
                                return new Property(name, valueFloat, Scope.longTerm);
                            } else {
                                return new Property(name, (String) null, Scope.longTerm);
                            }
                        },
                        (a, b) -> b, LinkedHashMap::new));

    }

    private void removeOldInvalidProperties() {
        IConversationProperties conversationProperties = conversationMemory.getConversationProperties();
        Map<String, Property> filteredConversationProperties =
                conversationProperties.entrySet().stream()
                        .filter(property -> property.getValue().getScope() != Scope.step)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        conversationProperties.clear();
        conversationProperties.putAll(filteredConversationProperties);
    }

    private void storePropertiesPermanently()
            throws IResourceStore.ResourceStoreException {

        Properties longTermConversationProperties = new Properties();
        for (Property property : conversationMemory.getConversationProperties().values()) {
            if (property.getScope() == Scope.longTerm) {
                var valueString = property.getValueString();
                var valueObject = property.getValueObject();
                var valueInt = property.getValueInt();
                var valueFloat = property.getValueFloat();
                if (valueString != null) {
                    longTermConversationProperties.put(property.getName(), valueString);
                } else if (valueObject != null) {
                    longTermConversationProperties.put(property.getName(), valueObject);
                } else if (valueInt != null) {
                    longTermConversationProperties.put(property.getName(), valueInt);
                } else if (valueFloat != null) {
                    longTermConversationProperties.put(property.getName(), valueFloat);
                }
            }
        }

        propertiesHandler.mergeProperties(longTermConversationProperties);
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

    private void executePackages(List<IData> data, List<String> lifecycleTaskTypes) throws LifecycleException, ConversationStopException {
        for (IExecutablePackage executablePackage : executablePackages) {
            conversationMemory.getCurrentStep().setCurrentPackageId(executablePackage.getPackageId());
            data.stream().filter(Objects::nonNull).
                    forEach(datum -> conversationMemory.getCurrentStep().storeData(datum));
            ILifecycleManager lifecycleManager = executablePackage.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory, lifecycleTaskTypes);
        }
    }
}
