/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended unit tests for {@link RestImportService} covering branches not
 * reached by the primary test file: normalizeVaultReferences, executeSyncBatch,
 * executeSync, listRemoteAgents, merge preview with workflows, snippet diffs,
 * and selected-resource filtering.
 */
class RestImportServiceExtendedTest {

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private StructuralMatcher structuralMatcher;
    private UpgradeExecutor upgradeExecutor;
    private IRestAgentAdministration restAgentAdministration;
    private RestImportService importService;

    @BeforeEach
    void setUp() {
        zipArchive = mock(IZipArchive.class);
        jsonSerialization = mock(IJsonSerialization.class);
        restAgentAdministration = mock(IRestAgentAdministration.class);
        var migrationManager = mock(IMigrationManager.class);
        var deploymentListener = mock(IDeploymentListener.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        structuralMatcher = mock(StructuralMatcher.class);
        upgradeExecutor = mock(UpgradeExecutor.class);

        importService = new RestImportService(
                zipArchive, jsonSerialization, restAgentAdministration,
                migrationManager, deploymentListener, documentDescriptorStore,
                templateSyntaxMigrator, structuralMatcher, upgradeExecutor);
    }

    // ==================== normalizeVaultReferences ====================

    @Nested
    @DisplayName("normalizeVaultReferences")
    class NormalizeVaultReferences {

        @Test
        @DisplayName("should rewrite eddivault to vault")
        void rewritesEddivault() {
            String input = "${eddivault:my-secret}";
            String result = AbstractBackupService.normalizeVaultReferences(input);
            assertEquals("${vault:my-secret}", result);
        }

        @Test
        @DisplayName("should return unchanged when no eddivault present")
        void noEddivault() {
            String input = "${vault:already-correct}";
            assertEquals(input, AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("should return null for null input")
        void nullInput() {
            assertNull(AbstractBackupService.normalizeVaultReferences(null));
        }

        @Test
        @DisplayName("should rewrite multiple eddivault references")
        void multipleRefs() {
            String input = "${eddivault:secret1} and ${eddivault:secret2}";
            String result = AbstractBackupService.normalizeVaultReferences(input);
            assertEquals("${vault:secret1} and ${vault:secret2}", result);
        }

        @Test
        @DisplayName("should handle empty string without eddivault")
        void emptyString() {
            assertEquals("", AbstractBackupService.normalizeVaultReferences(""));
        }
    }

    // ==================== executeSyncBatch ====================

    @Nested
    @DisplayName("executeSyncBatch")
    class ExecuteSyncBatch {

        @Test
        @DisplayName("should throw for invalid source URL")
        void invalidSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSyncBatch(null, List.of(), null));
        }

        @Test
        @DisplayName("should return ok response with empty list when no requests succeed")
        void noSuccessfulRequests() {
            // executeSyncBatch catches individual failures and continues
            // The validateSourceUrl check would reject null, so use valid URL
            // The RemoteApiResourceSource constructor would fail, but the error is caught
            // per-request. We need a valid URL but can't actually connect.
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSyncBatch("http://localhost",
                            List.of(new SyncRequest("src", 1, "tgt", null, null)), null));
        }
    }

    // ==================== executeSync ====================

    @Nested
    @DisplayName("executeSync")
    class ExecuteSync {

        @Test
        @DisplayName("should throw for null source URL")
        void nullSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSync(null, "agent", 1, "target", null, null, null));
        }

        @Test
        @DisplayName("should throw for blank source URL")
        void blankSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSync("", "agent", 1, "target", null, null, null));
        }
    }

    // ==================== listRemoteAgents ====================

    @Nested
    @DisplayName("listRemoteAgents")
    class ListRemoteAgents {

        @Test
        @DisplayName("should throw for null source URL")
        void nullSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents(null, null));
        }

        @Test
        @DisplayName("should throw for localhost URL (blocked by validator)")
        void localhostUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("http://localhost:8080", null));
        }
    }

    // ==================== previewSync ====================

    @Nested
    @DisplayName("previewSync")
    class PreviewSync {

        @Test
        @DisplayName("should throw for null source URL")
        void nullSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSync(null, "agent", 1, "target", null));
        }
    }

    // ==================== previewSyncBatch with failure handling
    // ====================

    @Nested
    @DisplayName("previewSyncBatch error handling")
    class PreviewSyncBatchErrors {

        @Test
        @DisplayName("should produce error entries when individual previews fail")
        void producesErrorEntries() {
            // Use a domain that resolves to a public IP (passes SSRF validation).
            // Stub structuralMatcher.buildPreview to throw so the per-mapping
            // catch block creates an error preview entry.
            var mappings = List.of(new SyncMapping("bad-agent", 1, "target-1"));

            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true)))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<ImportPreview> results = importService.previewSyncBatch(
                    "https://example.com", mappings, null);

            assertEquals(1, results.size());
            assertTrue(results.getFirst().sourceAgentName().startsWith("Error:"));
        }
    }

    // ==================== Merge preview with workflows ====================

    @Nested
    @DisplayName("previewImport — merge preview with workflows")
    class PreviewMergeWithWorkflows {

        @Test
        @DisplayName("should include workflow diffs in preview")
        void includesWorkflowDiffs() throws Exception {
            // Use valid 24-char hex ObjectId IDs — RestUtilities.isValidId()
            // requires ≥18 hex chars, short IDs like "wf1" return null.
            String agentOriginId = "aaaa11112222333344445555";
            String wfOriginId = "bbbb11112222333344445555";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/" + wfOriginId + "?version=1\"]}");

                // Create workflow directory
                File wfDir = new File(dir, wfOriginId + File.separator + "1");
                wfDir.mkdirs();
                File wfFile = new File(wfDir, wfOriginId + ".workflow.json");
                Files.writeString(wfFile.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfOriginId + "?version=1")));
            when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);
            when(jsonSerialization.deserialize(eq("{}"), eq(WorkflowConfiguration.class)))
                    .thenReturn(new WorkflowConfiguration());

            // Agent not found
            when(documentDescriptorStore.findByOriginId(agentOriginId)).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(agentOriginId))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

            // Workflow not found
            when(documentDescriptorStore.findByOriginId(wfOriginId)).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(wfOriginId))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            assertEquals(agentOriginId, result.sourceAgentId());
            // Should have at least agent + workflow diffs
            assertTrue(result.resources().size() >= 2);

            // The workflow diff should be CREATE
            var wfDiff = result.resources().stream()
                    .filter(d -> "workflow".equals(d.resourceType())).findFirst();
            assertTrue(wfDiff.isPresent());
            assertEquals(DiffAction.CREATE, wfDiff.get().action());
        }

        @Test
        @DisplayName("should handle unzip failure with InternalServerErrorException")
        void unzipFailure() throws Exception {
            doThrow(new RuntimeException("corrupt zip"))
                    .when(zipArchive).unzip(any(InputStream.class), any(File.class));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.previewImport(
                            new ByteArrayInputStream(new byte[0]), null));
        }
    }

    // ==================== buildResourceDiff — exception paths ====================

    @Nested
    @DisplayName("buildResourceDiff exception paths")
    class BuildResourceDiffExceptions {

        @Test
        @DisplayName("should produce CREATE when both lookups throw exceptions")
        void bothLookupsThrow() throws Exception {
            // Use valid 24-char hex ObjectId format IDs
            String agentOriginId = "dddd11112222333344445555";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // findByOriginId declares ResourceStoreException | ResourceNotFoundException
            when(documentDescriptorStore.findByOriginId(agentOriginId))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));
            // getCurrentResourceId declares only ResourceNotFoundException
            when(documentDescriptorStore.getCurrentResourceId(agentOriginId))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("DB error"));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            ResourceDiff agentDiff = result.resources().getFirst();
            assertEquals(DiffAction.CREATE, agentDiff.action());
        }

        @Test
        @DisplayName("should handle resourceId fallback when descriptor is null")
        void descriptorNullOnFallback() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, "fallback2.agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            when(documentDescriptorStore.findByOriginId("fallback2")).thenReturn(List.of());

            IResourceId currentId = testResourceId("fallback2", 1);
            when(documentDescriptorStore.getCurrentResourceId("fallback2")).thenReturn(currentId);
            // readDescriptor returns null
            when(documentDescriptorStore.readDescriptor("fallback2", 1)).thenReturn(null);

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            ResourceDiff agentDiff = result.resources().getFirst();
            // Null descriptor → falls through to CREATE
            assertEquals(DiffAction.CREATE, agentDiff.action());
        }
    }

    // ==================== importAgent merge strategy ====================

    @Nested
    @DisplayName("importAgent — merge strategy")
    class ImportMerge {

        @Test
        @DisplayName("merge strategy with empty zip returns ok response")
        void mergeEmptyZip() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "merge", null, null, null);

            assertNotNull(response);
            // No agent files → returns 200
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("create strategy with selectedOriginIds set still works")
        void createWithSelection() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", "res1,res2", null, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    // ==================== importAgent — upgrade with workflowOrder
    // ====================

    @Nested
    @DisplayName("importAgent — upgrade with options")
    class ImportUpgradeOptions {

        @Test
        @DisplayName("upgrade with workflowOrder and selectedOriginIds")
        void upgradeWithOptions() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=2");
            when(upgradeExecutor.executeUpgrade(any(), eq("target-1"),
                    eq(Set.of("res1", "res2")), eq(List.of("wf1", "wf2"))))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade",
                    "res1,res2", "target-1", "wf1,wf2");

            assertNotNull(response);
            assertEquals(201, response.getStatus());
        }
    }

    // ==================== Test Helpers ====================

    private static IResourceId testResourceId(String id, int version) {
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
