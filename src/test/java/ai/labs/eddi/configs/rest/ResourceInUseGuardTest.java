/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ResourceInUseGuard")
class ResourceInUseGuardTest {

    private static final String LLM_URI = "eddi://ai.labs.llm/llmstore/llms/";
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/";

    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IConversationMemoryStore conversations;
    private IRestConversationStore restConversations;
    private ResourceAccessGuard accessGuard;
    private ResourceInUseGuard guard;
    private UriInfo uriInfo;
    private MultivaluedHashMap<String, String> query;

    @BeforeEach
    void setUp() {
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        conversations = mock(IConversationMemoryStore.class);
        restConversations = mock(IRestConversationStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        when(accessGuard.canAccess(any(DocumentDescriptor.class), eq(AccessLevel.VIEW))).thenReturn(true);
        guard = new ResourceInUseGuard(agentStore, workflowStore, conversations, restConversations, accessGuard);
        uriInfo = mock(UriInfo.class);
        query = new MultivaluedHashMap<>();
        when(uriInfo.getQueryParameters()).thenReturn(query);
        guard.requestUri = uriInfo;
    }

    private static DocumentDescriptor descriptor(String name, String resourceUri) {
        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        descriptor.setResource(URI.create(resourceUri));
        return descriptor;
    }

    @Test
    @DisplayName("a config a workflow uses is not deleted: 409 naming the workflow (L5)")
    void usedConfigIsRefused() throws Exception {
        when(workflowStore.getWorkflowDescriptorsContainingResource(LLM_URI + "abc?version=1", false))
                .thenReturn(List.of(descriptor("Support flow", WORKFLOW_URI + "wf1?version=3")));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false));

        assertEquals(409, conflict.getResponse().getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) conflict.getResponse().getEntity();
        assertEquals("RESOURCE_IN_USE", body.get("code"));
        assertEquals(1, body.get("usedByCount"));
        assertEquals("force=true", body.get("override"));
        var usedBy = (List<?>) body.get("usedBy");
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) usedBy.getFirst();
        assertEquals("workflow", first.get("type"));
        assertEquals("wf1", first.get("id"));
        assertEquals("Support flow", first.get("name"));
        assertEquals(3, first.get("version"));
    }

    @Test
    @DisplayName("a workflow an agent lists is not deleted; permanent looks at every version")
    void usedWorkflowIsRefused() throws Exception {
        when(agentStore.getAgentDescriptorsContainingWorkflow("wf1", 2, true))
                .thenReturn(List.of(descriptor("Bot", "eddi://ai.labs.agent/agentstore/agents/a1?version=1")));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(WORKFLOW_URI, "wf1", 2, true));
        assertEquals(409, conflict.getResponse().getStatus());
        verify(agentStore).getAgentDescriptorsContainingWorkflow("wf1", 2, true);
    }

    @Test
    @DisplayName("an unused resource is deleted as before")
    void unusedPasses() throws Exception {
        when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean())).thenReturn(List.of());
        assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false));
    }

    @Test
    @DisplayName("?force=true deletes anyway and does not even look")
    void forceSkipsTheCheck() throws Exception {
        query.add("force", "true");
        assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false));
        verify(workflowStore, never()).getWorkflowDescriptorsContainingResource(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("the bypass scope (cascade, MCP force) skips the check and nests")
    void bypassScope() throws Exception {
        try (var outer = ResourceInUseGuard.bypass()) {
            try (var inner = ResourceInUseGuard.forced()) {
                assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false));
            }
            assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false), "the outer scope is still open");
        }
        when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean()))
                .thenReturn(List.of(descriptor("w", WORKFLOW_URI + "w1?version=1")));
        assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false));
    }

    @Test
    @DisplayName("at most ten users are named, all are counted, and ones the caller may not see are left out")
    void namesAtMostTen() throws Exception {
        var many = new java.util.ArrayList<DocumentDescriptor>();
        for (int i = 0; i < 14; i++) {
            many.add(descriptor("w" + i, WORKFLOW_URI + "w" + i + "?version=1"));
        }
        when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean())).thenReturn(many);
        when(accessGuard.canAccess(many.get(0), AccessLevel.VIEW)).thenReturn(false);

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false));
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) conflict.getResponse().getEntity();
        assertEquals(14, body.get("usedByCount"));
        assertEquals(ResourceInUseGuard.MAX_NAMED_USERS, ((List<?>) body.get("usedBy")).size());
        assertTrue(!body.toString().contains("name=w0,") && !body.toString().contains("w0}"), "the hidden one is not named");
    }

    @Test
    @DisplayName("a failing reverse lookup does not lock the resource in place")
    void lookupFailureFailsOpen() throws Exception {
        when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean())).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false));
    }

    @Test
    @DisplayName("a deployed agent with open conversations is not deleted (OI4); 409 like undeploy")
    void openConversationsRefuse() {
        when(conversations.getActiveConversationCount("a1", 1)).thenReturn(2L);

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireNoOpenConversations("a1", List.of(1)));

        assertEquals(409, conflict.getResponse().getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) conflict.getResponse().getEntity();
        assertEquals("AGENT_HAS_OPEN_CONVERSATIONS", body.get("code"));
        assertEquals(2L, body.get("openConversationsCount"));
        assertEquals("endAllActiveConversations=true", body.get("override"));
        verify(restConversations, never()).endActiveConversations(any(), anyString());
    }

    @Test
    @DisplayName("endAllActiveConversations=true ends them first and then lets the delete go on")
    void endAllEndsConversations() throws Exception {
        when(conversations.getActiveConversationCount("a1", 1)).thenReturn(1L);
        when(conversations.loadOpenConversationActivity("a1", 1, null, 500))
                .thenReturn(List.of(new ConversationActivitySummary("c1", null, "a1", 1, null)));
        when(conversations.loadOpenConversationActivity("a1", 1, "c1", 500)).thenReturn(List.of());
        when(restConversations.endActiveConversations(any(), anyString())).thenReturn(jakarta.ws.rs.core.Response.ok().build());
        query.add("endAllActiveConversations", "true");

        assertDoesNotThrow(() -> guard.requireNoOpenConversations("a1", List.of(1)));

        verify(restConversations).endActiveConversations(any(), anyString());
    }

    @Test
    @DisplayName("an agent that is not deployed, or has no open conversations, is deleted as before")
    void noConversationsNoRefusal() {
        assertDoesNotThrow(() -> guard.requireNoOpenConversations("a1", List.of()));
        when(conversations.getActiveConversationCount("a1", 1)).thenReturn(0L);
        assertDoesNotThrow(() -> guard.requireNoOpenConversations("a1", List.of(1)));
    }

    @Test
    @DisplayName("a store that nothing points at (groups, connections) is never checked")
    void unguardedTypes() {
        assertDoesNotThrow(() -> ResourceInUseGuard.requireUnusedOrForced("eddi://ai.labs.group/groupstore/groups/", "g", 1, false));
    }
}
