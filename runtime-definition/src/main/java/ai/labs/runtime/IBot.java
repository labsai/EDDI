package ai.labs.runtime;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IPropertiesHandler;
import ai.labs.models.Context;
import ai.labs.models.Deployment;

import java.util.Map;

import static ai.labs.lifecycle.IConversation.IConversationOutputRenderer;

/**
 * @author ginccc
 */
public interface IBot {
    String getBotId();

    Integer getBotVersion();

    Deployment.Status getDeploymentStatus();

    void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException;

    IConversation startConversation(String userId, Map<String, Context> context,
                                    IPropertiesHandler propertiesHandler,
                                    IConversationOutputRenderer outputProvider)
            throws InstantiationException, IllegalAccessException, LifecycleException;

    IConversation continueConversation(IConversationMemory conversationMemory,
                                       IPropertiesHandler propertiesHandler,
                                       IConversationOutputRenderer outputProvider)
            throws InstantiationException, IllegalAccessException;
}
