/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Deploying or undeploying an agent drops its cached configurations, so the
 * cache never outlives the agent's presence in the runtime.
 */
class AgentFactoryConfigurationCacheTest {

    private static final Deployment.Environment ENV = Deployment.Environment.production;

    @Test
    void deployAndUndeployDropTheAgentsEntries() throws Exception {
        var agentStore = mock(IAgentStore.class);
        doReturn(new AgentConfiguration()).when(agentStore).read(anyString(), anyInt());
        var cache = new AgentConfigurationCache(agentStore);
        var clientLibrary = mock(IAgentStoreClientLibrary.class);
        doReturn(new Agent("agent-1", 1)).when(clientLibrary).getAgent("agent-1", 1);
        var factory = new AgentFactory(clientLibrary, mock(IDeploymentListener.class), new SimpleMeterRegistry());
        factory.agentConfigurationCache = cache;

        cache.read("agent-1", 1);
        cache.read("agent-2", 1);
        factory.deployAgent(ENV, "agent-1", 1, null);
        cache.read("agent-1", 1);
        verify(agentStore, times(2)).read("agent-1", 1);

        factory.undeployAgent(ENV, "agent-1", 1);
        cache.read("agent-1", 1);
        verify(agentStore, times(3)).read("agent-1", 1);

        factory.undeployAgent(ENV, "agent-1", null);
        cache.read("agent-1", 1);
        cache.read("agent-2", 1);
        verify(agentStore, times(4)).read("agent-1", 1);
        verify(agentStore, times(1)).read("agent-2", 1);
    }
}
