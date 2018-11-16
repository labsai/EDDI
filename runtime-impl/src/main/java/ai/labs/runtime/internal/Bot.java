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
    private String botId;
    @Getter
    private Integer botVersion;
    private List<IExecutablePackage> executablePackages;

    @Getter
    @Setter
    private Deployment.Status deploymentStatus;

    public Bot(String botId, Integer botVersion) {
        this.botId = botId;
        this.botVersion = botVersion;
        executablePackages = new LinkedList<>();
    }

    @Override
    public void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException {
        executablePackages.add(executablePackage);
    }

    @Override
    public IConversation startConversation(final String userId,
                                           final Map<String, Context> context,
                                           final IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException, LifecycleException {
        Conversation conversation = new Conversation(executablePackages,
                new ConversationMemory(botId, botVersion, userId), outputProvider);
        conversation.init(context);
        return conversation;
    }

    @Override
    public IConversation continueConversation(final IConversationMemory conversationMemory,
                                              final IConversation.IConversationOutputRenderer outputProvider)
            throws IllegalAccessException {
        return new Conversation(executablePackages, conversationMemory, outputProvider);
    }
}
