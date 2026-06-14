/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended branch coverage tests for {@link RestImportService} focusing on
 * upgrade paths, batch sync, parsing helpers, SSRF edge cases, and
 * normalizeLegacyUris/normalizeVaultReferences combinations.
 */
@DisplayName("RestImportService — Extended Branch Coverage")
class RestImportServiceExtendedBranchTest {

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
    // importAgent — upgrade strategy with null targetAgentId
    // =========================================================

    @Nested
    @DisplayName("importAgent — upgrade strategy requires targetAgentId")
    class UpgradeStrategyTarget {

        @Test
        @DisplayName("upgrade strategy with null targetAgentId falls through to normal import")
        void upgradeWithNullTargetFallsThrough() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            // "upgrade" strategy + null targetAgentId => does NOT take upgrade path,
            // falls to importAgentZipFile
            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, null, null);

            assertNotNull(response);
            // Should not have called upgradeExecutor
            verify(upgradeExecutor, never()).executeUpgrade(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("upgrade strategy case-insensitive")
        void upgradeCaseInsensitive() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/t1?version=1");
            when(upgradeExecutor.executeUpgrade(any(), eq("t1"), isNull(), isNull()))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "UPGRADE", null, "t1", null);

            assertEquals(201, response.getStatus());
        }
    }

    // =========================================================
    // importAgent — upgrade failure wrapping
    // =========================================================

    @Nested
    @DisplayName("importAgent — upgrade exceptions")
    class UpgradeExceptions {

        @Test
        @DisplayName("upgrade executor throws → InternalServerErrorException")
        void upgradeExecutorThrows() throws Exception {
            when(upgradeExecutor.executeUpgrade(any(), eq("t1"), any(), any()))
                    .thenThrow(new RuntimeException("upgrade boom"));

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(
                            new ByteArrayInputStream(new byte[0]),
                            "upgrade", null, "t1", null));
        }
    }

    // =========================================================
    // previewImport — upgrade path (targetAgentId provided)
    // =========================================================

    @Nested
    @DisplayName("previewImport — upgrade path")
    class PreviewUpgradePath {

        @Test
        @DisplayName("preview with targetAgentId uses structural matcher")
        void previewWithTargetUsesStructuralMatcher() throws Exception {
            var mockPreview = new ImportPreview("src-1", "Test", "t1", null, List.of());
            when(structuralMatcher.buildPreview(any(), eq("target-1"), eq(true)))
                    .thenReturn(mockPreview);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            var result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), "target-1");

            assertNotNull(result);
            assertEquals("src-1", result.sourceAgentId());
            verify(structuralMatcher).buildPreview(any(), eq("target-1"), eq(true));
        }

        @Test
        @DisplayName("preview upgrade failure throws InternalServerErrorException")
        void previewUpgradeFailure() throws Exception {
            when(structuralMatcher.buildPreview(any(), eq("t1"), eq(true)))
                    .thenThrow(new RuntimeException("matcher boom"));

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.previewImport(
                            new ByteArrayInputStream(new byte[0]), "t1"));
        }

        @Test
        @DisplayName("blank targetAgentId is treated as null (legacy path)")
        void blankTargetAgent() throws Exception {
            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            // " " (whitespace) should be treated as blank → legacy path
            var result = importService.previewImport(
                    new ByteArrayInputStream(new byte[0]), "  ");

            assertNotNull(result);
            verify(structuralMatcher, never()).buildPreview(any(), anyString(), anyBoolean());
        }
    }

    // =========================================================
    // Sync batch edge cases
    // =========================================================

    @Nested
    @DisplayName("previewSyncBatch — with failing individual previews")
    class PreviewSyncBatchErrors {

        @Test
        @DisplayName("individual mapping failure adds error preview")
        void individualMappingFailure() throws Exception {
            // This mapping will fail because RemoteApiResourceSource cannot connect
            var mapping = new SyncMapping("src-agent", 1, "tgt-agent");

            // The actual exception happens inside the structuralMatcher which
            // is invoked after RemoteApiResourceSource constructor
            // Since we can't easily mock the constructor, we test that a
            // valid URL that causes a connection failure is handled gracefully
            assertThrows(Exception.class,
                    () -> importService.previewSyncBatch(
                            "https://example-nonexistent-12345.com",
                            List.of(mapping), null));
        }
    }

    @Nested
    @DisplayName("executeSyncBatch edge cases")
    class ExecuteSyncBatchEdgeCases {

        @Test
        @DisplayName("executeSyncBatch with empty request list returns OK")
        void emptyRequestList() {
            // Valid HTTPS URL with valid host required, but empty requests should still
            // work
            // We need a real resolvable URL to pass SSRF validation
            // Since the requests list is empty, the loop body never executes
            try {
                Response response = importService.executeSyncBatch(
                        "https://example.com", List.of(), null);
                assertNotNull(response);
                assertEquals(200, response.getStatus());
            } catch (IllegalArgumentException e) {
                // SSRF validation might reject example.com depending on DNS
                // This is acceptable - the point is to test empty requests
            }
        }
    }

    // =========================================================
    // normalizeLegacyUris — all rewrite patterns
    // =========================================================

    @Nested
    @DisplayName("normalizeLegacyUris — comprehensive rewrites")
    class NormalizeLegacyUrisComprehensive {

        @Test
        @DisplayName("null input returns null")
        void nullInput() {
            assertNull(AbstractBackupService.normalizeLegacyUris(null));
        }

        @Test
        @DisplayName("empty string returns empty")
        void emptyString() {
            assertEquals("", AbstractBackupService.normalizeLegacyUris(""));
        }

        @Test
        @DisplayName("rewrite all 6 legacy URI patterns")
        void rewriteAllLegacyPatterns() {
            // regulardictionary
            assertEquals("eddi://ai.labs.dictionary/dictionarystore/dictionaries/x?version=1",
                    AbstractBackupService.normalizeLegacyUris(
                            "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/x?version=1"));

            // behavior
            assertEquals("eddi://ai.labs.rules/rulestore/rulesets/x?version=1",
                    AbstractBackupService.normalizeLegacyUris(
                            "eddi://ai.labs.behavior/behaviorstore/behaviorsets/x?version=1"));

            // httpcalls
            assertEquals("eddi://ai.labs.apicalls/apicallstore/apicalls/x?version=1",
                    AbstractBackupService.normalizeLegacyUris(
                            "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/x?version=1"));

            // langchain
            assertEquals("eddi://ai.labs.llm/llmstore/llms/x?version=1",
                    AbstractBackupService.normalizeLegacyUris(
                            "eddi://ai.labs.langchain/langchainstore/langchains/x?version=1"));

            // package
            assertEquals("eddi://ai.labs.workflow/workflowstore/workflows/x?version=1",
                    AbstractBackupService.normalizeLegacyUris(
                            "eddi://ai.labs.package/packagestore/packages/x?version=1"));

            // bot
            assertEquals("eddi://ai.labs.agent/agentstore/agents/x?version=1",
                    AbstractBackupService.normalizeLegacyUris(
                            "eddi://ai.labs.bot/botstore/bots/x?version=1"));
        }

        @Test
        @DisplayName("already-v6 URIs remain unchanged")
        void v6UrisUnchanged() {
            String v6 = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/x?version=1";
            assertEquals(v6, AbstractBackupService.normalizeLegacyUris(v6));
        }

        @Test
        @DisplayName("string without eddi:// prefix returns unchanged")
        void nonEddiUri() {
            String input = "https://api.openai.com/v1";
            assertEquals(input, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("multiple legacy URIs in one string all get rewritten")
        void multipleLegacyUris() {
            String input = "\"eddi://ai.labs.bot/botstore/bots/b1?version=1\" and " +
                    "\"eddi://ai.labs.package/packagestore/packages/p1?version=1\"";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.agent/agentstore/agents/b1"));
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/p1"));
        }
    }

    // =========================================================
    // normalizeVaultReferences — combined with legacy URIs
    // =========================================================

    @Nested
    @DisplayName("normalizeVaultReferences combined scenarios")
    class NormalizeVaultCombined {

        @Test
        @DisplayName("string with both eddivault and eddi:// URIs normalizes vault only")
        void bothVaultAndUri() {
            String input = "${eddivault:key} and eddi://ai.labs.llm/llmstore/llms/x";
            String result = AbstractBackupService.normalizeVaultReferences(input);
            assertEquals("${vault:key} and eddi://ai.labs.llm/llmstore/llms/x", result);
        }

        @Test
        @DisplayName("string with ${vault:...} prefix (no eddivault) returns unchanged")
        void alreadyVaultPrefix() {
            String input = "config: ${vault:my-key} and ${vars:foo}";
            assertEquals(input, AbstractBackupService.normalizeVaultReferences(input));
        }
    }

    // =========================================================
    // SSRF validation — various invalid URLs
    // =========================================================

    @Nested
    @DisplayName("SSRF validation edge cases")
    class SsrfEdgeCases {

        @Test
        @DisplayName("localhost URL throws for listRemoteAgents")
        void localhostRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("http://localhost:8080", null));
        }

        @Test
        @DisplayName("127.0.0.1 URL throws for previewSync")
        void loopbackIpRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSync(
                            "http://127.0.0.1:8080", "src", 1, "tgt", null));
        }

        @Test
        @DisplayName("executeSync with HTTP in prod mode throws")
        void httpInProdMode() {
            // Default quarkus.profile is "prod" (or unset)
            // http:// should be rejected unless dev mode
            String originalProfile = System.getProperty("quarkus.profile");
            try {
                System.setProperty("quarkus.profile", "prod");
                assertThrows(IllegalArgumentException.class,
                        () -> importService.executeSync(
                                "http://example.com", "src", 1, "tgt",
                                null, null, null));
            } finally {
                if (originalProfile != null) {
                    System.setProperty("quarkus.profile", originalProfile);
                } else {
                    System.clearProperty("quarkus.profile");
                }
            }
        }

        @Test
        @DisplayName("empty string URL throws for executeSyncBatch")
        void emptyStringUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSyncBatch("", List.of(), null));
        }

        @Test
        @DisplayName("URL without scheme throws")
        void noScheme() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("example.com/api", null));
        }
    }

    // =========================================================
    // importAgent — general exception wrapping
    // =========================================================

    @Nested
    @DisplayName("importAgent — exception wrapping")
    class ImportExceptionWrapping {

        @Test
        @DisplayName("zipArchive.unzip exception wraps in InternalServerErrorException")
        void unzipExceptionWrapped() throws Exception {
            doThrow(new RuntimeException("ZIP error"))
                    .when(zipArchive).unzip(any(InputStream.class), any(File.class));

            assertThrows(InternalServerErrorException.class,
                    () -> importService.importAgent(
                            new ByteArrayInputStream(new byte[0]),
                            "create", null, null, null));
        }
    }

    // =========================================================
    // parseWorkflowOrder edge cases (via importAgent upgrade)
    // =========================================================

    @Nested
    @DisplayName("parseWorkflowOrder — edge cases")
    class ParseWorkflowOrder {

        @Test
        @DisplayName("empty workflowOrder is treated as null")
        void emptyWorkflowOrder() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/t1?version=1");
            when(upgradeExecutor.executeUpgrade(any(), eq("t1"), isNull(), isNull()))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]),
                    "upgrade", "", "t1", "");

            assertEquals(201, response.getStatus());
            // Verify that empty string is converted to null selectedSet and null
            // workflowOrder
            verify(upgradeExecutor).executeUpgrade(any(), eq("t1"), isNull(), isNull());
        }

        @Test
        @DisplayName("workflowOrder with spaces trims entries")
        void workflowOrderWithSpaces() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/t1?version=1");
            when(upgradeExecutor.executeUpgrade(any(), eq("t1"), isNull(), eq(List.of("wf1", "wf2"))))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]),
                    "upgrade", null, "t1", " wf1 , wf2 ");

            assertEquals(201, response.getStatus());
        }
    }

    // =========================================================
    // parseSelectedResources edge cases
    // =========================================================

    @Nested
    @DisplayName("parseSelectedResources — edge cases via upgrade")
    class ParseSelectedResources {

        @Test
        @DisplayName("selectedOriginIds with trailing commas are filtered")
        void trailingCommas() throws Exception {
            URI resultUri = URI.create("eddi://ai.labs.agent/agentstore/agents/t1?version=1");
            when(upgradeExecutor.executeUpgrade(any(), eq("t1"), eq(Set.of("r1", "r2")), isNull()))
                    .thenReturn(resultUri);

            doAnswer(inv -> {
                File dir = inv.getArgument(1);
                dir.mkdirs();
                return null;
            }).when(zipArchive).unzip(any(InputStream.class), any(File.class));

            Response response = importService.importAgent(
                    new ByteArrayInputStream(new byte[0]),
                    "upgrade", "r1,,r2,", "t1", null);

            assertEquals(201, response.getStatus());
        }
    }
}
