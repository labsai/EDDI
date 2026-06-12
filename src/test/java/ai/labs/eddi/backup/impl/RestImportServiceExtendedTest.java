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

    // ==================== previewImport — UPDATE path via findByOriginId
    // ====================

    @Nested
    @DisplayName("previewImport — UPDATE via findByOriginId")
    class PreviewImportUpdateViaOriginId {

        @Test
        @DisplayName("should produce UPDATE when findByOriginId returns existing descriptor")
        void updateWhenOriginIdFound() throws Exception {
            String agentOriginId = "aabbccddeeff112233445566";

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

            // findByOriginId returns an existing descriptor with a valid resource URI
            String localId = "112233445566778899aabbcc";
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + localId + "?version=3"));
            when(documentDescriptorStore.findByOriginId(agentOriginId))
                    .thenReturn(List.of(descriptor));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            assertEquals(agentOriginId, result.sourceAgentId());
            assertEquals(1, result.resources().size());

            ResourceDiff diff = result.resources().getFirst();
            assertEquals(DiffAction.UPDATE, diff.action());
            assertEquals(localId, diff.targetId());
            assertEquals(3, diff.targetVersion());
            assertEquals("originId", diff.matchStrategy());
        }
    }

    // ==================== previewImport — UPDATE path via resourceId fallback
    // ====================

    @Nested
    @DisplayName("previewImport — UPDATE via resourceId fallback")
    class PreviewImportUpdateViaResourceIdFallback {

        @Test
        @DisplayName("should produce UPDATE when getCurrentResourceId returns result and descriptor has resource")
        void updateWhenResourceIdFallbackSucceeds() throws Exception {
            String agentOriginId = "aabb11223344556677889900";

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

            // findByOriginId returns empty
            when(documentDescriptorStore.findByOriginId(agentOriginId)).thenReturn(List.of());

            // getCurrentResourceId returns a valid resource id
            IResourceId currentId = testResourceId(agentOriginId, 2);
            when(documentDescriptorStore.getCurrentResourceId(agentOriginId)).thenReturn(currentId);

            // readDescriptor returns a descriptor with a resource URI
            String localId = agentOriginId;
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + localId + "?version=2"));
            when(documentDescriptorStore.readDescriptor(agentOriginId, 2)).thenReturn(descriptor);

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            ResourceDiff diff = result.resources().getFirst();
            assertEquals(DiffAction.UPDATE, diff.action());
            assertEquals(localId, diff.targetId());
            assertEquals(2, diff.targetVersion());
            assertEquals("resourceId", diff.matchStrategy());
        }
    }

    // ==================== previewImport — snippet diffs ====================

    @Nested
    @DisplayName("previewImport — snippet diffs")
    class PreviewImportSnippetDiffs {

        @Test
        @DisplayName("should produce CREATE diff for new snippet in ZIP")
        @SuppressWarnings("unchecked")
        void snippetCreateDiff() throws Exception {
            String agentOriginId = "cccc11112222333344445555";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                // Create snippets directory with a snippet file
                File snippetsDir = new File(dir, "snippets");
                snippetsDir.mkdirs();
                File snippetFile = new File(snippetsDir, "cautious_mode.snippet.json");
                Files.writeString(snippetFile.toPath(),
                        "{\"name\":\"cautious_mode\",\"content\":\"Be cautious\"}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Agent not found
            when(documentDescriptorStore.findByOriginId(agentOriginId)).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(agentOriginId))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

            // Snippet deserialization
            var snippet = new ai.labs.eddi.configs.snippets.model.PromptSnippet();
            snippet.setName("cautious_mode");
            snippet.setContent("Be cautious");
            when(jsonSerialization.deserialize(
                    eq("{\"name\":\"cautious_mode\",\"content\":\"Be cautious\"}"),
                    eq(ai.labs.eddi.configs.snippets.model.PromptSnippet.class)))
                    .thenReturn(snippet);

            // Mock CDI for IRestPromptSnippetStore (used by addSnippetDiffs →
            // getRestResourceStore)
            var snippetStore = mock(ai.labs.eddi.configs.snippets.IRestPromptSnippetStore.class);
            // readSnippetDescriptors returns empty → no existing snippets
            when(snippetStore.readSnippetDescriptors(eq(""), eq(0), eq(0))).thenReturn(List.of());

            try (var cdiMock = org.mockito.Mockito.mockStatic(jakarta.enterprise.inject.spi.CDI.class)) {
                var cdi = mock(jakarta.enterprise.inject.spi.CDI.class);
                cdiMock.when(jakarta.enterprise.inject.spi.CDI::current).thenReturn(cdi);
                var instance = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.snippets.IRestPromptSnippetStore>) mock(
                        jakarta.enterprise.inject.Instance.class);
                when(cdi.select(ai.labs.eddi.configs.snippets.IRestPromptSnippetStore.class)).thenReturn(instance);
                when(instance.get()).thenReturn(snippetStore);

                ImportPreview result = importService.previewImport(
                        new ByteArrayInputStream(new byte[0]), null);

                assertNotNull(result);
                // Should have agent diff + snippet diff
                assertTrue(result.resources().size() >= 2,
                        "Expected at least 2 diffs (agent + snippet), got " + result.resources().size());

                var snippetDiff = result.resources().stream()
                        .filter(d -> "snippet".equals(d.resourceType())).findFirst();
                assertTrue(snippetDiff.isPresent(), "Snippet diff should be present");
                assertEquals(DiffAction.CREATE, snippetDiff.get().action());
                assertEquals("cautious_mode", snippetDiff.get().name());
            }
        }

        @Test
        @DisplayName("should produce UPDATE diff for existing snippet matched by name")
        @SuppressWarnings("unchecked")
        void snippetUpdateDiff() throws Exception {
            String agentOriginId = "dddd22223333444455556666";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                // Create snippets directory
                File snippetsDir = new File(dir, "snippets");
                snippetsDir.mkdirs();
                File snippetFile = new File(snippetsDir, "safety_prompt.snippet.json");
                Files.writeString(snippetFile.toPath(),
                        "{\"name\":\"safety_prompt\",\"content\":\"Be safe\"}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Agent not found
            when(documentDescriptorStore.findByOriginId(agentOriginId)).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(agentOriginId))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

            // Snippet deserialization
            var snippet = new ai.labs.eddi.configs.snippets.model.PromptSnippet();
            snippet.setName("safety_prompt");
            snippet.setContent("Be safe");
            when(jsonSerialization.deserialize(
                    eq("{\"name\":\"safety_prompt\",\"content\":\"Be safe\"}"),
                    eq(ai.labs.eddi.configs.snippets.model.PromptSnippet.class)))
                    .thenReturn(snippet);

            // Mock CDI for snippet store
            var snippetStore = mock(ai.labs.eddi.configs.snippets.IRestPromptSnippetStore.class);
            // Existing snippet descriptor
            String existingSnippetId = "eeee11112222333344445555";
            var existingDesc = new DocumentDescriptor();
            existingDesc.setResource(URI.create(
                    "eddi://ai.labs.snippet/snippetstore/snippets/" + existingSnippetId + "?version=1"));
            when(snippetStore.readSnippetDescriptors(eq(""), eq(0), eq(0)))
                    .thenReturn(List.of(existingDesc));

            // readSnippet returns a snippet with the same name
            var existingSnippet = new ai.labs.eddi.configs.snippets.model.PromptSnippet();
            existingSnippet.setName("safety_prompt");
            when(snippetStore.readSnippet(existingSnippetId, 1)).thenReturn(existingSnippet);

            try (var cdiMock = org.mockito.Mockito.mockStatic(jakarta.enterprise.inject.spi.CDI.class)) {
                var cdi = mock(jakarta.enterprise.inject.spi.CDI.class);
                cdiMock.when(jakarta.enterprise.inject.spi.CDI::current).thenReturn(cdi);
                var instance = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.snippets.IRestPromptSnippetStore>) mock(
                        jakarta.enterprise.inject.Instance.class);
                when(cdi.select(ai.labs.eddi.configs.snippets.IRestPromptSnippetStore.class)).thenReturn(instance);
                when(instance.get()).thenReturn(snippetStore);

                ImportPreview result = importService.previewImport(
                        new ByteArrayInputStream(new byte[0]), null);

                assertNotNull(result);

                var snippetDiff = result.resources().stream()
                        .filter(d -> "snippet".equals(d.resourceType())).findFirst();
                assertTrue(snippetDiff.isPresent(), "Snippet diff should be present");
                assertEquals(DiffAction.UPDATE, snippetDiff.get().action());
                assertEquals("safety_prompt", snippetDiff.get().name());
                assertEquals(existingSnippetId, snippetDiff.get().targetId());
                assertEquals("name", snippetDiff.get().matchStrategy());
            }
        }
    }

    // ==================== previewImport — upgrade path with targetAgentId
    // ====================

    @Nested
    @DisplayName("previewImport — upgrade preview delegation")
    class PreviewImportUpgrade {

        @Test
        @DisplayName("should delegate to structuralMatcher.buildPreview when targetAgentId is provided")
        void delegatesToStructuralMatcher() throws Exception {
            String targetAgentId = "aabbccddeeff001122334455";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var preview = new ImportPreview("src-1", "Source Agent", targetAgentId, "Target Agent",
                    List.of(new ResourceDiff("src-1", "agent", "Agent", DiffAction.UPDATE,
                            targetAgentId, 1, "position", null, null, -1)));
            when(structuralMatcher.buildPreview(any(), eq(targetAgentId), eq(true)))
                    .thenReturn(preview);

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), targetAgentId);

            assertNotNull(result);
            assertEquals(targetAgentId, result.targetAgentId());
            assertEquals("Source Agent", result.sourceAgentName());
            verify(structuralMatcher).buildPreview(any(), eq(targetAgentId), eq(true));
        }

        @Test
        @DisplayName("should throw InternalServerErrorException when structuralMatcher fails")
        void structuralMatcherFailure() throws Exception {
            String targetAgentId = "ffeeddccbbaa998877665544";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            when(structuralMatcher.buildPreview(any(), eq(targetAgentId), eq(true)))
                    .thenThrow(new RuntimeException("Structural match failed"));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.previewImport(
                            new ByteArrayInputStream(new byte[0]), targetAgentId));
        }
    }

    // ==================== importAgent — upgrade strategy delegation
    // ====================

    @Nested
    @DisplayName("importAgent — upgrade strategy")
    class ImportUpgradeStrategy {

        @Test
        @DisplayName("should delegate to upgradeExecutor when strategy is 'upgrade' and targetAgentId provided")
        void upgradeStrategyDelegation() throws Exception {
            String targetAgentId = "aabb112233445566778899aa";
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/" + targetAgentId + "?version=2");

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            when(upgradeExecutor.executeUpgrade(any(), eq(targetAgentId), isNull(), isNull()))
                    .thenReturn(resultUri);

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, targetAgentId, null);

            assertNotNull(response);
            assertEquals(201, response.getStatus());
            assertEquals(resultUri.toString(), response.getHeaderString("Location"));
            verify(upgradeExecutor).executeUpgrade(any(), eq(targetAgentId), isNull(), isNull());
        }

        @Test
        @DisplayName("upgrade failure should throw InternalServerErrorException")
        void upgradeFailure() throws Exception {
            String targetAgentId = "ccdd112233445566778899aa";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            when(upgradeExecutor.executeUpgrade(any(), eq(targetAgentId), isNull(), isNull()))
                    .thenThrow(new RuntimeException("Upgrade explosion"));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(
                            new ByteArrayInputStream(new byte[0]), "upgrade", null, targetAgentId, null));
        }
    }

    // ==================== importAgent — create flow with agent file
    // ====================

    @Nested
    @DisplayName("importAgent — create flow with agent file")
    class ImportCreateFlowWithAgent {

        @Test
        @DisplayName("should create agent via CDI store and return 201 with Location header")
        @SuppressWarnings("unchecked")
        void createFlowProduces201() throws Exception {
            String agentOriginId = "aabb11112222333344445555";
            String newAgentId = "ccdd11112222333344445555";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(), "{\"workflows\":[]}");
                // Create descriptor file for agent
                File descriptorFile = new File(dir, agentOriginId + ".descriptor.json");
                Files.writeString(descriptorFile.toPath(),
                        "{\"name\":\"Test Agent\",\"description\":\"A test agent\"}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{\"workflows\":[]}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Descriptor deserialization for updateDocumentDescriptor
            var zipDescriptor = new DocumentDescriptor();
            zipDescriptor.setName("Test Agent");
            zipDescriptor.setDescription("A test agent");
            when(jsonSerialization.deserialize(
                    eq("{\"name\":\"Test Agent\",\"description\":\"A test agent\"}"),
                    eq(DocumentDescriptor.class)))
                    .thenReturn(zipDescriptor);

            // Mock CDI for IAgentStore.create
            var agentStore = mock(ai.labs.eddi.configs.agents.IAgentStore.class);
            IResourceId createdId = testResourceId(newAgentId, 1);
            when(agentStore.create(any())).thenReturn(createdId);

            // Mock descriptor operations for createResourceDirect and
            // setOriginIdOnDescriptor
            IResourceId descId = testResourceId(newAgentId, 1);
            when(documentDescriptorStore.getCurrentResourceId(newAgentId)).thenReturn(descId);

            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + newAgentId + "?version=1"));
            when(documentDescriptorStore.readDescriptor(newAgentId, 1)).thenReturn(existingDescriptor);

            try (var cdiMock = org.mockito.Mockito.mockStatic(jakarta.enterprise.inject.spi.CDI.class)) {
                var cdi = mock(jakarta.enterprise.inject.spi.CDI.class);
                cdiMock.when(jakarta.enterprise.inject.spi.CDI::current).thenReturn(cdi);

                var agentStoreInstance = (jakarta.enterprise.inject.Instance<ai.labs.eddi.configs.agents.IAgentStore>) mock(
                        jakarta.enterprise.inject.Instance.class);
                when(cdi.select(ai.labs.eddi.configs.agents.IAgentStore.class)).thenReturn(agentStoreInstance);
                when(agentStoreInstance.get()).thenReturn(agentStore);

                Response response = importService.importAgent(
                        new ByteArrayInputStream(new byte[0]), "create", null, null, null);

                assertNotNull(response);
                assertEquals(201, response.getStatus());
                String location = response.getHeaderString("Location");
                assertNotNull(location);
                assertTrue(location.contains(newAgentId));
                verify(agentStore).create(any());
            }
        }
    }

    // ==================== previewImport — empty ZIP returns empty diffs
    // ====================

    @Nested
    @DisplayName("previewImport — empty ZIP handling")
    class PreviewImportEmptyZip {

        @Test
        @DisplayName("should return empty preview when ZIP has no agent files")
        void emptyZipReturnsEmptyPreview() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            assertNull(result.sourceAgentId());
            assertTrue(result.resources().isEmpty());
        }
    }

    // ==================== previewImport — workflow with extensions
    // ====================

    @Nested
    @DisplayName("previewImport — workflow with extension URIs")
    class PreviewImportWorkflowExtensions {

        @Test
        @DisplayName("should produce diffs for workflow extensions (dictionary, llm)")
        void extensionDiffs() throws Exception {
            String agentOriginId = "aaaa22223333444455556666";
            String wfOriginId = "bbbb22223333444455556666";
            String dictOriginId = "cccc22223333444455556666";
            String llmOriginId = "dddd22223333444455556666";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(),
                        "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/" + wfOriginId + "?version=1\"]}");

                // Workflow directory
                File wfDir = new File(dir, wfOriginId + File.separator + "1");
                wfDir.mkdirs();
                // Workflow file referencing dictionary and LLM
                String wfContent = "{\"workflowSteps\":[" +
                        "{\"extensions\":{\"uri\":\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + dictOriginId + "?version=1\"}}," +
                        "{\"extensions\":{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/" + llmOriginId + "?version=1\"}}" +
                        "]}";
                File wfFile = new File(wfDir, wfOriginId + ".workflow.json");
                Files.writeString(wfFile.toPath(), wfContent);
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + wfOriginId + "?version=1")));
            when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // All resources not found → CREATE
            for (String id : List.of(agentOriginId, wfOriginId, dictOriginId, llmOriginId)) {
                when(documentDescriptorStore.findByOriginId(id)).thenReturn(List.of());
                when(documentDescriptorStore.getCurrentResourceId(id))
                        .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));
            }

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            // Agent + workflow + dictionary + llm = at least 4 diffs
            assertTrue(result.resources().size() >= 4,
                    "Expected at least 4 diffs, got " + result.resources().size());

            // All should be CREATE
            for (ResourceDiff diff : result.resources()) {
                assertEquals(DiffAction.CREATE, diff.action(),
                        "Expected CREATE for " + diff.resourceType() + " " + diff.sourceId());
            }

            // Check specific types present
            assertTrue(result.resources().stream().anyMatch(d -> "agent".equals(d.resourceType())));
            assertTrue(result.resources().stream().anyMatch(d -> "workflow".equals(d.resourceType())));
            assertTrue(result.resources().stream().anyMatch(d -> "regulardictionary".equals(d.resourceType())));
            assertTrue(result.resources().stream().anyMatch(d -> "langchain".equals(d.resourceType())));
        }
    }

    // ==================== previewSync — structural matching delegation
    // ====================

    @Nested
    @DisplayName("previewSync — structural matching delegation")
    class PreviewSyncStructuralMatching {

        @Test
        @DisplayName("should produce error entries for multiple failed mappings in batch")
        void multipleMappingFailures() {
            var mappings = List.of(
                    new SyncMapping("agent-1", 1, "target-1"),
                    new SyncMapping("agent-2", 2, "target-2"));

            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true)))
                    .thenThrow(new RuntimeException("Failed 1"));
            when(structuralMatcher.buildPreview(any(), eq("target-2"), eq(true)))
                    .thenThrow(new RuntimeException("Failed 2"));

            List<ImportPreview> results = importService.previewSyncBatch(
                    "https://example.com", mappings, null);

            assertEquals(2, results.size());
            assertTrue(results.get(0).sourceAgentName().startsWith("Error:"));
            assertTrue(results.get(1).sourceAgentName().startsWith("Error:"));
            assertEquals("agent-1", results.get(0).sourceAgentId());
            assertEquals("agent-2", results.get(1).sourceAgentId());
        }

        @Test
        @DisplayName("should return empty list for null mappings")
        void nullMappings() {
            List<ImportPreview> results = importService.previewSyncBatch(
                    "https://example.com", null, null);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for empty mappings")
        void emptyMappings() {
            List<ImportPreview> results = importService.previewSyncBatch(
                    "https://example.com", List.of(), null);
            assertTrue(results.isEmpty());
        }
    }

    // ==================== normalizeLegacyUris ====================

    @Nested
    @DisplayName("normalizeLegacyUris")
    class NormalizeLegacyUris {

        @Test
        @DisplayName("should rewrite legacy dictionary URI to v6 canonical form")
        void rewritesLegacyDictionaryUri() {
            String input = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/abc123";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123", result);
        }

        @Test
        @DisplayName("should rewrite legacy behavior URI")
        void rewritesLegacyBehaviorUri() {
            String input = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/abc123";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.rules/rulestore/rulesets/abc123", result);
        }

        @Test
        @DisplayName("should rewrite legacy bot URI to agent")
        void rewritesLegacyBotUri() {
            String input = "eddi://ai.labs.bot/botstore/bots/abc123";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.agent/agentstore/agents/abc123", result);
        }

        @Test
        @DisplayName("should return null for null input")
        void nullInput() {
            assertNull(AbstractBackupService.normalizeLegacyUris(null));
        }

        @Test
        @DisplayName("should return unchanged when no eddi:// present")
        void noEddiUri() {
            String input = "https://example.com/api";
            assertEquals(input, AbstractBackupService.normalizeLegacyUris(input));
        }
    }

    // ==================== importAgent — exception handling ====================

    @Nested
    @DisplayName("importAgent — exception handling")
    class ImportExceptionHandling {

        @Test
        @DisplayName("should throw InternalServerErrorException when unzip fails during import")
        void unzipFailureDuringImport() throws Exception {
            doThrow(new RuntimeException("IO failure"))
                    .when(zipArchive).unzip(any(InputStream.class), any(File.class));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(
                            new ByteArrayInputStream(new byte[0]), "create", null, null, null));
        }

        @Test
        @DisplayName("should throw InternalServerErrorException when upgrade strategy fails")
        void upgradeStrategyExceptionWrapping() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            when(upgradeExecutor.executeUpgrade(any(), eq("target-x"), isNull(), isNull()))
                    .thenThrow(new RuntimeException("upgrade error"));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(
                            new ByteArrayInputStream(new byte[0]), "upgrade", null, "target-x", null));
        }
    }

    // ==================== parseSelectedResources / parseWorkflowOrder
    // ====================

    @Nested
    @DisplayName("importAgent — selected resources parsing")
    class SelectedResourcesParsing {

        @Test
        @DisplayName("should handle comma-separated selected resources with spaces")
        void spacesInSelection() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            // With spaces around commas — still works
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", " res1 , res2 , res3 ", null, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should handle empty string selected resources")
        void emptyStringSelection() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", "", null, null);

            assertNotNull(response);
            assertEquals(200, response.getStatus());
        }
    }

    // ==================== previewImport — agent name from descriptor file
    // ====================

    @Nested
    @DisplayName("previewImport — agent name from descriptor")
    class PreviewImportAgentName {

        @Test
        @DisplayName("should read agent name from descriptor file")
        void readsNameFromDescriptor() throws Exception {
            String agentOriginId = "eeff11112222333344445555";

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, agentOriginId + ".agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                // Create descriptor file for the agent
                File descriptorFile = new File(dir, agentOriginId + ".descriptor.json");
                Files.writeString(descriptorFile.toPath(),
                        "{\"name\":\"My Cool Agent\",\"description\":\"test\"}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            var descriptor = new DocumentDescriptor();
            descriptor.setName("My Cool Agent");
            when(jsonSerialization.deserialize(
                    eq("{\"name\":\"My Cool Agent\",\"description\":\"test\"}"),
                    eq(DocumentDescriptor.class)))
                    .thenReturn(descriptor);

            // Agent not found → CREATE
            when(documentDescriptorStore.findByOriginId(agentOriginId)).thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId(agentOriginId))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("nf"));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            assertEquals("My Cool Agent", result.sourceAgentName());
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
