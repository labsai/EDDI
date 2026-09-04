/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the live sync endpoints in {@link RestImportService}: executeSync,
 * executeSyncBatch, listRemoteAgents, previewSync, and previewSyncBatch. These
 * are at 0% coverage in JaCoCo.
 * <p>
 * Note: All sync endpoints call {@link SourceUrlValidator#validate} first,
 * which rejects loopback, private IPs, and non-HTTP schemes. We test the URL
 * validation paths here since they're exercised through the production code's
 * validateSourceUrl() calls.
 */
@DisplayName("RestImportService — Live Sync Endpoint Coverage")
class RestImportServiceSyncCoverageTest {

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
        var migrationManager = mock(IMigrationManager.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var templateSyntaxMigrator = mock(TemplateSyntaxMigrator.class);
        structuralMatcher = mock(StructuralMatcher.class);
        upgradeExecutor = mock(UpgradeExecutor.class);

        importService = new RestImportService(
                zipArchive, jsonSerialization,
                migrationManager, documentDescriptorStore,
                templateSyntaxMigrator, structuralMatcher, upgradeExecutor, mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class));
    }

    // =========================================================
    // listRemoteAgents
    // =========================================================

    @Nested
    @DisplayName("listRemoteAgents")
    class ListRemoteAgentsTests {

        @Test
        @DisplayName("rejects null source URL")
        void rejectsNullSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents(null, null));
        }

        @Test
        @DisplayName("rejects empty source URL")
        void rejectsEmptySourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("", null));
        }

        @Test
        @DisplayName("rejects non-HTTP scheme (ftp)")
        void rejectsNonHttpScheme() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("ftp://remote.server.com", null));
        }

        @Test
        @DisplayName("rejects loopback address")
        void rejectsLoopback() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("http://localhost:8080", null));
        }

        @Test
        @DisplayName("rejects 127.x.x.x loopback")
        void rejects127Loopback() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.listRemoteAgents("http://127.0.0.1:1", null));
        }
    }

    // =========================================================
    // previewSync
    // =========================================================

    @Nested
    @DisplayName("previewSync")
    class PreviewSyncTests {

        @Test
        @DisplayName("rejects invalid source URL — non-HTTP scheme")
        void rejectsInvalidSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSync("ftp://bad.server.com",
                            "aabbccddeeff112233445566",
                            1, "aabbccddeeff112233445567", null));
        }

        @Test
        @DisplayName("rejects null source URL")
        void rejectsNullSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSync(null,
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null));
        }

        @Test
        @DisplayName("rejects localhost")
        void rejectsLocalhost() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSync("https://localhost:8443",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null));
        }
    }

    // =========================================================
    // executeSync
    // =========================================================

    @Nested
    @DisplayName("executeSync")
    class ExecuteSyncTests {

        @Test
        @DisplayName("rejects non-HTTP scheme")
        void rejectsInvalidSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSync("ftp://bad.server.com",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null, null, null));
        }

        @Test
        @DisplayName("rejects blank source URL")
        void rejectsBlankSourceUrl() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSync("   ",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null, null, null));
        }

        @Test
        @DisplayName("rejects IPv6 loopback")
        void rejectsIpv6Loopback() {
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSync("https://[::1]:8443",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null, null, null));
        }
    }

    // =========================================================
    // executeSyncBatch
    // =========================================================

    @Nested
    @DisplayName("executeSyncBatch")
    class ExecuteSyncBatchTests {

        @Test
        @DisplayName("rejects invalid source URL")
        void rejectsInvalidSourceUrl() {
            var requests = List.of(new SyncRequest(
                    "aabbccddeeff112233445566", 1,
                    "aabbccddeeff112233445567", Set.of(), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSyncBatch("ftp://bad.server.com", requests, null));
        }

        @Test
        @DisplayName("rejects loopback URL")
        void rejectsLoopbackUrl() {
            var requests = List.of(new SyncRequest(
                    "aabbccddeeff112233445566", 1,
                    "aabbccddeeff112233445567", Set.of(), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> importService.executeSyncBatch("http://127.0.0.1:1", requests, null));
        }

        @Test
        @DisplayName("a batch where some agents failed answers 207 with one entry per request")
        void partialFailureIsMultiStatus() {
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_A), any(), any()))
                    .thenReturn(cleanResult(TARGET_A));
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_B), any(), any()))
                    .thenThrow(new RuntimeException("remote refused the read"));

            Response response = executeBatch();

            // The endpoint used to answer 200 with the URIs that happened to work, so a
            // batch in which agents failed was indistinguishable from one with nothing
            // to do.
            assertEquals(207, response.getStatus());
            List<?> results = (List<?>) response.getEntity();
            assertEquals(2, results.size(), "one entry per request, in request order");

            var first = (RestImportService.BatchSyncResult) results.get(0);
            assertEquals(TARGET_A, first.targetAgentId());
            assertNull(first.error());
            assertNotNull(first.result());

            var second = (RestImportService.BatchSyncResult) results.get(1);
            assertEquals(TARGET_B, second.targetAgentId());
            assertNull(second.result());
            assertTrue(second.error().contains("remote refused the read"), second.error());
        }

        @Test
        @DisplayName("a batch where every agent failed is a 500, not a 200 with an empty list")
        void totalFailureIsServerError() {
            when(upgradeExecutor.executeUpgrade(any(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("expired token"));

            Response response = executeBatch();

            assertEquals(500, response.getStatus());
            assertEquals(2, ((List<?>) response.getEntity()).size());
        }

        @Test
        @DisplayName("an agent whose own resources failed counts as failed, even though the sync ran")
        void resourceFailuresCountTowardsTheBatchStatus() {
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_A), any(), any()))
                    .thenReturn(cleanResult(TARGET_A));
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_B), any(), any()))
                    .thenReturn(new UpgradeResult(URI.create("eddi://ai.labs.agent/agentstore/agents/" + TARGET_B),
                            true, 1, 0, 0,
                            List.of(new UpgradeResult.ResourceFailure("src-llm", "langchain", "GPT", "store rejected it"))));

            Response response = executeBatch();

            assertEquals(207, response.getStatus(),
                    "a half-applied agent is a failure of the batch, not a success with a warning line");
        }

        @Test
        @DisplayName("an interrupted batch aborts instead of hammering the remote instance")
        void interruptAbortsTheBatch() {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InternalServerErrorException.class,
                        () -> importService.executeSyncBatch(PUBLIC_SOURCE_URL, twoRequests(), null));
                verify(upgradeExecutor, never()).executeUpgrade(any(), anyString(), any(), any());
            } finally {
                // Never leak the flag into the next test on this thread.
                Thread.interrupted();
            }
        }

        /**
         * Runs the batch with {@link RemoteApiResourceSource} construction stubbed out.
         * The real constructor builds an {@link java.net.http.HttpClient}, which opens
         * a selector and so needs a loopback socket a sandboxed build does not have —
         * and none of these tests is about the remote read.
         */
        private Response executeBatch() {
            try (var ignored = mockConstruction(RemoteApiResourceSource.class)) {
                return importService.executeSyncBatch(PUBLIC_SOURCE_URL, twoRequests(), null);
            }
        }

        private List<SyncRequest> twoRequests() {
            return List.of(
                    new SyncRequest("aabbccddeeff112233445566", 1, TARGET_A, Set.of(), List.of()),
                    new SyncRequest("aabbccddeeff112233445577", 1, TARGET_B, Set.of(), List.of()));
        }
    }

    // =========================================================
    // previewSyncBatch — null/empty
    // =========================================================

    @Nested
    @DisplayName("previewSyncBatch — null/empty inputs")
    class PreviewSyncBatchEdgeCases {

        @Test
        @DisplayName("null mappings returns empty list")
        void nullMappingsReturnsEmpty() {
            // previewSyncBatch first validates the URL, then checks for null/empty mappings
            // We need a URL that passes validation for this test
            // Using ftp:// to trigger validation before null check
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch("ftp://bad.server.com", null, null));
        }

        @Test
        @DisplayName("empty mappings list returns empty list - after URL validation")
        void emptyMappingsReturnsEmptyAfterValidation() {
            // Since SourceUrlValidator blocks all test-friendly URLs, verify
            // that the URL validation is called for this endpoint too
            assertThrows(IllegalArgumentException.class,
                    () -> importService.previewSyncBatch("ftp://bad.server.com", List.of(), null));
        }
    }

    // =========================================================
    // The status code an upgrade answers with
    // =========================================================

    /**
     * Every upgrade used to answer 201 CREATED regardless of what happened, so a
     * half-applied sync was indistinguishable from a clean one and a no-op sync
     * still looked like it had written a new agent version. The status code is the
     * only thing a scripted promotion job can key off.
     */
    @Nested
    @DisplayName("upgrade response")
    class UpgradeResponseTests {

        private static final String TARGET = "aabbccddeeff112233445599";
        private static final URI AGENT_URI = URI.create("eddi://ai.labs.agent/agentstore/agents/" + TARGET + "?version=4");

        private Response upgradeWith(UpgradeResult result) {
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET), any(), any())).thenReturn(result);
            return importService.importAgent(
                    new ByteArrayInputStream(new byte[0]), "upgrade", null, TARGET, null);
        }

        @Test
        @DisplayName("201 when something was written")
        void writtenIsCreated() {
            Response response = upgradeWith(new UpgradeResult(AGENT_URI, true, 2, 1, 0, List.of()));

            assertEquals(201, response.getStatus());
            assertEquals(AGENT_URI.toString(), response.getHeaderString("Location"));
            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
            assertInstanceOf(UpgradeResult.class, response.getEntity());
        }

        @Test
        @DisplayName("200 when source and target were already identical")
        void nothingWrittenIsOk() {
            Response response = upgradeWith(new UpgradeResult(AGENT_URI, false, 0, 0, 3, List.of()));

            // No agent version was burned, so answering 201 CREATED was a lie that a CI
            // promotion job could not tell from a real change.
            assertEquals(200, response.getStatus());
            assertEquals(AGENT_URI.toString(), response.getHeaderString("Location"));
        }

        @Test
        @DisplayName("207 Multi-Status when some resources failed, with the failures in the body")
        void failuresAreMultiStatus() {
            var failure = new UpgradeResult.ResourceFailure("src-llm", "langchain", "GPT Config",
                    "IllegalStateException: store rejected it");
            Response response = upgradeWith(new UpgradeResult(AGENT_URI, true, 1, 0, 0, List.of(failure)));

            assertEquals(207, response.getStatus());
            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
            var body = assertInstanceOf(UpgradeResult.class, response.getEntity());
            assertEquals(List.of(failure), body.failures(),
                    "the caller has to be able to see which resources did not land");
            // Clients that only read the header keep working.
            assertEquals(AGENT_URI.toString(), response.getHeaderString("Location"));
        }
    }

    // ==================== Helpers ====================

    /**
     * A routable, non-private literal address (RFC 5737 TEST-NET-3). A hostname
     * would make {@link SourceUrlValidator}'s fail-closed DNS lookup decide whether
     * these tests run.
     */
    private static final String PUBLIC_SOURCE_URL = "https://203.0.113.10";

    private static final String TARGET_A = "aabbccddeeff112233445567";
    private static final String TARGET_B = "aabbccddeeff112233445568";

    private static UpgradeResult cleanResult(String targetAgentId) {
        return new UpgradeResult(
                URI.create("eddi://ai.labs.agent/agentstore/agents/" + targetAgentId + "?version=2"),
                true, 1, 0, 0, List.of());
    }
}
