/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Extended tests for {@link RestAgentStore} — readJsonSchema,
 * readAgentDescriptors, readAgent, createAgent, updateAgent,
 * updateResourceInAgent, duplicateAgent, getResourceURI, getCurrentResourceId,
 * and deleteAgent edge cases.
 */
@DisplayName("RestAgentStore — Extended Branch Coverage")
class RestAgentStoreExtendedTest {

    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String WF1_ID = "ff00112233445566aa77";

    @Mock
    private IAgentStore agentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private CapabilityRegistryService capabilityRegistryService;
    @Mock
    private IDeploymentStore deploymentStore;

    private RestAgentStore restAgentStore;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        restAgentStore = new RestAgentStore(agentStore, restWorkflowStore,
                documentDescriptorStore, jsonSchemaCreator, scheduleStore, capabilityRegistryService, deploymentStore,
                permissiveGuard(), mock(AgentSigningService.class), "default");
        // The Agent is live at v1. A cascade only runs against the CURRENT version —
        // it tears workflows and schedules down before the delete's own version check.
        when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(createResourceId(AGENT_ID, 1));
    }

    // ==================== readJsonSchema ====================

    @Nested
    @DisplayName("readJsonSchema")
    class ReadJsonSchemaTests {

        @Test
        @DisplayName("should return 200 with generated schema")
        void returnsSchema() throws Exception {
            when(jsonSchemaCreator.generateSchema(AgentConfiguration.class)).thenReturn("{\"type\":\"object\"}");
            Response response = restAgentStore.readJsonSchema();
            assertEquals(200, response.getStatus());
            assertEquals("{\"type\":\"object\"}", response.getEntity());
        }

        @Test
        @DisplayName("should throw when schema generation fails")
        void throwsOnFailure() throws Exception {
            when(jsonSchemaCreator.generateSchema(AgentConfiguration.class))
                    .thenThrow(new RuntimeException("schema error"));
            assertThrows(RuntimeException.class, () -> restAgentStore.readJsonSchema());
        }
    }

    // ==================== readAgentDescriptors (with containingWorkflowUri)
    // ====================

    @Nested
    @DisplayName("readAgentDescriptors with containingWorkflowUri")
    class ReadAgentDescriptorsWithUri {

        @Test
        @DisplayName("invalid workflow URI returns error list")
        void invalidWorkflowUri() {
            // A completely invalid URI should cause a BadRequestException
            assertThrows(jakarta.ws.rs.BadRequestException.class, () -> restAgentStore.readAgentDescriptors(
                    null, 0, 10, "invalid-uri", false));
        }

        @Test
        @DisplayName("valid workflow URI delegates to agentStore")
        void validWorkflowUri() throws Exception {
            String wfUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1";
            DocumentDescriptor desc = new DocumentDescriptor();
            when(agentStore.getAgentDescriptorsContainingWorkflow(WF1_ID, 1, false))
                    .thenReturn(List.of(desc));

            List<DocumentDescriptor> result = restAgentStore.readAgentDescriptors(
                    null, 0, 10, wfUri, false);

            assertEquals(1, result.size());
        }

        /**
         * {@code filter}, {@code index} and {@code limit} are declared on this POST
         * overload (with {@code @DefaultValue("20")} on limit, advertising paging) and
         * were then never referenced: a client paging through got the whole list back
         * on every page.
         */
        @Test
        @DisplayName("index and limit actually page the result")
        void pagesTheResult() throws Exception {
            String wfUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1";
            when(agentStore.getAgentDescriptorsContainingWorkflow(WF1_ID, 1, false))
                    .thenReturn(List.of(named("a"), named("b"), named("c"), named("d"), named("e")));

            assertEquals(List.of("a", "b"), names(restAgentStore.readAgentDescriptors(null, 0, 2, wfUri, false)));
            assertEquals(List.of("c", "d"), names(restAgentStore.readAgentDescriptors(null, 1, 2, wfUri, false)));
            assertEquals(List.of("e"), names(restAgentStore.readAgentDescriptors(null, 2, 2, wfUri, false)));
            assertTrue(restAgentStore.readAgentDescriptors(null, 9, 2, wfUri, false).isEmpty(), "past the end must be empty, not the full list");
        }

        @Test
        @DisplayName("filter narrows the result")
        void filtersTheResult() throws Exception {
            String wfUri = "eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1";
            when(agentStore.getAgentDescriptorsContainingWorkflow(WF1_ID, 1, false))
                    .thenReturn(List.of(named("support agent"), named("Sales Agent"), named("triage")));

            assertEquals(List.of("Sales Agent"), names(restAgentStore.readAgentDescriptors("sales", 0, 20, wfUri, false)));
        }

        private DocumentDescriptor named(String name) {
            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName(name);
            return descriptor;
        }

        private List<String> names(List<DocumentDescriptor> descriptors) {
            return descriptors.stream().map(DocumentDescriptor::getName).toList();
        }
    }

    // ==================== updateResourceInAgent ====================

    @Nested
    @DisplayName("updateResourceInAgent")
    class UpdateResourceInAgent {

        @Test
        @DisplayName("matching workflow URI is updated")
        void matchingUriUpdated() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            config.setSecurity(null);
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            URI newUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=2");
            Response response = restAgentStore.updateResourceInAgent(AGENT_ID, 1, newUri);

            // Should have updated successfully
            assertNotNull(response);
        }

        @Test
        @DisplayName("non-matching workflow URI returns 400")
        void nonMatchingUri() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            URI newUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/differentId?version=2");
            Response response = restAgentStore.updateResourceInAgent(AGENT_ID, 1, newUri);

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        }

        /**
         * The 400 body names the resource that was not updated, and this is the AGENT
         * store: the constant was copy-pasted from the workflow-store twin, so the body
         * came back as {@code eddi://ai.labs.workflow/workflowstore/workflows/} built
         * from an AGENT id — a URI no resource has, which callers that parse the entity
         * (UpgradeExecutor, the Manager) read as the wrong resource type. Only the
         * status code was asserted, so the wrong URI went unnoticed.
         */
        @Test
        @DisplayName("the 400 body names this agent, not a workflow")
        void nonMatchingUriReportsTheAgentUri() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            URI newUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/differentId?version=2");
            Response response = restAgentStore.updateResourceInAgent(AGENT_ID, 1, newUri);

            assertEquals(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"),
                    response.getEntity());
        }
    }

    // ==================== deleteAgent — schedule cascade ====================

    @Nested
    @DisplayName("deleteAgent — schedule cascade")
    class DeleteAgentScheduleCascade {

        @Test
        @DisplayName("cascade deletes schedules first")
        void cascadeDeletesSchedules() throws Exception {
            when(scheduleStore.deleteSchedulesByAgentId(AGENT_ID)).thenReturn(2);

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            verify(scheduleStore).deleteSchedulesByAgentId(AGENT_ID);
        }

        @Test
        @DisplayName("schedule deletion failure does not block agent deletion")
        void scheduleDeletionFailureHandled() throws Exception {
            when(scheduleStore.deleteSchedulesByAgentId(AGENT_ID))
                    .thenThrow(new RuntimeException("schedule error"));

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));
        }

        @Test
        @DisplayName("no cascade — schedules not deleted")
        void noCascadeNoSchedules() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);
            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
        }

        @Test
        @DisplayName("deleteAgent — ResourceStoreException during agent read")
        void resourceStoreExceptionDuringCascade() throws Exception {
            when(agentStore.read(AGENT_ID, 1))
                    .thenThrow(new IResourceStore.ResourceStoreException("store error"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));
        }
    }

    // ==================== getResourceURI / getCurrentResourceId
    // ====================

    @Nested
    @DisplayName("getResourceURI and getCurrentResourceId")
    class ResourceInfoTests {

        @Test
        @DisplayName("getResourceURI returns non-null")
        void getResourceUri() {
            assertNotNull(restAgentStore.getResourceURI());
        }

        @Test
        @DisplayName("getCurrentResourceId delegates to store")
        void getCurrentResourceId() throws Exception {
            IResourceId resourceId = createResourceId(AGENT_ID, 3);
            when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId);

            IResourceId result = restAgentStore.getCurrentResourceId(AGENT_ID);
            assertEquals(AGENT_ID, result.getId());
            assertEquals(3, result.getVersion());
        }

        @Test
        @DisplayName("getCurrentResourceId propagates ResourceNotFoundException")
        void propagatesNotFound() throws Exception {
            when(agentStore.getCurrentResourceId("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertThrows(IResourceStore.ResourceNotFoundException.class,
                    () -> restAgentStore.getCurrentResourceId("missing"));
        }
    }

    // ==================== duplicateAgent ====================

    @Nested
    @DisplayName("duplicateAgent")
    class DuplicateAgentTests {

        @Test
        @DisplayName("deep copy duplicates workflows")
        void deepCopyDuplicatesWorkflows() throws Exception {
            AgentConfiguration sourceConfig = new AgentConfiguration();
            sourceConfig.setSecurity(null);
            sourceConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            String newWfId = "newwf11223344556677";
            URI newWfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + newWfId + "?version=1");
            Response wfDupResponse = Response.created(newWfUri).location(newWfUri)
                    .header("X-Resource-URI", newWfUri.toString()).build();
            when(restWorkflowStore.duplicateWorkflow(WF1_ID, 1, true)).thenReturn(wfDupResponse);

            String newAgentId = "newagent1122334455";
            IResourceId newResId = createResourceId(newAgentId, 1);
            when(agentStore.create(any())).thenReturn(newResId);

            // Mock the descriptor lookup for createDocumentDescriptorForDuplicate
            var oldDescriptor = new DocumentDescriptor();
            oldDescriptor.setName("Original Agent");
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(oldDescriptor);

            Response response = restAgentStore.duplicateAgent(AGENT_ID, 1, true);

            assertNotNull(response);
            assertEquals(201, response.getStatus());
        }

        @Test
        @DisplayName("shallow copy keeps original workflow URIs")
        void shallowCopy() throws Exception {
            AgentConfiguration sourceConfig = new AgentConfiguration();
            sourceConfig.setSecurity(null);
            sourceConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            String newAgentId = "newagent1122334455";
            IResourceId newResId = createResourceId(newAgentId, 1);
            when(agentStore.create(any())).thenReturn(newResId);

            // Mock the descriptor lookup for createDocumentDescriptorForDuplicate
            var oldDescriptor = new DocumentDescriptor();
            oldDescriptor.setName("Original Agent");
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(oldDescriptor);

            Response response = restAgentStore.duplicateAgent(AGENT_ID, 1, false);

            assertNotNull(response);
            assertEquals(201, response.getStatus());
            verify(restWorkflowStore, never()).duplicateWorkflow(anyString(), anyInt(), anyBoolean());
        }

        /**
         * {@code ?version=0} is the documented "current version" shorthand that PUT and
         * DELETE honour. {@code validateParameters} maps it, but its return value was
         * discarded here, so {@code agentStore.read(id, 0)} matched nothing — neither
         * the current row nor the history fallback — and duplicating the current
         * version answered 404.
         */
        @Test
        @DisplayName("version=0 duplicates the current version instead of 404")
        void versionZeroDuplicatesCurrent() throws Exception {
            AgentConfiguration sourceConfig = new AgentConfiguration();
            sourceConfig.setSecurity(null);
            sourceConfig.setWorkflows(new ArrayList<>());
            when(agentStore.getCurrentResourceId(AGENT_ID)).thenReturn(createResourceId(AGENT_ID, 3));
            when(agentStore.read(AGENT_ID, 3)).thenReturn(sourceConfig);
            when(agentStore.create(any())).thenReturn(createResourceId("newagent1122334455", 1));

            var oldDescriptor = new DocumentDescriptor();
            oldDescriptor.setName("Original Agent");
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 3)).thenReturn(oldDescriptor);

            Response response = restAgentStore.duplicateAgent(AGENT_ID, 0, false);

            assertEquals(201, response.getStatus());
            verify(agentStore).read(AGENT_ID, 3);
        }

        @Test
        @DisplayName("duplicateAgent — X-Resource-URI header fallback")
        void xResourceUriHeaderFallback() throws Exception {
            AgentConfiguration sourceConfig = new AgentConfiguration();
            sourceConfig.setSecurity(null);
            sourceConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            String newWfId = "newwf11223344556677";
            URI newWfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + newWfId + "?version=1");
            // Response without location but with X-Resource-URI header
            Response wfDupResponse = Response.ok()
                    .header("X-Resource-URI", newWfUri.toString()).build();
            when(restWorkflowStore.duplicateWorkflow(WF1_ID, 1, true)).thenReturn(wfDupResponse);

            String newAgentId = "newagent1122334455";
            IResourceId newResId = createResourceId(newAgentId, 1);
            when(agentStore.create(any())).thenReturn(newResId);

            // Mock the descriptor lookup for createDocumentDescriptorForDuplicate
            var oldDescriptor = new DocumentDescriptor();
            oldDescriptor.setName("Original Agent");
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(oldDescriptor);

            Response response = restAgentStore.duplicateAgent(AGENT_ID, 1, true);

            assertNotNull(response);
        }

        @Test
        @DisplayName("duplicateAgent — null location, null header, entity as URI string")
        void entityAsUriFallback() throws Exception {
            AgentConfiguration sourceConfig = new AgentConfiguration();
            sourceConfig.setSecurity(null);
            sourceConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            String newWfId = "newwf11223344556677";
            URI newWfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + newWfId + "?version=1");
            // Response without location and without X-Resource-URI, but entity is URI
            // string
            Response wfDupResponse = Response.ok(newWfUri.toString()).build();
            when(restWorkflowStore.duplicateWorkflow(WF1_ID, 1, true)).thenReturn(wfDupResponse);

            String newAgentId = "newagent1122334455";
            IResourceId newResId = createResourceId(newAgentId, 1);
            when(agentStore.create(any())).thenReturn(newResId);

            // Mock the descriptor lookup for createDocumentDescriptorForDuplicate
            var oldDescriptor = new DocumentDescriptor();
            oldDescriptor.setName("Original Agent");
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(oldDescriptor);

            Response response = restAgentStore.duplicateAgent(AGENT_ID, 1, true);

            assertNotNull(response);
        }

        @Test
        @DisplayName("duplicateAgent — all URI extraction strategies fail throws")
        void allUriStrategiesFail() throws Exception {
            AgentConfiguration sourceConfig = new AgentConfiguration();
            sourceConfig.setSecurity(null);
            sourceConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1"))));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(sourceConfig);

            // Response with no location, no header, and non-URI entity
            Response wfDupResponse = Response.ok().build();
            when(restWorkflowStore.duplicateWorkflow(WF1_ID, 1, true)).thenReturn(wfDupResponse);

            assertThrows(Exception.class,
                    () -> restAgentStore.duplicateAgent(AGENT_ID, 1, true));
        }
    }

    // ==================== createAgent — capability registration failure
    // ====================

    @Nested
    @DisplayName("createAgent — capability registration")
    class CreateAgentCapability {

        @Test
        @DisplayName("capability registration failure does not block creation")
        void registrationFailureHandled() throws Exception {
            var config = new AgentConfiguration();
            config.setSecurity(null);
            config.setWorkflows(new ArrayList<>());

            IResourceId newId = createResourceId(AGENT_ID, 1);
            when(agentStore.create(any())).thenReturn(newId);
            doThrow(new RuntimeException("registration failed"))
                    .when(capabilityRegistryService).register(anyString(), any());

            Response response = restAgentStore.createAgent(config);
            assertEquals(201, response.getStatus());
        }
    }

    // ==================== validateSecurityFlags — identity with empty keys and
    // blank public key ====================

    @Nested
    @DisplayName("validateSecurityFlags — empty keys")
    class SecurityFlagsEmptyKeys {

        @Test
        @DisplayName("empty keys list and blank publicKey throws BadRequestException")
        void emptyKeysAndBlankPublicKey() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);

            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("   "); // blank
            identity.setKeys(List.of()); // empty
            config.setIdentity(identity);

            assertThrows(jakarta.ws.rs.BadRequestException.class,
                    () -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("null identity with crypto enabled throws BadRequestException")
        void nullIdentity() {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setRequirePeerVerification(true);
            config.setSecurity(security);
            config.setIdentity(null);

            assertThrows(jakarta.ws.rs.BadRequestException.class,
                    () -> restAgentStore.createAgent(config));
        }
    }

    // ==================== Helpers ====================

    private IResourceId createResourceId(String id, int version) {
        return new IResourceId() {
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
        // Without this the bare mock returns null from redactForCaller and visibleOnly
        // hands back a list of nulls — which every size-only assertion passes anyway.
        lenient().when(guard.redactForCaller(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return guard;
    }
}
