/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import ai.labs.eddi.configs.channels.model.EmailSenderRoles;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The MCP tools {@code list_email_roles} and {@code update_email_roles}: parity
 * with the REST edit.
 */
@DisplayName("McpAdminTools — email sender roles")
class McpEmailRoleToolsTest {

    private static final String ID = "aabbccddee1122334455aabb";

    private IRestChannelIntegrationStore store;
    private IJsonSerialization json;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IRestChannelIntegrationStore.class);
        json = mock(IJsonSerialization.class);
        when(json.serialize(any())).thenAnswer(inv -> String.valueOf((Object) inv.getArgument(0)));
        var factory = mock(IRestInterfaceFactory.class);
        when(factory.get(IRestChannelIntegrationStore.class)).thenReturn(store);
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        var admin = mock(IRestAgentAdministration.class);
        tools = new McpAdminTools(factory, admin, json, mock(StrictConfigurationParser.class), mock(IRestScheduleStore.class), identity, false,
                new AgentCascadeService(factory, admin));
    }

    @Test
    @DisplayName("list_email_roles answers the version, the roles and the policies")
    void list() throws Exception {
        when(store.readEmailRoles(ID)).thenReturn(new EmailSenderRoles.View(3, List.of(new SenderRole("exec", List.of("cfo@x.com"))),
                Map.of("exec", new RolePolicy(null, null, null, null, null))));

        tools.listEmailRoles(ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> answer = ArgumentCaptor.forClass(Map.class);
        verify(json).serialize(answer.capture());
        assertEquals(3, answer.getValue().get("version"));
        assertEquals(List.of(new SenderRole("exec", List.of("cfo@x.com"))), answer.getValue().get("senderRoles"));
    }

    @Test
    @DisplayName("update_email_roles sends the parsed roles with the expected version and reason")
    void update() throws Exception {
        var control = new EmailControl(List.of(new SenderRole("customer", List.of("*@c.com"))),
                Map.of("customer", new RolePolicy(List.of("support"), List.of("kb_search"), null, null, null)));
        when(json.deserialize(anyString(), eq(EmailControl.class))).thenReturn(control);
        when(store.updateEmailRoles(eq(ID), any())).thenReturn(Response.ok("ok").build());

        String result = tools.updateEmailRoles(ID, 3, "{...}", "customers to support only");

        var request = ArgumentCaptor.forClass(EmailSenderRoles.UpdateRequest.class);
        verify(store).updateEmailRoles(eq(ID), request.capture());
        assertEquals(3, request.getValue().expectedVersion());
        assertEquals(control.getSenderRoles(), request.getValue().senderRoles());
        assertEquals(control.getRolePolicies(), request.getValue().rolePolicies());
        assertEquals("customers to support only", request.getValue().reason());
        assertTrue(result.contains("updated"), result);
    }

    @Test
    @DisplayName("a stale version is a CONFLICT; without a version or roles nothing is sent")
    void conflictAndRequired() throws Exception {
        when(json.deserialize(anyString(), eq(EmailControl.class))).thenReturn(new EmailControl());
        when(store.updateEmailRoles(eq(ID), any())).thenReturn(Response.status(409).entity("current").build());
        assertTrue(tools.updateEmailRoles(ID, 3, "{}", null).contains("CONFLICT"));

        assertTrue(tools.updateEmailRoles(ID, null, "{}", null).contains("expectedVersion"));
        assertTrue(tools.updateEmailRoles(ID, 3, " ", null).contains("roles is required"));
        verify(store, never()).updateEmailRoles(eq("other"), any());
    }
}
