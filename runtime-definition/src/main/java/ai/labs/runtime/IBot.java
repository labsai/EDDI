package ai.labs.runtime;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.models.Context;
import ai.labs.models.Deployment;

import java.util.Map;

/**
 * @author ginccc
 */
public interface IBot {
    String getBotId();

    Integer getBotVersion();

    Deployment.Status getDeploymentStatus();

    void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException;

    IConversation startConversation(String userId, Map<String, Context> context, IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException, LifecycleException;

    IConversation continueConversation(IConversationMemory conversationMemory, IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException;
}
