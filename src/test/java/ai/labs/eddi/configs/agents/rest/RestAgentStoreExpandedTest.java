/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional unit tests for {@link RestAgentStore} — CRUD operations, schema,
 * descriptors, capability registry, resource update in agent, and PostConstruct
 * initialization. Existing tests in {@link RestAgentStoreTest} cover cascade
 * delete and security flag validation.
 */
class RestAgentStoreExpandedTest {

    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String PKG_ID = "ff00112233445566aa77";

    private IAgentStore agentStore;
    private IRestWorkflowStore restWorkflowStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private IScheduleStore scheduleStore;
    private CapabilityRegistryService capabilityRegistryService;
    private IDeploymentStore deploymentStore;
    private RestAgentStore sut;

    @BeforeEach
    void setUp() {
        agentStore = mock(IAgentStore.class);
        restWorkflowStore = mock(IRestWorkflowStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        scheduleStore = mock(IScheduleStore.class);
        capabilityRegistryService = mock(CapabilityRegistryService.class);
        deploymentStore = mock(IDeploymentStore.class);

        sut = new RestAgentStore(agentStore, restWorkflowStore, documentDescriptorStore,
                jsonSchemaCreator, scheduleStore, capabilityRegistryService, deploymentStore, permissiveGuard());
    }

    private IResourceStore.IResourceId dummyResourceId(String id, int version) {
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

    // ─── readJsonSchema ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchema {

        @Test
        @DisplayName("should return JSON schema")
        void success() throws Exception {
            when(jsonSchemaCreator.generateSchema(AgentConfiguration.class)).thenReturn("{}");

            Response response = sut.readJsonSchema();

            assertEquals(200, response.getStatus());
            assertEquals("{}", response.getEntity());
        }

        @Test
        @DisplayName("should propagate exception when schema creation fails")
        void failure() throws Exception {
            when(jsonSchemaCreator.generateSchema(AgentConfiguration.class))
                    .thenThrow(new RuntimeException("schema error"));

            assertThrows(RuntimeException.class, () -> sut.readJsonSchema());
        }
    }

    // ─── readAgentDescriptors ──────────────────────────────────────────────────

    @Nested
    @DisplayName("readAgentDescriptors")
    class ReadDescriptors {

        @Test
        @DisplayName("should delegate to document descriptor store")
        void standard() throws Exception {
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), eq("filter"), eq(0), eq(20), eq(false), any()))
                    .thenReturn(List.of(new DocumentDescriptor()));

            List<DocumentDescriptor> result = sut.readAgentDescriptors("filter", 0, 20);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should query agents containing a workflow URI")
        void containingWorkflow() throws Exception {
            String workflowUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1";
            when(agentStore.getAgentDescriptorsContainingWorkflow(PKG_ID, 1, false))
                    .thenReturn(List.of(new DocumentDescriptor()));

            List<DocumentDescriptor> result = sut.readAgentDescriptors("", 0, 20, workflowUri, false);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should reject non-workflow URI")
        void nonWorkflowUri() {
            // Not a workflow URI — createMalFormattedResourceUriException throws
            // BadRequestException
            assertThrows(BadRequestException.class, () -> sut.readAgentDescriptors("", 0, 20,
                    "eddi://ai.labs.rules/rulestore/rulesets/abc?version=1", false));
        }

        @Test
        @DisplayName("should reject malformed URI")
        void malformedUri() {
            // Malformed URI — createMalFormattedResourceUriException throws
            // BadRequestException
            assertThrows(BadRequestException.class, () -> sut.readAgentDescriptors("", 0, 20, "not-a-valid-uri", false));
        }
    }

    // ─── readAgent ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readAgent")
    class ReadAgent {

        @Test
        @DisplayName("should return agent configuration")
        void success() throws Exception {
            var config = new AgentConfiguration();
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            AgentConfiguration result = sut.readAgent(AGENT_ID, 1);

            assertNotNull(result);
        }
    }

    // ─── createAgent ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createAgent")
    class CreateAgent {

        @Test
        @DisplayName("should create agent and return 201 with location header")
        void success() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.create(any())).thenReturn(dummyResourceId(AGENT_ID, 1));

            Response response = sut.createAgent(config);

            assertEquals(201, response.getStatus());
            assertNotNull(response.getLocation());
            verify(capabilityRegistryService).register(eq(AGENT_ID), any());
        }

        @Test
        @DisplayName("should handle capability registration failure gracefully")
        void capabilityRegistrationFails() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.create(any())).thenReturn(dummyResourceId(AGENT_ID, 1));
            doThrow(new RuntimeException("registry error"))
                    .when(capabilityRegistryService).register(any(), any());

            // Should not throw — failure is logged and swallowed
            assertDoesNotThrow(() -> sut.createAgent(config));
        }
    }

    // ─── updateAgent ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateAgent")
    class UpdateAgent {

        @Test
        @DisplayName("should update agent and register capabilities")
        void success() throws Exception {
            var config = new AgentConfiguration();
            when(agentStore.update(eq(AGENT_ID), eq(1), any())).thenReturn(2);

            Response response = sut.updateAgent(AGENT_ID, 1, config);

            assertEquals(200, response.getStatus());
            verify(capabilityRegistryService).register(AGENT_ID, config);
        }
    }

    // ─── updateResourceInAgent ─────────────────────────────────────────────────

    @Nested
    @DisplayName("updateResourceInAgent")
    class UpdateResourceInAgent {

        @Test
        @DisplayName("should update matching workflow URI in agent")
        void updatesMatchingUri() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(agentStore.update(eq(AGENT_ID), eq(1), any())).thenReturn(2);

            URI newUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=2");
            Response response = sut.updateResourceInAgent(AGENT_ID, 1, newUri);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return 400 when no matching workflow URI found")
        void noMatch() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/OTHER12345678901234?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            URI newUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=2");
            Response response = sut.updateResourceInAgent(AGENT_ID, 1, newUri);

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("preserves hitlConfig — the approval gate survives a re-point")
        void preservesHitlConfig() throws Exception {
            // This endpoint is the ONLY way an approval-gated agent can be re-pointed at
            // a new workflow version, because the full PUT /agentstore/agents/{id} is
            // permanently unbound (the gate lives in that document, so one approved write
            // there would remove all subsequent gating). It is safe to bind only if it
            // provably cannot drop the gate: the caller cannot SUPPLY a hitlConfig — the
            // body is a text/plain URI — and the implementation must PRESERVE the stored
            // one. The signature guarantees the first; this asserts the second.
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1"))));
            var hitl = new AgentConfiguration.HitlConfig();
            var toolApprovals = new ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of("http.post:*", "http.put:*", "http.delete:*"));
            toolApprovals.setExempt(List.of("http.get:*"));
            hitl.setToolApprovals(toolApprovals);
            config.setHitlConfig(hitl);

            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(agentStore.update(eq(AGENT_ID), eq(1), any())).thenReturn(2);

            URI newUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=2");
            assertEquals(200, sut.updateResourceInAgent(AGENT_ID, 1, newUri).getStatus());

            var captor = org.mockito.ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(agentStore).update(eq(AGENT_ID), eq(1), captor.capture());
            var written = captor.getValue();
            assertNotNull(written.getHitlConfig(), "the gate must survive a re-point");
            assertEquals(List.of("http.post:*", "http.put:*", "http.delete:*"),
                    written.getHitlConfig().getToolApprovals().getRequireApproval());
            assertEquals(List.of("http.get:*"), written.getHitlConfig().getToolApprovals().getExempt());
            // ...and ONLY the URI list changed.
            assertEquals(List.of(newUri), written.getWorkflows());
        }

        @Test
        @DisplayName("a URI without ?version is a 400, not a 500")
        void uriWithoutVersionIsRejected() throws Exception {
            // resourceURI.toString().substring(0, lastIndexOf("?")) throws
            // StringIndexOutOfBoundsException when there is no '?', turning a caller's
            // malformed input into a 500. That matters here more than usual: this
            // endpoint is on the re-point cascade an approval-gated operator has to walk
            // to finish editing an agent, so its failure mode is one an LLM will hit and
            // has to be able to act on.
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1"))));
            lenient().when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            URI noVersion = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID);
            Response response = sut.updateResourceInAgent(AGENT_ID, 1, noVersion);

            assertEquals(400, response.getStatus());
            verify(agentStore, never()).update(eq(AGENT_ID), eq(1), any());
        }

        @Test
        @DisplayName("a query without a version parameter cannot unpin the stored reference")
        void queryWithoutVersionParameterIsRejected() throws Exception {
            // Guarding on the presence of a '?' alone is not enough: the stored reference
            // is matched by everything BEFORE the query and then replaced by the supplied
            // URI, so '?other=2' would match the versioned reference and overwrite it
            // with a versionless one — silently unpinning the workflow the agent resolves
            // at runtime.
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1"))));
            lenient().when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            for (String query : List.of("?other=2", "?version=", "?version=abc", "?versionx=2", "?")) {
                URI bad = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + query);
                assertEquals(400, sut.updateResourceInAgent(AGENT_ID, 1, bad).getStatus(), "must refuse: " + query);
            }
            verify(agentStore, never()).update(eq(AGENT_ID), eq(1), any());
        }
    }

    // ─── duplicateAgent ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("duplicateAgent")
    class DuplicateAgent {

        @Test
        @DisplayName("should duplicate agent without deep copy")
        void shallowCopy() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(agentStore.create(any())).thenReturn(dummyResourceId("newAgent1234567890ab", 1));
            // createDocumentDescriptorForDuplicate reads the old descriptor to build the
            // copy
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(new DocumentDescriptor());

            Response response = sut.duplicateAgent(AGENT_ID, 1, false);

            assertEquals(201, response.getStatus());
            // Should NOT have called duplicateWorkflow since deepCopy=false
            verify(restWorkflowStore, never()).duplicateWorkflow(anyString(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("should duplicate agent with deep copy")
        void deepCopy() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(agentStore.create(any())).thenReturn(dummyResourceId("newAgent1234567890ab", 1));
            // createDocumentDescriptorForDuplicate reads the old descriptor to build the
            // copy
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(new DocumentDescriptor());

            // Mock workflow duplicate response with X-Resource-URI header
            var dupResponse = Response.created(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/newPkg1234567890ab?version=1"))
                    .header("X-Resource-URI", "eddi://ai.labs.workflow/workflowstore/workflows/newPkg1234567890ab?version=1")
                    .entity("eddi://ai.labs.workflow/workflowstore/workflows/newPkg1234567890ab?version=1")
                    .build();
            when(restWorkflowStore.duplicateWorkflow(PKG_ID, 1, true)).thenReturn(dupResponse);

            Response response = sut.duplicateAgent(AGENT_ID, 1, true);

            assertEquals(201, response.getStatus());
            verify(restWorkflowStore).duplicateWorkflow(PKG_ID, 1, true);
        }
    }

    // ─── deleteAgent with schedule cascade ─────────────────────────────────────

    @Nested
    @DisplayName("deleteAgent with schedule cascade")
    class DeleteAgentScheduleCascade {

        @Test
        @DisplayName("should cascade-delete schedules for agent")
        void deletesSchedules() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(scheduleStore.deleteSchedulesByAgentId(AGENT_ID)).thenReturn(3);

            sut.deleteAgent(AGENT_ID, 1, true, true);

            verify(scheduleStore).deleteSchedulesByAgentId(AGENT_ID);
        }

        @Test
        @DisplayName("should continue if schedule cascade fails")
        void scheduleDeleteFails() throws Exception {
            var config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(scheduleStore.deleteSchedulesByAgentId(AGENT_ID))
                    .thenThrow(new RuntimeException("schedule store error"));

            assertDoesNotThrow(() -> sut.deleteAgent(AGENT_ID, 1, true, true));
        }

        @Test
        @DisplayName("should unregister capabilities on delete")
        void unregistersCapabilities() throws Exception {
            sut.deleteAgent(AGENT_ID, 1, false, false);

            verify(capabilityRegistryService).unregister(AGENT_ID);
        }
    }

    // ─── getResourceURI / getCurrentResourceId ─────────────────────────────────

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("getResourceURI should return the resource URI")
        void getResourceUri() {
            String uri = sut.getResourceURI();

            assertNotNull(uri);
            assertTrue(uri.contains("agent"));
        }

        @Test
        @DisplayName("getCurrentResourceId should delegate to agentStore")
        void getCurrentResourceId() throws Exception {
            when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(dummyResourceId(AGENT_ID, 5));

            IResourceStore.IResourceId result = sut.getCurrentResourceId(AGENT_ID);

            assertEquals(AGENT_ID, result.getId());
            assertEquals(5, result.getVersion());
        }
    }

    // ─── populateCapabilityRegistry ────────────────────────────────────────────

    @Nested
    @DisplayName("populateCapabilityRegistry (@PostConstruct)")
    class CapabilityRegistryInit {

        @Test
        @DisplayName("should register agents with capabilities on startup")
        void registersCapabilities() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false))
                    .thenReturn(List.of(descriptor));

            var config = new AgentConfiguration();
            config.setCapabilities(List.of(
                    new AgentConfiguration.Capability("greeting", Map.of(), "medium"),
                    new AgentConfiguration.Capability("farewell", Map.of(), "medium")));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            sut.populateCapabilityRegistry();

            verify(capabilityRegistryService).register(AGENT_ID, config);
        }

        @Test
        @DisplayName("should skip agents without capabilities")
        void skipsNonCapableAgents() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false))
                    .thenReturn(List.of(descriptor));

            var config = new AgentConfiguration();
            // No capabilities set
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            sut.populateCapabilityRegistry();

            verify(capabilityRegistryService, never()).register(anyString(), any());
        }

        @Test
        @DisplayName("should handle individual agent read failure gracefully")
        void individualAgentFailure() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false))
                    .thenReturn(List.of(descriptor));
            when(agentStore.read(AGENT_ID, 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> sut.populateCapabilityRegistry());
        }

        @Test
        @DisplayName("should handle complete registry population failure gracefully")
        void totalFailure() throws Exception {
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false))
                    .thenThrow(new RuntimeException("db error"));

            assertDoesNotThrow(() -> sut.populateCapabilityRegistry());
        }
    }

    /**
     * A guard that admits everything. {@code visibleOnly} filters with
     * {@code canAccess}, which a bare Mockito mock answers {@code false} to —
     * correct for a security check, wrong for a test that is not about the security
     * check.
     */
    private static ResourceAccessGuard permissiveGuard() {
        ResourceAccessGuard guard = mock(ResourceAccessGuard.class);
        lenient().when(guard.seesEverything()).thenReturn(true);
        lenient().when(guard.canAccess(any(), any())).thenReturn(true);
        return guard;
    }
}
