/*
 * Copyright EDDI contributors
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
     * User memory config from agent deployment. {@code null} when the agent
     * declares no {@code userMemoryConfig} and does not enable the memory tools.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return null;
    }

    /**
     * Whether the agent enables the LLM memory tools ({@code enableMemoryTools}).
     */
    default boolean isMemoryToolsEnabled() {
        return getUserMemoryConfig() != null;
    }

    /**
     * The agent's {@code turnDeadlineMs}; {@code null} when no deadline is
     * configured.
     *
     * @since 6.6.0
     */
    default Long getTurnDeadlineMs() {
        return null;
    }

    /**
     * The agent's {@code turnDeadlineReserveMs}; {@code null} means the engine
     * default.
     *
     * @since 6.6.0
     */
    default Long getTurnDeadlineReserveMs() {
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

    /**
     * The deployed version's compatibility generation — see
     * {@link AgentConfiguration#getCompatibilityGeneration()}. {@code null} for a
     * version stored before generations existed, which is compatible only with
     * itself.
     *
     * @since 6.5.0
     */
    default Integer getCompatibilityGeneration() {
        return null;
    }
}
