package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class Agent implements IAgent {
    private final String agentId;
    private final Integer agentVersion;
    private final List<IExecutableWorkflow> executableWorkflows;

    private Deployment.Status deploymentStatus;
    private AgentConfiguration.UserMemoryConfig userMemoryConfig;
    private AgentConfiguration.MemoryPolicy memoryPolicy;

    public Agent(String agentId, Integer agentVersion) {
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        executableWorkflows = new LinkedList<>();
    }

    @Override
    public void addWorkflow(IExecutableWorkflow executableWorkflow) throws IllegalAccessException {
        executableWorkflows.add(executableWorkflow);
    }

    @Override
    public IConversation startConversation(final String userId, final Map<String, Context> context, IPropertiesHandler propertiesHandler,
                                           final IConversation.IConversationOutputRenderer outputProvider)
            throws LifecycleException, IllegalAccessException {
        var conversationMemory = new ConversationMemory(agentId, agentVersion, userId);
        if (memoryPolicy != null) {
            conversationMemory.setMemoryPolicy(memoryPolicy);
        }
        Conversation conversation = new Conversation(executableWorkflows, conversationMemory, propertiesHandler,
                outputProvider);
        conversation.init(context);
        return conversation;
    }

    @Override
    public IConversation continueConversation(final IConversationMemory conversationMemory, final IPropertiesHandler propertiesHandler,
                                              final IConversation.IConversationOutputRenderer outputProvider)
            throws IllegalAccessException {
        if (memoryPolicy != null) {
            conversationMemory.setMemoryPolicy(memoryPolicy);
        }
        return new Conversation(executableWorkflows, conversationMemory, propertiesHandler, outputProvider);
    }

    public String getAgentId() {
        return agentId;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public Deployment.Status getDeploymentStatus() {
        return deploymentStatus;
    }

    public void setDeploymentStatus(Deployment.Status deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    @Override
    public AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return userMemoryConfig;
    }

    public void setUserMemoryConfig(AgentConfiguration.UserMemoryConfig userMemoryConfig) {
        this.userMemoryConfig = userMemoryConfig;
    }

    @Override
    public AgentConfiguration.MemoryPolicy getMemoryPolicy() {
        return memoryPolicy;
    }

    public void setMemoryPolicy(AgentConfiguration.MemoryPolicy memoryPolicy) {
        this.memoryPolicy = memoryPolicy;
    }
}
