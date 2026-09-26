/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.util.Optional;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two guarantees the ZIP import owes its caller beyond producing an
 * agent:
 * <ul>
 * <li><b>D11</b> — a ZIP that fails at its last resource leaves no orphans: the
 * resources already written are deleted again.</li>
 * <li><b>D12</b> — no unzipped scratch directory survives a request, on any of
 * the four paths that unpack a ZIP.</li>
 * </ul>
 */
class RestImportServiceRollbackAndCleanupTest {

    private static final String AGENT_ORIGIN_ID = "aaaa11112222333344445555";
    private static final String SCHEDULE_ORIGIN_ID = "ffff11112222333344445555";
    private static final String NEW_AGENT_ID = "dddd11112222333344445555";
    private static final String WORKFLOW_ORIGIN_ID = "bbbb11112222333344445555";
    private static final String NEW_WORKFLOW_ID = "cccc11112222333344445555";
    private static final String NEW_SNIPPET_ID = "eeee11112222333344445555";
    private static final String SNIPPET_NAME = "cautious_mode";
    private static final String SNIPPET_RESOURCE_URI = "eddi://ai.labs.snippet/snippetstore/snippets/" + NEW_SNIPPET_ID + "?version=1";

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private StructuralMatcher structuralMatcher;
    private UpgradeExecutor upgradeExecutor;
    private RestImportService importService;

    @BeforeEach
    void setUp() {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        structuralMatcher = mock(StructuralMatcher.class);
        upgradeExecutor = mock(UpgradeExecutor.class);

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                mock(IMigrationManager.class), documentDescriptorStore,
                mock(TemplateSyntaxMigrator.class), structuralMatcher, upgradeExecutor, mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class),
                true, false, Optional.empty());
    }

    // ==================== D11 — rollback of a partial import ====================

    @Nested
    @DisplayName("import rollback (D11)")
    class ImportRollback {

        @Test
        @DisplayName("agent creation failing after the workflow landed deletes the workflow again")
        void failureAtLastResourceLeavesNoOrphans() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            stubAgentWithOneWorkflowZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            // The agent is the very last write of the import — blowing up here is
            // exactly the case that used to leave the workflow behind forever.
            when(agentStore.create(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore);

                assertThrows(InternalServerErrorException.class,
                        () -> importService.importAgent(
                                new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                verify(workflowStore).create(any());
                verify(workflowStore).deleteAllPermanently(NEW_WORKFLOW_ID);
                verify(documentDescriptorStore).deleteAllDescriptor(NEW_WORKFLOW_ID);
            }
        }

        @Test
        @DisplayName("a rollback that itself fails does not mask the original error")
        void rollbackFailureDoesNotMaskOriginalError() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            stubAgentWithOneWorkflowZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));
            doThrow(new IllegalStateException("delete exploded"))
                    .when(workflowStore).deleteAllPermanently(anyString());

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore);

                var thrown = assertThrows(InternalServerErrorException.class,
                        () -> importService.importAgent(
                                new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                assertNotNull(thrown.getCause());
                assertEquals("agent store unavailable", thrown.getCause().getMessage());
                // the descriptor cleanup still runs even though the store delete blew up
                verify(documentDescriptorStore).deleteAllDescriptor(NEW_WORKFLOW_ID);
            }
        }

        /**
         * Snippets are created before any other resource and used to be invisible to
         * the transaction, so a ZIP that failed later left them behind — contradicting
         * the guarantee {@code importAgentZipFile} advertises in its own javadoc.
         */
        @Test
        @DisplayName("a snippet this import created is deleted again when a later resource fails")
        void snippetCreatedByAFailedImportIsRolledBack() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var snippetStore = mock(IPromptSnippetStore.class);
            var restSnippetStore = stubSnippetCreation();
            stubAgentWithOneWorkflowAndOneSnippetZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore, snippetStore, restSnippetStore);

                assertThrows(InternalServerErrorException.class,
                        () -> importService.importAgent(
                                new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                verify(restSnippetStore).createSnippet(any());
                verify(snippetStore).deleteAllPermanently(NEW_SNIPPET_ID);
                verify(documentDescriptorStore).deleteAllDescriptor(NEW_SNIPPET_ID);
            }
        }

        @Test
        @DisplayName("a snippet that only existed already is never deleted by a rollback")
        void preexistingSnippetSurvivesARollback() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var snippetStore = mock(IPromptSnippetStore.class);
            var restSnippetStore = mock(IRestPromptSnippetStore.class);
            stubAgentWithOneWorkflowAndOneSnippetZip();

            // Name already taken and strategy is "create" → the snippet is skipped, so
            // nothing was created and nothing may be deleted.
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(SNIPPET_RESOURCE_URI));
            when(restSnippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of(descriptor));
            var existing = new PromptSnippet();
            existing.setName(SNIPPET_NAME);
            when(restSnippetStore.readSnippet(NEW_SNIPPET_ID, 1)).thenReturn(existing);

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore, snippetStore, restSnippetStore);

                assertThrows(InternalServerErrorException.class,
                        () -> importService.importAgent(
                                new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                verify(restSnippetStore, never()).createSnippet(any());
                verify(snippetStore, never()).deleteAllPermanently(anyString());
            }
        }

        /**
         * Import creates Agents through the store directly, so it registers their
         * skills itself — and the capability index is a process-local map that nothing
         * else prunes. A ZIP that landed one Agent and then failed therefore deleted
         * the Agent row while leaving its skills behind, so {@code findBySkill} and A2A
         * discovery kept answering with an agent id that no longer existed, until the
         * next restart.
         */
        @Test
        @DisplayName("a rolled-back Agent is taken back out of the capability index")
        void rolledBackAgentIsUnregisteredFromTheCapabilityIndex() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var capabilityRegistry = mock(CapabilityRegistryService.class);
            var restScheduleStore = mock(IRestScheduleStore.class);
            stubOneCapableAgentWithOneScheduleZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            // The Agent lands and is registered; the archive's schedule — the first
            // write after it — blows up, so everything this ZIP created is rolled back.
            when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
            when(restScheduleStore.createSchedule(any()))
                    .thenThrow(new IllegalStateException("schedule store unavailable"));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubBean(stubCdi(cdiMock, workflowStore, agentStore, capabilityRegistry),
                        IRestScheduleStore.class, restScheduleStore);

                assertThrows(InternalServerErrorException.class,
                        () -> importService.importAgent(
                                new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                verify(capabilityRegistry).register(eq(NEW_AGENT_ID), any());
                verify(agentStore).deleteAllPermanently(NEW_AGENT_ID);
                verify(capabilityRegistry).unregister(NEW_AGENT_ID);
            }
        }

        /**
         * Adding an imported Agent to the discovery index is best-effort: the Agent row
         * is already written by the time it happens, so a registry that cannot be
         * updated must not turn a completed import into a 500 — and must certainly not
         * trigger the rollback, which would delete an Agent the caller was about to be
         * told it had.
         */
        @Test
        @DisplayName("a capability index that refuses the registration does not fail the import")
        void registrationFailureDoesNotFailTheImport() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var capabilityRegistry = mock(CapabilityRegistryService.class);
            stubOneCapableAgentZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
            doThrow(new IllegalStateException("registry unavailable"))
                    .when(capabilityRegistry).register(anyString(), any());

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore, capabilityRegistry);

                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null);

                // An imported Agent's skills must be offered to the index at all —
                // import creates Agents through the store directly, so without this
                // call they stayed invisible to capabilityMatch rules and to A2A
                // discovery until the node restarted, with no error to explain it.
                verify(capabilityRegistry).register(eq(NEW_AGENT_ID), any());
                assertEquals(201, response.getStatus(), "the Agent was written; a discovery-index failure is not the caller's problem");
                verify(agentStore, never()).deleteAllPermanently(anyString());
                verify(workflowStore, never()).deleteAllPermanently(anyString());
            }
        }

        /**
         * The rollback's own steps are each guarded so one failure cannot mask the
         * original error or abandon the resources it has not reached yet. An
         * unreachable capability index while unwinding must still leave every created
         * resource deleted.
         */
        @Test
        @DisplayName("a capability index that refuses the unregistration does not abandon the rest of the rollback")
        void unregistrationFailureDoesNotAbandonTheRollback() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var capabilityRegistry = mock(CapabilityRegistryService.class);
            var restScheduleStore = mock(IRestScheduleStore.class);
            stubOneCapableAgentWithOneScheduleZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));
            when(restScheduleStore.createSchedule(any()))
                    .thenThrow(new IllegalStateException("schedule store unavailable"));
            doThrow(new IllegalStateException("registry unavailable"))
                    .when(capabilityRegistry).unregister(anyString());

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubBean(stubCdi(cdiMock, workflowStore, agentStore, capabilityRegistry),
                        IRestScheduleStore.class, restScheduleStore);

                assertThrows(InternalServerErrorException.class,
                        () -> importService.importAgent(
                                new ByteArrayInputStream(new byte[0]), "create", null, null, null));

                verify(capabilityRegistry).unregister(NEW_AGENT_ID);
                verify(agentStore).deleteAllPermanently(NEW_AGENT_ID);
                // Rolled back newest first, so this one comes AFTER the failing
                // unregistration — it is the proof the loop was not abandoned.
                verify(workflowStore).deleteAllPermanently(NEW_WORKFLOW_ID);
            }
        }

        @Test
        @DisplayName("a successful import deletes nothing")
        void successfulImportDoesNotRollBack() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            stubAgentWithOneWorkflowZip();

            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any())).thenReturn(resourceId("dddd11112222333344445555", 1));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore);

                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null);

                assertEquals(201, response.getStatus());
                verify(workflowStore, never()).deleteAllPermanently(anyString());
                verify(documentDescriptorStore, never()).deleteAllDescriptor(anyString());
            }
        }
    }

    // ==================== merge: selection and compensation of updates
    // ====================

    @Nested
    @DisplayName("merge rollback and snippet selection (M-P4)")
    class MergeRollbackAndSnippetSelection {

        private static final String LOCAL_WORKFLOW_ID = "abab11112222333344445555";
        private static final String ARCHIVE_SNIPPET_ID = "cdcd11112222333344445555";

        /**
         * A merge updates resources that already exist. When a later write failed, the
         * rollback deleted what the import had created and left every update in place,
         * so the target agent was left half-promoted.
         */
        @Test
        @DisplayName("a failed merge writes the pre-import content of an updated workflow back, descriptor included")
        void failedMergeRestoresAnUpdatedWorkflow() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var restWorkflowStore = mock(IRestWorkflowStore.class);
            stubAgentWithOneWorkflowZip();
            WorkflowConfiguration previous = stubExistingLocalWorkflow(workflowStore);
            DocumentDescriptor previousDescriptor = stubCurrentDescriptor(LOCAL_WORKFLOW_ID, 3, "Before");
            when(restWorkflowStore.updateWorkflow(eq(LOCAL_WORKFLOW_ID), anyInt(), any())).thenReturn(Response.ok().build());
            when(agentStore.create(any())).thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubBean(stubCdi(cdiMock, workflowStore, agentStore, mock(CapabilityRegistryService.class)),
                        IRestWorkflowStore.class, restWorkflowStore);

                assertThrows(InternalServerErrorException.class, () -> importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", null, null, null));

                // the merge's own write, v3 -> v4 ...
                verify(restWorkflowStore).updateWorkflow(eq(LOCAL_WORKFLOW_ID), eq(3), any());
                // ... and the compensation: the pre-import content written over v4
                verify(restWorkflowStore).updateWorkflow(LOCAL_WORKFLOW_ID, 4, previous);
                verify(documentDescriptorStore).setDescriptor(eq(LOCAL_WORKFLOW_ID), eq(3), argThat((DocumentDescriptor d) -> d == previousDescriptor
                        && d.getResource().toString().endsWith(LOCAL_WORKFLOW_ID + "?version=5")));
                verify(workflowStore, never()).deleteAllPermanently(LOCAL_WORKFLOW_ID);
            }
        }

        @Test
        @DisplayName("a merge that succeeds restores nothing")
        void successfulMergeRestoresNothing() throws Exception {
            var workflowStore = mock(IWorkflowStore.class);
            var agentStore = mock(IAgentStore.class);
            var restWorkflowStore = mock(IRestWorkflowStore.class);
            stubAgentWithOneWorkflowZip();
            stubExistingLocalWorkflow(workflowStore);
            stubCurrentDescriptor(LOCAL_WORKFLOW_ID, 3, "Before");
            when(restWorkflowStore.updateWorkflow(eq(LOCAL_WORKFLOW_ID), anyInt(), any())).thenReturn(Response.ok().build());
            when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubBean(stubCdi(cdiMock, workflowStore, agentStore, mock(CapabilityRegistryService.class)),
                        IRestWorkflowStore.class, restWorkflowStore);

                Response response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge", null, null, null);

                assertEquals(201, response.getStatus());
                verify(restWorkflowStore).updateWorkflow(eq(LOCAL_WORKFLOW_ID), eq(3), any());
                verify(restWorkflowStore, never()).updateWorkflow(eq(LOCAL_WORKFLOW_ID), eq(4), any());
            }
        }

        @Test
        @DisplayName("a merge leaves a live snippet alone when selectedResources does not name it")
        void unselectedSnippetIsNotOverwritten() throws Exception {
            var agentStore = mock(IAgentStore.class);
            var workflowStore = mock(IWorkflowStore.class);
            var restSnippetStore = stubExistingSnippet();
            stubAgentWithOneWorkflowAndArchivedSnippet();
            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore, mock(IPromptSnippetStore.class), restSnippetStore);

                Response response = importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge",
                        WORKFLOW_ORIGIN_ID, null, null);

                assertEquals(201, response.getStatus());
                verify(restSnippetStore, never()).updateSnippet(anyString(), anyInt(), any());
                verify(restSnippetStore, never()).createSnippet(any());
            }
        }

        @Test
        @DisplayName("a merge updates a snippet selectedResources names by its archive id")
        void selectedSnippetIsUpdated() throws Exception {
            var agentStore = mock(IAgentStore.class);
            var workflowStore = mock(IWorkflowStore.class);
            var restSnippetStore = stubExistingSnippet();
            stubAgentWithOneWorkflowAndArchivedSnippet();
            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any())).thenReturn(resourceId(NEW_AGENT_ID, 1));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore, mock(IPromptSnippetStore.class), restSnippetStore);

                importService.importAgent(new ByteArrayInputStream(new byte[0]), "merge",
                        WORKFLOW_ORIGIN_ID + "," + ARCHIVE_SNIPPET_ID, null, null);

                verify(restSnippetStore).updateSnippet(eq(NEW_SNIPPET_ID), eq(1), any());
            }
        }

        @Test
        @DisplayName("a failed merge writes an updated snippet's previous content back")
        void failedMergeRestoresAnUpdatedSnippet() throws Exception {
            var agentStore = mock(IAgentStore.class);
            var workflowStore = mock(IWorkflowStore.class);
            var snippetStore = mock(IPromptSnippetStore.class);
            var restSnippetStore = stubExistingSnippet();
            var previous = new PromptSnippet();
            previous.setName(SNIPPET_NAME);
            when(snippetStore.read(NEW_SNIPPET_ID, 1)).thenReturn(previous);
            stubAgentWithOneWorkflowAndArchivedSnippet();
            when(workflowStore.create(any())).thenReturn(resourceId(NEW_WORKFLOW_ID, 1));
            when(agentStore.create(any())).thenThrow(new IResourceStore.ResourceStoreException("agent store unavailable"));

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, workflowStore, agentStore, snippetStore, restSnippetStore);

                assertThrows(InternalServerErrorException.class, () -> importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "merge", null, null, null));

                verify(restSnippetStore).updateSnippet(eq(NEW_SNIPPET_ID), eq(1), any());
                verify(restSnippetStore).updateSnippet(NEW_SNIPPET_ID, 2, previous);
                verify(snippetStore, never()).deleteAllPermanently(NEW_SNIPPET_ID);
            }
        }

        @Test
        @DisplayName("the merge preview names a snippet row by its archive id, so the row can be selected")
        void previewSnippetRowCarriesTheArchiveId() throws Exception {
            var restSnippetStore = stubExistingSnippet();
            stubAgentWithOneWorkflowAndArchivedSnippet();

            try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
                stubCdi(cdiMock, mock(IWorkflowStore.class), mock(IAgentStore.class), mock(IPromptSnippetStore.class),
                        restSnippetStore);

                ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

                var snippetRow = preview.resources().stream().filter(r -> "snippet".equals(r.resourceType())).findFirst()
                        .orElseThrow();
                assertEquals(ARCHIVE_SNIPPET_ID, snippetRow.sourceId());
                assertEquals(NEW_SNIPPET_ID, snippetRow.targetId());
            }
        }

        private WorkflowConfiguration stubExistingLocalWorkflow(IWorkflowStore workflowStore) throws Exception {
            var local = new DocumentDescriptor();
            local.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + LOCAL_WORKFLOW_ID + "?version=3"));
            when(documentDescriptorStore.findByOriginId(WORKFLOW_ORIGIN_ID)).thenReturn(List.of(local));
            var previous = new WorkflowConfiguration();
            when(workflowStore.read(LOCAL_WORKFLOW_ID, 3)).thenReturn(previous);
            return previous;
        }

        private DocumentDescriptor stubCurrentDescriptor(String id, int version, String name) throws Exception {
            when(documentDescriptorStore.getCurrentResourceId(id)).thenReturn(resourceId(id, version));
            var descriptor = new DocumentDescriptor();
            descriptor.setName(name);
            // The first read is the snapshot the merge takes; every later read is a fresh
            // copy, as from the real store, so the snapshot is never mutated behind its
            // back.
            when(documentDescriptorStore.readDescriptor(id, version)).thenReturn(descriptor, new DocumentDescriptor(),
                    new DocumentDescriptor(), new DocumentDescriptor(), new DocumentDescriptor());
            return descriptor;
        }

        /**
         * A snippet REST store that already holds {@link #SNIPPET_NAME} as
         * NEW_SNIPPET_ID v1.
         */
        private IRestPromptSnippetStore stubExistingSnippet() {
            IRestPromptSnippetStore restSnippetStore = mock(IRestPromptSnippetStore.class);
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(SNIPPET_RESOURCE_URI));
            when(restSnippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of(descriptor));
            var existing = new PromptSnippet();
            existing.setName(SNIPPET_NAME);
            when(restSnippetStore.readSnippet(NEW_SNIPPET_ID, 1)).thenReturn(existing);
            when(restSnippetStore.updateSnippet(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
            return restSnippetStore;
        }

        /**
         * As {@link #stubAgentWithOneWorkflowAndOneSnippetZip()}, but the archived
         * snippet's file carries its own archive id, distinct from the id of the local
         * snippet it matches by name — as it does between two deployments.
         */
        private void stubAgentWithOneWorkflowAndArchivedSnippet() throws Exception {
            stubAgentWithOneWorkflowAndOneSnippetZip();
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
                File workflowDir = new File(new File(dir, WORKFLOW_ORIGIN_ID), "1");
                workflowDir.mkdirs();
                Files.writeString(new File(workflowDir, WORKFLOW_ORIGIN_ID + ".workflow.json").toPath(), "WORKFLOWJSON");
                File snippetsDir = new File(dir, "snippets");
                snippetsDir.mkdirs();
                Files.writeString(new File(snippetsDir, ARCHIVE_SNIPPET_ID + ".snippet.json").toPath(), "SNIPPETJSON");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
        }
    }

    // ==================== H15 — archive limits ====================

    @Nested
    @DisplayName("archive limits (H15)")
    class ArchiveLimits {

        @Test
        @DisplayName("an archive over the unpacking limits is a 413 naming the limit, on import and on preview")
        void overSizedArchiveIs413() throws Exception {
            doThrow(new ZipArchive.ZipLimitExceededException("The archive inflates to more than 10 bytes"))
                    .when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var onImport = assertThrows(WebApplicationException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null));
            assertEquals(413, onImport.getResponse().getStatus());
            assertTrue(onImport.getMessage().contains("inflates to more than"), onImport.getMessage());

            var onPreview = assertThrows(WebApplicationException.class,
                    () -> importService.previewImport(new ByteArrayInputStream(new byte[0]), null));
            assertEquals(413, onPreview.getResponse().getStatus());

            var onUpgrade = assertThrows(WebApplicationException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, "target-1", null));
            assertEquals(413, onUpgrade.getResponse().getStatus());
        }
    }

    // ==================== D12 — temp directory cleanup ====================

    @Nested
    @DisplayName("temp directory cleanup (D12)")
    class TempDirectoryCleanup {

        @Test
        @DisplayName("create import removes the unzipped directory")
        void createImportCleansUp() throws Exception {
            AtomicReference<File> unzipped = stubEmptyZip();

            // An archive with no agent file is now a 400, and the tree must go anyway.
            assertThrows(BadRequestException.class, () -> importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null));

            assertUnzippedDirectoryRemoved(unzipped);
        }

        @Test
        @DisplayName("a failed import still removes the unzipped directory")
        void failedImportCleansUp() throws Exception {
            AtomicReference<File> unzipped = new AtomicReference<>();
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                unzipped.set(dir);
                Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class)))
                    .thenThrow(new IllegalStateException("corrupt agent json"));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(
                            new ByteArrayInputStream(new byte[0]), "create", null, null, null));

            assertUnzippedDirectoryRemoved(unzipped);
        }

        @Test
        @DisplayName("legacy merge preview removes the unzipped directory")
        void legacyPreviewCleansUp() throws Exception {
            AtomicReference<File> unzipped = stubEmptyZip();

            assertThrows(BadRequestException.class,
                    () -> importService.previewImport(new ByteArrayInputStream(new byte[0]), null));

            assertUnzippedDirectoryRemoved(unzipped);
        }

        @Test
        @DisplayName("upgrade preview closes the ZipResourceSource, removing the unzipped directory")
        void upgradePreviewCleansUp() throws Exception {
            AtomicReference<File> unzipped = stubEmptyZip();
            when(structuralMatcher.buildPreview(any(), anyString(), anyBoolean()))
                    .thenReturn(new ImportPreview("src", "Source", "target-1", "Target", List.of()));

            importService.previewImport(new ByteArrayInputStream(new byte[0]), "target-1");

            assertUnzippedDirectoryRemoved(unzipped);
        }

        @Test
        @DisplayName("upgrade import closes the ZipResourceSource, removing the unzipped directory")
        void upgradeImportCleansUp() throws Exception {
            AtomicReference<File> unzipped = stubEmptyZip();
            when(upgradeExecutor.executeUpgrade(any(), eq("target-1"), any(), any()))
                    .thenReturn(new UpgradeResult(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ORIGIN_ID + "?version=2"), true, 1, 0,
                            0, List.of()));

            importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, "target-1", null);

            assertUnzippedDirectoryRemoved(unzipped);
        }
    }

    // ==================== Helpers ====================

    private AtomicReference<File> stubEmptyZip() throws Exception {
        AtomicReference<File> unzipped = new AtomicReference<>();
        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            unzipped.set(dir);
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));
        return unzipped;
    }

    private static void assertUnzippedDirectoryRemoved(AtomicReference<File> unzipped) {
        File dir = unzipped.get();
        assertNotNull(dir, "unzip was never invoked — the test setup is wrong");
        assertTrue(dir.toString().contains("import"), "expected the scratch dir under tmp/import: " + dir);
        assertFalse(dir.exists(), "unzipped temp directory was left behind: " + dir);
    }

    /**
     * Lays out the minimal ZIP an import needs: one agent referencing one workflow,
     * each in the directory shape {@code RestImportService} expects.
     */
    private void stubAgentWithOneWorkflowZip() throws Exception {
        URI workflowUri = URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ORIGIN_ID + "?version=1");

        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            File workflowDir = new File(new File(dir, WORKFLOW_ORIGIN_ID), "1");
            workflowDir.mkdirs();
            Files.writeString(new File(workflowDir, WORKFLOW_ORIGIN_ID + ".workflow.json").toPath(), "WORKFLOWJSON");
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(workflowUri));
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
        when(jsonSerialization.deserialize(eq("WORKFLOWJSON"), eq(WorkflowConfiguration.class)))
                .thenReturn(new WorkflowConfiguration());
    }

    /**
     * As {@link #stubAgentWithOneWorkflowZip()}, but the single agent declares a
     * skill — so the import reaches the capability registration at all. One agent,
     * not two, because a second agent re-reads the workflow directory under its
     * already-remapped id and fails for a reason that has nothing to do with this
     * test.
     */
    private void stubOneCapableAgentZip() throws Exception {
        stubAgentWithOneWorkflowZip();

        URI workflowUri = URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ORIGIN_ID + "?version=1");
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(workflowUri));
        agentConfig.setCapabilities(List.of(new AgentConfiguration.Capability("translation", Map.of(), "high")));
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
    }

    /**
     * As {@link #stubOneCapableAgentZip()}, plus a {@code schedules/} directory
     * holding one schedule — the shape that makes a half-imported ZIP reachable
     * from a single-agent archive, which is the only kind the import accepts.
     * <p>
     * Schedules carry the id of the agent they fire, so they are deliberately
     * written <em>after</em> the Agent exists (and its skills are registered) and
     * before the descriptor bookkeeping. A schedule that cannot be written fails
     * the whole import, so it is the first thing that can blow up with a registered
     * Agent already recorded on the transaction.
     */
    private void stubOneCapableAgentWithOneScheduleZip() throws Exception {
        stubOneCapableAgentZip();

        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            File workflowDir = new File(new File(dir, WORKFLOW_ORIGIN_ID), "1");
            workflowDir.mkdirs();
            Files.writeString(new File(workflowDir, WORKFLOW_ORIGIN_ID + ".workflow.json").toPath(), "WORKFLOWJSON");
            File schedulesDir = new File(dir, "schedules");
            schedulesDir.mkdirs();
            Files.writeString(new File(schedulesDir, SCHEDULE_ORIGIN_ID + ".schedule.json").toPath(), "SCHEDULEJSON");
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        var schedule = new ScheduleConfiguration();
        schedule.setName("nightly");
        when(jsonSerialization.deserialize(eq("SCHEDULEJSON"), eq(ScheduleConfiguration.class))).thenReturn(schedule);
    }

    /**
     * Same ZIP as {@link #stubAgentWithOneWorkflowZip()} plus a {@code snippets/}
     * directory holding one snippet — the layout {@code findSnippetsDir} looks for
     * directly under the unzipped root.
     */
    private void stubAgentWithOneWorkflowAndOneSnippetZip() throws Exception {
        URI workflowUri = URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ORIGIN_ID + "?version=1");

        doAnswer(inv -> {
            File dir = inv.getArgument(1);
            dir.mkdirs();
            Files.writeString(new File(dir, AGENT_ORIGIN_ID + ".agent.json").toPath(), "AGENTJSON");
            File workflowDir = new File(new File(dir, WORKFLOW_ORIGIN_ID), "1");
            workflowDir.mkdirs();
            Files.writeString(new File(workflowDir, WORKFLOW_ORIGIN_ID + ".workflow.json").toPath(), "WORKFLOWJSON");
            File snippetsDir = new File(dir, "snippets");
            snippetsDir.mkdirs();
            Files.writeString(new File(snippetsDir, NEW_SNIPPET_ID + ".snippet.json").toPath(), "SNIPPETJSON");
            return null;
        }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(workflowUri));
        when(jsonSerialization.deserialize(eq("AGENTJSON"), eq(AgentConfiguration.class))).thenReturn(agentConfig);
        when(jsonSerialization.deserialize(eq("WORKFLOWJSON"), eq(WorkflowConfiguration.class)))
                .thenReturn(new WorkflowConfiguration());

        var snippet = new PromptSnippet();
        snippet.setName(SNIPPET_NAME);
        when(jsonSerialization.deserialize(eq("SNIPPETJSON"), eq(PromptSnippet.class))).thenReturn(snippet);
    }

    /**
     * A snippet REST store with no existing snippets, whose create returns the 201
     * the real store returns — carrying the resource URI in {@code X-Resource-URI},
     * because JAX-RS reports {@code getLocation()} as null for the eddi:// scheme.
     */
    private static IRestPromptSnippetStore stubSnippetCreation() {
        IRestPromptSnippetStore restSnippetStore = mock(IRestPromptSnippetStore.class);
        when(restSnippetStore.readSnippetDescriptors(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(restSnippetStore.createSnippet(any()))
                .thenReturn(Response.status(201).header("X-Resource-URI", SNIPPET_RESOURCE_URI).build());
        return restSnippetStore;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubCdi(MockedStatic<CDI> cdiMock, IWorkflowStore workflowStore, IAgentStore agentStore) {
        CDI cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);

        Instance<IWorkflowStore> workflowInstance = mock(Instance.class);
        when(cdi.select(IWorkflowStore.class)).thenReturn(workflowInstance);
        when(workflowInstance.get()).thenReturn(workflowStore);

        Instance<IAgentStore> agentInstance = mock(Instance.class);
        when(cdi.select(IAgentStore.class)).thenReturn(agentInstance);
        when(agentInstance.get()).thenReturn(agentStore);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CDI stubCdi(MockedStatic<CDI> cdiMock, IWorkflowStore workflowStore, IAgentStore agentStore,
                               CapabilityRegistryService capabilityRegistry) {
        CDI cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);

        Instance<IWorkflowStore> workflowInstance = mock(Instance.class);
        when(cdi.select(IWorkflowStore.class)).thenReturn(workflowInstance);
        when(workflowInstance.get()).thenReturn(workflowStore);

        Instance<IAgentStore> agentInstance = mock(Instance.class);
        when(cdi.select(IAgentStore.class)).thenReturn(agentInstance);
        when(agentInstance.get()).thenReturn(agentStore);

        Instance<CapabilityRegistryService> registryInstance = mock(Instance.class);
        when(cdi.select(CapabilityRegistryService.class)).thenReturn(registryInstance);
        when(registryInstance.get()).thenReturn(capabilityRegistry);

        return cdi;
    }

    /** Adds one more bean to a {@link CDI} already stubbed by {@link #stubCdi}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void stubBean(CDI cdi, Class<T> beanClass, T bean) {
        Instance<T> instance = mock(Instance.class);
        when(cdi.select(beanClass)).thenReturn(instance);
        when(instance.get()).thenReturn(bean);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubCdi(MockedStatic<CDI> cdiMock, IWorkflowStore workflowStore, IAgentStore agentStore,
                                IPromptSnippetStore snippetStore, IRestPromptSnippetStore restSnippetStore) {
        CDI cdi = mock(CDI.class);
        cdiMock.when(CDI::current).thenReturn(cdi);

        Instance<IWorkflowStore> workflowInstance = mock(Instance.class);
        when(cdi.select(IWorkflowStore.class)).thenReturn(workflowInstance);
        when(workflowInstance.get()).thenReturn(workflowStore);

        Instance<IAgentStore> agentInstance = mock(Instance.class);
        when(cdi.select(IAgentStore.class)).thenReturn(agentInstance);
        when(agentInstance.get()).thenReturn(agentStore);

        Instance<IPromptSnippetStore> snippetInstance = mock(Instance.class);
        when(cdi.select(IPromptSnippetStore.class)).thenReturn(snippetInstance);
        when(snippetInstance.get()).thenReturn(snippetStore);

        Instance<IRestPromptSnippetStore> restSnippetInstance = mock(Instance.class);
        when(cdi.select(IRestPromptSnippetStore.class)).thenReturn(restSnippetInstance);
        when(restSnippetInstance.get()).thenReturn(restSnippetStore);
    }

    private static IResourceId resourceId(String id, int version) {
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
}
