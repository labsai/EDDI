/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.ForgetOutcome;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.crawl.FakeSite;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.HtmlDocumentExtractor;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Document replacement against a real Chroma server.
 *
 * <p>
 * Replacing a changed document, tombstoning a removed one and forgetting a
 * deleted upload all end in {@code EmbeddingStore.removeAll(Filter)} with a
 * compound metadata filter — document id and owning source, and for a re-ingest
 * also "not this run". A store whose driver cannot translate that filter makes
 * the run report {@code replaceUnsupported} and leaves the stale chunks
 * retrievable, which is the behaviour {@code docs/rag.md} documents per store.
 * This test establishes it for Chroma rather than assuming it.
 *
 * <p>
 * Everything between the crawl and the Chroma server is production code: the
 * real {@link IngestionPipeline}, and the real {@link EmbeddingStoreFactory},
 * so the store is built by {@code buildChroma} with its default API version
 * (V2) and its bounded HTTP client. Only the embedding model is a deterministic
 * offline stand-in. The image is the one {@code docker-compose.chroma.yml}
 * ships, so this verifies the server EDDI is deployed beside.
 */
class ChromaReplacementIT {

    /** Keep in step with docker-compose.chroma.yml. */
    private static final String CHROMA_IMAGE = "chromadb/chroma:1.1.0";
    private static final int CHROMA_PORT = 8000;
    private static final String SITE = "https://example.com";

    static final GenericContainer<?> CHROMA = new GenericContainer<>(CHROMA_IMAGE)
            .withExposedPorts(CHROMA_PORT)
            .withEnv("ANONYMIZED_TELEMETRY", "false")
            .waitingFor(Wait.forHttp("/api/v2/heartbeat").forPort(CHROMA_PORT).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private final EmbeddingModel embeddingModel = new BagOfWordsEmbeddingModel();
    private final InMemoryIngestedFileStore fileStore = new InMemoryIngestedFileStore();
    private InMemoryIngestionStateStore stateStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    /** A fresh knowledge base, and so a fresh Chroma collection, per test. */
    private String ragConfigId;

    @BeforeAll
    static void startChroma() {
        CHROMA.start();
    }

    @AfterAll
    static void stopChroma() {
        CHROMA.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new InMemoryIngestionStateStore();
        ragConfigId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        SecretResolver secrets = mock(SecretResolver.class);
        when(variables.resolveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secrets.resolveSecrets(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // Permissive target policy: the container is on loopback, which the
        // production SSRF policy would (rightly) refuse.
        storeFactory = new EmbeddingStoreFactory(variables, secrets, 50_000);

        modelFactory = mock(EmbeddingModelFactory.class);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(embeddingModel);
    }

    @Test
    @DisplayName("the store under test is the Chroma store buildChroma makes")
    void storeIsChroma() {
        assertInstanceOf(ChromaEmbeddingStore.class, store());
    }

    @Test
    @DisplayName("re-ingesting a changed page replaces its chunks in Chroma — the old text is gone")
    void changedPageReplacesItsChunks() {
        IngestionReport first = ingest(new FakeSite().page(SITE + "/", page("Refunds are issued within fourteen days of the request.")));
        assertEquals(IngestionReport.Outcome.COMPLETED, first.outcome(), first.message());
        assertTrue(storedTexts().stream().anyMatch(text -> text.contains("fourteen days")), "first run must store the page");

        IngestionReport second = ingest(new FakeSite().page(SITE + "/", page("Refunds are issued within three days of the request.")));

        assertEquals(IngestionReport.Outcome.COMPLETED, second.outcome(), second.message());
        assertEquals(1, second.documentsIngested(), "changed content must be re-ingested");
        assertFalse(second.replaceUnsupported(), "Chroma must honour the compound removeAll filter");
        List<String> texts = storedTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("three days")), texts.toString());
        assertTrue(texts.stream().noneMatch(text -> text.contains("fourteen days")),
                "the superseded chunks must be deleted, not left beside the new ones: " + texts);
    }

    @Test
    @DisplayName("a page removed from the site is tombstoned and its chunks deleted from Chroma")
    void removedPageLosesItsChunks() {
        ingest(new FakeSite()
                .page(SITE + "/", linkTo(SITE + "/refunds"))
                .page(SITE + "/refunds", page("Refunds are issued within fourteen days of the request.")));
        assertTrue(storedTexts().stream().anyMatch(text -> text.contains("fourteen days")));

        // The page is gone; the site still answers, so the crawl covers the source.
        var reduced = new FakeSite()
                .page(SITE + "/", page("Nothing here about that topic."))
                .status(SITE + "/refunds", 404);
        ingest(reduced);
        IngestionReport last = ingest(reduced);

        assertEquals(IngestionReport.Outcome.COMPLETED, last.outcome(), last.message());
        assertFalse(last.replaceUnsupported(), "Chroma must honour the compound removeAll filter");
        List<String> texts = storedTexts();
        assertTrue(texts.stream().noneMatch(text -> text.contains("fourteen days")),
                "a tombstoned page's chunks must be deleted: " + texts);
        assertTrue(texts.stream().anyMatch(text -> text.contains("Nothing here")),
                "the surviving page must be left alone: " + texts);
    }

    @Test
    @DisplayName("forgetting a deleted upload removes exactly its chunks from Chroma")
    void forgottenUploadLosesItsChunks() {
        IngestionSource upload = uploadSource();
        String key = IngestionPipeline.stateKey(ragConfigId, upload);
        var kept = fileStore.store(key, "kept.txt", "text/plain",
                "Employees get 30 days of paid leave.".getBytes(StandardCharsets.UTF_8));
        var deleted = fileStore.store(key, "deleted.txt", "text/plain",
                "Parking permits cost forty euros a month.".getBytes(StandardCharsets.UTF_8));
        IngestionReport report = pipeline(new FakeSite()).run(ragConfigId, knowledgeBase(), upload, Mode.INGEST);
        assertEquals(2, report.documentsIngested(), report.message());

        ForgetOutcome outcome = pipeline(new FakeSite()).forgetDocument(ragConfigId, knowledgeBase(), upload, deleted.fileId());

        assertEquals(ForgetOutcome.REMOVED, outcome);
        List<String> texts = storedTexts();
        assertTrue(texts.stream().noneMatch(text -> text.contains("forty euros")), texts.toString());
        assertTrue(texts.stream().anyMatch(text -> text.contains("30 days")),
                "the filter must not reach the other document of the source (" + kept.fileName() + "): " + texts);
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private IngestionReport ingest(FakeSite site) {
        return pipeline(site).run(ragConfigId, knowledgeBase(), webSource(), Mode.INGEST);
    }

    private IngestionPipeline pipeline(FakeSite site) {
        return new IngestionPipeline(new WebCrawler(site), new HtmlToMarkdownConverter(), stateStore, fileStore,
                new DocumentExtractors(List.of(new PlainTextExtractor(), new HtmlDocumentExtractor(new HtmlToMarkdownConverter()))),
                modelFactory, storeFactory, new SimpleMeterRegistry());
    }

    private EmbeddingStore<TextSegment> store() {
        return storeFactory.getOrCreate(ragConfigId, knowledgeBase());
    }

    /**
     * Every chunk in the collection. The collections here hold a handful of chunks,
     * so a search with no score floor and a generous limit returns all of them,
     * whatever the query.
     */
    private List<String> storedTexts() {
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed("refunds leave parking nothing").content())
                .maxResults(100)
                .minScore(0.0)
                .build();
        return store().search(request).matches().stream().map(match -> match.embedded().text()).toList();
    }

    private RagConfiguration knowledgeBase() {
        var config = new RagConfiguration();
        config.setName("chroma-replacement-it");
        config.setStoreNamespace("id");
        config.setStoreType("chroma");
        config.setStoreParameters(Map.of(
                "baseUrl", "http://" + CHROMA.getHost() + ":" + CHROMA.getMappedPort(CHROMA_PORT),
                "collectionName", "eddi_it_" + ragConfigId));
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        return config;
    }

    private static IngestionSource webSource() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl(SITE + "/");
        var source = new IngestionSource();
        source.setId("src-web");
        source.setName("docs-crawl");
        source.setWeb(web);
        return source;
    }

    private static IngestionSource uploadSource() {
        var source = new IngestionSource();
        source.setId("src-files");
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        return source;
    }

    private static String page(String body) {
        return "<html><head><title>Refund policy</title></head><body><p>" + body + "</p></body></html>";
    }

    private static String linkTo(String url) {
        return "<html><head><title>Home</title></head><body><a href=\"" + url + "\">refunds</a></body></html>";
    }

    /** Deterministic and offline: a hashed, normalised bag of words. */
    private static final class BagOfWordsEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSIONS = 64;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(segment -> embedText(segment.text())).toList());
        }

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(embedText(text));
        }

        @Override
        public Response<Embedding> embed(TextSegment segment) {
            return Response.from(embedText(segment.text()));
        }

        private static Embedding embedText(String text) {
            float[] vector = new float[DIMENSIONS];
            // A constant component keeps every vector non-zero, so no chunk is
            // scored NaN against the query.
            vector[0] = 1.0f;
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")) {
                if (!word.isEmpty()) {
                    vector[1 + Math.floorMod(word.hashCode(), DIMENSIONS - 1)] += 1.0f;
                }
            }
            double length = 0;
            for (float value : vector) {
                length += value * value;
            }
            length = Math.sqrt(length);
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) length;
            }
            return Embedding.from(vector);
        }
    }
}
