/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.AgentCascadeService.CascadeException;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentCascadeRequest;
import ai.labs.eddi.configs.agents.model.AgentCascadeRequest.ResourceMapping;
import ai.labs.eddi.configs.agents.model.AgentCascadeResult;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /agentstore/agents/{id}/cascade}: the REST facade over
 * {@link AgentCascadeService}. The cascade itself is tested there; this pins
 * what the endpoint adds — status mapping, the up-front EDIT check, environment
 * parsing — and its role.
 */
@DisplayName("RestAgentStore.cascadeAgentChanges")
class RestAgentStoreCascadeTest {

    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String OLD = "eddi://ai.labs.llm/llmstore/llms/112233445566778899aabbcc?version=1";
    private static final String NEW = "eddi://ai.labs.llm/llmstore/llms/112233445566778899aabbcc?version=2";

    private AgentCascadeService cascadeService;
    private ResourceAccessGuard accessGuard;
    private RestAgentStore store;

    @BeforeEach
    void setUp() {
        cascadeService = mock(AgentCascadeService.class);
        accessGuard = mock(ResourceAccessGuard.class);
        store = new RestAgentStore(mock(IAgentStore.class), mock(IRestWorkflowStore.class), mock(IDocumentDescriptorStore.class),
                mock(IJsonSchemaCreator.class), mock(IScheduleStore.class), mock(CapabilityRegistryService.class), mock(IDeploymentStore.class),
                accessGuard, mock(AgentSigningService.class), mock(IAgentFactory.class), "default", cascadeService);
    }

    private static AgentCascadeRequest request(String environment, Boolean redeploy, Boolean compatible) {
        return new AgentCascadeRequest(List.of(new ResourceMapping(OLD, NEW)), redeploy, environment, compatible);
    }

    private static AgentCascadeResult result() {
        return new AgentCascadeResult(AGENT_ID, 1, 2, List.of(), 1, 1, false, null);
    }

    @Test
    @DisplayName("hands the mappings, flags and environment to the shared service and returns its result")
    void delegates() {
        var expected = result();
        when(cascadeService.cascade(eq(AGENT_ID), eq(1), any(), anyBoolean(), any(), anyBoolean())).thenReturn(expected);

        var actual = store.cascadeAgentChanges(AGENT_ID, 1, request("test", true, true));

        assertSame(expected, actual);
        ArgumentCaptor<List<ResourceMapping>> mappings = ArgumentCaptor.forClass(List.class);
        verify(cascadeService).cascade(eq(AGENT_ID), eq(1), mappings.capture(), eq(true), eq(Deployment.Environment.test), eq(true));
        assertEquals(List.of(new ResourceMapping(OLD, NEW)), mappings.getValue());
    }

    @Test
    @DisplayName("defaults: no redeploy, production, breaking change")
    void defaults() {
        when(cascadeService.cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(result());

        store.cascadeAgentChanges(AGENT_ID, 1, request(null, null, null));

        verify(cascadeService).cascade(eq(AGENT_ID), eq(1), any(), eq(false), eq(Deployment.Environment.production), eq(false));
    }

    @Test
    @DisplayName("an unknown mapping is a 400")
    void badRequest() {
        when(cascadeService.cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean()))
                .thenThrow(new CascadeException(CascadeException.Kind.BAD_REQUEST, "Not referenced by agent"));

        var ex = assertThrows(BadRequestException.class, () -> store.cascadeAgentChanges(AGENT_ID, 1, request(null, null, null)));

        assertTrue(ex.getMessage().contains("Not referenced"));
        assertEquals(400, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("a version conflict is a 409")
    void conflict() {
        when(cascadeService.cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean()))
                .thenThrow(new CascadeException(CascadeException.Kind.CONFLICT, "Agent is at version 3"));

        var ex = assertThrows(ClientErrorException.class, () -> store.cascadeAgentChanges(AGENT_ID, 2, request(null, null, null)));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("a missing agent is a 404")
    void notFound() {
        when(cascadeService.cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean()))
                .thenThrow(new CascadeException(CascadeException.Kind.NOT_FOUND, "Agent not found"));

        var ex = assertThrows(NotFoundException.class, () -> store.cascadeAgentChanges(AGENT_ID, 1, request(null, null, null)));

        assertEquals(404, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("a store's own status (a 409 from the agent update, a 403) passes through untouched")
    void storeStatusPropagates() {
        when(cascadeService.cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean())).thenThrow(new ForbiddenException("no"));

        assertThrows(ForbiddenException.class, () -> store.cascadeAgentChanges(AGENT_ID, 1, request(null, null, null)));
    }

    @Test
    @DisplayName("EDIT access is checked first: a caller who may not edit the agent learns nothing about it")
    void editAccessFirst() {
        doThrow(new ForbiddenException("not yours")).when(accessGuard).requireAccess(AGENT_ID, AccessLevel.EDIT, "agent");

        assertThrows(ForbiddenException.class, () -> store.cascadeAgentChanges(AGENT_ID, 1, request(null, null, null)));

        verify(cascadeService, never()).cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an unknown environment is a 400, before anything is read")
    void unknownEnvironment() {
        var ex = assertThrows(BadRequestException.class, () -> store.cascadeAgentChanges(AGENT_ID, 1, request("staging", true, null)));

        assertTrue(ex.getMessage().contains("staging"));
        verify(cascadeService, never()).cascade(any(), anyInt(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a missing body or version is a 400")
    void missingInputs() {
        assertThrows(BadRequestException.class, () -> store.cascadeAgentChanges(AGENT_ID, 1, null));
        assertThrows(BadRequestException.class, () -> store.cascadeAgentChanges(AGENT_ID, null, request(null, null, null)));
        verify(cascadeService, never()).cascade(anyString(), anyInt(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("the endpoint is a JSON POST that inherits the store's admin/editor role gate and does not weaken it")
    void roleAndShape() throws Exception {
        Method method = null;
        for (Method candidate : IRestAgentStore.class.getMethods()) {
            if (candidate.getName().equals("cascadeAgentChanges")) {
                method = candidate;
            }
        }
        assertNotNull(method);
        assertNotNull(method.getAnnotation(POST.class));
        assertEquals("/{id}/cascade", method.getAnnotation(Path.class).value());
        // No method-level annotation that could widen the class-level gate.
        assertNull(method.getAnnotation(PermitAll.class));
        assertNull(method.getAnnotation(RolesAllowed.class), "the gate is the class-level one, exactly like every sibling write");
        // The writes it composes (agent update, workflow update, deploy) all require
        // exactly
        // these roles, so the cascade must not be reachable by anyone who could not do
        // each step.
        assertEquals(List.of("eddi-admin", "eddi-editor"), List.of(IRestAgentStore.class.getAnnotation(RolesAllowed.class).value()));
    }
}
