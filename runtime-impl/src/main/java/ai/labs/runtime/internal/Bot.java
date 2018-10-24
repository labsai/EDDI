package ai.labs.runtime.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.ConversationMemory;
import ai.labs.memory.IConversationMemory;
import ai.labs.models.Context;
import ai.labs.models.Deployment;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IExecutablePackage;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class Bot implements IBot {
    @Getter
    private String id;
    @Getter
    private Integer version;
    private List<IExecutablePackage> executablePackages;

    @Getter
    @Setter
    private Deployment.Status deploymentStatus;

    public Bot(String id, Integer version) {
        this.id = id;
        this.version = version;
        executablePackages = new LinkedList<>();
    }

    @Override
    public void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException {
        executablePackages.add(executablePackage);
    }

    @Override
    public IConversation startConversation(final Map<String, Context> context,
                                           final IConversation.IConversationOutputRenderer outputProvider) throws LifecycleException, IllegalAccessException {
        Conversation conversation = new Conversation(executablePackages, new ConversationMemory(id, version), outputProvider);
        conversation.init(context);
        return conversation;
    }

    @Override
    public IConversation continueConversation(final IConversationMemory conversationMemory, final IConversation.IConversationOutputRenderer outputProvider) throws IllegalAccessException {
        return new Conversation(executablePackages, conversationMemory, outputProvider);
    }
}
