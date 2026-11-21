/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.ReferringVersions;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ResourceInUseGuard")
class ResourceInUseGuardTest {

    private static final String LLM_URI = "eddi://ai.labs.llm/llmstore/llms/";
    private static final String WORKFLOW_URI = "eddi://ai.labs.workflow/workflowstore/workflows/";

    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IDeploymentStore deploymentStore;
    private IDocumentDescriptorStore descriptorStore;
    private IConversationMemoryStore conversations;
    private IRestConversationStore restConversations;
    private ResourceAccessGuard accessGuard;
    private ResourceInUseGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        deploymentStore = mock(IDeploymentStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        conversations = mock(IConversationMemoryStore.class);
        restConversations = mock(IRestConversationStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        when(accessGuard.seesEverything()).thenReturn(true);
        when(accessGuard.canAccess(any(DocumentDescriptor.class), eq(AccessLevel.VIEW))).thenReturn(true);
        when(agentStore.findAgentVersionsListing(anyCollection())).thenReturn(ReferringVersions.none());
        when(workflowStore.findWorkflowVersionsReferencing(anyCollection())).thenReturn(ReferringVersions.none());
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        guard = new ResourceInUseGuard(agentStore, workflowStore, deploymentStore, descriptorStore, conversations, restConversations,
                accessGuard);
    }

    private static IResourceStore.IResourceId id(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static DocumentDescriptor descriptor(String name) {
        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        return descriptor;
    }

    private static DeploymentInfo deployed(String agentId, int version) {
        var info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        return info;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(WebApplicationException e) {
        return (Map<String, Object>) e.getResponse().getEntity();
    }

    @Test
    @DisplayName("a config a workflow's current version uses is not deleted: 409 naming the workflow (L5)")
    void usedConfigIsRefused() throws Exception {
        when(workflowStore.findWorkflowVersionsReferencing(List.of(LLM_URI + "abc?version=1")))
                .thenReturn(new ReferringVersions(List.of(id("wf1", 3)), List.of()));
        when(descriptorStore.readCurrentDescriptor("wf1")).thenReturn(descriptor("Support flow"));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false, false));

        assertEquals(409, conflict.getResponse().getStatus());
        var body = body(conflict);
        assertEquals("RESOURCE_IN_USE", body.get("code"));
        assertEquals(1, body.get("usedByCount"));
        assertEquals(false, body.get("othersNotListed"));
        assertEquals("force=true", body.get("override"));
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) ((List<?>) body.get("usedBy")).getFirst();
        assertEquals("workflow", first.get("type"));
        assertEquals("wf1", first.get("id"));
        assertEquals("Support flow", first.get("name"));
        assertEquals(3, first.get("version"));
    }

    @Test
    @DisplayName("S2: a workflow version only an older, deployed agent version lists is protected")
    void deployedOlderAgentVersionProtectsTheWorkflow() throws Exception {
        // Agent a1 is at v2 (which replaced the workflow) but v1 — which lists it — is
        // the deployed one.
        when(agentStore.findAgentVersionsListing(List.of(WORKFLOW_URI + "wf1?version=1")))
                .thenReturn(new ReferringVersions(List.of(), List.of(id("a1", 1))));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("a1", 1)));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(WORKFLOW_URI, "wf1", 1, false, false));
        assertEquals(409, conflict.getResponse().getStatus());

        // The same history hit for a version that is not deployed is stale and does not
        // count.
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("a1", 2)));
        assertDoesNotThrow(() -> guard.requireUnused(WORKFLOW_URI, "wf1", 1, false, false));
    }

    @Test
    @DisplayName("S2: a config only an older workflow version uses is protected when a current or deployed agent pins that version")
    void pinnedOlderWorkflowVersionProtectsTheConfig() throws Exception {
        when(workflowStore.findWorkflowVersionsReferencing(List.of(LLM_URI + "abc?version=1")))
                .thenReturn(new ReferringVersions(List.of(), List.of(id("wf1", 1))));
        when(agentStore.findAgentVersionsListing(List.of(WORKFLOW_URI + "wf1?version=1")))
                .thenReturn(new ReferringVersions(List.of(), List.of(id("a1", 1))));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("a1", 1)));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false, false));
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) ((List<?>) body(conflict).get("usedBy")).getFirst();
        assertEquals("agent", first.get("type"));
        assertEquals("a1", first.get("id"));
    }

    @Test
    @DisplayName("a permanent delete asks for every version in one lookup; a soft delete only for the version addressed")
    void permanentLooksAtEveryVersion() throws Exception {
        guard.requireUnused(WORKFLOW_URI, "wf1", 3, true, false);
        verify(agentStore).findAgentVersionsListing(
                List.of(WORKFLOW_URI + "wf1?version=1", WORKFLOW_URI + "wf1?version=2", WORKFLOW_URI + "wf1?version=3"));
        guard.requireUnused(WORKFLOW_URI, "wf1", 3, false, false);
        verify(agentStore).findAgentVersionsListing(List.of(WORKFLOW_URI + "wf1?version=3"));
    }

    @Test
    @DisplayName("S6: the lookup is a fixed number of store calls, however many agents use the resource; no reads to decide")
    void boundedLookup() throws Exception {
        List<IResourceStore.IResourceId> many = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            many.add(id("a" + i, 1));
        }
        when(agentStore.findAgentVersionsListing(anyCollection())).thenReturn(new ReferringVersions(many, List.of()));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(WORKFLOW_URI, "wf1", 1, false, false));

        verify(agentStore, times(1)).findAgentVersionsListing(anyCollection());
        verify(descriptorStore, times(ResourceInUseGuard.MAX_NAMED_USERS)).readCurrentDescriptor(anyString());
        assertEquals(5000, body(conflict).get("usedByCount"), "an administrator is told the full count");
        assertEquals(true, body(conflict).get("othersNotListed"));
    }

    @Test
    @DisplayName("S4: a caller who does not see everything is told only about users they may view, and whether there are others")
    void countsOnlyVisibleUsers() throws Exception {
        when(accessGuard.seesEverything()).thenReturn(false);
        List<IResourceStore.IResourceId> users = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            users.add(id("w" + i, 1));
            DocumentDescriptor descriptor = descriptor("w" + i);
            when(descriptorStore.readCurrentDescriptor("w" + i)).thenReturn(descriptor);
            when(accessGuard.canAccess(descriptor, AccessLevel.VIEW)).thenReturn(i % 2 == 0);
        }
        when(workflowStore.findWorkflowVersionsReferencing(anyCollection())).thenReturn(new ReferringVersions(users, List.of()));

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false, false));
        var body = body(conflict);

        assertEquals(ResourceInUseGuard.MAX_INSPECTED_USERS / 2, body.get("usedByCount"),
                "only the visible ones among the inspected are counted");
        assertEquals(ResourceInUseGuard.MAX_NAMED_USERS, ((List<?>) body.get("usedBy")).size());
        assertEquals(true, body.get("othersNotListed"));
        assertFalse(body.toString().contains("name=w1,"), "a user the caller may not view is not named");
        verify(descriptorStore, times(ResourceInUseGuard.MAX_INSPECTED_USERS)).readCurrentDescriptor(anyString());
    }

    @Test
    @DisplayName("an unused resource is deleted as before")
    void unusedPasses() {
        assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false, false));
    }

    @Test
    @DisplayName("force deletes anyway and does not even look")
    void forceSkipsTheCheck() {
        assertDoesNotThrow(() -> guard.requireUnused(LLM_URI, "abc", 1, false, true));
        verify(workflowStore, never()).findWorkflowVersionsReferencing(anyCollection());
    }

    @Test
    @DisplayName("callUnguarded (the agent cascade) skips the check for the call only")
    void unguardedScope() {
        when(workflowStore.findWorkflowVersionsReferencing(anyCollection()))
                .thenReturn(new ReferringVersions(List.of(id("w1", 1)), List.of()));
        assertDoesNotThrow(() -> ResourceInUseGuard.callUnguarded(() -> {
            guard.requireUnused(LLM_URI, "abc", 1, false, false);
            return null;
        }));
        assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false, false),
                "the binding ends with the call");
    }

    @Test
    @DisplayName("a failing reverse lookup refuses with a retryable 503 instead of deleting unguarded")
    void lookupFailureRefuses() {
        when(workflowStore.findWorkflowVersionsReferencing(anyCollection())).thenThrow(new IllegalStateException("db down"));

        var refused = assertThrows(WebApplicationException.class, () -> guard.requireUnused(LLM_URI, "abc", 1, false, false));

        assertEquals(503, refused.getResponse().getStatus());
        assertEquals("IN_USE_CHECK_FAILED", body(refused).get("code"));
        assertEquals(String.valueOf(ResourceInUseGuard.RETRY_AFTER_SECONDS), refused.getResponse().getHeaderString("Retry-After"));
        assertFalse(body(refused).get("error").toString().contains("db down"), "no driver text reaches the client");
    }

    @Test
    @DisplayName("a deployed agent with active conversations is not deleted (OI4): 409 like undeploy, nothing ended")
    void openConversationsRefuse() {
        when(conversations.getActiveConversationCount("a1", 1)).thenReturn(2L);

        var conflict = assertThrows(WebApplicationException.class, () -> guard.requireNoOpenConversations("a1", List.of(1), false));

        assertEquals(409, conflict.getResponse().getStatus());
        var body = body(conflict);
        assertEquals("AGENT_HAS_OPEN_CONVERSATIONS", body.get("code"));
        assertEquals(2L, body.get("openConversationsCount"));
        assertEquals("endAllActiveConversations=true", body.get("override"));
        verify(restConversations, never()).endActiveConversations(any(), anyString());
    }

    @Test
    @DisplayName("M6: with endAll the count is returned, nothing is ended until the caller asks")
    void endAllOnlyCounts() {
        when(conversations.getActiveConversationCount("a1", 1)).thenReturn(1L);

        var open = guard.requireNoOpenConversations("a1", List.of(1), true);

        assertEquals(1L, open.total());
        verify(restConversations, never()).endActiveConversations(any(), anyString());
    }

    @Test
    @DisplayName("ending runs page by page, skips conversations paused for a human, and records agent-deleted")
    void endsInPagesWithTheDeletionReason() throws Exception {
        when(conversations.loadOpenConversationActivity("a1", 1, null, 500)).thenReturn(List.of(
                new ConversationActivitySummary("c1", ConversationState.READY, "a1", 1, null),
                new ConversationActivitySummary("c2", ConversationState.AWAITING_HUMAN, "a1", 1, null)));
        when(conversations.loadOpenConversationActivity("a1", 1, "c2", 500)).thenReturn(List.of(
                new ConversationActivitySummary("c3", ConversationState.IN_PROGRESS, "a1", 1, null)));
        when(conversations.loadOpenConversationActivity("a1", 1, "c3", 500)).thenReturn(List.of());
        when(restConversations.endActiveConversations(any(), anyString())).thenReturn(Response.ok().build());

        guard.endOpenConversations("a1", new ResourceInUseGuard.OpenConversations(Map.of(1, 2L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationStatus>> pages = ArgumentCaptor.forClass(List.class);
        verify(restConversations, times(2)).endActiveConversations(pages.capture(), eq(IConversationService.END_REASON_AGENT_DELETED));
        assertEquals(List.of("c1"), pages.getAllValues().get(0).stream().map(ConversationStatus::getConversationId).toList());
        assertEquals(List.of("c3"), pages.getAllValues().get(1).stream().map(ConversationStatus::getConversationId).toList());
    }

    @Test
    @DisplayName("a conversation that could not be ended stops the delete")
    void failedEndStops() throws Exception {
        when(conversations.loadOpenConversationActivity("a1", 1, null, 500))
                .thenReturn(List.of(new ConversationActivitySummary("c1", ConversationState.READY, "a1", 1, null)));
        when(restConversations.endActiveConversations(any(), anyString())).thenReturn(Response.serverError().build());

        assertThrows(IllegalStateException.class,
                () -> guard.endOpenConversations("a1", new ResourceInUseGuard.OpenConversations(Map.of(1, 1L))));
    }

    @Test
    @DisplayName("a count that fails refuses with 503")
    void countFailureRefuses() {
        when(conversations.getActiveConversationCount("a1", 1)).thenThrow(new IllegalStateException("db down"));
        var refused = assertThrows(WebApplicationException.class, () -> guard.requireNoOpenConversations("a1", List.of(1), true));
        assertEquals(503, refused.getResponse().getStatus());
    }

    @Test
    @DisplayName("an agent that is not deployed, or has no active conversations, is deleted as before")
    void noConversationsNoRefusal() {
        assertTrue(guard.requireNoOpenConversations("a1", List.of(), false).isEmpty());
        when(conversations.getActiveConversationCount("a1", 1)).thenReturn(0L);
        assertTrue(guard.requireNoOpenConversations("a1", List.of(1), false).isEmpty());
    }

    @Test
    @DisplayName("a store that nothing points at (groups, connections) is never checked")
    void unguardedTypes() {
        assertFalse(ResourceInUseGuard.isGuarded("eddi://ai.labs.group/groupstore/groups/"));
        assertTrue(ResourceInUseGuard.isGuarded(LLM_URI));
        assertDoesNotThrow(() -> ResourceInUseGuard.requireUnusedOrForced("eddi://ai.labs.group/groupstore/groups/", "g", 1, false, false));
    }
}
