/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AgentConfigurationCache}: an immutable {@code (agentId, version)} is
 * read from the store once; absence and failures are re-asked; deploy and
 * undeploy drop the agent's entries (see
 * {@code AgentFactoryConfigurationCacheTest}).
 */
class AgentConfigurationCacheTest {

    private IAgentStore agentStore;
    private AgentConfigurationCache cache;

    @BeforeEach
    void setUp() {
        agentStore = mock(IAgentStore.class);
        cache = new AgentConfigurationCache(agentStore);
    }

    @Test
    void readsAVersionOnce() throws Exception {
        var config = new AgentConfiguration();
        doReturn(config).when(agentStore).read("agent-1", 3);

        assertSame(config, cache.read("agent-1", 3));
        assertSame(config, cache.read("agent-1", 3));
        assertSame(config, cache.read("agent-1", 3));

        verify(agentStore, times(1)).read("agent-1", 3);
    }

    @Test
    void versionsAreSeparateEntries() throws Exception {
        var v1 = new AgentConfiguration();
        var v2 = new AgentConfiguration();
        doReturn(v1).when(agentStore).read("agent-1", 1);
        doReturn(v2).when(agentStore).read("agent-1", 2);

        assertSame(v1, cache.read("agent-1", 1));
        assertSame(v2, cache.read("agent-1", 2));
    }

    @Test
    void anAbsentVersionIsAskedAgain() throws Exception {
        var config = new AgentConfiguration();
        doThrow(new ResourceNotFoundException("not yet")).doReturn(config).when(agentStore).read("agent-1", 3);

        assertThrows(ResourceNotFoundException.class, () -> cache.read("agent-1", 3));
        assertSame(config, cache.read("agent-1", 3));
    }

    @Test
    void aStoreFailureIsAskedAgain() throws Exception {
        var config = new AgentConfiguration();
        doThrow(new ResourceStoreException("down")).doReturn(config).when(agentStore).read("agent-1", 3);

        assertThrows(ResourceStoreException.class, () -> cache.read("agent-1", 3));
        assertSame(config, cache.read("agent-1", 3));
    }

    @Test
    void invalidateDropsOnlyThatAgent() throws Exception {
        doReturn(new AgentConfiguration()).when(agentStore).read(anyString(), anyInt());
        cache.read("agent-1", 1);
        cache.read("agent-1", 2);
        cache.read("agent-2", 1);

        cache.invalidate("agent-1");
        cache.read("agent-1", 1);
        cache.read("agent-1", 2);
        cache.read("agent-2", 1);

        verify(agentStore, times(2)).read("agent-1", 1);
        verify(agentStore, times(2)).read("agent-1", 2);
        verify(agentStore, times(1)).read("agent-2", 1);
    }
}
