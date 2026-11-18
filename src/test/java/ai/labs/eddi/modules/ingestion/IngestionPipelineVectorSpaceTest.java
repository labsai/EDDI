/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.crawl.FakeSite;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.RagFailures;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A run into an external vector store checks the embedding space first, and
 * stops when the space or the credentials are wrong — instead of writing
 * vectors of another model next to the old ones, or calling the provider once
 * per remaining document.
 */
@DisplayName("IngestionPipeline and the vector space")
class IngestionPipelineVectorSpaceTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_KEY = KB_ID + ":src-files";

    private InMemoryIngestionStateStore stateStore;
    private InMemoryIngestedFileStore fileStore;
    private RecordingEmbeddingStore embeddingStore;
    private EmbeddingModel embeddingModel;
    private IngestionPipeline pipeline;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        fileStore = new InMemoryIngestedFileStore();
        embeddingStore = new RecordingEmbeddingStore();
        embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStoreFactory storeFactory = mock(EmbeddingStoreFactory.class);
        EmbeddingModelFactory modelFactory = mock(EmbeddingModelFactory.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(embeddingModel);
        embedding(2);

        pipeline = new IngestionPipeline(new WebCrawler(new FakeSite()), new HtmlToMarkdownConverter(), stateStore, fileStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory, new SimpleMeterRegistry());
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(call -> call.getArgument(0));
        pipeline.vectorSpaceGuard = new VectorSpaceGuard(stateStore, variables);
    }

    private void embedding(int dimension) {
        // doAnswer, not when(): re-stubbing with when() would call the previous answer
        // with
        // the matcher's null.
        doAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[dimension])).toList());
        }).when(embeddingModel).embedAll(any());
    }

    private static RagConfiguration knowledgeBase(String model) {
        RagConfiguration config = new RagConfiguration();
        config.setName("product-docs");
        config.setStoreNamespace("id");
        config.setStoreType("qdrant");
        config.setStoreParameters(Map.of("host", "qdrant.internal"));
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", model));
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        return config;
    }

    private static IngestionSource uploadSource() {
        var source = new IngestionSource();
        source.setId("src-files");
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        return source;
    }

    private void upload(String name, String text) {
        fileStore.store(SOURCE_KEY, name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a run after the embedding model changed fails with the reason and writes nothing")
    void aChangedModelIsRefused() {
        upload("a.txt", "Refunds within 47 days.");
        assertEquals(IngestionReport.Outcome.COMPLETED,
                pipeline.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST).outcome());
        int stored = embeddingStore.segments().size();
        upload("b.txt", "Shipping takes 3 days.");

        IngestionReport report = pipeline.run(KB_ID, knowledgeBase("other-8"), uploadSource(), Mode.INGEST);

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertTrue(report.message().contains("emb-8") && report.message().contains("other-8"), report.message());
        assertTrue(report.message().contains("/reembed"), report.message());
        assertEquals(stored, embeddingStore.segments().size(), "nothing of the new model was written");
    }

    @Test
    @DisplayName("the same model keeps working run after run")
    void theSameModelIsAccepted() {
        upload("a.txt", "Refunds within 47 days.");
        pipeline.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST);
        upload("b.txt", "Shipping takes 3 days.");

        IngestionReport report = pipeline.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST);

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
        assertEquals(1, report.documentsIngested());
    }

    @Test
    @DisplayName("a model that now returns vectors of another size stops the run before it writes")
    void aChangedVectorSizeStopsTheRun() {
        upload("a.txt", "Refunds within 47 days.");
        pipeline.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST);
        int stored = embeddingStore.segments().size();
        embedding(3);
        upload("b.txt", "Shipping takes 3 days.");
        upload("c.txt", "Returns need a receipt.");

        IngestionReport report = pipeline.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST);

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertTrue(report.message().contains("3 dimensions"), report.message());
        assertEquals(stored, embeddingStore.segments().size());
        // The first document found the problem; the second was not even tried.
        verify(embeddingModel, times(2)).embedAll(any());
    }

    @Test
    @DisplayName("rejected credentials stop the run at the first document instead of trying every one")
    void rejectedCredentialsStopTheRun() {
        doThrow(new AuthenticationException("401 sk-live-secret")).when(embeddingModel).embedAll(any());
        upload("a.txt", "Refunds within 47 days.");
        upload("b.txt", "Shipping takes 3 days.");
        upload("c.txt", "Returns need a receipt.");

        IngestionReport report = pipeline.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST);

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertTrue(report.message().contains("credentials"), report.message());
        assertFalse(report.message().contains("sk-live-secret"), report.message());
        verify(embeddingModel, times(1)).embedAll(any());
    }

    @Test
    @DisplayName("a run's failure message carries no driver text")
    void aStoreFailureMessageIsFixedText() {
        upload("a.txt", "Refunds within 47 days.");
        EmbeddingStoreFactory down = mock(EmbeddingStoreFactory.class);
        when(down.getOrCreateForIngestion(anyString(), any(RagConfiguration.class)))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host/db: password authentication failed"));
        var failing = new IngestionPipeline(new WebCrawler(new FakeSite()), new HtmlToMarkdownConverter(), stateStore, fileStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), mock(EmbeddingModelFactory.class), down,
                new SimpleMeterRegistry());

        IngestionReport report = failing.run(KB_ID, knowledgeBase("emb-8"), uploadSource(), Mode.INGEST);

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertEquals(RagFailures.GENERIC, report.message());
    }

    @Test
    @DisplayName("an in-memory knowledge base is not tracked, so a changed model does not fail the run")
    void inMemoryIsNotTracked() {
        RagConfiguration first = knowledgeBase("emb-8");
        first.setStoreType("in-memory");
        first.setStoreParameters(null);
        RagConfiguration second = knowledgeBase("other-8");
        second.setStoreType("in-memory");
        second.setStoreParameters(null);
        upload("a.txt", "Refunds within 47 days.");
        pipeline.run(KB_ID, first, uploadSource(), Mode.INGEST);
        upload("b.txt", "Shipping takes 3 days.");

        IngestionReport report = pipeline.run(KB_ID, second, uploadSource(), Mode.INGEST);

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
        verify(embeddingModel, atLeastOnce()).embedAll(any());
    }
}
