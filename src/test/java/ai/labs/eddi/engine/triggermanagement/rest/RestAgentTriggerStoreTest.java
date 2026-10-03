/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.model.AgentDeployment;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        // Visibility of a stored trigger (listing and single read) is decided by
        // hasAccess on its targets; admit everything unless a test says otherwise.
        lenient().when(resourceAccessGuard.hasAccess(any(), any())).thenReturn(true);
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
        doThrow(new ForbiddenException("no")).when(resourceAccessGuard).requireAccess("abcdef1234567890abcdef", AccessLevel.EDIT, "agent");

        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.createAgentTrigger(configuration));
        verify(agentTriggerStore, never()).createAgentTrigger(any());
    }

    @Test
    void deleteAgentTrigger_existingIntent_returns200() throws Exception {
        // Arrange — the intent exists and is unchanged at the write
        var stored = triggerRouting("greeting", "abcdef1234567890abcdef");
        when(agentTriggerStore.readAgentTrigger("greeting")).thenReturn(stored);
        when(agentTriggerStore.deleteAgentTriggerIfUnchanged("greeting", stored)).thenReturn(true);

        // Act
        Response response = restAgentTriggerStore.deleteAgentTrigger("greeting");

        // Assert
        assertEquals(200, response.getStatus());
        verify(agentTriggerStore).deleteAgentTriggerIfUnchanged("greeting", stored);
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
                .when(agentTriggerStore).readAgentTrigger("nonexistent");

        // Act & Assert — sneakyThrow re-throws as unchecked
        assertThrows(ResourceNotFoundException.class,
                () -> restAgentTriggerStore.deleteAgentTrigger("nonexistent"));

        // Cache should NOT be updated when delete fails
        verify(cache, never()).remove("nonexistent");
    }

    @Test
    void deleteAgentTrigger_storeError_throwsResourceStoreException() throws Exception {
        // Arrange — store throws ResourceStoreException
        var stored = triggerRouting("broken", "abcdef1234567890abcdef");
        when(agentTriggerStore.readAgentTrigger("broken")).thenReturn(stored);
        doThrow(new ResourceStoreException("DB connection failed"))
                .when(agentTriggerStore).deleteAgentTriggerIfUnchanged("broken", stored);

        // Act & Assert
        assertThrows(ResourceStoreException.class,
                () -> restAgentTriggerStore.deleteAgentTrigger("broken"));

        verify(cache, never()).remove("broken");
    }

    // --- Finding 4: a trigger carries no owner, so edit/delete is gated on USE of
    // the agents it currently routes to (a foreign editor must not re-point or
    // remove another team's trigger). ---

    private static AgentTriggerConfiguration triggerRouting(String intent, String agentId) {
        var deployment = new AgentDeployment();
        deployment.setAgentId(agentId);
        var configuration = new AgentTriggerConfiguration();
        configuration.setIntent(intent);
        configuration.getAgentDeployments().add(deployment);
        return configuration;
    }

    @Test
    void deleteAgentTrigger_storedTriggerRoutesToAgentCallerMayNotUse_refused() throws Exception {
        when(agentTriggerStore.readAgentTrigger("greeting")).thenReturn(triggerRouting("greeting", "abcdef1234567890abcdef"));
        doThrow(new ForbiddenException("no")).when(resourceAccessGuard).requireAccess("abcdef1234567890abcdef", AccessLevel.EDIT, "agent");

        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.deleteAgentTrigger("greeting"));
        verify(agentTriggerStore, never()).deleteAgentTriggerIfUnchanged(eq("greeting"), any());
        verify(cache, never()).remove("greeting");
    }

    @Test
    void updateAgentTrigger_storedTriggerRoutesToAgentCallerMayNotUse_refused() throws Exception {
        when(agentTriggerStore.readAgentTrigger("greeting")).thenReturn(triggerRouting("greeting", "abcdef1234567890abcdef"));
        doThrow(new ForbiddenException("no")).when(resourceAccessGuard).requireAccess("abcdef1234567890abcdef", AccessLevel.EDIT, "agent");

        var newConfig = triggerRouting("greeting", "0000111122223333aaaabbbb");
        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.updateAgentTrigger("greeting", newConfig));
        verify(agentTriggerStore, never()).updateAgentTriggerIfUnchanged(anyString(), any(), any());
    }

    // --- F2: USE is what an agent's users hold; changing where an intent routes is
    // an
    // edit. A caller who may only USE the agents must not re-point, remove or
    // create
    // a trigger for them. ---

    /**
     * A caller the agents were shared with for chatting: USE passes, EDIT does not.
     */
    private void callerHoldsUseButNotEdit() {
        lenient().doNothing().when(resourceAccessGuard).requireAgentUseAccess(anyString());
        lenient().doNothing().when(resourceAccessGuard).requireUseAccess(anyString(), anyString());
        lenient().doAnswer(i -> {
            if (i.<AccessLevel>getArgument(1).includes(AccessLevel.EDIT)) {
                throw new ForbiddenException("edit access required");
            }
            return AccessLevel.USE;
        }).when(resourceAccessGuard).requireAccess(anyString(), any(), anyString());
    }

    @Test
    void updateAgentTrigger_callerWhoMayOnlyUseTheStoredTargets_cannotRepointIt() throws Exception {
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(triggerRouting("support", "teamagent0000000000000"));
        callerHoldsUseButNotEdit();

        var hijack = triggerRouting("support", "myagent000000000000000");
        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.updateAgentTrigger("support", hijack));
        verify(agentTriggerStore, never()).updateAgentTriggerIfUnchanged(anyString(), any(), any());
        verify(cache, never()).put(anyString(), any());
    }

    @Test
    void deleteAgentTrigger_callerWhoMayOnlyUseTheStoredTargets_cannotRemoveIt() throws Exception {
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(triggerRouting("support", "teamagent0000000000000"));
        callerHoldsUseButNotEdit();

        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.deleteAgentTrigger("support"));
        verify(agentTriggerStore, never()).deleteAgentTriggerIfUnchanged(anyString(), any());
    }

    @Test
    void createAgentTrigger_callerWhoMayOnlyUseTheTarget_isRefused() throws Exception {
        callerHoldsUseButNotEdit();

        assertThrows(ForbiddenException.class,
                () -> restAgentTriggerStore.createAgentTrigger(triggerRouting("support", "teamagent0000000000000")));
        verify(agentTriggerStore, never()).createAgentTrigger(any());
    }

    @Test
    void updateAgentTrigger_requiresEditOnTheStoredAndTheNewTargets() throws Exception {
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(triggerRouting("support", "oldagent00000000000000"));

        when(agentTriggerStore.updateAgentTriggerIfUnchanged(eq("support"), any(), any())).thenReturn(true);
        restAgentTriggerStore.updateAgentTrigger("support", triggerRouting("support", "newagent00000000000000"));

        verify(resourceAccessGuard).requireAccess("oldagent00000000000000", AccessLevel.EDIT, "agent");
        verify(resourceAccessGuard).requireAccess("newagent00000000000000", AccessLevel.EDIT, "agent");
        verify(agentTriggerStore).updateAgentTriggerIfUnchanged(eq("support"), any(), any());
    }

    @Test
    void updateAgentTrigger_bodyNamingAnotherIntent_isRefused() throws Exception {
        // MongoDB's replace renamed the trigger to the body's intent; PostgreSQL kept
        // the
        // row under the path's. A rename is a delete plus a create, each checked.
        var renamed = triggerRouting("someone-elses-intent", "myagent000000000000000");

        assertThrows(BadRequestException.class, () -> restAgentTriggerStore.updateAgentTrigger("mine", renamed));
        verify(agentTriggerStore, never()).updateAgentTriggerIfUnchanged(anyString(), any(), any());
    }

    @Test
    void updateAgentTrigger_bodyWithoutIntent_takesThePathIntent() throws Exception {
        var noIntent = triggerRouting(null, "myagent000000000000000");
        var stored = triggerRouting("mine", "myagent000000000000000");
        when(agentTriggerStore.readAgentTrigger("mine")).thenReturn(stored);
        when(agentTriggerStore.updateAgentTriggerIfUnchanged("mine", stored, noIntent)).thenReturn(true);

        restAgentTriggerStore.updateAgentTrigger("mine", noIntent);

        assertEquals("mine", noIntent.getIntent());
        verify(agentTriggerStore).updateAgentTriggerIfUnchanged("mine", stored, noIntent);
    }

    @Test
    void createAgentTrigger_forAnIntentThatExists_isNotATakeover() throws Exception {
        // Both stores refuse a duplicate intent; the REST layer must neither overwrite
        // the stored trigger nor cache the caller's version as if it had.
        var takeover = triggerRouting("support", "myagent000000000000000");
        doThrow(new ResourceAlreadyExistsException("exists"))
                .when(agentTriggerStore).createAgentTrigger(takeover);

        assertThrows(ResourceAlreadyExistsException.class,
                () -> restAgentTriggerStore.createAgentTrigger(takeover));
        verify(agentTriggerStore, never()).updateAgentTriggerIfUnchanged(anyString(), any(), any());
        verify(cache, never()).put(anyString(), any());
    }

    // --- PR 942 review: a trigger with no target, and a change landing between the
    // check and the write ---

    private static AgentTriggerConfiguration targetless(String intent) {
        var configuration = new AgentTriggerConfiguration();
        configuration.setIntent(intent);
        return configuration;
    }

    @Test
    void createAgentTrigger_routingNowhere_isRefused() throws Exception {
        assertThrows(BadRequestException.class, () -> restAgentTriggerStore.createAgentTrigger(targetless("orphan")));
        var blankAgent = triggerRouting("orphan", " ");
        assertThrows(BadRequestException.class, () -> restAgentTriggerStore.createAgentTrigger(blankAgent));
        verify(agentTriggerStore, never()).createAgentTrigger(any());
    }

    @Test
    void updateAgentTrigger_toRouteNowhere_isRefused() throws Exception {
        assertThrows(BadRequestException.class, () -> restAgentTriggerStore.updateAgentTrigger("orphan", targetless("orphan")));
        verify(agentTriggerStore, never()).updateAgentTriggerIfUnchanged(anyString(), any(), any());
    }

    @Test
    void storedTargetlessTrigger_isAnAdministratorsToChange_underEnforcement() throws Exception {
        // Nothing to derive a right from: no EDIT check could run, so it must not
        // default to "anybody".
        var stored = targetless("orphan");
        when(agentTriggerStore.readAgentTrigger("orphan")).thenReturn(stored);
        when(resourceAccessGuard.seesEverything()).thenReturn(false);

        assertThrows(ForbiddenException.class,
                () -> restAgentTriggerStore.updateAgentTrigger("orphan", triggerRouting("orphan", "myagent000000000000000")));
        assertThrows(ForbiddenException.class, () -> restAgentTriggerStore.deleteAgentTrigger("orphan"));
        verify(agentTriggerStore, never()).updateAgentTriggerIfUnchanged(anyString(), any(), any());
        verify(agentTriggerStore, never()).deleteAgentTriggerIfUnchanged(anyString(), any());
    }

    @Test
    void storedTargetlessTrigger_canBeRepairedByAnAdministrator() throws Exception {
        var stored = targetless("orphan");
        when(agentTriggerStore.readAgentTrigger("orphan")).thenReturn(stored);
        when(resourceAccessGuard.seesEverything()).thenReturn(true);
        when(agentTriggerStore.deleteAgentTriggerIfUnchanged("orphan", stored)).thenReturn(true);

        assertEquals(200, restAgentTriggerStore.deleteAgentTrigger("orphan").getStatus());
    }

    @Test
    void updateAgentTrigger_rePointedBetweenCheckAndWrite_isAConflict_andNothingIsCached() throws Exception {
        var checked = triggerRouting("support", "teamagent0000000000000");
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(checked);
        // The conditional write finds the trigger routing elsewhere by now.
        when(agentTriggerStore.updateAgentTriggerIfUnchanged(eq("support"), eq(checked), any())).thenReturn(false);

        var thrown = assertThrows(WebApplicationException.class,
                () -> restAgentTriggerStore.updateAgentTrigger("support", triggerRouting("support", "teamagent0000000000000")));
        assertEquals(409, thrown.getResponse().getStatus());
        verify(agentTriggerStore, never()).updateAgentTrigger(anyString(), any());
        verify(cache, never()).put(anyString(), any());
    }

    @Test
    void deleteAgentTrigger_rePointedBetweenCheckAndWrite_isAConflict() throws Exception {
        var checked = triggerRouting("support", "teamagent0000000000000");
        when(agentTriggerStore.readAgentTrigger("support")).thenReturn(checked);
        when(agentTriggerStore.deleteAgentTriggerIfUnchanged("support", checked)).thenReturn(false);

        var thrown = assertThrows(WebApplicationException.class, () -> restAgentTriggerStore.deleteAgentTrigger("support"));
        assertEquals(409, thrown.getResponse().getStatus());
        verify(agentTriggerStore, never()).deleteAgentTrigger(anyString());
        verify(cache, never()).remove(anyString());
    }

    @Test
    void routesIdentically_comparesAgentsAndEnvironmentsInOrder() {
        var a = triggerRouting("x", "agent-1");
        assertTrue(IAgentTriggerStore.routesIdentically(a, triggerRouting("y", "agent-1")));
        assertFalse(IAgentTriggerStore.routesIdentically(a, triggerRouting("x", "agent-2")));
        assertFalse(IAgentTriggerStore.routesIdentically(a, targetless("x")));
        assertTrue(IAgentTriggerStore.routesIdentically(targetless("x"), targetless("y")));
    }

    // --- Visibility: a trigger is listed and readable only by callers who may USE
    // every agent it routes to ---

    @Test
    void readAllAgentTriggers_hidesTriggersRoutingToAgentsTheCallerMayNotUse() throws Exception {
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of(
                triggerRouting("mine", "myagent000000000000000"), triggerRouting("theirs", "theiragent000000000000")));
        when(resourceAccessGuard.hasAccess("theiragent000000000000", AccessLevel.USE)).thenReturn(false);

        var visible = restAgentTriggerStore.readAllAgentTriggers();

        assertEquals(List.of("mine"), visible.stream().map(AgentTriggerConfiguration::getIntent).toList());
    }

    @Test
    void readAgentTrigger_anotherTeamsIntent_answersLikeAnAbsentOne() throws Exception {
        when(agentTriggerStore.readAgentTrigger("theirs")).thenReturn(triggerRouting("theirs", "theiragent000000000000"));
        when(resourceAccessGuard.hasAccess("theiragent000000000000", AccessLevel.USE)).thenReturn(false);

        var refused = assertThrows(ResourceNotFoundException.class, () -> restAgentTriggerStore.readAgentTrigger("theirs"));
        assertInstanceOf(IRestAgentTriggerStore.TriggerNotVisibleException.class, refused,
                "a refusal must be distinguishable in-process, so callers do not clean up as if it were deleted");
    }

    @Test
    void readAllAgentTriggers_workspacesOff_isUnfiltered() throws Exception {
        when(resourceAccessGuard.seesEverything()).thenReturn(true);
        when(resourceAccessGuard.hasAccess(any(), any())).thenReturn(false);
        when(agentTriggerStore.readAllAgentTriggers()).thenReturn(List.of(triggerRouting("any", "someagent0000000000000")));

        assertEquals(1, restAgentTriggerStore.readAllAgentTriggers().size());
    }
}
