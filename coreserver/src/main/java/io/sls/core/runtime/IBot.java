package io.sls.core.runtime;

import io.sls.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.model.Deployment;

/**
 * @author ginccc
 */
public interface IBot {
    String getId();

    Integer getVersion();

    Deployment.Status getDeploymentStatus();

    void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException;

    IConversation startConversation(IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException, LifecycleException;

    IConversation continueConversation(IConversationMemory conversationMemory, IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException;
}
