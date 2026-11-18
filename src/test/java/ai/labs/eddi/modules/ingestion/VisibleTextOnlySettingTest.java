/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.crawl.FakeSite;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.HtmlDocumentExtractor;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code settings.visibleTextOnly}: on by default for an upload, off by default
 * for a crawl (a page hides accordions that script reveals), and settable
 * either way.
 */
@DisplayName("visibleTextOnly on an ingestion source")
class VisibleTextOnlySettingTest {

    private static final String SITE = "https://example.com";
    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String PAGE = "<html><head><title>Docs</title></head><body><main>"
            + "<p>Visible words about returns.</p><p style=\"display:none\">HIDDEN-ACCORDION-TEXT</p></main></body></html>";

    private InMemoryIngestionStateStore stateStore;
    private InMemoryIngestedFileStore fileStore;
    private RecordingEmbeddingStore embeddingStore;
    private IngestionPipeline pipeline;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        fileStore = new InMemoryIngestedFileStore();
        embeddingStore = new RecordingEmbeddingStore();
        EmbeddingStoreFactory storeFactory = mock(EmbeddingStoreFactory.class);
        EmbeddingModelFactory modelFactory = mock(EmbeddingModelFactory.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        when(model.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f, 0.2f})).toList());
        });
        pipeline = new IngestionPipeline(new WebCrawler(new FakeSite().page(SITE + "/", PAGE)), new HtmlToMarkdownConverter(), stateStore,
                fileStore, new DocumentExtractors(List.of(new HtmlDocumentExtractor(new HtmlToMarkdownConverter()))), modelFactory,
                storeFactory, new SimpleMeterRegistry());
    }

    private static RagConfiguration knowledgeBase() {
        RagConfiguration config = new RagConfiguration();
        config.setName("product-docs");
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        return config;
    }

    private static IngestionSource webSource() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl(SITE + "/");
        web.setRespectRobots(false);
        web.setRequestDelayMs(0);
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

    private String storedText() {
        return String.join("\n", embeddingStore.segments().stream().map(TextSegment::text).toList());
    }

    @Test
    @DisplayName("the default is per type: on for an upload, off for a crawl, and an explicit value wins")
    void defaults() {
        var settings = new IngestionSource.IngestionSettings();
        assertNull(settings.getVisibleTextOnly());
        assertTrue(settings.visibleTextOnlyOrDefault(true));
        assertFalse(settings.visibleTextOnlyOrDefault(false));

        settings.setVisibleTextOnly(false);
        assertFalse(settings.visibleTextOnlyOrDefault(true));
        settings.setVisibleTextOnly(true);
        assertTrue(settings.visibleTextOnlyOrDefault(false));
    }

    @Test
    @DisplayName("a crawled page keeps what it hides until script reveals it, by default")
    void crawlKeepsHiddenElementsByDefault() {
        pipeline.run(KB_ID, knowledgeBase(), webSource(), Mode.INGEST);

        assertTrue(storedText().contains("Visible words about returns."), storedText());
        assertTrue(storedText().contains("HIDDEN-ACCORDION-TEXT"), storedText());
    }

    @Test
    @DisplayName("a crawl with visibleTextOnly drops the elements the markup hides")
    void crawlCanDropHiddenElements() {
        var source = webSource();
        var settings = new IngestionSource.IngestionSettings();
        settings.setVisibleTextOnly(true);
        source.setSettings(settings);

        pipeline.run(KB_ID, knowledgeBase(), source, Mode.INGEST);

        assertTrue(storedText().contains("Visible words about returns."), storedText());
        assertFalse(storedText().contains("HIDDEN-ACCORDION-TEXT"), storedText());
    }

    @Test
    @DisplayName("an uploaded HTML file drops what it hides by default, and keeps it when asked")
    void uploadDropsHiddenElementsByDefault() {
        fileStore.store(KB_ID + ":src-files", "page.html", "text/html", PAGE.getBytes(StandardCharsets.UTF_8));
        pipeline.run(KB_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

        assertTrue(storedText().contains("Visible words about returns."), storedText());
        assertFalse(storedText().contains("HIDDEN-ACCORDION-TEXT"), storedText());
    }

    @Test
    @DisplayName("an upload with visibleTextOnly off keeps hidden elements")
    void uploadCanKeepHiddenElements() {
        fileStore.store(KB_ID + ":src-files", "page.html", "text/html", PAGE.getBytes(StandardCharsets.UTF_8));
        var source = uploadSource();
        var settings = new IngestionSource.IngestionSettings();
        settings.setVisibleTextOnly(false);
        source.setSettings(settings);

        pipeline.run(KB_ID, knowledgeBase(), source, Mode.INGEST);

        assertTrue(storedText().contains("HIDDEN-ACCORDION-TEXT"), storedText());
    }
}
