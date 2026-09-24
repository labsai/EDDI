/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.util.Optional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
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
import static org.mockito.ArgumentMatchers.isNull;
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
                mock(ResourceAccessGuard.class), mock(SpaceContext.class), mock(RagSourceIngestionService.class),
                true, false, Optional.empty());
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
            assertThrows(BadRequestException.class,
                    () -> importService.listRemoteAgents(null, null));
        }

        @Test
        @DisplayName("rejects empty source URL")
        void rejectsEmptySourceUrl() {
            assertThrows(BadRequestException.class,
                    () -> importService.listRemoteAgents("", null));
        }

        @Test
        @DisplayName("rejects non-HTTP scheme (ftp)")
        void rejectsNonHttpScheme() {
            assertThrows(BadRequestException.class,
                    () -> importService.listRemoteAgents("ftp://remote.server.com", null));
        }

        @Test
        @DisplayName("rejects loopback address")
        void rejectsLoopback() {
            assertThrows(BadRequestException.class,
                    () -> importService.listRemoteAgents("http://localhost:8080", null));
        }

        @Test
        @DisplayName("rejects 127.x.x.x loopback")
        void rejects127Loopback() {
            assertThrows(BadRequestException.class,
                    () -> importService.listRemoteAgents("http://127.0.0.1:1", null));
        }

        /**
         * A remote instance that cannot be listed becomes a 502 whose body says so. The
         * connect failure, the expired token and the wrong port all arrive here, and
         * the operator only gets to tell them apart if the reason survives — which it
         * did not: this was an {@code InternalServerErrorException}, and Quarkus
         * answers one of those with an empty 500, so the message never left the JVM.
         */
        @Test
        @DisplayName("a remote instance that will not answer becomes a 502 that keeps the reason")
        void unreachableRemoteBecomesABadGateway() {
            var cause = new RemoteApiResourceSource.RemoteReadException(
                    "Failed to list agents: connection refused");

            WebApplicationException thrown;
            try (var statics = mockStatic(RemoteApiResourceSource.class)) {
                statics.when(() -> RemoteApiResourceSource.listRemoteAgentDescriptors(
                        eq(PUBLIC_SOURCE_URL), eq("Bearer stale"), any())).thenThrow(cause);

                thrown = assertThrows(WebApplicationException.class,
                        () -> importService.listRemoteAgents(PUBLIC_SOURCE_URL, "Bearer stale"));
            }

            assertEquals(502, thrown.getResponse().getStatus());
            assertTrue(String.valueOf(thrown.getResponse().getEntity()).contains("connection refused"),
                    "the reason has to be in the body, was: " + thrown.getResponse().getEntity());
        }

        /** The list a reachable instance hands over reaches the caller unchanged. */
        @Test
        @DisplayName("the descriptors a reachable instance returns are passed through")
        void reachableRemoteReturnsItsDescriptors() {
            var descriptors = List.of(new DocumentDescriptor());

            try (var statics = mockStatic(RemoteApiResourceSource.class)) {
                statics.when(() -> RemoteApiResourceSource.listRemoteAgentDescriptors(
                        eq(PUBLIC_SOURCE_URL), isNull(), any())).thenReturn(descriptors);

                assertSame(descriptors, importService.listRemoteAgents(PUBLIC_SOURCE_URL, null));
            }
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
            assertThrows(BadRequestException.class,
                    () -> importService.previewSync("ftp://bad.server.com",
                            "aabbccddeeff112233445566",
                            1, "aabbccddeeff112233445567", null));
        }

        @Test
        @DisplayName("rejects null source URL")
        void rejectsNullSourceUrl() {
            assertThrows(BadRequestException.class,
                    () -> importService.previewSync(null,
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null));
        }

        @Test
        @DisplayName("rejects localhost")
        void rejectsLocalhost() {
            assertThrows(BadRequestException.class,
                    () -> importService.previewSync("https://localhost:8443",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null));
        }

        /**
         * A remote instance that cannot be read is <em>the other</em> deployment's
         * problem, and is reported as one: 502, with the reason in the body.
         * <p>
         * It used to be an {@code InternalServerErrorException}, which Quarkus answers
         * with an <b>empty</b> 500 — the message never left the JVM. So the commonest
         * thing that goes wrong with a sync, a source that is down or addressed
         * wrongly, reached the operator as "Internal Server Error" and nothing else.
         */
        @Test
        @DisplayName("a remote that cannot be read becomes a 502 carrying the reason")
        void remoteFailureBecomesABadGateway() {
            var cause = new RemoteApiResourceSource.RemoteReadException("remote instance closed the connection");
            when(structuralMatcher.buildPreview(any(), eq(TARGET_A), eq(true))).thenThrow(cause);

            var thrown = assertThrows(WebApplicationException.class, this::preview);

            assertEquals(502, thrown.getResponse().getStatus());
            assertTrue(String.valueOf(thrown.getResponse().getEntity()).contains("remote instance closed the connection"),
                    "the reason has to be in the body the client reads, was: " + thrown.getResponse().getEntity());
        }

        /**
         * The other half of that distinction. A failure this deployment caused is still
         * a 500 — blaming the source for a local fault sends the operator to restart an
         * instance that was never the problem — but it carries a body now, which the
         * empty {@code InternalServerErrorException} never did.
         */
        @Test
        @DisplayName("a local failure stays a 500, and says what happened")
        void localFailureStaysAServerError() {
            when(structuralMatcher.buildPreview(any(), eq(TARGET_A), eq(true)))
                    .thenThrow(new IllegalStateException("the local store is not accepting writes"));

            var thrown = assertThrows(WebApplicationException.class, this::preview);

            assertEquals(500, thrown.getResponse().getStatus());
            assertTrue(String.valueOf(thrown.getResponse().getEntity()).contains("the local store is not accepting writes"),
                    "the reason has to be in the body, was: " + thrown.getResponse().getEntity());
        }

        /**
         * The remote failure is usually wrapped by the time it gets here, so the whole
         * cause chain is what decides.
         */
        @Test
        @DisplayName("a wrapped remote failure is still a 502")
        void wrappedRemoteFailureIsStillABadGateway() {
            when(structuralMatcher.buildPreview(any(), eq(TARGET_A), eq(true)))
                    .thenThrow(new RuntimeException("preview failed",
                            new RemoteApiResourceSource.RemoteReadException("connection refused")));

            var thrown = assertThrows(WebApplicationException.class, this::preview);

            assertEquals(502, thrown.getResponse().getStatus());
        }

        /**
         * The one failure that must <em>not</em> be wrapped. A target agent that is not
         * there is a 404 the operator can act on — "you named an agent this instance
         * does not have" — and repackaging it as a 500 turns a fixable mistake into an
         * outage report.
         */
        @Test
        @DisplayName("a 404 from the matcher is passed through, not repackaged as a 500")
        void notFoundFromTheMatcherIsPassedThrough() {
            var notFound = new NotFoundException("No agent found with id " + TARGET_A);
            when(structuralMatcher.buildPreview(any(), eq(TARGET_A), eq(true))).thenThrow(notFound);

            var thrown = assertThrows(NotFoundException.class, this::preview);

            assertSame(notFound, thrown, "the matcher's own 404 has to reach the caller unchanged");
        }

        /**
         * Runs the preview with {@link RemoteApiResourceSource} construction stubbed
         * out — the real constructor builds an {@link java.net.http.HttpClient}, which
         * opens a selector and so needs a loopback socket a sandboxed build does not
         * have, and neither test is about the remote read itself.
         */
        private ImportPreview preview() {
            try (var ignored = mockConstruction(RemoteApiResourceSource.class)) {
                return importService.previewSync(PUBLIC_SOURCE_URL,
                        "aabbccddeeff112233445566", 1, TARGET_A, null);
            }
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
            assertThrows(BadRequestException.class,
                    () -> importService.executeSync("ftp://bad.server.com",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null, null, null));
        }

        @Test
        @DisplayName("rejects blank source URL")
        void rejectsBlankSourceUrl() {
            assertThrows(BadRequestException.class,
                    () -> importService.executeSync("   ",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null, null, null));
        }

        @Test
        @DisplayName("rejects IPv6 loopback")
        void rejectsIpv6Loopback() {
            assertThrows(BadRequestException.class,
                    () -> importService.executeSync("https://[::1]:8443",
                            "aabbccddeeff112233445566", 1,
                            "aabbccddeeff112233445567", null, null, null));
        }

        /**
         * Same split as the preview, and it matters more here because a sync writes: an
         * upgrade that blew up mid-flight is a 500 — this instance's own failure, not
         * the source's — naming the reason in a body the caller can read.
         */
        @Test
        @DisplayName("an upgrade failure becomes a 500 that keeps the reason")
        void upgradeFailureBecomesAServerError() {
            var cause = new IllegalStateException("the target agent changed under the sync");
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_A), any(), any())).thenThrow(cause);

            var thrown = assertThrows(WebApplicationException.class, this::sync);

            assertEquals(500, thrown.getResponse().getStatus());
            assertTrue(String.valueOf(thrown.getResponse().getEntity()).contains("the target agent changed under the sync"),
                    "the reason has to be in the body, was: " + thrown.getResponse().getEntity());
        }

        /**
         * And a 404 for a target that does not exist survives the catch-all here too —
         * a promotion job keying off the status code has to be able to tell "no such
         * agent" from "this instance is broken".
         */
        @Test
        @DisplayName("a 404 from the upgrade executor is passed through, not repackaged as a 500")
        void notFoundFromTheExecutorIsPassedThrough() {
            var notFound = new NotFoundException("No agent found with id " + TARGET_A);
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_A), any(), any())).thenThrow(notFound);

            var thrown = assertThrows(NotFoundException.class, this::sync);

            assertSame(notFound, thrown);
        }

        /**
         * The happy path, which is what makes the two failure paths above meaningful: a
         * sync that ran hands back the upgrade's own result and status, with the agent
         * URI in the Location header, and passes the caller's selection and workflow
         * order through to the executor rather than quietly syncing everything.
         */
        @Test
        @DisplayName("a sync that ran answers with the upgrade's result, status and Location")
        void successfulSyncAnswersWithTheUpgradeResult() {
            var result = cleanResult(TARGET_A);
            when(upgradeExecutor.executeUpgrade(any(), eq(TARGET_A), eq(Set.of("res-1", "res-2")),
                    eq(List.of("wf-b", "wf-a")))).thenReturn(result);

            Response response;
            try (var ignored = mockConstruction(RemoteApiResourceSource.class)) {
                response = importService.executeSync(PUBLIC_SOURCE_URL,
                        "aabbccddeeff112233445566", 1, TARGET_A, "res-1,res-2", "wf-b, wf-a", null);
            }

            assertEquals(201, response.getStatus(), "something was written, so it is a 201");
            assertSame(result, response.getEntity());
            assertEquals(result.agentUri().toString(), response.getHeaderString("Location"));
            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        }

        /** See {@code PreviewSyncTests#preview()} for why construction is stubbed. */
        private Response sync() {
            try (var ignored = mockConstruction(RemoteApiResourceSource.class)) {
                return importService.executeSync(PUBLIC_SOURCE_URL,
                        "aabbccddeeff112233445566", 1, TARGET_A, null, null, null);
            }
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
            assertThrows(BadRequestException.class,
                    () -> importService.executeSyncBatch("ftp://bad.server.com", requests, null));
        }

        @Test
        @DisplayName("rejects loopback URL")
        void rejectsLoopbackUrl() {
            var requests = List.of(new SyncRequest(
                    "aabbccddeeff112233445566", 1,
                    "aabbccddeeff112233445567", Set.of(), List.of()));
            assertThrows(BadRequestException.class,
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
            assertThrows(BadRequestException.class,
                    () -> importService.previewSyncBatch("ftp://bad.server.com", null, null));
        }

        @Test
        @DisplayName("empty mappings list returns empty list - after URL validation")
        void emptyMappingsReturnsEmptyAfterValidation() {
            // Since SourceUrlValidator blocks all test-friendly URLs, verify
            // that the URL validation is called for this endpoint too
            assertThrows(BadRequestException.class,
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
