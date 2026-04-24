/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;

import java.util.Map;

/**
 * @author ginccc
 */
public interface IAgent {
    String getAgentId();

    Integer getAgentVersion();

    Deployment.Status getDeploymentStatus();

    void addWorkflow(IExecutableWorkflow executableWorkflow) throws IllegalAccessException;

    IConversation startConversation(String userId, Map<String, Context> context, IPropertiesHandler propertiesHandler,
                                    IConversationOutputRenderer outputProvider)
            throws InstantiationException, IllegalAccessException, LifecycleException;

    IConversation continueConversation(IConversationMemory conversationMemory, IPropertiesHandler propertiesHandler,
                                       IConversationOutputRenderer outputProvider)
            throws InstantiationException, IllegalAccessException;

    /**
     * User memory config from agent deployment. {@code null} when memory is
     * disabled.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return null;
    }

    /**
     * Memory policy from agent deployment. {@code null} when no policy is
     * configured.
     *
     * @since 6.0.0
     */
    default AgentConfiguration.MemoryPolicy getMemoryPolicy() {
        return null;
    }
}
