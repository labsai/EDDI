/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.configs.ingestion.model.WebSourceConfig;
import ai.labs.eddi.modules.ingestion.RagIngestionService;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class RagIngestionIT extends BaseIntegrationIT {

    private static GenericContainer<?> nginx;
    private static ResourceId ragConfigId;
    private static String ragConfigUri;
    private static boolean initialized;
    private static final String RAG_PATH = "/ragstore/rags/";

    @Inject
    RagIngestionService ingestionService;

    @BeforeAll
    static void beforeAll() {
        UrlValidationUtils.setValidationEnabled(false);
        nginx = new GenericContainer<>("nginx:alpine")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("tests/ingestion/index.html"),
                        "/usr/share/nginx/html/index.html")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("tests/ingestion/about.html"),
                        "/usr/share/nginx/html/about.html")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("tests/ingestion/docs/guide.html"),
                        "/usr/share/nginx/html/docs/guide.html")
                .withExposedPorts(80)
                .waitingFor(Wait.forHttp("/").withStartupTimeout(Duration.ofMinutes(2)));
        nginx.start();
    }

    @AfterAll
    static void afterAll() {
        try {
            if (ragConfigId != null)
                deleteResourceQuietly(RAG_PATH, ragConfigId.id(), ragConfigId.version());
        } finally {
            try {
                if (nginx != null)
                    nginx.stop();
            } finally {
                UrlValidationUtils.setValidationEnabled(true);
            }
        }
    }

    private static synchronized void ensureRagConfig() {
        if (initialized)
            return;
        initialized = true;
        String response = given()
                .body("""
                        {"name":"test-ingestion-kb","embeddingProvider":"openai",
                         "embeddingParameters":{"apiKey":"test-key","model":"text-embedding-3-small"},
                         "storeType":"in-memory","chunkSize":512,"chunkOverlap":64}
                        """)
                .contentType(ContentType.JSON)
                .post(RAG_PATH)
                .then().statusCode(201).extract().header("location");
        ragConfigId = extractResourceId(response);
        ragConfigUri = "eddi://ai.labs.rag" + RAG_PATH + ragConfigId.id() + "?version=" + ragConfigId.version();
    }

    private String nginxUrl(String path) {
        return "http://" + nginx.getHost() + ":" + nginx.getMappedPort(80) + path;
    }

    private RagIngestionSource createSource(String sourceId, String startUrl,
                                            WebSourceConfig.Scope scope) {
        ensureRagConfig();
        var cfg = new WebSourceConfig(startUrl, scope,
                new WebSourceConfig.CrawlSettings(0, 15, "EDDI-Test/1.0"));
        return new RagIngestionSource(sourceId, "Test", "web", cfg, ragConfigUri,
                new RagIngestionSource.IngestionSettings(
                        "recursive", 512, 64, true, 100000),
                null);
    }

    private static WebSourceConfig.Scope scope(int maxDepth, int maxPages) {
        return new WebSourceConfig.Scope(true, "/", maxDepth, maxPages, List.of());
    }

    @Test
    void shouldIngestSinglePage() throws Exception {
        String sourceId = "single-" + UUID.randomUUID().toString().substring(0, 8);
        var source = createSource(sourceId, nginxUrl("/index.html"), scope(1, 1));

        RagIngestionService.IngestionResult result = ingestionService.ingest(sourceId, source);

        assertTrue(result.isSuccess());
        assertEquals(1, result.documentsProcessed());
        assertEquals(1, result.documentsNew());
        assertTrue(result.chunksStored() > 0, "Should have stored chunks");
        assertEquals(0, result.errors());
    }

    @Test
    void shouldCrawlMultiplePages() throws Exception {
        String sourceId = "multi-" + UUID.randomUUID().toString().substring(0, 8);
        var source = createSource(sourceId, nginxUrl("/index.html"), scope(3, 200));

        RagIngestionService.IngestionResult result = ingestionService.ingest(sourceId, source);

        assertTrue(result.isSuccess());
        assertTrue(result.documentsProcessed() >= 3,
                "Should have crawled at least 3 pages (index, about, guide), got: " + result.documentsProcessed());
        // /errors/broken.html does not exist and will produce a 404 error
        assertTrue(result.errors() <= 1, "At most 1 error for broken.html, got: " + result.errors());
    }

    @Test
    void shouldDedupUnchangedContent() throws Exception {
        String sourceId = "dedup-" + UUID.randomUUID().toString().substring(0, 8);
        var source = createSource(sourceId, nginxUrl("/index.html"), scope(1, 1));

        RagIngestionService.IngestionResult first = ingestionService.ingest(sourceId, source);
        assertTrue(first.isSuccess());
        assertEquals(1, first.documentsNew());

        RagIngestionService.IngestionResult second = ingestionService.ingest(sourceId, source);

        assertTrue(second.isSuccess());
        assertEquals(0, second.documentsNew(), "No new docs on re-ingest of unchanged content");
        assertEquals(1, second.documentsUnchanged(), "Should detect 1 unchanged document");
        assertEquals(0, second.chunksStored(), "No chunks should be stored for unchanged content");
    }

    @Test
    void shouldDetectContentChanges() throws Exception {
        String sourceId = "change-" + UUID.randomUUID().toString().substring(0, 8);
        var source = createSource(sourceId, nginxUrl("/index.html"), scope(1, 1));

        RagIngestionService.IngestionResult first = ingestionService.ingest(sourceId, source);
        assertTrue(first.isSuccess());
        assertEquals(1, first.documentsNew());

        nginx.copyFileToContainer(
                MountableFile.forClasspathResource("tests/ingestion/index_updated.html"),
                "/usr/share/nginx/html/index.html");

        RagIngestionService.IngestionResult second = ingestionService.ingest(sourceId, source);

        assertTrue(second.isSuccess());
        assertEquals(1, second.documentsNew(), "Changed content should be re-ingested");
        assertEquals(0, second.documentsUnchanged());

        nginx.copyFileToContainer(
                MountableFile.forClasspathResource("tests/ingestion/index.html"),
                "/usr/share/nginx/html/index.html");
    }

    @Test
    void shouldDetectStaleDocuments() throws Exception {
        String sourceId = "stale-" + UUID.randomUUID().toString().substring(0, 8);
        var source = createSource(sourceId, nginxUrl("/index.html"), scope(3, 200));

        RagIngestionService.IngestionResult first = ingestionService.ingest(sourceId, source);
        assertTrue(first.isSuccess());
        assertTrue(first.documentsProcessed() >= 3);

        nginx.execInContainer("rm", "/usr/share/nginx/html/about.html");

        RagIngestionService.IngestionResult second = ingestionService.ingest(sourceId, source);

        assertTrue(second.documentsStale() >= 1, "Should mark at least 1 stale document");
        assertTrue(second.errors() > 0, "Should report error for removed page");

        nginx.copyFileToContainer(
                MountableFile.forClasspathResource("tests/ingestion/about.html"),
                "/usr/share/nginx/html/about.html");
    }

    @Test
    void shouldHandle404StartUrl() throws Exception {
        String sourceId = "err404-" + UUID.randomUUID().toString().substring(0, 8);
        var source = createSource(sourceId, nginxUrl("/nonexistent.html"), scope(1, 1));

        RagIngestionService.IngestionResult result = ingestionService.ingest(sourceId, source);

        assertEquals(0, result.documentsProcessed());
        assertTrue(result.errors() > 0, "Should report error for 404 start URL");
    }

    @Test
    void shouldRespectPathPrefixScope() throws Exception {
        String sourceId = "prefix-" + UUID.randomUUID().toString().substring(0, 8);
        var scope = new WebSourceConfig.Scope(true, "/docs/", 3, 200, List.of());
        var source = createSource(sourceId, nginxUrl("/docs/guide.html"), scope);

        RagIngestionService.IngestionResult result = ingestionService.ingest(sourceId, source);

        assertTrue(result.isSuccess());
        assertEquals(1, result.documentsProcessed(), "Only /docs/ pages should be crawled");
        assertEquals(0, result.errors());
    }
}
