/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for BUG-5: RestAgentTriggerStore.deleteAgentTrigger() must
 * handle ResourceNotFoundException for nonexistent intents.
 * <p>
 * Before the fix, {@code IAgentTriggerStore.deleteAgentTrigger()} did not
 * declare {@code ResourceNotFoundException} in its throws clause, so the REST
 * layer only caught {@code ResourceStoreException}. A delete for a nonexistent
 * intent would result in an unhandled exception.
 */
class RestAgentTriggerStoreTest {

    private RestAgentTriggerStore restAgentTriggerStore;
    private IAgentTriggerStore agentTriggerStore;
    private ICache<String, AgentTriggerConfiguration> cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentTriggerStore = mock(IAgentTriggerStore.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        cache = mock(ICache.class);
        doReturn(cache).when(cacheFactory).getCache("agentTriggers");

        restAgentTriggerStore = new RestAgentTriggerStore(agentTriggerStore, cacheFactory);
    }

    @Test
    void deleteAgentTrigger_existingIntent_returns200() throws Exception {
        // Arrange — store does not throw (intent exists)
        doNothing().when(agentTriggerStore).deleteAgentTrigger("greeting");

        // Act
        Response response = restAgentTriggerStore.deleteAgentTrigger("greeting");

        // Assert
        assertEquals(200, response.getStatus());
        verify(agentTriggerStore).deleteAgentTrigger("greeting");
        verify(cache).remove("greeting");
    }

    /**
     * BUG-5: When the intent does not exist, the store throws
     * ResourceNotFoundException. The sneakyThrow in RestAgentTriggerStore
     * propagates it as an unchecked exception (which JAX-RS ExceptionMappers will
     * handle). This test verifies the exception propagates correctly.
     */
    @Test
    void deleteAgentTrigger_nonexistentIntent_throwsResourceNotFoundException() throws Exception {
        // Arrange — store throws ResourceNotFoundException
        doThrow(new ResourceNotFoundException("Intent 'nonexistent' not found"))
                .when(agentTriggerStore).deleteAgentTrigger("nonexistent");

        // Act & Assert — sneakyThrow re-throws as unchecked
        assertThrows(ResourceNotFoundException.class,
                () -> restAgentTriggerStore.deleteAgentTrigger("nonexistent"));

        // Cache should NOT be updated when delete fails
        verify(cache, never()).remove("nonexistent");
    }

    @Test
    void deleteAgentTrigger_storeError_throwsResourceStoreException() throws Exception {
        // Arrange — store throws ResourceStoreException
        doThrow(new ResourceStoreException("DB connection failed"))
                .when(agentTriggerStore).deleteAgentTrigger("broken");

        // Act & Assert
        assertThrows(ResourceStoreException.class,
                () -> restAgentTriggerStore.deleteAgentTrigger("broken"));

        verify(cache, never()).remove("broken");
    }
}
