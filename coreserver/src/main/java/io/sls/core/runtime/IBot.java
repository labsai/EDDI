package io.sls.core.runtime;

import io.sls.memory.model.Deployment;
import io.sls.core.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;

/**
 * User: jarisch
 * Date: 23.06.12
 * Time: 20:20
 */
public interface IBot {
    String getId();

    Integer getVersion();

    Deployment.Status getDeploymentStatus();

    void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException;

    IConversation startConversation(IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException, LifecycleException;

    IConversation continueConversation(IConversationMemory conversationMemory, IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException;
}
