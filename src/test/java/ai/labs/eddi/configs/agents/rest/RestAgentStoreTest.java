/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.SigningTestSupport;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import jakarta.ws.rs.BadRequestException;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import jakarta.ws.rs.ForbiddenException;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import jakarta.ws.rs.NotFoundException;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import jakarta.ws.rs.WebApplicationException;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import jakarta.ws.rs.core.Response;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import org.junit.jupiter.api.BeforeEach;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import org.junit.jupiter.api.DisplayName;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import org.junit.jupiter.api.Nested;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import org.junit.jupiter.api.Test;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import org.mockito.ArgumentCaptor;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import org.mockito.Mock;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import java.net.URI;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import java.util.ArrayList;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import java.util.List;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import jakarta.ws.rs.BadRequestException;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static ai.labs.eddi.utils.LogCaptureSupport.FORGED_RECORD;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static org.junit.jupiter.api.Assertions.*;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static org.mockito.ArgumentMatchers.*;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static org.mockito.Mockito.*;
import ai.labs.eddi.configs.agents.AgentCascadeService;
import static org.mockito.MockitoAnnotations.openMocks;

class RestAgentStoreTest {

    // Realistic IDs (extractResourceId requires 18+ hex chars)
    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String PKG1_ID = "ff00112233445566aa77";
    private static final String PKG2_ID = "bb99887766554433cc22";

    @Mock
    private IAgentStore AgentStore;
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
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentFactory agentFactory;

    private RestAgentStore restAgentStore;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        restAgentStore = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                capabilityRegistryService, deploymentStore, mock(ResourceAccessGuard.class), agentSigningService, agentFactory, "default",
                mock(AgentCascadeService.class));
        // The Agent is live at v1: a cascade is only allowed against the CURRENT
        // version, since it tears down workflows and schedules before the delete —
        // the only place the version used to be checked — has run.
        when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 1));
        // A workflow reference is version-pinned and is NOT re-pointed when the
        // workflow is edited, so the cascade resolves each one to the version that
        // EXISTS before it asks who else uses it or deletes anything.
        when(restWorkflowStore.getCurrentResourceId(PKG1_ID)).thenReturn(resourceId(PKG1_ID, 2));
        when(restWorkflowStore.getCurrentResourceId(PKG2_ID)).thenReturn(resourceId(PKG2_ID, 1));
        // The Agent stops being a referrer of its own workflows the moment it is
        // deleted; see referrers().
        doAnswer(invocation -> {
            agentDeleted.set(true);
            return null;
        }).when(AgentStore).delete(anyString(), anyInt());
        doAnswer(invocation -> {
            agentDeleted.set(true);
            return null;
        }).when(AgentStore).deleteAllPermanently(anyString());
    }

    static IResourceStore.IResourceId resourceId(String id, int version) {
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

    /** Helper to create a dummy DocumentDescriptor for reference-count mocking */
    private DocumentDescriptor dummyDescriptor() {
        return new DocumentDescriptor();
    }

    /**
     * Whether the Agent under test has been deleted yet; see {@link #referrers}.
     */
    private final AtomicBoolean agentDeleted = new AtomicBoolean();

    private static DeploymentInfo deployed(Deployment.Environment environment, String agentId, int version) {
        DeploymentInfo info = new DeploymentInfo();
        info.setEnvironment(environment);
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        return info;
    }

    /**
     * Deleting an Agent removed its deployment records but left it running: the
     * deleted Agent kept answering in its environment until a restart, and no
     * undeploy path could stop it because each starts by reading the deleted
     * configuration.
     */
    @Nested
    @DisplayName("deleting a deployed Agent")
    class DeleteUndeploysLiveVersions {

        private static final String OTHER_AGENT_ID = "cc00112233445566dd88";

        @Test
        @DisplayName("undeploys every recorded or running version of the Agent, and nothing else")
        void undeploysRecordedAndRunningVersions() throws Exception {
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployed(Deployment.Environment.production, AGENT_ID, 1),
                            deployed(Deployment.Environment.production, OTHER_AGENT_ID, 3)));
            IAgent runningWithoutRecord = mock(IAgent.class);
            when(runningWithoutRecord.getAgentId()).thenReturn(AGENT_ID);
            when(runningWithoutRecord.getAgentVersion()).thenReturn(1);
            IAgent unrelated = mock(IAgent.class);
            when(unrelated.getAgentId()).thenReturn(OTHER_AGENT_ID);
            when(unrelated.getAgentVersion()).thenReturn(3);
            when(agentFactory.getAllLatestAgents(Deployment.Environment.test)).thenReturn(List.of(runningWithoutRecord, unrelated));
            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(List.of());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentFactory).undeployAgent(Deployment.Environment.production, AGENT_ID, 1);
            verify(agentFactory).undeployAgent(Deployment.Environment.test, AGENT_ID, 1);
            verify(agentFactory, never()).undeployAgent(any(), eq(OTHER_AGENT_ID), anyInt());
        }

        @Test
        @DisplayName("does not undeploy the same version twice when a record and the runtime both name it")
        void deduplicatesRecordAndRuntime() throws Exception {
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployed(Deployment.Environment.production, AGENT_ID, 1)));
            IAgent running = mock(IAgent.class);
            when(running.getAgentId()).thenReturn(AGENT_ID);
            when(running.getAgentVersion()).thenReturn(1);
            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(List.of(running));
            when(agentFactory.getAllLatestAgents(Deployment.Environment.test)).thenReturn(List.of());

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(agentFactory, times(1)).undeployAgent(Deployment.Environment.production, AGENT_ID, 1);
        }

        @Test
        @DisplayName("a refused delete leaves the Agent running")
        void refusedDeleteKeepsAgentRunning() throws Exception {
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployed(Deployment.Environment.production, AGENT_ID, 1)));
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(agentFactory, never()).undeployAgent(any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("an undeploy failure does not fail the delete")
        void undeployFailureIsNotFatal() throws Exception {
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(deployed(Deployment.Environment.production, AGENT_ID, 1)));
            doThrow(new IllegalStateException("busy")).when(agentFactory).undeployAgent(Deployment.Environment.production, AGENT_ID, 1);

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }
    }

    /**
     * A create or update naming a workflow that does not exist used to be accepted
     * with 201, moving the failure to an opaque deployment ERROR.
     */
    @Nested
    @DisplayName("workflow references on create and update")
    class WorkflowReferencesMustResolve {

        private AgentConfiguration referencing(String workflowId) {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=1"))));
            return config;
        }

        @Test
        @DisplayName("update checks EDIT on the agent before looking any workflow up — no existence oracle")
        void updateChecksAgentAccessBeforeWorkflowLookup() {
            ResourceAccessGuard denyingGuard = mock(ResourceAccessGuard.class);
            doThrow(new ForbiddenException("no")).when(denyingGuard).requireAccess(eq("agent-x"), eq(AccessLevel.EDIT), any());
            var guardedStore = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                    capabilityRegistryService, deploymentStore, denyingGuard, agentSigningService, agentFactory, "default",
                    mock(AgentCascadeService.class));

            assertThrows(ForbiddenException.class, () -> guardedStore.updateAgent("agent-x", 1, referencing(PKG1_ID), false));

            verify(restWorkflowStore, never()).readWorkflow(anyString(), anyInt());
        }

        @Test
        @DisplayName("create names the missing workflow in a 400")
        void createRejectsMissingWorkflow() {
            when(restWorkflowStore.readWorkflow(PKG1_ID, 1)).thenThrow(new NotFoundException());

            BadRequestException e = assertThrows(BadRequestException.class, () -> restAgentStore.createAgent(referencing(PKG1_ID)));

            assertEquals(400, e.getResponse().getStatus());
            assertTrue(String.valueOf(e.getResponse().getEntity()).contains(PKG1_ID));
            verifyNoInteractions(capabilityRegistryService);
        }

        @Test
        @DisplayName("update names the missing workflow in a 400")
        void updateRejectsMissingWorkflow() {
            when(restWorkflowStore.readWorkflow(PKG2_ID, 1)).thenThrow(new NotFoundException());

            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> restAgentStore.updateAgent(AGENT_ID, 1, referencing(PKG2_ID), false));

            assertTrue(String.valueOf(e.getResponse().getEntity()).contains(PKG2_ID));
        }

        @Test
        @DisplayName("a store failure while resolving is not reported as a missing workflow")
        void storeFailureIsNotABadRequest() {
            when(restWorkflowStore.readWorkflow(PKG1_ID, 1)).thenThrow(new IllegalStateException("store down"));

            assertThrows(IllegalStateException.class, () -> restAgentStore.createAgent(referencing(PKG1_ID)));
        }
    }

    /**
     * The reverse lookup's answer, as the real store gives it: {@code total} counts
     * the Agent being deleted among a workflow's referrers, and that one disappears
     * once the Agent has been deleted — {@code AbstractResourceStore
     * .isStaleReference} drops a referrer that has no current row.
     *
     * <p>
     * Modelling that is what lets the post-delete re-check in {@code deleteAgent}
     * mean anything: against a stub frozen at its pre-delete answer, "only this
     * Agent used it" and "another Agent has picked it up since" look identical.
     * </p>
     */
    private List<DocumentDescriptor> referrers(int total) {
        int remaining = agentDeleted.get() ? total - 1 : total;
        List<DocumentDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < remaining; i++) {
            descriptors.add(dummyDescriptor());
        }
        return descriptors;
    }

    @Nested
    @DisplayName("deleteAgent")
    class DeleteAgentTests {

        @Test
        @DisplayName("should delete Agent without cascade when cascade=false")
        void deleteAgent_noCascade() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).delete(eq(AGENT_ID), eq(1));
        }

        @Test
        @DisplayName("should delete the Agent's deployment records even without cascade")
        void deleteAgent_deletesDeploymentRecords() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            // A surviving record makes the runtime retry a doomed redeploy
            // of a now-missing Agent on every startup.
            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }

        @Test
        @DisplayName("should keep deployment records when the Agent delete itself fails")
        void deleteAgent_keepsDeploymentRecordsWhenDeleteFails() throws Exception {
            // A stale/unknown version is rejected inside restVersionInfo.delete.
            // Clearing first would strip a still-live Agent of what it needs.
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(deploymentStore, never()).deleteDeploymentInfos(any());
        }

        @Test
        @DisplayName("should still delete the Agent when clearing its deployment records fails")
        void deleteAgent_deploymentCleanupFailureIsNotFatal() throws Exception {
            when(deploymentStore.deleteDeploymentInfos(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceStoreException("boom", new RuntimeException()));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(AgentStore).delete(eq(AGENT_ID), eq(1));
        }

        @Test
        @DisplayName("should cascade-delete packages when cascade=true and packages are not shared")
        void deleteAgent_cascade() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // Each package is only referenced by this one agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // permanent=false on the cascaded workflows even though the request said
            // permanent=true. These two assertions used to read `true` and so pinned the
            // defect: the reference guard above asks a VERSION-scoped question
            // ("who references W?version=2?") while a permanent delete is ID-scoped and
            // drops every version plus all history. The Agent itself is still erased
            // permanently — that is what the caller asked for and owns.
            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should delete deployment records on the cascade path too")
        void deleteAgent_cascade_alsoDeletesDeploymentRecords() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            verify(deploymentStore).deleteDeploymentInfos(AGENT_ID);
        }

        @Test
        @DisplayName("should skip cascade-delete of packages shared with other agents")
        void deleteAgent_cascade_skipsSharedWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // PKG1 is shared with 2 agents — should be SKIPPED
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(2));
            // PKG2 is only in this Agent — should be deleted
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, true);

            // Only PKG2 should be deleted — PKG1 is shared
            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should continue deleting Agent even when package cascade fails")
        void deleteAgent_cascade_partialFailure() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // both packages only referenced by this agent
            when(AgentStore.getAgentDescriptorsContainingWorkflow(anyString(), anyInt(), eq(true))).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(PKG1_ID, 2, false, true)).thenThrow(new RuntimeException("Workflow in use"));
            when(restWorkflowStore.deleteWorkflow(PKG2_ID, 1, false, true)).thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should still delete Agent when Agent config not found for cascade")
        void deleteAgent_cascade_agentNotFound() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        @Test
        @DisplayName("should handle empty packages list in cascade")
        void deleteAgent_cascade_emptyWorkflows() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }
    }

    @Nested
    @DisplayName("deleteAgent — cascade version guard")
    class CascadeVersionGuard {

        /**
         * The cascade used to run against whatever version the request named, because
         * {@code agentStore.read} falls back to history for a superseded version and
         * the only version check lived in {@code restVersionInfo.delete()} — at the
         * very end. A stale tab deleting v1 of an Agent that is at v2 therefore
         * destroyed v1's workflows and schedules and only then answered 409.
         */
        @Test
        @DisplayName("refuses a stale version with 409 before touching workflows or schedules")
        void staleVersionRefusedBeforeAnyDeletion() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 2));

            var thrown = assertThrows(WebApplicationException.class,
                    () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, true));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), thrown.getResponse().getStatus());
            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore, never()).delete(anyString(), anyInt());
            verify(AgentStore, never()).deleteAllPermanently(anyString());
        }

        /**
         * {@code ?version=0} is the documented "current version" shorthand. It used to
         * be resolved only inside {@code restVersionInfo.delete()}, so the cascade read
         * version 0, found nothing, and skipped itself with a WARN while the delete
         * went through — {@code cascade=true} silently did not cascade.
         */
        @Test
        @DisplayName("version=0 resolves to the current version and does cascade")
        void versionZeroCascades() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 0, false, true);

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * The ordinary "purge what I already soft-deleted" flow:
         * {@code ?permanent=true&cascade=true} against an Agent with no current row.
         * {@code getCurrentResourceId} throws for it, and the documented contract is to
         * skip the cascade and still purge the history — so the guard has to answer
         * false rather than propagate, and must not dereference the missing current id,
         * which on this path would NPE partway through a destructive operation.
         */
        @Test
        @DisplayName("an already soft-deleted Agent skips the cascade and still purges")
        void softDeletedAgentSkipsCascadeButStillPurges() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no current version"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, true));

            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * The capability index feeds {@code capabilityMatch} behaviour rules and A2A
         * discovery. Clearing it before the delete stripped a still-live Agent of what
         * it routes on when the delete then answered 409.
         */
        @Test
        @DisplayName("keeps the capability registration when the delete itself fails")
        void capabilityRegistrationSurvivesAFailedDelete() throws Exception {
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, false));

            verify(capabilityRegistryService, never()).unregister(anyString());
        }

        @Test
        @DisplayName("clears the capability registration once the delete succeeded")
        void capabilityRegistrationClearedAfterDelete() throws Exception {
            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(capabilityRegistryService).unregister(AGENT_ID);
        }

        /**
         * Nothing else in the codebase removes an agent's private key, and the vault
         * entry outlives the config that documented what it was for. This assertion
         * used to pass {@code permanent=false} — see
         * {@link #keepsSigningKeyPairOnASoftDelete()} for why that was wrong.
         */
        @Test
        @DisplayName("removes the signing key from the vault when the Agent is permanently deleted")
        void deletesSigningKeyPair() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(agentWithSigningIdentity());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            // An EMPTY version list, not null: this Agent carries the legacy
            // unversioned key and no rotated ones, and null is what asks for the
            // blind 1..100 sweep.
            verify(agentSigningService).deleteKeyPair("default", AGENT_ID, List.of());
        }

        /**
         * The two-step purge the API documents as ordinary: soft-delete an Agent, then
         * come back and {@code DELETE ?permanent=true}. The probe used to read through
         * {@code agentStore.read}, which throws {@code ResourceNotFoundException} for a
         * history row flagged deleted — i.e. for EVERY soft-deleted Agent — so it
         * answered "no key material" and the Ed25519 private key stayed in the vault
         * after the config and all of its history had been erased. That is the leak
         * this cleanup exists to close, surviving on the only recommended path to
         * closing it.
         */
        @Test
        @DisplayName("purging an already soft-deleted Agent still removes its vault keys")
        void purgingASoftDeletedAgentStillRemovesItsKeys() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("no current version"));
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("soft-deleted"));
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(agentWithRotatedKeys());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService).deleteKeyPair("default", AGENT_ID, List.of(1, 3));
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * A soft delete is the deliberately recoverable path, and the Ed25519 private
         * key is the one thing about an Agent that cannot be recreated:
         * {@code createSigningKey} makes a new key, never the old one, so an Agent
         * restored from a ZIP backup would come back with a public key whose private
         * half is gone and could not sign until an admin generated it a new key. The
         * vault cleanup was briefly unconditional, which made every recoverable delete
         * irreversible for key material.
         */
        @Test
        @DisplayName("keeps the signing key in the vault on a soft delete")
        void keepsSigningKeyPairOnASoftDelete() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(agentWithSigningIdentity());

            restAgentStore.deleteAgent(AGENT_ID, 1, false, false);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("does not probe the vault for an Agent with no key material")
        void skipsVaultForAgentWithoutIdentity() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(new AgentConfiguration());

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
        }

        /**
         * The key-cleanup probe is best-effort by design: an Agent that cannot be read
         * reports "no key material" rather than failing the delete. A leaked vault
         * entry is recoverable; a config that cannot be deleted is not — so the probe
         * must never decide whether the delete happens.
         */
        @Test
        @DisplayName("an unreadable Agent is still deleted, with the vault probe answering no")
        void unreadableAgentDoesNotBlockThePermanentDelete() throws Exception {
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            assertDoesNotThrow(() -> restAgentStore.deleteAgent(AGENT_ID, 1, true, false));

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        /**
         * The pre-cascade version check is a check-then-act, exactly as it was in
         * {@code RestWorkflowStore.deleteWorkflow}. A concurrent update committing
         * between it and {@code restVersionInfo.delete()} — a PUT, or the 10-second
         * deployment sweep touching this Agent — used to leave the schedules deleted
         * and every exclusively owned workflow (and, through deleteWorkflow's own
         * cascade, its extensions) torn down while the delete answered 409 "nothing was
         * deleted". The live Agent kept its config and lost everything it pointed at,
         * and the 409 contract {@code IRestAgentStore} documents was false on this
         * path. Deleting the Agent FIRST makes the store's own version check the gate.
         */
        @Test
        @DisplayName("an Agent that moved on between the guard and the delete loses neither workflows nor schedules")
        void concurrentUpdateBetweenGuardAndDeleteCascadesNothing() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            // The guard saw v1; by the time the delete runs the Agent is at v2.
            doThrow(new IResourceStore.ResourceModifiedException("not the latest version")).when(AgentStore).delete(AGENT_ID, 1);

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> restAgentStore.deleteAgent(AGENT_ID, 1, false, true));

            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(scheduleStore, never()).deleteSchedulesByAgentId(anyString());
        }

        /**
         * The other half of the reordering: DECIDING still happens before the delete,
         * so the reference guard counts this Agent among a workflow's referrers (hence
         * the {@code > 1} test). Were the reverse lookup moved after the delete, the
         * Agent would no longer count itself, a workflow shared with exactly one other
         * Agent would come back as size 1, and the cascade would delete a workflow that
         * is still in use.
         */
        @Test
        @DisplayName("a workflow shared with another Agent survives the cascade")
        void sharedWorkflowIsNotCascadeDeleted() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // This Agent plus one other — the guard must read that as "still referenced".
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true))
                    .thenAnswer(invocation -> referrers(2));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
        }

        /**
         * The window the ordering leaves open: the workflow was judged exclusive BEFORE
         * this Agent was deleted, and another Agent can be created — or re-pointed at
         * it — between that decision and the cascade delete. Nothing downstream
         * re-asks: {@code deleteWorkflow} only checks WORKFLOW referrers, not Agent
         * ones. So the newly shared workflow (and, through that delete's own cascade,
         * its rule sets, output sets and dictionaries) was torn down under a live
         * Agent. The re-check runs after this Agent is gone, so anything the reverse
         * lookup still returns is a referrer that must stop the delete.
         */
        @Test
        @DisplayName("a workflow another Agent picks up during the cascade is not deleted")
        void workflowThatBecomesSharedDuringTheCascadeIsSkipped() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            // One referrer at both moments, but not the SAME one: when the cascade is
            // planned it is this Agent (so the workflow is a candidate), and by the time
            // the cascade delete runs this Agent is gone and the one referrer is an
            // Agent that has just been pointed at the workflow.
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenReturn(List.of(dummyDescriptor()));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(AgentStore).delete(AGENT_ID, 1);
            verify(restWorkflowStore, never()).deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean());
        }

        /**
         * The counterpart to
         * {@link #workflowThatBecomesSharedDuringTheCascadeIsSkipped} — without it,
         * that test would pass equally against a cascade that had simply stopped
         * deleting anything.
         */
        @Test
        @DisplayName("a workflow nobody picks up during the cascade is still deleted")
        void workflowThatStaysExclusiveIsStillCascadeDeleted() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(
                    new ArrayList<>(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(restWorkflowStore).deleteWorkflow(PKG1_ID, 2, false, true);
        }

        /**
         * A reference to a workflow that has no live version left is nothing to cascade
         * at — there is no version to address the delete at, and guessing the pinned
         * one would resurrect the very stale-version delete the guard exists to refuse.
         * It is skipped, and the workflows after it in the list are still planned: one
         * dangling reference must not disarm the whole cascade.
         */
        @Test
        @DisplayName("a workflow with no live version left is skipped, and the rest of the cascade still runs")
        void workflowWithNoLiveVersionIsSkipped() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(restWorkflowStore.getCurrentResourceId(PKG1_ID))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("already soft-deleted"));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * FAIL CLOSED per workflow. A reference check that cannot answer is not a
         * licence to delete, and letting the throwable out of the planning step would
         * abort the request before the Agent itself had been deleted — leaving the
         * Agent undeletable for as long as the store misbehaves.
         */
        @Test
        @DisplayName("a reference check that blows up skips that workflow and still deletes the Agent")
        void referenceCheckFailureSkipsOnlyThatWorkflow() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + PKG2_ID + "?version=1"))));
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(config);
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG1_ID, 2, true))
                    .thenThrow(new IResourceStore.ResourceStoreException("reverse lookup broken"));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(PKG2_ID, 1, true)).thenAnswer(invocation -> referrers(1));

            restAgentStore.deleteAgent(AGENT_ID, 1, false, true);

            verify(restWorkflowStore, never()).deleteWorkflow(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restWorkflowStore).deleteWorkflow(PKG2_ID, 1, false, true);
            verify(AgentStore).delete(AGENT_ID, 1);
        }

        /**
         * An {@code identity} block is not by itself key material: an Agent can carry
         * one for its name or metadata with neither a legacy {@code publicKey} nor any
         * rotated {@code keys}. Destroying vault entries is irreversible — there is no
         * key-generation endpoint — so the cleanup must only run for an Agent that
         * actually declares a key.
         */
        @Test
        @DisplayName("an identity block with no key material does not trigger the vault cleanup")
        void identityWithoutKeysDoesNotTriggerVaultCleanup() throws Exception {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("   ");
            identity.setKeys(new ArrayList<>());
            config.setIdentity(identity);
            when(AgentStore.readIncludingDeleted(AGENT_ID, 1)).thenReturn(config);

            restAgentStore.deleteAgent(AGENT_ID, 1, true, false);

            verify(agentSigningService, never()).deleteKeyPair(anyString(), anyString(), any());
            verify(AgentStore).deleteAllPermanently(AGENT_ID);
        }

        private AgentConfiguration agentWithSigningIdentity() {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("MCowBQYDK2VwAyEA-not-a-real-key");
            config.setIdentity(identity);
            return config;
        }

        /**
         * A rotated identity whose declared versions skip a number, which nothing
         * forbids.
         */
        private AgentConfiguration agentWithRotatedKeys() {
            var config = new AgentConfiguration();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, "k1"), AgentPublicKey.createCurrent(3, "k3"))));
            config.setIdentity(identity);
            return config;
        }
    }

    // --- Security flag validation: signing is accepted only when the agent can
    // sign

    /**
     * Run against a REAL {@link AgentSigningService} over an in-memory vault: the
     * check under test is whether the vault key signs as the declared public key,
     * which a stubbed service would only restate.
     */
    @Nested
    @DisplayName("validateSecurityFlags")
    class SecurityFlagValidationTests {

        private ISecretProvider vault;
        private AgentSigningService signing;
        private RestAgentStore store;

        @BeforeEach
        void setUpRealSigning() throws Exception {
            vault = SigningTestSupport.inMemoryVault();
            signing = SigningTestSupport.signingService(vault, new SimpleMeterRegistry());
            store = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                    capabilityRegistryService, deploymentStore, mock(ResourceAccessGuard.class), signing, agentFactory, "default",
                    mock(AgentCascadeService.class));
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenReturn(2);
        }

        /**
         * An agent saved with signing on, whose STORED version declares
         * {@code identity}: the identity is engine-owned, so the one the save checks is
         * the stored one, not the body's.
         */
        private AgentConfiguration signingAgent(AgentConfiguration.AgentIdentity identity) throws Exception {
            var stored = new AgentConfiguration();
            stored.setWorkflows(new ArrayList<>());
            stored.setIdentity(identity);
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(stored);

            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);
            config.setIdentity(identity);
            config.setWorkflows(new ArrayList<>());
            return config;
        }

        private AgentConfiguration.AgentIdentity identityWith(AgentPublicKey... keys) {
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(keys)));
            return identity;
        }

        private void assertRefused(AgentConfiguration config, String expectedMessagePart) {
            var e = assertThrows(BadRequestException.class, () -> store.updateAgent(AGENT_ID, 1, config, false));
            assertTrue(e.getMessage().contains(expectedMessagePart), e.getMessage());
            assertTrue(e.getMessage().contains("/agentstore/agents/{id}/signing-keys"), "names the way out: " + e.getMessage());
        }

        @Test
        @DisplayName("updateAgent accepts signing with a generated key the vault holds")
        void updateAgent_acceptsAUsableKey() throws Exception {
            var generated = signing.generateKey("default", AGENT_ID, 1);

            Response response = store.updateAgent(AGENT_ID, 1,
                    signingAgent(identityWith(AgentPublicKey.createCurrent(1, generated.publicKeyB64()))), false);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("updateAgent checks the NEWEST key, the one that signs")
        void updateAgent_checksTheKeyThatWouldSign() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);
            // v2 is declared, but its private key never reached the vault.
            String v2PublicKey = signing.generateKey("default", "someone-else", 1).publicKeyB64();

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, v1.publicKeyB64()),
                    AgentPublicKey.createCurrent(2, v2PublicKey))), "Signing key v2 has no private key in the secrets vault");
        }

        @Test
        @DisplayName("updateAgent refuses signing with no key declared")
        void updateAgent_rejectsSigningWithoutAKey() throws Exception {
            assertRefused(signingAgent(null), "declares none");
        }

        @Test
        @DisplayName("updateAgent refuses a public key that is not Ed25519")
        void updateAgent_rejectsAGarbagePublicKey() throws Exception {
            signing.generateKey("default", AGENT_ID, 1);

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, "base64key=="))), "is not a Base64 X.509 Ed25519 public key");
        }

        @Test
        @DisplayName("updateAgent refuses a key whose private half is not in the vault")
        void updateAgent_rejectsAKeyMissingFromTheVault() throws Exception {
            String publicKey = signing.generateKey("default", "someone-else", 1).publicKeyB64();

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, publicKey))), "has no private key in the secrets vault");
        }

        @Test
        @DisplayName("updateAgent refuses a public key the vault key does not sign as")
        void updateAgent_rejectsAMismatchedKey() throws Exception {
            signing.generateKey("default", AGENT_ID, 1);
            String publicKeyOfAnotherAgent = signing.generateKey("default", "someone-else", 1).publicKeyB64();

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, publicKeyOfAnotherAgent))),
                    "does not match the private key in the secrets vault");
        }

        @Test
        @DisplayName("updateAgent checks the legacy unversioned key when no versioned key exists")
        void updateAgent_checksTheLegacyKey() throws Exception {
            String publicKey = signing.generateKey("default", "someone-else", 1).publicKeyB64();
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey(publicKey);

            var e = assertThrows(BadRequestException.class, () -> store.updateAgent(AGENT_ID, 1, signingAgent(identity), false));
            assertTrue(e.getMessage().contains("The legacy signing key (identity.publicKey) has no private key"), e.getMessage());
        }

        /**
         * The signer does not sign with a revoked newest key and does not fall back to
         * an older one, so "signing on" with it would be the state this validator
         * exists to refuse.
         */
        @Test
        @DisplayName("updateAgent refuses signing when the newest key is revoked")
        void updateAgent_rejectsARevokedNewestKey() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);
            var v2 = signing.generateKey("default", AGENT_ID, 2);

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, v1.publicKeyB64()),
                    AgentPublicKey.createCurrent(2, v2.publicKeyB64()).revoke())), "Signing key v2 is revoked");
        }

        @Test
        @DisplayName("updateAgent refuses signing when the newest key has expired")
        void updateAgent_rejectsAnExpiredNewestKey() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);

            assertRefused(signingAgent(identityWith(new AgentPublicKey(1, v1.publicKeyB64(), 0, System.currentTimeMillis() - 60_000))),
                    "Signing key v1 is outside its validity window");
        }

        @Test
        @DisplayName("updateAgent refuses signing when the newest key is not valid yet")
        void updateAgent_rejectsANewestKeyNotValidYet() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);

            assertRefused(signingAgent(identityWith(new AgentPublicKey(1, v1.publicKeyB64(), System.currentTimeMillis() + 3_600_000, 0))),
                    "Signing key v1 is outside its validity window");
        }

        /**
         * A peer trusts every key the agent declares, not only the newest: a second v1
         * placed first wins the version lookup, so checking the newest key alone let it
         * through.
         */
        @Test
        @DisplayName("updateAgent refuses an identity that declares a version twice")
        void updateAgent_rejectsADuplicateVersion() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);
            var v2 = signing.generateKey("default", AGENT_ID, 2);
            String planted = signing.generateKey("default", "someone-else", 1).publicKeyB64();

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, planted), AgentPublicKey.createCurrent(1, v1.publicKeyB64()),
                    AgentPublicKey.createCurrent(2, v2.publicKeyB64()))), "Signing key v1 is declared more than once");
        }

        @Test
        @DisplayName("updateAgent refuses an older key that is not an Ed25519 public key")
        void updateAgent_rejectsAMalformedOlderKey() throws Exception {
            var v2 = signing.generateKey("default", AGENT_ID, 2);

            assertRefused(
                    signingAgent(identityWith(AgentPublicKey.createCurrent(1, "not-a-key"), AgentPublicKey.createCurrent(2, v2.publicKeyB64()))),
                    "Signing key v1 is not a Base64 X.509 Ed25519 public key");
        }

        @Test
        @DisplayName("updateAgent refuses a key version outside 1 to MAX_VERSION")
        void updateAgent_rejectsAnOutOfRangeVersion() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);

            assertRefused(signingAgent(identityWith(AgentPublicKey.createCurrent(1, v1.publicKeyB64()),
                    AgentPublicKey.createCurrent(Integer.MAX_VALUE, v1.publicKeyB64()))), "has a version outside 1 to 1000000");
        }

        @Test
        @DisplayName("updateAgent ignores a malformed key once it is revoked")
        void updateAgent_ignoresARevokedMalformedKey() throws Exception {
            var v2 = signing.generateKey("default", AGENT_ID, 2);

            Response response = store.updateAgent(AGENT_ID, 1, signingAgent(identityWith(AgentPublicKey.createCurrent(1, "not-a-key").revoke(),
                    AgentPublicKey.createCurrent(2, v2.publicKeyB64()))), false);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("signingKeyProblem reads the stored identity: null when it can sign, the reason when it cannot")
        void signingKeyProblem_readsTheStoredIdentity() throws Exception {
            var v1 = signing.generateKey("default", AGENT_ID, 1);
            signingAgent(identityWith(AgentPublicKey.createCurrent(1, v1.publicKeyB64())));
            assertNull(store.signingKeyProblem(AGENT_ID));

            signingAgent(identityWith(AgentPublicKey.createCurrent(1, v1.publicKeyB64()).revoke()));
            assertTrue(store.signingKeyProblem(AGENT_ID).contains("Signing key v1 is revoked"));
        }

        @Test
        @DisplayName("updateAgent answers 503 when the vault cannot be asked")
        void updateAgent_vaultFailureIs503() throws Exception {
            String publicKey = signing.generateKey("default", AGENT_ID, 1).publicKeyB64();
            doThrow(new ISecretProvider.SecretProviderException("Persistence failure")).when(vault).resolve(any());

            var e = assertThrows(WebApplicationException.class,
                    () -> store.updateAgent(AGENT_ID, 1, signingAgent(identityWith(AgentPublicKey.createCurrent(1, publicKey))), false));
            assertEquals(503, e.getResponse().getStatus());
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("createAgent refuses signing: a new agent has no key yet")
        void createAgent_rejectsSignInterAgentMessages() throws Exception {
            String publicKey = signing.generateKey("default", "someone-else", 1).publicKeyB64();

            var e = assertThrows(BadRequestException.class,
                    () -> store.createAgent(signingAgent(identityWith(AgentPublicKey.createCurrent(1, publicKey)))));
            assertTrue(e.getMessage().contains("A new agent has no signing key yet"), e.getMessage());
            verify(AgentStore, never()).create(any());
        }

        @Test
        @DisplayName("createAgent accepts requirePeerVerification alone: verifying needs no key of the agent's own")
        void createAgent_acceptsRequirePeerVerification() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setRequirePeerVerification(true);
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());
            when(AgentStore.create(any())).thenReturn(resourceId(AGENT_ID, 1));

            assertEquals(201, store.createAgent(config).getStatus());
        }

        @Test
        @DisplayName("createAgent should allow null security block")
        void createAgent_allowsNullSecurity() throws Exception {
            var config = new AgentConfiguration();
            config.setSecurity(null);
            config.setWorkflows(new ArrayList<>());

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            // Validation passes — no BadRequestException thrown
            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        @Test
        @DisplayName("createAgent should allow security block with all flags false")
        void createAgent_allowsAllFlagsFalse() throws Exception {
            var config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            // all default to false
            config.setSecurity(security);
            config.setWorkflows(new ArrayList<>());

            when(AgentStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return AGENT_ID;
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            });

            // Validation passes — no BadRequestException thrown
            assertDoesNotThrow(() -> restAgentStore.createAgent(config));
        }

        /**
         * The original's private keys are stored under the original's id, so a copy
         * that kept its identity and signing flag could never sign — and would publish
         * the original's public keys as its own.
         */
        @Test
        @DisplayName("duplicateAgent gives the copy no identity and signing off")
        void duplicateAgent_dropsTheIdentityAndSigning() throws Exception {
            var generated = signing.generateKey("default", AGENT_ID, 1);
            var source = signingAgent(identityWith(AgentPublicKey.createCurrent(1, generated.publicKeyB64())));
            source.getIdentity().setAgentDid("did:eddi:agent:original");
            source.getSecurity().setRequirePeerVerification(true);
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(source);
            when(AgentStore.create(any())).thenReturn(resourceId("ccddeeff0011223344aa", 1));
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(dummyDescriptor());

            store.duplicateAgent(AGENT_ID, 1, false);

            var copy = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).create(copy.capture());
            assertNull(copy.getValue().getIdentity());
            assertFalse(copy.getValue().getSecurity().isSignInterAgentMessages());
            assertTrue(copy.getValue().getSecurity().isRequirePeerVerification(), "verifying peers needs no key of the copy's own");
        }
    }

    /**
     * {@code POST /agentstore/agents/{id}/signing-keys}, against a REAL
     * {@link AgentSigningService} and an in-memory vault.
     */
    @Nested
    @DisplayName("createSigningKey")
    class SigningKeyProvisioning {

        private ISecretProvider vault;
        private AgentSigningService signing;
        private RestAgentStore store;
        private AgentConfiguration stored;

        @BeforeEach
        void setUpRealSigning() throws Exception {
            vault = SigningTestSupport.inMemoryVault();
            signing = SigningTestSupport.signingService(vault, new SimpleMeterRegistry());
            store = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                    capabilityRegistryService, deploymentStore, mock(ResourceAccessGuard.class), signing, agentFactory, "default",
                    mock(AgentCascadeService.class));
            stored = new AgentConfiguration();
            stored.setWorkflows(new ArrayList<>());
            // A fresh copy per read, as the real store deserializes one: the endpoint
            // edits what it reads, and that must not change what the store holds.
            when(AgentStore.read(AGENT_ID, 1)).thenAnswer(invocation -> storedCopy());
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenReturn(2);
            descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(IRestAgentStore.resourceURI + AGENT_ID + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(descriptor);
        }

        private DocumentDescriptor descriptor;

        private AgentConfiguration storedCopy() {
            var copy = new AgentConfiguration();
            copy.setWorkflows(new ArrayList<>(stored.getWorkflows()));
            copy.setDescription(stored.getDescription());
            copy.setSecurity(stored.getSecurity());
            if (stored.getIdentity() != null) {
                var identity = new AgentConfiguration.AgentIdentity(stored.getIdentity().getAgentDid(), stored.getIdentity().getPublicKey());
                identity.setKeys(new ArrayList<>(stored.getIdentity().getKeys()));
                copy.setIdentity(identity);
            }
            return copy;
        }

        private String vaultKey(int version) throws Exception {
            return vault.resolve(new SecretReference("default", "agent-signing-key:" + AGENT_ID + ":v" + version));
        }

        private AgentConfiguration written() throws Exception {
            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).update(eq(AGENT_ID), eq(1), captor.capture(), eq(true));
            return captor.getValue();
        }

        /**
         * The key is written in process, so the descriptor filter never moves the
         * descriptor; and a POST answered 201 is what that filter takes for a creation:
         * it tried to create a second descriptor for the agent, failed on the duplicate
         * key, and answered 500 — with the agent already at the new version and its
         * descriptor left behind, so every later save of it answered 404.
         */
        @Test
        @DisplayName("moves the agent's descriptor to the new version and answers 200, not 201")
        void advancesTheDescriptorAndAnswers200() throws Exception {
            Response response = store.createSigningKey(AGENT_ID, 1);

            assertEquals(200, response.getStatus(), "201 makes DocumentDescriptorFilter create a second descriptor");
            verify(documentDescriptorStore).updateDescriptor(eq(AGENT_ID), eq(1), same(descriptor));
            assertEquals(URI.create(IRestAgentStore.resourceURI + AGENT_ID + "?version=2"), descriptor.getResource());
            assertNotNull(descriptor.getLastModifiedOn());
            verify(documentDescriptorStore, never()).createDescriptor(anyString(), anyInt(), any());
        }

        /**
         * A socket error or a lost commit acknowledgement can arrive after the write
         * was applied. Discarding the key then left an agent declaring a key its vault
         * no longer held: it could not sign, and every later save was refused.
         */
        @Test
        @DisplayName("keeps the key, and carries on, when a write that reported a failure landed after all")
        void keepsTheKeyWhenAnAmbiguousWriteLanded() throws Exception {
            var landed = new AtomicReference<AgentConfiguration>();
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenAnswer(invocation -> {
                landed.set(invocation.getArgument(2));
                throw new IllegalStateException("socket closed after the write");
            });
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenAnswer(invocation -> resourceId(AGENT_ID, landed.get() == null ? 1 : 2));
            when(AgentStore.read(AGENT_ID, 2)).thenAnswer(invocation -> landed.get());

            Response response = store.createSigningKey(AGENT_ID, 1);

            assertEquals(200, response.getStatus());
            assertTrue(response.getLocation().toString().endsWith(AGENT_ID + "?version=2"), response.getLocation().toString());
            assertNotNull(vaultKey(1), "the declared key's private half is kept");
            verify(documentDescriptorStore).updateDescriptor(eq(AGENT_ID), eq(1), same(descriptor));
        }

        @Test
        @DisplayName("discards the key when a write that reported a failure did not land")
        void discardsTheKeyWhenAnAmbiguousWriteDidNotLand() throws Exception {
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenThrow(new IllegalStateException("socket closed"));

            var e = assertThrows(IllegalStateException.class, () -> store.createSigningKey(AGENT_ID, 1));

            assertEquals("socket closed", e.getMessage());
            assertThrows(ISecretProvider.SecretNotFoundException.class, () -> vaultKey(1), "no undeclared private key is left behind");
        }

        @Test
        @DisplayName("keeps the key when it cannot tell whether a failed write landed")
        void keepsTheKeyWhenTheOutcomeCannotBeRead() throws Exception {
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenThrow(new IllegalStateException("socket closed"));
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 1))
                    .thenThrow(new IllegalStateException("store down"));

            assertThrows(IllegalStateException.class, () -> store.createSigningKey(AGENT_ID, 1));

            assertNotNull(vaultKey(1), "a key left in the vault is swept later; a declared key that is gone breaks signing");
        }

        /**
         * A version near Integer.MAX_VALUE made "highest + 1" wrap: the new key came
         * out as v1 while the bogus entry stayed the one that signs. Versions outside 1
         * to MAX_VERSION are not keys the engine signs with, so numbering ignores them.
         */
        @Test
        @DisplayName("numbers the new key past the highest well-formed version, ignoring one out of range")
        void ignoresAnOutOfRangeVersionWhenNumbering() throws Exception {
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, "k1"), AgentPublicKey.createCurrent(Integer.MAX_VALUE, "kx"))));
            stored.setIdentity(identity);

            var key = (AgentPublicKey) store.createSigningKey(AGENT_ID, 1).getEntity();

            assertEquals(2, key.version());
            assertEquals(2, written().getIdentity().signingKeyVersion(), "the new key is the one that signs");
        }

        @Test
        @DisplayName("refuses with 409, before touching the vault, once every key version is used")
        void refusesWhenEveryVersionIsUsed() throws Exception {
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(AgentPublicKey.MAX_VERSION, "k"))));
            stored.setIdentity(identity);

            var e = assertThrows(WebApplicationException.class, () -> store.createSigningKey(AGENT_ID, 1));

            assertEquals(409, e.getResponse().getStatus());
            verify(vault, never()).storeIfAbsent(any(), anyString(), any(), any());
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("stores the private key in the vault and appends the public key as a new, compatible version")
        void storesTheVaultEntryAndAppendsThePublicKey() throws Exception {
            Response response = store.createSigningKey(AGENT_ID, 1);

            assertEquals(200, response.getStatus());
            assertTrue(response.getLocation().toString().endsWith(AGENT_ID + "?version=2"), response.getLocation().toString());
            var key = (AgentPublicKey) response.getEntity();
            assertEquals(1, key.version());
            assertEquals(0, key.validUntilMs(), "no expiry");
            assertNotNull(vaultKey(1), "the private key is in the vault under v1");
            assertEquals(List.of(key), written().getIdentity().getKeys());
            assertEquals(AgentSigningService.SigningKeyStatus.USABLE, signing.checkSigningKey("default", AGENT_ID, 1, key.publicKeyB64()),
                    "the vault key signs as the public key the agent now declares");
        }

        @Test
        @DisplayName("numbers the new key one above the highest declared, keeping the older keys and the legacy key")
        void rotatesPastTheHighestDeclaredVersion() throws Exception {
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setPublicKey("legacy-public-key");
            identity.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, "k1"), AgentPublicKey.createCurrent(3, "k3"))));
            stored.setIdentity(identity);

            var key = (AgentPublicKey) store.createSigningKey(AGENT_ID, 1).getEntity();

            assertEquals(4, key.version());
            var writtenIdentity = written().getIdentity();
            assertEquals(List.of(1, 3, 4), writtenIdentity.getKeys().stream().map(AgentPublicKey::version).toList());
            assertEquals("legacy-public-key", writtenIdentity.getPublicKey(), "entries signed with the legacy key still verify");
            assertEquals(4, writtenIdentity.signingKeyVersion(), "the new key is the one that signs");
        }

        @Test
        @DisplayName("keeps what else the agent holds, signing flag included")
        void keepsTheRestOfTheAgent() throws Exception {
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            stored.setSecurity(security);
            stored.setDescription("kept");

            store.createSigningKey(AGENT_ID, 1);

            assertTrue(written().getSecurity().isSignInterAgentMessages());
            assertEquals("kept", written().getDescription());
        }

        /**
         * The next version is computed from the configuration read here: from a stale
         * one it could name a version the current configuration already declares.
         */
        @Test
        @DisplayName("refuses a stale version with 409 before the vault is touched")
        void refusesAStaleVersionBeforeTouchingTheVault() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 3));

            var e = assertThrows(WebApplicationException.class, () -> store.createSigningKey(AGENT_ID, 1));

            assertEquals(409, e.getResponse().getStatus());
            verify(vault, never()).storeIfAbsent(any(), anyString(), any(), any());
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("takes the key back out of the vault when the agent cannot be written")
        void discardsTheKeyWhenTheConfigWriteFails() throws Exception {
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean()))
                    .thenThrow(new IResourceStore.ResourceModifiedException("changed meanwhile"));

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> store.createSigningKey(AGENT_ID, 1));

            assertThrows(ISecretProvider.SecretNotFoundException.class, () -> vaultKey(1), "no undeclared private key is left behind");
        }

        @Test
        @DisplayName("answers 503 and writes nothing when the vault cannot store the key")
        void vaultFailureIs503() throws Exception {
            doThrow(new ISecretProvider.SecretProviderException("Secrets Vault is not available")).when(vault).storeIfAbsent(any(), anyString(),
                    any(), any());

            var e = assertThrows(WebApplicationException.class, () -> store.createSigningKey(AGENT_ID, 1));

            assertEquals(503, e.getResponse().getStatus());
            assertTrue(e.getMessage().contains("Secrets Vault is not available"), e.getMessage());
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
        }

        /**
         * Whoever can mint a key can sign as the agent, so the gate is narrower than
         * the class-level eddi-admin/eddi-editor one every other agent write has.
         */
        @Test
        @DisplayName("is admin only")
        void isAdminOnly() throws Exception {
            var method = IRestAgentStore.class.getMethod("createSigningKey", String.class, Integer.class);

            assertEquals("/{id}/signing-keys", method.getAnnotation(Path.class).value());
            assertEquals(List.of("eddi-admin"), List.of(method.getAnnotation(RolesAllowed.class).value()));
        }
    }

    /**
     * {@code POST /agentstore/agents/{id}/signing-keys/{keyVersion}/revoke},
     * against a REAL {@link AgentSigningService} and an in-memory vault. Revoking
     * used to mean editing {@code validUntilMs} through {@code PUT}, which is now
     * impossible — and which any editor could undo.
     */
    @Nested
    @DisplayName("revokeSigningKey")
    class SigningKeyRevocation {

        private ISecretProvider vault;
        private AgentSigningService signing;
        private RestAgentStore store;
        private AgentConfiguration stored;
        private DocumentDescriptor descriptor;
        private AgentSigningService.GeneratedKey v1;
        private AgentSigningService.GeneratedKey v2;

        @BeforeEach
        void setUpAnAgentWithTwoKeys() throws Exception {
            vault = SigningTestSupport.inMemoryVault();
            signing = SigningTestSupport.signingService(vault, new SimpleMeterRegistry());
            store = new RestAgentStore(AgentStore, restWorkflowStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore,
                    capabilityRegistryService, deploymentStore, mock(ResourceAccessGuard.class), signing, agentFactory, "default",
                    mock(AgentCascadeService.class));
            v1 = signing.generateKey("default", AGENT_ID, 1);
            v2 = signing.generateKey("default", AGENT_ID, 2);
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, v1.publicKeyB64()), AgentPublicKey.createCurrent(2,
                    v2.publicKeyB64()))));
            stored = new AgentConfiguration();
            stored.setWorkflows(new ArrayList<>());
            stored.setIdentity(identity);
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(stored);
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenReturn(2);
            descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(IRestAgentStore.resourceURI + AGENT_ID + "?version=1"));
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(descriptor);
        }

        private String vaultKey(String name) throws Exception {
            return vault.resolve(new SecretReference("default", name));
        }

        private AgentConfiguration written() throws Exception {
            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).update(eq(AGENT_ID), eq(1), captor.capture(), eq(true));
            return captor.getValue();
        }

        @Test
        @DisplayName("marks the key revoked in a new compatible version, moves the descriptor, and deletes the private key")
        void revokesTheKey() throws Exception {
            Response response = store.revokeSigningKey(AGENT_ID, 1, 1);

            assertEquals(200, response.getStatus());
            assertTrue(response.getLocation().toString().endsWith(AGENT_ID + "?version=2"), response.getLocation().toString());
            assertTrue(((AgentPublicKey) response.getEntity()).revoked());
            var keys = written().getIdentity().getKeys();
            assertTrue(keys.get(0).revoked(), "v1 is revoked");
            assertFalse(keys.get(1).revoked(), "v2 is untouched");
            assertEquals(v1.publicKeyB64(), keys.get(0).publicKeyB64(), "the entry stays, so entries it signed are recognised and refused");
            assertThrows(ISecretProvider.SecretNotFoundException.class, () -> vaultKey("agent-signing-key:" + AGENT_ID + ":v1"));
            assertNotNull(vaultKey("agent-signing-key:" + AGENT_ID + ":v2"));
            verify(documentDescriptorStore).updateDescriptor(eq(AGENT_ID), eq(1), same(descriptor));
            assertEquals(URI.create(IRestAgentStore.resourceURI + AGENT_ID + "?version=2"), descriptor.getResource());
        }

        @Test
        @DisplayName("revokes every entry of a version that is declared twice")
        void revokesEveryEntryOfAVersion() throws Exception {
            String planted = signing.generateKey("default", "someone-else", 1).publicKeyB64();
            var keys = new ArrayList<>(stored.getIdentity().getKeys());
            keys.add(0, AgentPublicKey.createCurrent(1, planted));
            stored.getIdentity().setKeys(keys);

            store.revokeSigningKey(AGENT_ID, 1, 1);

            assertEquals(List.of(true, true, false), written().getIdentity().getKeys().stream().map(AgentPublicKey::revoked).toList());
        }

        @Test
        @DisplayName("revoking the legacy key (v0) removes it, with its vault entry")
        void revokesTheLegacyKey() throws Exception {
            stored.getIdentity().setPublicKey(v1.publicKeyB64());
            vault.storeIfAbsent(new SecretReference("default", "agent-signing-key:" + AGENT_ID), "legacy-private-key", null, null);

            Response response = store.revokeSigningKey(AGENT_ID, 0, 1);

            assertEquals(200, response.getStatus());
            assertNull(written().getIdentity().getPublicKey());
            assertEquals(2, written().getIdentity().getKeys().size(), "the versioned keys are untouched");
            assertThrows(ISecretProvider.SecretNotFoundException.class, () -> vaultKey("agent-signing-key:" + AGENT_ID));
        }

        /**
         * The save-time check names a key with a version below 1 and says to revoke it.
         * No vault entry can exist for such a version, and discarding "version
         * {@code <= 0}" meant the legacy key: revoking the malformed entry deleted the
         * private half of the legacy key the agent still declares.
         */
        @Test
        @DisplayName("revoking a key with a negative version leaves the legacy key's vault entry alone")
        void revokingANegativeVersionKeepsTheLegacyVaultKey() throws Exception {
            stored.getIdentity().setPublicKey(v1.publicKeyB64());
            vault.storeIfAbsent(new SecretReference("default", "agent-signing-key:" + AGENT_ID), "legacy-private-key", null, null);
            var keys = new ArrayList<>(stored.getIdentity().getKeys());
            keys.add(AgentPublicKey.createCurrent(-5, "planted"));
            stored.getIdentity().setKeys(keys);

            Response response = store.revokeSigningKey(AGENT_ID, -5, 1);

            assertEquals(200, response.getStatus());
            assertTrue(written().getIdentity().getKeys().get(2).revoked(), "the malformed entry is revoked");
            assertEquals(v1.publicKeyB64(), written().getIdentity().getPublicKey(), "the legacy key stays declared");
            assertEquals("legacy-private-key", vaultKey("agent-signing-key:" + AGENT_ID), "and keeps its private half");
        }

        /**
         * An identity.keys entry declared as version 0 is what peers check a v0
         * signature against (it wins over the legacy publicKey), and the save-time
         * check refuses it as malformed and says to revoke it. Revoking v0 only looked
         * at the legacy publicKey, so with none declared the entry could never be
         * retired and signing could never be turned back on.
         */
        @Test
        @DisplayName("revoking v0 also revokes an identity.keys entry declared as version 0")
        void revokesAVersionZeroEntry() throws Exception {
            var keys = new ArrayList<>(stored.getIdentity().getKeys());
            keys.add(0, AgentPublicKey.createCurrent(0, "planted"));
            stored.getIdentity().setKeys(keys);

            Response response = store.revokeSigningKey(AGENT_ID, 0, 1);

            assertEquals(200, response.getStatus());
            assertEquals(List.of(true, false, false), written().getIdentity().getKeys().stream().map(AgentPublicKey::revoked).toList());
            assertNotNull(vaultKey("agent-signing-key:" + AGENT_ID + ":v1"), "the other keys are untouched");
        }

        @Test
        @DisplayName("a key already revoked writes nothing")
        void alreadyRevokedWritesNothing() throws Exception {
            stored.getIdentity().setKeys(List.of(AgentPublicKey.createCurrent(1, v1.publicKeyB64()).revoke()));

            Response response = store.revokeSigningKey(AGENT_ID, 1, 1);

            assertEquals(200, response.getStatus());
            assertTrue(response.getLocation().toString().endsWith(AGENT_ID + "?version=1"), response.getLocation().toString());
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a version the agent does not declare is a 404, and nothing is written")
        void unknownVersionIs404() throws Exception {
            assertThrows(NotFoundException.class, () -> store.revokeSigningKey(AGENT_ID, 7, 1));
            assertThrows(NotFoundException.class, () -> store.revokeSigningKey(AGENT_ID, 0, 1), "no legacy key declared");

            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
            assertNotNull(vaultKey("agent-signing-key:" + AGENT_ID + ":v1"));
        }

        @Test
        @DisplayName("refuses a stale version with 409, and keeps the key")
        void refusesAStaleVersion() throws Exception {
            when(AgentStore.getCurrentResourceId(AGENT_ID)).thenReturn(resourceId(AGENT_ID, 3));

            var e = assertThrows(WebApplicationException.class, () -> store.revokeSigningKey(AGENT_ID, 1, 1));

            assertEquals(409, e.getResponse().getStatus());
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
            assertNotNull(vaultKey("agent-signing-key:" + AGENT_ID + ":v1"));
        }

        @Test
        @DisplayName("keeps the private key when the configuration cannot be written")
        void keepsTheKeyWhenTheWriteFails() throws Exception {
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenThrow(new IResourceStore.ResourceModifiedException("changed"));

            assertThrows(IResourceStore.ResourceModifiedException.class, () -> store.revokeSigningKey(AGENT_ID, 1, 1));

            assertNotNull(vaultKey("agent-signing-key:" + AGENT_ID + ":v1"), "still declared unrevoked, so still needed");
        }

        @Test
        @DisplayName("is admin only")
        void isAdminOnly() throws Exception {
            var method = IRestAgentStore.class.getMethod("revokeSigningKey", String.class, Integer.class, Integer.class);

            assertEquals("/{id}/signing-keys/{keyVersion}/revoke", method.getAnnotation(Path.class).value());
            assertEquals(List.of("eddi-admin"), List.of(method.getAnnotation(RolesAllowed.class).value()));
        }
    }
    /**
     * CWE-117 (log injection). Every identifier below reaches a {@code log.warnf}
     * or {@code log.infof} from outside the process — {@code id} is the DELETE path
     * parameter, the workflow id and the exception messages come back out of the
     * store — and a CR/LF in any of them closes the real record and lets the
     * remainder read as a second line the server wrote itself.
     *
     * <p>
     * These assert the CONTRACT, not the call: drive a forged record through the
     * real path and require that nothing carrying a record boundary reached the
     * log. Removing any {@code sanitize(...)} on the pinned line fails them.
     * </p>
     */
    @Nested
    @DisplayName("deleteAgent — log injection (CWE-117)")
    class LogInjection {

        /** The Agent id as an attacker supplies it on the DELETE path. */
        private static final String POISONED_AGENT_ID = AGENT_ID + FORGED_RECORD;

        private static final String PKG1_URI = "eddi://ai.labs.workflow/workflowstore/workflows/" + PKG1_ID + "?version=2";

        /**
         * The Agent is live at v1 under its poisoned id, so the cascade is allowed to
         * run and reach the lines under test.
         */
        @BeforeEach
        void agentIsLiveUnderThePoisonedId() throws Exception {
            when(AgentStore.getCurrentResourceId(POISONED_AGENT_ID)).thenReturn(resourceId(POISONED_AGENT_ID, 1));
        }

        private AgentConfiguration configReferencing(String workflowUri) {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>(List.of(URI.create(workflowUri))));
            return config;
        }

        @Test
        @DisplayName("a CR/LF Agent id cannot forge a record through the schedule-cascade INFO")
        void schedulesCascadeDeleted() throws Exception {
            when(AgentStore.read(POISONED_AGENT_ID, 1)).thenReturn(configReferencing(PKG1_URI));
            when(scheduleStore.deleteSchedulesByAgentId(POISONED_AGENT_ID)).thenReturn(3);

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Cascade-deleted %d schedule(s)")
                    || value.contains("schedule(s) for Agent")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.deleteAgent's schedule-cascade INFO");
        }

        @Test
        @DisplayName("a CR/LF Agent id and store message cannot forge a record through the schedule-cascade WARN")
        void schedulesCascadeFails() throws Exception {
            when(AgentStore.read(POISONED_AGENT_ID, 1)).thenReturn(configReferencing(PKG1_URI));
            when(scheduleStore.deleteSchedulesByAgentId(POISONED_AGENT_ID))
                    .thenThrow(new IllegalStateException("scheduler unreachable" + FORGED_RECORD));

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Failed to cascade-delete schedules")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.deleteAgent's schedule-cascade WARN");
        }

        @Test
        @DisplayName("a CR/LF Agent id cannot forge a record through the not-found-for-cascade WARN")
        void agentNotFoundForCascade() throws Exception {
            when(AgentStore.read(POISONED_AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("not found for cascade")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.planCascade's Agent-not-found WARN");
        }

        @Test
        @DisplayName("a CR/LF store error message cannot forge a record through the read-failed WARN")
        void agentReadFailsForCascade() throws Exception {
            when(AgentStore.read(POISONED_AGENT_ID, 1))
                    .thenThrow(new IResourceStore.ResourceStoreException("index unavailable" + FORGED_RECORD));

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Error reading Agent")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.planCascade's read-failed WARN");
        }

        @Test
        @DisplayName("a CR/LF store error message cannot forge a record through the plan-failed WARN")
        void planningOneWorkflowFails() throws Exception {
            when(AgentStore.read(POISONED_AGENT_ID, 1)).thenReturn(configReferencing(PKG1_URI));
            // The reference check cannot answer — the cascade fails closed for this
            // workflow and reports why, quoting the store's message back.
            when(restWorkflowStore.getCurrentResourceId(PKG1_ID)).thenThrow(new RuntimeException("lookup failed" + FORGED_RECORD));

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Failed to plan cascade-delete of package")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.planCascade's plan-failed WARN");
        }

        @Test
        @DisplayName("a CR/LF Agent id or workflow id cannot forge a record through the cascade-deleted INFO")
        void cascadeDeletedPackage() throws Exception {
            String poisonedWorkflowId = PKG1_ID + FORGED_RECORD;
            when(AgentStore.read(POISONED_AGENT_ID, 1)).thenReturn(configReferencing(PKG1_URI));
            when(restWorkflowStore.getCurrentResourceId(PKG1_ID)).thenReturn(resourceId(poisonedWorkflowId, 2));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(poisonedWorkflowId, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(Response.ok().build());

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Cascade-deleted package")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.deleteAgent's cascade-deleted INFO");
        }

        @Test
        @DisplayName("a CR/LF workflow id or failure message cannot forge a record through the cascade-failed WARN")
        void cascadeDeleteOfOnePackageFails() throws Exception {
            String poisonedWorkflowId = PKG1_ID + FORGED_RECORD;
            when(AgentStore.read(POISONED_AGENT_ID, 1)).thenReturn(configReferencing(PKG1_URI));
            when(restWorkflowStore.getCurrentResourceId(PKG1_ID)).thenReturn(resourceId(poisonedWorkflowId, 2));
            when(AgentStore.getAgentDescriptorsContainingWorkflow(poisonedWorkflowId, 2, true)).thenAnswer(invocation -> referrers(1));
            when(restWorkflowStore.deleteWorkflow(anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new RuntimeException("workflow in use" + FORGED_RECORD));

            List<String> captured = captureLogsOf(RestAgentStore.class,
                    () -> assertDoesNotThrow(() -> restAgentStore.deleteAgent(POISONED_AGENT_ID, 1, false, true)));

            assertFalse(captured.isEmpty(), "nothing was captured, so this proves nothing — the logger was not open");
            assertTrue(captured.stream().anyMatch(value -> value.contains("Failed to cascade-delete package")),
                    "the line under test did not fire; captured: " + captured);
            assertNoForgedRecordBoundary(captured, "RestAgentStore.deleteAgent's cascade-failed WARN");
        }
    }
    /**
     * {@code dynamicOrigin} is the provenance teardown_agent and group cleanup
     * require before deleting an agent. Only AgentSetupService writes it; no edit
     * may erase or replace it, and a copy is not a sub-agent.
     */
    @Nested
    @DisplayName("dynamicOrigin is engine-owned")
    class DynamicOriginIsEngineOwned {

        private final AgentConfiguration.DynamicOrigin storedOrigin = new AgentConfiguration.DynamicOrigin("parent", "conv-1", "gc-1",
                "user-1");

        private AgentConfiguration stored() {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(new ArrayList<>());
            config.setDynamicOrigin(storedOrigin);
            return config;
        }

        private AgentConfiguration persistedByUpdate() throws Exception {
            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).update(eq(AGENT_ID), eq(1), captor.capture(), eq(false));
            return captor.getValue();
        }

        @Test
        @DisplayName("an update whose body omits the marker keeps the stored one")
        void updateWithoutMarkerKeepsStoredOrigin() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(stored());
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), eq(false))).thenReturn(2);
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());

            restAgentStore.updateAgent(AGENT_ID, 1, body, false);

            assertSame(storedOrigin, persistedByUpdate().getDynamicOrigin());
        }

        @Test
        @DisplayName("an update whose body carries a different marker keeps the stored one")
        void updateCannotReplaceStoredOrigin() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(stored());
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), eq(false))).thenReturn(2);
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());
            body.setDynamicOrigin(new AgentConfiguration.DynamicOrigin("attacker", "conv-evil", "gc-evil", "someone"));

            restAgentStore.updateAgent(AGENT_ID, 1, body, false);

            assertSame(storedOrigin, persistedByUpdate().getDynamicOrigin());
        }

        @Test
        @DisplayName("an update cannot plant a marker on an agent a person built")
        void updateCannotPlantOrigin() throws Exception {
            AgentConfiguration personBuilt = new AgentConfiguration();
            personBuilt.setWorkflows(new ArrayList<>());
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(personBuilt);
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), eq(false))).thenReturn(2);
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());
            body.setDynamicOrigin(new AgentConfiguration.DynamicOrigin("parent", "conv-1", null, "user-1"));

            restAgentStore.updateAgent(AGENT_ID, 1, body, false);

            assertNull(persistedByUpdate().getDynamicOrigin());
        }

        @Test
        @DisplayName("a store failure reading the stored marker fails the update instead of dropping the marker")
        void unreadableStoredOriginFailsTheUpdate() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("store down"));
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());

            assertThrows(IResourceStore.ResourceStoreException.class, () -> restAgentStore.updateAgent(AGENT_ID, 1, body, false));
            verify(AgentStore, never()).update(anyString(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a duplicate does not inherit the original's marker")
        void duplicateDropsOrigin() throws Exception {
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(stored());
            when(AgentStore.create(any())).thenReturn(resourceId("112233445566778899aa", 1));
            when(documentDescriptorStore.readDescriptor(AGENT_ID, 1)).thenReturn(dummyDescriptor());

            restAgentStore.duplicateAgent(AGENT_ID, 1, false);

            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).create(captor.capture());
            assertNull(captor.getValue().getDynamicOrigin());
        }
    }

    /**
     * {@code identity} holds the public keys peers verify the agent's signatures
     * with. Writable through {@code PUT}, it let any editor plant a key whose
     * private half they hold, delete the revocation an admin made, or shrink the
     * list a permanent delete sizes its vault sweep from — so it is kept from the
     * stored version, like {@code dynamicOrigin}, and only the admin-only
     * signing-key endpoints change it.
     */
    @Nested
    @DisplayName("identity is engine-owned")
    class IdentityIsEngineOwned {

        private AgentConfiguration.AgentIdentity storedIdentity;

        @BeforeEach
        void storeAnAgentWithKeys() throws Exception {
            storedIdentity = new AgentConfiguration.AgentIdentity();
            storedIdentity
                    .setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, "stored-v1"), AgentPublicKey.createCurrent(2, "stored-v2"))));
            AgentConfiguration stored = new AgentConfiguration();
            stored.setWorkflows(new ArrayList<>());
            stored.setIdentity(storedIdentity);
            when(AgentStore.read(AGENT_ID, 1)).thenReturn(stored);
            when(AgentStore.update(eq(AGENT_ID), eq(1), any(), anyBoolean())).thenReturn(2);
        }

        private AgentConfiguration persistedByUpdate() throws Exception {
            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).update(eq(AGENT_ID), eq(1), captor.capture(), eq(false));
            return captor.getValue();
        }

        @Test
        @DisplayName("an update whose body declares other keys keeps the stored ones")
        void updateCannotPlantAKey() throws Exception {
            var planted = new AgentConfiguration.AgentIdentity();
            planted.setPublicKey("planted-legacy-key");
            planted.setKeys(new ArrayList<>(List.of(AgentPublicKey.createCurrent(1, "planted-v1"))));
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());
            body.setIdentity(planted);

            restAgentStore.updateAgent(AGENT_ID, 1, body, false);

            assertSame(storedIdentity, persistedByUpdate().getIdentity());
        }

        /**
         * A permanent delete sizes its vault sweep from the identity of the version it
         * deletes, and skipped the sweep for none at all: an identity an editor could
         * drop left every private key in the vault.
         */
        @Test
        @DisplayName("an update whose body has no identity keeps the stored one, so a permanent delete still finds the keys")
        void updateCannotDropTheIdentity() throws Exception {
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());

            restAgentStore.updateAgent(AGENT_ID, 1, body, false);

            assertSame(storedIdentity, persistedByUpdate().getIdentity());
        }

        @Test
        @DisplayName("a created agent gets no identity, whatever the body declares")
        void createIgnoresTheBodysIdentity() throws Exception {
            when(AgentStore.create(any())).thenReturn(resourceId(AGENT_ID, 1));
            AgentConfiguration body = new AgentConfiguration();
            body.setWorkflows(new ArrayList<>());
            body.setIdentity(storedIdentity);

            restAgentStore.createAgent(body);

            var captor = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(AgentStore).create(captor.capture());
            assertNull(captor.getValue().getIdentity());
        }
    }
}
