package ai.labs.runtime.internal;

import ai.labs.lifecycle.ConversationStopException;
import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.ILifecycleManager;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.*;
import ai.labs.memory.IConversationMemory.IConversationProperties;
import ai.labs.memory.model.Data;
import ai.labs.models.Context;
import ai.labs.models.ConversationState;
import ai.labs.models.Property;
import ai.labs.models.Property.Scope;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.properties.model.Properties;
import ai.labs.runtime.IExecutablePackage;

import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.memory.IConversationMemory.IWritableConversationStep;

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
            executePackages(new LinkedList<>(createContextData(context)));
        } catch (ConversationStopException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
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
                if (userInfo instanceof Map) {
                    conversationMemory.getConversationProperties().
                            put(KEY_USER_INFO, new Property(KEY_USER_INFO, userInfo, Scope.conversation));
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
    public void say(final String message, final Map<String, Context> contexts)
            throws LifecycleException, ConversationNotReadyException {
        if (getConversationState() == ConversationState.IN_PROGRESS) {
            String errorMessage = "Conversation is currently IN_PROGRESS! Please try again later!";
            errorMessage = String.format(errorMessage, getConversationState());
            throw new ConversationNotReadyException(errorMessage);
        }

        try {
            setConversationState(ConversationState.IN_PROGRESS);

            startNextStep();

            var lifecycleData = prepareLifecycleData(message, contexts);
            executeConversationStep(lifecycleData);

            checkActionsForConversationEnd();
            removeOldInvalidProperties();
            storePropertiesPermanently();

        } catch (LifecycleException.LifecycleInterruptedException e) {
            setConversationState(ConversationState.EXECUTION_INTERRUPTED);
            throw e;
        } catch (Exception e) {
            setConversationState(ConversationState.ERROR);
            throw new LifecycleException(e.getLocalizedMessage(), e);
        } finally {
            if (getConversationState() == ConversationState.IN_PROGRESS) {
                setConversationState(ConversationState.READY);
            }

            if (outputProvider != null) {
                outputProvider.renderOutput(conversationMemory);
            }
        }
    }

    private void startNextStep() {
        ((ConversationMemory) conversationMemory).startNextStep();
    }

    private List<IData> prepareLifecycleData(String message, Map<String, Context> contexts) {
        List<IData<Context>> contextData = createContextData(contexts);
        List<IData> lifecycleData = new LinkedList<>(contextData);
        addContextToConversationOutput(conversationMemory.getCurrentStep(), contextData);
        storeUserInputInMemory(message, lifecycleData);
        return lifecycleData;
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

    private void executeConversationStep(List<IData> lifecycleData) throws LifecycleException {
        try {
            executePackages(lifecycleData);
        } catch (ConversationStopException unused) {
            endConversation();
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
                        name -> new Property(name, properties.get(name), Scope.longTerm),
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

        Properties longTermConversationProperties = conversationMemory.getConversationProperties().values().stream()
                .filter(property -> property.getScope() == Scope.longTerm)
                .filter(property -> property.getValue() != null)
                .collect(Collectors.toMap(Property::getName, Property::getValue, (a, b) -> b, Properties::new));

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

    private void executePackages(List<IData> data) throws LifecycleException, ConversationStopException {
        for (IExecutablePackage executablePackage : executablePackages) {
            data.stream().filter(Objects::nonNull).
                    forEach(datum -> conversationMemory.getCurrentStep().storeData(datum));
            ILifecycleManager lifecycleManager = executablePackage.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory);
        }
    }
}
