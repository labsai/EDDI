/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.crawl.FakeSite;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.llm.impl.RagContextProvider;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.KnowledgeBaseReference;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Crawl, embed, retrieve — the whole way round, in one test.
 *
 * <p>
 * Every other test in this feature exercises one layer against doubles, and the
 * draft this work replaces shows why that is not enough: it ingested into a
 * store keyed by the <em>source</em> name while retrieval read one keyed by the
 * <em>knowledge base</em> name. Every unit test passed, every run reported
 * success, and retrieval returned nothing, for ever. No test here failed,
 * because no test asked a question that spanned both halves.
 *
 * <p>
 * So the store factory is keyed here, exactly as the real one is: ask for a
 * different key and you get a different, empty store. Ingestion runs through
 * the real {@link IngestionPipeline} and retrieval through the real
 * {@link RagContextProvider}, and the assertion is the agent-visible one —
 * whether the crawled sentence ends up in the context the LLM is given.
 */
class IngestionRetrievalRoundTripTest {

    private static final String SITE = "https://example.com";
    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String KB_NAME = "product-docs";
    /**
     * Unique to this class. Workflow discovery memoizes per (agentId, version)
     * process-wide, so an id shared with another test class serves that class's
     * knowledge base here and turns a match into a silent miss.
     */
    private static final String AGENT_ID = "ingestion-round-trip-agent";

    /** Keyed like the real factory: a wrong key is an empty store, not a miss. */
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> storesByKey = new HashMap<>();
    private final List<String> requestedKeys = new ArrayList<>();
    /** The role each half asked the model factory for, in call order. */
    private final List<EmbeddingInputType> requestedRoles = new ArrayList<>();

    private InMemoryIngestionStateStore stateStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    private RagContextProvider retrieval;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new InMemoryIngestionStateStore();

        EmbeddingModel embeddingModel = new BagOfWordsEmbeddingModel();
        modelFactory = mock(EmbeddingModelFactory.class);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class)))
                .thenAnswer(invocation -> {
                    requestedRoles.add(invocation.getArgument(1));
                    return embeddingModel;
                });

        storeFactory = mock(EmbeddingStoreFactory.class);
        when(storeFactory.getOrCreate(any(RagConfiguration.class), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1);
            requestedKeys.add(key);
            return storesByKey.computeIfAbsent(key, ignored -> new InMemoryEmbeddingStore<>());
        });

        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);
        var dataFactory = mock(IDataFactory.class);
        @SuppressWarnings("unchecked")
        IData<Object> data = mock(IData.class);
        lenient().when(dataFactory.createData(anyString(), any())).thenReturn(data);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.rag/ragstore/rag/rag-1?version=1"));
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(List.of(step));
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
        when(agentStore.read(AGENT_ID, 1)).thenReturn(agent);
        when(workflowStore.read("wf-1", 1)).thenReturn(workflow);
        when(resourceClientLibrary.getResource(any(URI.class), eq(RagConfiguration.class)))
                .thenReturn(knowledgeBase());

        retrieval = new RagContextProvider(agentStore, workflowStore, resourceClientLibrary,
                modelFactory, storeFactory, dataFactory);
    }

    @Test
    @DisplayName("a crawled page is retrievable, through the key both halves actually use")
    void crawledPageIsRetrievable() {
        ingest(new FakeSite().page(SITE + "/", page("Refunds are issued within fourteen days of the request.")));

        String context = retrieve("how long do refunds take");

        assertNotNull(context, "the crawled page must reach the LLM context");
        assertTrue(context.contains("fourteen days"), context);
        assertEquals(List.of(KB_NAME, KB_NAME), requestedKeys,
                "ingestion and retrieval must ask the factory for the same store");
        // The same store, but deliberately NOT the same model. An asymmetric provider
        // bakes the role in at construction and the factory keys its cache on it, so
        // the two halves sharing one entry is exactly how a query got embedded as a
        // document.
        assertEquals(List.of(EmbeddingInputType.DOCUMENT, EmbeddingInputType.QUERY), requestedRoles,
                "ingestion must embed as DOCUMENT and retrieval as QUERY");
    }

    @Test
    @DisplayName("re-ingesting changed content replaces it — the old answer is gone")
    void changedPageReplacesItsChunks() {
        var before = new FakeSite().page(SITE + "/", page("Refunds are issued within fourteen days of the request."));
        ingest(before);

        var after = new FakeSite().page(SITE + "/", page("Refunds are issued within three days of the request."));
        IngestionReport second = ingest(after);
        assertEquals(1, second.documentsIngested(), "changed content must be re-ingested");

        String context = retrieve("how long do refunds take");

        assertNotNull(context);
        assertTrue(context.contains("three days"), context);
        assertFalse(context.contains("fourteen days"),
                "the superseded text must not still be retrievable: " + context);
    }

    @Test
    @DisplayName("unchanged content costs no embedding, and stays retrievable")
    void unchangedPageIsNotReEmbedded() {
        var site = new FakeSite().page(SITE + "/", page("Refunds are issued within fourteen days of the request."));
        ingest(site);
        IngestionReport second = ingest(site);

        assertEquals(0, second.documentsIngested(), "an unchanged page must not be embedded again");
        assertEquals(1, second.documentsUnchanged());
        assertTrue(retrieve("how long do refunds take").contains("fourteen days"));
    }

    @Test
    @DisplayName("a page that disappears stops answering once it is tombstoned")
    void deletedPageStopsBeingRetrievable() {
        var full = new FakeSite()
                .page(SITE + "/", linkTo(SITE + "/refunds"))
                .page(SITE + "/refunds", page("Refunds are issued within fourteen days of the request."));
        ingest(full);
        assertTrue(retrieve("how long do refunds take").contains("fourteen days"));

        // The page is gone; the site still answers, so the crawl covers the source.
        var reduced = new FakeSite()
                .page(SITE + "/", page("Nothing here about that topic."))
                .status(SITE + "/refunds", 404);
        ingest(reduced);
        ingest(reduced);

        String context = retrieve("how long do refunds take");
        assertTrue(context == null || !context.contains("fourteen days"),
                "a tombstoned page must stop reaching the LLM: " + context);
    }

    @Test
    @DisplayName("a preview embeds nothing, so it cannot be retrieved")
    void previewDoesNotReachRetrieval() {
        var site = new FakeSite().page(SITE + "/", page("Refunds are issued within fourteen days of the request."));
        var pipeline = pipelineFor(site);

        IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.PREVIEW);

        assertEquals(IngestionReport.Outcome.PREVIEW, report.outcome());
        assertNull(retrieve("how long do refunds take"), "a preview must not put anything in the store");
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private IngestionReport ingest(FakeSite site) {
        return pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
    }

    private IngestionPipeline pipelineFor(FakeSite site) {
        return new IngestionPipeline(new WebCrawler(site), new HtmlToMarkdownConverter(), stateStore,
                modelFactory, storeFactory, new SimpleMeterRegistry());
    }

    private String retrieve(String query) {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getAgentId()).thenReturn(AGENT_ID);
        when(memory.getAgentVersion()).thenReturn(1);

        var reference = new KnowledgeBaseReference();
        reference.setName(KB_NAME);
        reference.setMaxResults(5);
        reference.setMinScore(0.3);
        var task = new LlmConfiguration.Task();
        task.setId("task-1");
        task.setKnowledgeBases(List.of(reference));

        return retrieval.retrieveContext(memory, task, query);
    }

    private static RagConfiguration knowledgeBase() {
        var config = new RagConfiguration();
        config.setName(KB_NAME);
        config.setStoreType("in-memory");
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        config.setMaxResults(5);
        config.setMinScore(0.3);
        return config;
    }

    private static IngestionSource source() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl(SITE + "/");
        var source = new IngestionSource();
        source.setId("src-1");
        source.setName("docs-crawl");
        source.setWeb(web);
        return source;
    }

    private static String page(String body) {
        return "<html><head><title>Refund policy</title></head><body><p>" + body + "</p></body></html>";
    }

    private static String linkTo(String url) {
        return "<html><head><title>Home</title></head><body><a href=\"" + url + "\">refunds</a></body></html>";
    }

    /**
     * Deterministic and offline: a hashed bag of words, normalised, so a query
     * sharing words with a chunk scores high and an unrelated one does not. A
     * constant vector would make every document match every query and the retrieval
     * half of this test would prove nothing.
     */
    private static final class BagOfWordsEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSIONS = 128;

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
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+")) {
                if (word.isEmpty()) {
                    continue;
                }
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1.0f;
            }
            double length = 0;
            for (float value : vector) {
                length += value * value;
            }
            length = Math.sqrt(length);
            if (length > 0) {
                for (int i = 0; i < vector.length; i++) {
                    vector[i] /= (float) length;
                }
            }
            return Embedding.from(vector);
        }
    }
}
