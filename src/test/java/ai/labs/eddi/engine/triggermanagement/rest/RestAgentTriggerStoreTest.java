/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import java.util.List;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.model.AgentDeployment;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import io.quarkus.security.ForbiddenException;
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
    private ResourceAccessGuard resourceAccessGuard;
    private ICache<String, AgentTriggerConfiguration> cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentTriggerStore = mock(IAgentTriggerStore.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        cache = mock(ICache.class);
        doReturn(cache).when(cacheFactory).getCache("agentTriggers");

        resourceAccessGuard = mock(ResourceAccessGuard.class);
        restAgentTriggerStore = new RestAgentTriggerStore(agentTriggerStore, cacheFactory, resourceAccessGuard);
    }

    @Test
    void createAgentTrigger_agentTheCallerMayNotUse_refused() throws Exception {
        // A trigger routes inbound messages into conversations with the agents its
        // deployments name, and that routing runs with no interactive caller — below
        // the USE gate by design. So the gate lives at authoring time; without it,
        // pointing a trigger at a private agent is a standing bypass of the check on
        // /agents/{id}/start.
        var deployment = new AgentDeployment();
        deployment.setAgentId("abcdef1234567890abcdef");
        var configuration = new AgentTriggerConfiguration();
        configuration.setIntent("greeting");
        configuration.getAgentDeployments().add(deployment);
        doThrow(new ForbiddenException("no")).when(resourceAccessGuard).requireAgentUseAccess("abcdef1234567890abcdef");

        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.createAgentTrigger(configuration));
        verify(agentTriggerStore, never()).createAgentTrigger(any());
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

    // --- H2b: authority over an existing trigger ---

    private static AgentTriggerConfiguration trigger(String intent, String agentId) {
        var deployment = new AgentDeployment();
        deployment.setAgentId(agentId);
        var configuration = new AgentTriggerConfiguration();
        configuration.setIntent(intent);
        configuration.getAgentDeployments().add(deployment);
        return configuration;
    }

    @Test
    void updateAgentTrigger_repointingAnotherTeamsIntent_refused() throws Exception {
        // The victim's intent routes to their agent; the caller may use their own
        // agent (the new target) but may not edit the victim's.
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(trigger("support", "victimagent00000000000"));
        when(resourceAccessGuard.hasAccess("victimagent00000000000", AccessLevel.EDIT)).thenReturn(false);

        assertThrows(ForbiddenException.class,
                () -> restAgentTriggerStore.updateAgentTrigger("support", trigger("support", "attackeragent000000000")));

        verify(agentTriggerStore, never()).updateAgentTrigger(any(), any());
        verify(cache, never()).put(any(), any());
    }

    @Test
    void updateAgentTrigger_callerMayEditTheCurrentTarget_allowed() throws Exception {
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(trigger("support", "teamagent0000000000000"));
        when(resourceAccessGuard.hasAccess("teamagent0000000000000", AccessLevel.EDIT)).thenReturn(true);

        Response response = restAgentTriggerStore.updateAgentTrigger("support", trigger("support", "teamagent0000000000000"));

        assertEquals(200, response.getStatus());
        verify(agentTriggerStore).updateAgentTrigger(eq("support"), any());
    }

    @Test
    void deleteAgentTrigger_anotherTeamsIntent_refused() throws Exception {
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(trigger("support", "victimagent00000000000"));
        when(resourceAccessGuard.hasAccess("victimagent00000000000", AccessLevel.EDIT)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.deleteAgentTrigger("support"));

        verify(agentTriggerStore, never()).deleteAgentTrigger(any());
        verify(cache, never()).remove(any());
    }

    @Test
    void readAllAgentTriggers_hidesTriggersRoutingToAgentsTheCallerMayNotUse() throws Exception {
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of(
                trigger("mine", "myagent000000000000000"), trigger("theirs", "theiragent000000000000")));
        when(resourceAccessGuard.hasAccess("myagent000000000000000", AccessLevel.USE)).thenReturn(true);
        when(resourceAccessGuard.hasAccess("theiragent000000000000", AccessLevel.USE)).thenReturn(false);

        var visible = restAgentTriggerStore.readAllAgentTriggers();

        assertEquals(List.of("mine"), visible.stream().map(AgentTriggerConfiguration::getIntent).toList());
    }

    @Test
    void readAgentTrigger_anotherTeamsIntent_answersLikeAnAbsentOne() throws Exception {
        when(agentTriggerStore.readAgentTrigger("theirs")).thenReturn(trigger("theirs", "theiragent000000000000"));
        when(resourceAccessGuard.hasAccess("theiragent000000000000", AccessLevel.USE)).thenReturn(false);

        var refused = assertThrows(ResourceNotFoundException.class, () -> restAgentTriggerStore.readAgentTrigger("theirs"));
        assertInstanceOf(IRestAgentTriggerStore.TriggerNotVisibleException.class, refused,
                "a refusal must be distinguishable in-process, so callers do not clean up as if it were deleted");
    }

    @Test
    void workspacesOff_everythingBehavesAsBefore() throws Exception {
        when(resourceAccessGuard.seesEverything()).thenReturn(true);
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of(trigger("any", "someagent0000000000000")));

        assertEquals(1, restAgentTriggerStore.readAllAgentTriggers().size());
        restAgentTriggerStore.deleteAgentTrigger("any");
        verify(agentTriggerStore, never()).readAgentTrigger(any());
        verify(agentTriggerStore).deleteAgentTrigger("any");
    }
}
