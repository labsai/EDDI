package ai.labs.runtime.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.ILifecycleManager;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.ConversationMemory;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.model.ConversationState;
import ai.labs.runtime.IExecutablePackage;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    public void init() throws LifecycleException {
        setConversationState(ConversationState.READY);
        executePackages(new LinkedList<>());
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

            //store user input in memory
            IData initialData;
            if (!"".equals(message.trim())) {
                initialData = new Data<>("input:initial", message);
                initialData.setPublic(true);
                data.add(initialData);
            }

            //store context data
            List<IData<Context>> contextData = new LinkedList<>();
            for (String key : contexts.keySet()) {
                Context context = contexts.get(key);
                contextData.add(new Data<>("context:" + key, context));

            }
            data.addAll(contextData);

            //execute input processing
            executePackages(data);


            IConversationMemory.IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
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

            if (outputProvider != null) {
                outputProvider.renderOutput(conversationMemory);
            }
        }
    }

    private void executePackages(List<IData> data) throws LifecycleException {
        for (IExecutablePackage executablePackage : executablePackages) {
            conversationMemory.setCurrentContext(executablePackage.getName());
            data.stream().filter(Objects::nonNull).
                    forEach(datum -> conversationMemory.getCurrentStep().storeData(datum));
            ILifecycleManager lifecycleManager = executablePackage.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory);
        }
    }
}
