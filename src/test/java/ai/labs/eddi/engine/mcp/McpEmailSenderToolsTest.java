/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.EmailHeldMailView;
import ai.labs.eddi.configs.channels.model.EmailSenderLists;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ClientErrorException;
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
 * The MCP tools {@code list_email_senders} and {@code update_email_senders}.
 */
@DisplayName("McpAdminTools — email sender lists")
class McpEmailSenderToolsTest {

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
    @DisplayName("list_email_senders answers the version and both lists")
    void list() throws Exception {
        when(store.readEmailSenders(ID)).thenReturn(new EmailSenderLists.View(3, List.of("bob@partner.com"), List.of()));

        tools.listEmailSenders(ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> answer = ArgumentCaptor.forClass(Map.class);
        verify(json).serialize(answer.capture());
        assertEquals(3, answer.getValue().get("version"));
        assertEquals(List.of("bob@partner.com"), answer.getValue().get("allow"));
    }

    @Test
    @DisplayName("update_email_senders sends the comma-separated entries with the expected version")
    void update() {
        when(store.updateEmailSenders(eq(ID), any())).thenReturn(Response.ok(new EmailSenderLists.UpdateResult(3, 4, true,
                List.of("bob@partner.com", "alice@partner.com"), List.of(), List.of("allow:alice@partner.com"), List.of(), List.of(), List.of()))
                .build());

        String result = tools.updateEmailSenders(ID, 3, " alice@partner.com , ", null, null, "*@spam.example", "new supplier", null);

        var request = ArgumentCaptor.forClass(EmailSenderLists.UpdateRequest.class);
        verify(store).updateEmailSenders(eq(ID), request.capture());
        assertEquals(3, request.getValue().expectedVersion());
        assertEquals(List.of("alice@partner.com"), request.getValue().add().allow());
        assertEquals(List.of("*@spam.example"), request.getValue().remove().block());
        assertEquals("new supplier", request.getValue().reason());
        assertEquals(false, request.getValue().confirmOpen());
        assertTrue(result.contains("updated"), result);
    }

    @Test
    @DisplayName("a stale version is a CONFLICT with the current lists — answered or thrown by the client")
    void conflict() {
        var current = new EmailSenderLists.View(5, List.of("bob@partner.com"), List.of());
        when(store.updateEmailSenders(eq(ID), any())).thenReturn(Response.status(409).entity(current).build());
        String answered = tools.updateEmailSenders(ID, 3, "alice@partner.com", null, null, null, null, null);
        assertTrue(answered.contains("CONFLICT"), answered);

        when(store.updateEmailSenders(eq(ID), any())).thenThrow(new ClientErrorException(Response.status(409).entity(current).build()));
        String thrown = tools.updateEmailSenders(ID, 3, "alice@partner.com", null, null, null, null, null);
        assertTrue(thrown.contains("CONFLICT"), thrown);
    }

    @Test
    @DisplayName("without an expected version nothing is sent")
    void versionRequired() {
        String result = tools.updateEmailSenders(ID, null, "alice@partner.com", null, null, null, null, null);
        assertTrue(result.contains("expectedVersion"), result);
        verify(store, never()).updateEmailSenders(anyString(), any());
    }

    @Test
    @DisplayName("list_held_email lists the queue, or with a rowId the live preview; decide_held_email passes the decision and the note")
    void heldMail() throws Exception {
        var queue = new EmailHeldMailView.Queue(ID, "Invoices", List.of(), false);
        when(store.readHeldEmail(ID, 50)).thenReturn(queue);
        tools.listHeldEmail(ID, null, null);
        verify(store).readHeldEmail(ID, 50);
        verify(store, never()).readHeldEmailPreview(any(), any());

        tools.listHeldEmail(ID, " row-1 ", null);
        verify(store).readHeldEmailPreview(ID, "row-1");

        when(store.decideHeldEmail(eq(ID), eq("row-1"), any()))
                .thenReturn(Response.ok(new EmailHeldMailView.DecisionResult("row-1", "runOnce", "RECEIVED", null, List.of())).build());
        String decided = tools.decideHeldEmail(ID, "row-1", " runOnce ", "known partner");
        var request = ArgumentCaptor.forClass(EmailHeldMailView.DecisionRequest.class);
        verify(store).decideHeldEmail(eq(ID), eq("row-1"), request.capture());
        assertEquals("runOnce", request.getValue().decision());
        assertEquals("known partner", request.getValue().note());
        assertTrue(decided.contains("decided"), decided);

        assertTrue(tools.decideHeldEmail(ID, null, "discard", null).contains("rowId is required"));
        assertTrue(tools.decideHeldEmail(ID, "row-1", " ", null).contains("decision is required"));
    }
}