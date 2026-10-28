/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.alerts.AlertDeliveryService;
import ai.labs.eddi.engine.alerts.AlertDispatcher;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.alerts.rest.AlertAccess;
import ai.labs.eddi.engine.alerts.rest.RestAlertEvents;
import ai.labs.eddi.engine.api.IRestAlertEvents;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The alert inbox tools run the REST resource's scoping against the MCP caller:
 * the real {@link RestAlertEvents} with mocked guards and store.
 */
@DisplayName("McpAlertTools")
class McpAlertToolsTest {

    private static final String AGENT = "6aba70011a25f8be7920e055";
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ResourceAccessGuard guard;
    private IAlertEventStore store;
    private McpAlertTools tools;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        ConversationAccessGuard conversationGuard = mock(ConversationAccessGuard.class);
        when(conversationGuard.callerActor(anyString())).thenReturn("alice");
        store = mock(IAlertEventStore.class);
        var rest = new RestAlertEvents(store, mock(IAlertRuleStore.class), mock(AlertDeliveryService.class), mock(AlertDispatcher.class),
                new AlertAccess(guard, conversationGuard));
        tools = new McpAlertTools(rest, new JsonSerialization(mapper), identity(Set.of("eddi-editor")), true);
    }

    private static SecurityIdentity identity(Set<String> roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(id.getRoles()).thenReturn(roles);
        return id;
    }

    private static AlertEvent event(String agentId) {
        return new AlertEvent("6aba70011a25f8be7920e0a1", "6aba70011a25f8be7920e0a2", "Broken turns", AlertSource.CONVERSATION_ERROR,
                AlertEvent.Kind.ALERT, agentId, 2, "6aba70011a25f8be7920e0a3", NOW, "Broken turns: a turn failed",
                AlertMatch.error(ErrorKind.TURN_ERROR),
                List.of(), AlertEvent.State.INBOX_ONLY, null, "k", 0, null, null, null, null, 0);
    }

    @Test
    @DisplayName("viewers and users are refused by both tools")
    void roleTier() {
        for (String role : List.of("eddi-viewer", "eddi-user")) {
            var refused = new McpAlertTools(mock(IRestAlertEvents.class), new JsonSerialization(mapper), identity(Set.of(role)), true);
            assertThrows(ForbiddenException.class, () -> refused.listAlertEvents(AGENT, null, null, null, null, null, null, null, null, null));
            assertThrows(ForbiddenException.class, () -> refused.acknowledgeAlertEvent("e"));
        }
    }

    @Test
    @DisplayName("an editor names an agent (AGENT_ID_REQUIRED otherwise) and holds EDIT on it (FORBIDDEN otherwise)")
    void scoping() throws Exception {
        when(guard.seesEverything()).thenReturn(false);
        assertEquals("AGENT_ID_REQUIRED",
                mapper.readTree(tools.listAlertEvents(null, null, null, null, null, null, null, null, null, null)).get("errorCode").asText());
        doThrow(new ForbiddenException("Access denied: no edit access")).when(guard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
        assertEquals("FORBIDDEN",
                mapper.readTree(tools.listAlertEvents(AGENT, null, null, null, null, null, null, null, null, null)).get("errorCode").asText());
        verify(store, never()).list(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("the open inbox of an agent; acknowledgement records the caller from the identity")
    void listAndAcknowledge() throws Exception {
        when(guard.seesEverything()).thenReturn(false);
        when(store.list(any(), eq(0), eq(20))).thenReturn(List.of(event(AGENT)));
        when(store.count(any())).thenReturn(1L);

        JsonNode page = mapper.readTree(tools.listAlertEvents(AGENT, null, "conversation_error", false, null, null, null, null, null, null));
        assertEquals("Broken turns", page.get("events").get(0).get("ruleName").asText());
        ArgumentCaptor<AlertEventFilter> filter = ArgumentCaptor.forClass(AlertEventFilter.class);
        verify(store).list(filter.capture(), eq(0), eq(20));
        assertEquals(Boolean.FALSE, filter.getValue().acknowledged());
        assertEquals(AlertSource.CONVERSATION_ERROR, filter.getValue().source());

        when(store.read("6aba70011a25f8be7920e0a1")).thenReturn(Optional.of(event(AGENT)));
        tools.acknowledgeAlertEvent("6aba70011a25f8be7920e0a1");
        verify(store).acknowledge(eq("6aba70011a25f8be7920e0a1"), eq("alice"), any());
    }

    @Test
    @DisplayName("an agent-less digest is an administrator's; a blank id is BAD_REQUEST")
    void digestAndBadRequest() throws Exception {
        when(guard.seesEverything()).thenReturn(false);
        when(store.read("d")).thenReturn(Optional.of(event(null)));
        assertEquals("NOT_PERMITTED", mapper.readTree(tools.acknowledgeAlertEvent("d")).get("errorCode").asText());
        assertEquals("BAD_REQUEST", mapper.readTree(tools.acknowledgeAlertEvent(" ")).get("errorCode").asText());
        verify(store, never()).acknowledge(any(), any(), any());
    }
}
