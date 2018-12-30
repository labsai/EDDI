package ai.labs.runtime.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.ILifecycleManager;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.ConversationMemory;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemory.IConversationProperties;
import ai.labs.memory.IData;
import ai.labs.memory.model.Data;
import ai.labs.models.Context;
import ai.labs.models.ConversationState;
import ai.labs.models.Property;
import ai.labs.models.Property.Scope;
import ai.labs.runtime.IExecutablePackage;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class Conversation implements IConversation {
    private static final String CONVERSATION_END = "CONVERSATION_END";
    private final List<IExecutablePackage> executablePackages;
    private final IConversationMemory conversationMemory;
    private final IConversation.IConversationOutputRenderer outputProvider;

    Conversation(List<IExecutablePackage> executablePackages,
                 IConversationMemory conversationMemory,
                 IConversationOutputRenderer outputProvider) {
        this.executablePackages = executablePackages;
        this.conversationMemory = conversationMemory;
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
        executePackages(createContextData(context));
        if (conversationMemory.getUserId() != null) {
            initProperties();
        }
    }

    private void initProperties() {
        //TODO
    }

    private void setConversationState(ConversationState conversationState) {
        this.conversationMemory.setConversationState(conversationState);
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

            ((ConversationMemory) conversationMemory).startNextStep();
            List<IData> data = new LinkedList<>();

            //add userInfo
            IData userData;
            String userId = conversationMemory.getUserId();
            if (userId == null) {
                userId = "anonymous-" + RandomStringUtils.randomAlphanumeric(10);
            }

            userData = new Data<>("userInfo:userId", userId);
            userData.setPublic(true);
            data.add(userData);

            //store context data
            data.addAll(createContextData(contexts));

            IConversationMemory.IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
            //todo load userInfo from db
            currentStep.addConversationOutputMap("userInfo", Map.of("userId", userId));

            //store user input in memory
            IData initialData;
            if (!"".equals(message.trim())) {
                initialData = new Data<>("input:initial", message);
                initialData.setPublic(true);
                data.add(initialData);
                currentStep.addConversationOutputString("input", message);
            }

            //execute input processing
            executePackages(data);

            IData<List<String>> actionData = currentStep.getLatestData("action");
            if (actionData != null) {
                List<String> result = actionData.getResult();
                if (result != null && result.contains(CONVERSATION_END)) {
                    endConversation();
                }
            }
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

            removeOldInvalidProperties();
            storePropertiesPermanently();

            if (outputProvider != null) {
                outputProvider.renderOutput(conversationMemory);
            }
        }
    }

    private void storePropertiesPermanently() {
/*
        List<IData<List<Property>>> propertiesData = conversationMemory.getCurrentStep().getAllData("properties");
        List<List<Property>> collect = propertiesData.stream().
                reduce(data -> data.getResult()).collect(Collectors.toList());
        List<Map<String, Object>> properties = collect.stream().
                filter(data -> data.getResult().getScope() == Scope.longTerm).map(data -> {
            String key = data.getKey();
            return Map.of(key.substring(key.indexOf(":") + 1), data.getResult().getValue());
        }).collect(Collectors.toList());
*/

        //todo store in userinfo db
    }

    private void removeOldInvalidProperties() {
        IConversationProperties conversationProperties = this.conversationMemory.getConversationProperties();
        Map<String, Property> filteredConversationProperties =
                conversationProperties.entrySet().stream()
                        .filter(property -> property.getValue().getScope() != Scope.step)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        conversationProperties.clear();
        conversationProperties.putAll(filteredConversationProperties);
    }

    private List<IData> createContextData(Map<String, Context> context) {
        List<IData> contextData = new LinkedList<>();
        if (context != null) {
            for (String key : context.keySet()) {
                contextData.add(new Data<>("context:" + key, context.get(key)));

            }
        }
        return contextData;
    }

    private void executePackages(List<IData> data) throws LifecycleException {
        for (IExecutablePackage executablePackage : executablePackages) {
            data.stream().filter(Objects::nonNull).
                    forEach(datum -> conversationMemory.getCurrentStep().storeData(datum));
            ILifecycleManager lifecycleManager = executablePackage.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory);
        }
    }
}
