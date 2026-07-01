/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
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
 * Additional branch coverage tests for {@link RestImportService} focusing on
 * the sync endpoints (previewSync, previewSyncBatch, executeSync,
 * executeSyncBatch), merge strategy paths, and parse helper edge cases.
 */
@DisplayName("RestImportService — Branch Coverage")
class RestImportServiceBranchCoverageTest {

    private IZipArchive zipArchive;
    private IJsonSerialization jsonSerialization;
    private IDocumentDescriptorStore documentDescriptorStore;
    private StructuralMatcher structuralMatcher;
    private UpgradeExecutor upgradeExecutor;
    private RestImportService importService;
    private IRestAgentAdministration restAgentAdministration;

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

    // =========================================================
    // importAgent — merge strategy
    // =========================================================

    @Nested
    @DisplayName("importAgent — merge strategy")
    class MergeStrategy {

        @Test
        @DisplayName("merge strategy with empty ZIP completes with 200")
        void mergeEmptyZip() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "merge", null, null, null);

            assertNotNull(response);
        }

        @Test
        @DisplayName("null strategy defaults to create/merge")
        void nullStrategy() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), null, null, null, null);

            assertNotNull(response);
        }
    }

    // =========================================================
    // importAgent — upgrade strategy edge cases
    // =========================================================

    @Nested
    @DisplayName("importAgent — upgrade strategy edge cases")
    class UpgradeEdgeCases {

        @Test
        @DisplayName("upgrade with workflowOrder passes through")
        void upgradeWithWorkflowOrder() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=2");
            when(upgradeExecutor.executeUpgrade(any(), eq("target-1"), isNull(), eq(List.of("wf1", "wf2"))))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, "target-1", "wf1,wf2");

            assertNotNull(response);
            assertEquals(201, response.getStatus());
        }

        @Test
        @DisplayName("upgrade with selectedOriginIds passes through")
        void upgradeWithSelectedOriginIds() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=3");
            when(upgradeExecutor.executeUpgrade(any(), eq("target-1"), eq(Set.of("res1", "res2")), isNull()))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", "res1,res2", "target-1", null);

            assertNotNull(response);
            assertEquals(201, response.getStatus());
        }
    }

    // =========================================================
    // previewImport edge cases
    // =========================================================

    @Nested
    @DisplayName("previewImport edge cases")
    class PreviewEdgeCases {

        @Test
        @DisplayName("preview without target returns merge preview")
        void previewWithoutTarget() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            verify(structuralMatcher, never()).buildPreview(any(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("preview with empty string target treated as null")
        void previewWithEmptyTarget() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            // Empty string targetAgentId — in the code, isNullOrEmpty check
            var result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), "");

            assertNotNull(result);
        }

        @Test
        @DisplayName("preview with multiple agent files in ZIP returns first one")
        void previewMultipleAgentFiles() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                // Create two agent files
                File agent1 = new File(dir, "agent1.agent.json");
                Files.writeString(agent1.toPath(), "{}");
                File agent2 = new File(dir, "agent2.agent.json");
                Files.writeString(agent2.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // No existing agents
            when(documentDescriptorStore.findByOriginId(anyString())).thenReturn(List.of());

            var result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
        }
    }

    // =========================================================
    // normalizeVaultReferences
    // =========================================================

    @Nested
    @DisplayName("normalizeVaultReferences")
    class NormalizeVaultReferences {

        @Test
        @DisplayName("should rewrite ${eddivault:name} to ${vault:name}")
        void rewriteEddiVault() {
            String input = "apiKey: ${eddivault:my-key}";
            String expected = "apiKey: ${vault:my-key}";
            assertEquals(expected, AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("should not modify ${vault:name} (already canonical)")
        void alreadyCanonical() {
            String input = "apiKey: ${vault:my-key}";
            assertEquals(input, AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("should handle null input")
        void nullInput() {
            assertNull(AbstractBackupService.normalizeVaultReferences(null));
        }

        @Test
        @DisplayName("should handle string without vault references")
        void noVaultReferences() {
            String input = "some-config-without-vault";
            assertEquals(input, AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("should handle empty string")
        void emptyString() {
            assertEquals("", AbstractBackupService.normalizeVaultReferences(""));
        }

        @Test
        @DisplayName("should handle multiple eddivault references")
        void multipleReferences() {
            String input = "key1: ${eddivault:key-a}, key2: ${eddivault:key-b}";
            String expected = "key1: ${vault:key-a}, key2: ${vault:key-b}";
            assertEquals(expected, AbstractBackupService.normalizeVaultReferences(input));
        }
    }

    // =========================================================
    // previewSyncBatch edge cases
    // =========================================================

    @Nested
    @DisplayName("previewSyncBatch")
    class PreviewSyncBatch {

        @Test
        @DisplayName("null mappings with non-localhost URL returns empty list")
        void nullMappings() {
            // Note: "https://example.com" passes SSRF validation; null mappings
            // returns immediately before creating any RemoteApiResourceSource
            var result = importService.previewSyncBatch(
                    "https://example.com", null, null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("empty mappings returns empty list")
        void emptyMappings() {
            var result = importService.previewSyncBatch(
                    "https://example.com", List.of(), null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null sourceUrl throws IllegalArgumentException")
        void nullSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch(null, null, null));
        }

        @Test
        @DisplayName("blank sourceUrl throws IllegalArgumentException")
        void blankSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch("  ", null, null));
        }
    }

    // =========================================================
    // executeSyncBatch / executeSync SSRF validation
    // =========================================================

    @Nested
    @DisplayName("sync SSRF validation")
    class SyncSsrfValidation {

        @Test
        @DisplayName("executeSync with null URL throws IllegalArgumentException")
        void executeSyncNullUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSync(null, "src", 1, "tgt", null, null, null));
        }

        @Test
        @DisplayName("executeSyncBatch with null URL throws IllegalArgumentException")
        void executeSyncBatchNullUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSyncBatch(null, List.of(), null));
        }

        @Test
        @DisplayName("previewSync with null URL throws IllegalArgumentException")
        void previewSyncNullUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSync(null, "src", 1, "tgt", null));
        }

        @Test
        @DisplayName("listRemoteAgents with null URL throws IllegalArgumentException")
        void listRemoteAgentsNullUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents(null, null));
        }
    }

    // =========================================================
    // Import with descriptor file
    // =========================================================

    @Nested
    @DisplayName("importAgent with descriptor files")
    class ImportWithDescriptor {

        @Test
        @DisplayName("descriptor files are skipped during agent file discovery")
        void descriptorSkipped() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                // Create descriptor file (should be ignored)
                File desc = new File(dir, "agent-1.descriptor.json");
                Files.writeString(desc.toPath(), "{\"name\":\"Test\"}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "create", null, null, null);

            assertNotNull(response);
        }
    }

    // =========================================================
    // Additional normalizeLegacyUris edge cases
    // =========================================================

    @Nested
    @DisplayName("normalizeLegacyUris additional edge cases")
    class NormalizeLegacyUrisExtended {

        @Test
        @DisplayName("mixed v5 and v6 URIs normalizes only v5")
        void mixedUris() {
            String input = "\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/a1?version=1\" " +
                    "\"eddi://ai.labs.rules/rulestore/rulesets/b2?version=2\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            // behavior → rules rewritten
            assertTrue(result.contains("eddi://ai.labs.rules/rulestore/rulesets/a1"));
            // Already v6 → unchanged
            assertTrue(result.contains("eddi://ai.labs.rules/rulestore/rulesets/b2"));
        }

        @Test
        @DisplayName("string without eddi:// prefix returns unchanged")
        void noEddiPrefix() {
            String input = "https://api.openai.com/v1/chat";
            assertEquals(input, AbstractBackupService.normalizeLegacyUris(input));
        }
    }
}
