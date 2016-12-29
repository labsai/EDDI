package io.sls.core.runtime.internal;

import io.sls.core.lifecycle.ILifecycleManager;
import io.sls.core.runtime.IConversation;
import io.sls.core.runtime.IExecutablePackage;
import io.sls.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.memory.impl.ConversationMemory;
import io.sls.memory.impl.Data;
import io.sls.memory.model.ConversationState;

import java.util.List;

/**
 * @author ginccc
 */
public class Conversation implements IConversation {
    private ConversationState conversationState;
    private final IConversationMemory conversationMemory;

    private List<IExecutablePackage> executablePackages;

    private final IConversationOutputRenderer outputProvider;

    public Conversation(List<IExecutablePackage> executablePackages, IConversationMemory conversationMemory, IConversationOutputRenderer outputProvider) {
        this.executablePackages = executablePackages;
        this.conversationMemory = conversationMemory;
        this.outputProvider = outputProvider;
        setConversationState(ConversationState.READY);
    }

    @Override
    public boolean isInProgress() {
        return conversationState == ConversationState.IN_PROGRESS;
    }

    @Override
    public boolean isEnded() {
        return conversationState == ConversationState.ENDED;
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
        executePackages(null);
    }

    private void setConversationState(ConversationState conversationState) {
        this.conversationState = conversationState;
        conversationMemory.setConversationState(conversationState);
    }

    @Override
    public void say(final String message) throws LifecycleException, ConversationNotReadyException {
        if (conversationState != ConversationState.READY) {
            String errorMessage = "Conversation is *NOT* ready. Current Status: %s";
            errorMessage = String.format(errorMessage, conversationState);
            throw new ConversationNotReadyException(errorMessage);
        }

        try {
            setConversationState(ConversationState.IN_PROGRESS);

            ((ConversationMemory) conversationMemory).startNextStep();

            //store user input in memory
            IData initialData = null;
            if (!"".equals(message.trim())) {
                initialData = new Data("input:initial", message);
                initialData.setPublic(true);
            }

            //execute input processing
            executePackages(initialData);

            // get final output
            IConversationMemory.IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
            IData latestData = currentStep.getLatestData("output");
            final String output = latestData == null ? "" : (String) latestData.getResult();
            if (outputProvider != null) {
                outputProvider.renderOutput(output);
            }

            IData actionData = currentStep.getLatestData("action");
            if (actionData != null) {
                Object result = actionData.getResult();
                if (result instanceof List && ((List) result).contains("CONVERSATION_END")) {
                    endConversation();
                }
            }
        } catch (Exception e) {
            setConversationState(ConversationState.ERROR);
            throw new LifecycleException(e.getLocalizedMessage(), e);
        } finally {
            if (conversationState == ConversationState.IN_PROGRESS) {
                setConversationState(ConversationState.READY);
            }
        }
    }

    private void executePackages(IData initialData) throws LifecycleException {
        for (IExecutablePackage executablePackage : executablePackages) {
            conversationMemory.setCurrentContext(executablePackage.getContext());
            if (initialData != null) {
                conversationMemory.getCurrentStep().storeData(initialData);
            }
            ILifecycleManager lifecycleManager = executablePackage.getLifecycleManager();
            lifecycleManager.executeLifecycle(conversationMemory);
        }
    }
}
