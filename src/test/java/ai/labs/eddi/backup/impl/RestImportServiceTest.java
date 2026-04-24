/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestImportService} covering strategy dispatch, preview
 * logic, sync endpoint edge cases, URI replacement, and buildResourceDiff via
 * the merge preview flow.
 */
class RestImportServiceTest {

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
        var restAgentAdministration = mock(IRestAgentAdministration.class);
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

    // ==================== Strategy Dispatch ====================

    @Nested
    @DisplayName("importAgent — strategy dispatch")
    class StrategyDispatch {

        @Test
        @DisplayName("upgrade strategy with targetAgentId delegates to upgradeExecutor")
        void upgradeStrategy() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/target-1?version=2");
            when(upgradeExecutor.executeUpgrade(any(), eq("target-1"), isNull(), isNull()))
                    .thenReturn(resultUri);

            // Mock zipArchive.unzip to create minimal directory
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, "target-1", null);

            assertNotNull(response);
            assertEquals(201, response.getStatus());
            assertTrue(response.getHeaderString("Location").contains("target-1"));
        }

        @Test
        @DisplayName("upgrade strategy without targetAgentId falls through to create path")
        void upgradeWithoutTarget() throws Exception {
            // Without targetAgentId, upgrade strategy is ignored → falls to create/merge
            // This will fail during import (empty zip), wrapped in
            // InternalServerErrorException
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            // Empty zip → no agent files → returns 200 with null location
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, null, null);

            // With empty ZIP and CDI calls for importSnippets, this will throw
            // because getRestResourceStore needs CDI — but importSnippets checks
            // for snippets dir first. Empty dir → no snippets dir → skips.
            // Then no .agent.json files → returns null → 200 response
            assertNotNull(response);
            verify(upgradeExecutor, never()).executeUpgrade(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("exception wraps in InternalServerErrorException")
        void exceptionWrapping() throws Exception {
            doThrow(new RuntimeException("zip broken"))
                    .when(zipArchive).unzip(any(InputStream.class), any(File.class));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]),
                            "create", null, null, null));
        }

        @Test
        @DisplayName("upgrade strategy exception wraps in InternalServerErrorException")
        void upgradeException() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            when(upgradeExecutor.executeUpgrade(any(), eq("target-1"), any(), any()))
                    .thenThrow(new RuntimeException("upgrade failed"));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(new ByteArrayInputStream(new byte[0]),
                            "upgrade", null, "target-1", null));
        }
    }

    // ==================== Preview — Upgrade Path ====================

    @Nested
    @DisplayName("previewImport — upgrade path")
    class PreviewUpgrade {

        @Test
        @DisplayName("delegates to structuralMatcher when targetAgentId provided")
        void delegatesToStructuralMatcher() throws Exception {
            var expectedPreview = new ImportPreview("src-1", "Source", "target-1", "Target", List.of());
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true)))
                    .thenReturn(expectedPreview);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), "target-1");

            assertNotNull(result);
            assertEquals("src-1", result.sourceAgentId());
            assertEquals("target-1", result.targetAgentId());
            verify(structuralMatcher).buildPreview(any(), eq("target-1"), eq(true));
        }

        @Test
        @DisplayName("exception wraps in InternalServerErrorException")
        void exceptionWrapping() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            doThrow(new RuntimeException("matcher failed"))
                    .when(structuralMatcher).buildPreview(any(), anyString(), anyBoolean());

            assertThrows(InternalServerErrorException.class,
                    () -> importService.previewImport(new ByteArrayInputStream(new byte[0]), "target-1"));
        }
    }

    // ==================== Preview — Merge Path ====================

    @Nested
    @DisplayName("previewImport — merge preview path")
    class PreviewMerge {

        @Test
        @DisplayName("empty ZIP returns preview with empty diffs")
        void emptyZip() throws Exception {
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

        @Test
        @DisplayName("agent found by originId produces UPDATE diff")
        void agentFoundByOriginId() throws Exception {
            // Setup: unzip creates agent file in temp dir
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                // Create agent JSON file
                File agentFile = new File(dir, "agent-src-1.agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            // Deserialize to AgentConfiguration with no workflows
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Origin ID lookup finds existing
            // Use valid 24-char hex ID (MongoDB ObjectId format) for
            // RestUtilities.extractResourceId
            String localId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            var existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setResource(
                    URI.create("eddi://ai.labs.agent/agentstore/agents/" + localId + "?version=3"));
            when(documentDescriptorStore.findByOriginId("agent-src-1"))
                    .thenReturn(List.of(existingDescriptor));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            assertEquals("agent-src-1", result.sourceAgentId());
            assertFalse(result.resources().isEmpty());

            ResourceDiff agentDiff = result.resources().getFirst();
            assertEquals(DiffAction.UPDATE, agentDiff.action());
            assertEquals("originId", agentDiff.matchStrategy());
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaa", agentDiff.targetId());
        }

        @Test
        @DisplayName("agent not found produces CREATE diff")
        void agentNotFound() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, "new-agent.agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // Both lookup paths return nothing
            when(documentDescriptorStore.findByOriginId("new-agent"))
                    .thenReturn(List.of());
            when(documentDescriptorStore.getCurrentResourceId("new-agent"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            assertNotNull(result);
            ResourceDiff agentDiff = result.resources().getFirst();
            assertEquals(DiffAction.CREATE, agentDiff.action());
            assertNull(agentDiff.targetId());
            assertNull(agentDiff.matchStrategy());
        }

        @Test
        @DisplayName("agent found by resourceId fallback produces UPDATE with resourceId strategy")
        void agentFoundByResourceIdFallback() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                File agentFile = new File(dir, "fallback-agent.agent.json");
                Files.writeString(agentFile.toPath(), "{}");
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(jsonSerialization.deserialize(eq("{}"), eq(AgentConfiguration.class)))
                    .thenReturn(agentConfig);

            // originId lookup returns empty
            when(documentDescriptorStore.findByOriginId("fallback-agent"))
                    .thenReturn(List.of());

            // resourceId fallback finds it
            IResourceId currentId = testResourceId("fallback-agent", 2);
            when(documentDescriptorStore.getCurrentResourceId("fallback-agent"))
                    .thenReturn(currentId);
            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/fallback-agent?version=2"));
            when(documentDescriptorStore.readDescriptor("fallback-agent", 2))
                    .thenReturn(desc);

            ImportPreview result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), null);

            ResourceDiff agentDiff = result.resources().getFirst();
            assertEquals(DiffAction.UPDATE, agentDiff.action());
            assertEquals("resourceId", agentDiff.matchStrategy());
        }
    }

    // ==================== previewSyncBatch Edge Cases ====================

    @Nested
    @DisplayName("previewSyncBatch edge cases")
    class PreviewSyncBatchEdgeCases {

        @Test
        @DisplayName("null mappings returns empty list")
        void nullMappings() {
            List<ImportPreview> result = importService.previewSyncBatch(
                    "https://example.com", null, null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("empty mappings returns empty list")
        void emptyMappings() {
            List<ImportPreview> result = importService.previewSyncBatch(
                    "https://example.com", List.of(), null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== normalizeLegacyUris ====================

    @Nested
    @DisplayName("normalizeLegacyUris")
    class NormalizeLegacyUris {

        @Test
        @DisplayName("rewrites v5 dictionary URIs to v6 canonical form")
        void rewritesDictionaryUri() {
            String input = "\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/abc?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc"));
        }

        @Test
        @DisplayName("rewrites v5 behavior URIs")
        void rewritesBehaviorUri() {
            String input = "\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/abc?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.rules/rulestore/rulesets/abc"));
        }

        @Test
        @DisplayName("rewrites v5 httpcalls URIs")
        void rewritesHttpcallsUri() {
            String input = "\"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/abc?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.apicalls/apicallstore/apicalls/abc"));
        }

        @Test
        @DisplayName("rewrites v5 langchain URIs")
        void rewritesLangchainUri() {
            String input = "\"eddi://ai.labs.langchain/langchainstore/langchains/abc?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.llm/llmstore/llms/abc"));
        }

        @Test
        @DisplayName("rewrites v5 package URIs to workflow")
        void rewritesPackageUri() {
            String input = "\"eddi://ai.labs.package/packagestore/packages/abc?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/abc"));
        }

        @Test
        @DisplayName("rewrites v5 bot URIs to agent")
        void rewritesBotUri() {
            String input = "\"eddi://ai.labs.bot/botstore/bots/abc?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.agent/agentstore/agents/abc"));
        }

        @Test
        @DisplayName("null input returns null")
        void nullInput() {
            assertNull(AbstractBackupService.normalizeLegacyUris(null));
        }

        @Test
        @DisplayName("string without eddi:// returns unchanged")
        void noEddiUri() {
            String input = "no uris here";
            assertEquals(input, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("v6 URIs are not rewritten")
        void v6Unchanged() {
            String input = "\"eddi://ai.labs.llm/llmstore/llms/abc?version=1\"";
            assertEquals(input, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("multiple legacy URIs rewritten in one pass")
        void multipleRewrites() {
            String input = "\"eddi://ai.labs.bot/botstore/bots/a?version=1\" and " +
                    "\"eddi://ai.labs.package/packagestore/packages/b?version=2\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("agentstore/agents/a"));
            assertTrue(result.contains("workflowstore/workflows/b"));
        }
    }

    // ==================== SourceUrlValidator (tested via sync endpoints)
    // ====================

    @Nested
    @DisplayName("validateSourceUrl")
    class ValidateSourceUrl {

        @Test
        @DisplayName("null URL throws IllegalArgumentException")
        void nullUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch(null, List.of(new SyncMapping("a", 1, "b")), null));
        }

        @Test
        @DisplayName("blank URL throws IllegalArgumentException")
        void blankUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch("   ", List.of(new SyncMapping("a", 1, "b")), null));
        }

        @Test
        @DisplayName("localhost URL throws IllegalArgumentException")
        void localhostUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch("http://localhost:8080",
                            List.of(new SyncMapping("a", 1, "b")), null));
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
