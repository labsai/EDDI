/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
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
                mock(TemplateSyntaxMigrator.class), structuralMatcher, upgradeExecutor, mock(ResourceAccessGuard.class));
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

    // ==================== D12 — temp directory cleanup ====================

    @Nested
    @DisplayName("temp directory cleanup (D12)")
    class TempDirectoryCleanup {

        @Test
        @DisplayName("create import removes the unzipped directory")
        void createImportCleansUp() throws Exception {
            AtomicReference<File> unzipped = stubEmptyZip();

            importService.importAgent(new ByteArrayInputStream(new byte[0]), "create", null, null, null);

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

            ImportPreview preview = importService.previewImport(new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(preview);
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
                    .thenReturn(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ORIGIN_ID + "?version=2"));

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
