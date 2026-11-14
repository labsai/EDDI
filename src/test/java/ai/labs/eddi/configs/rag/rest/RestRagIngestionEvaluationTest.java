/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rag.IRestRagIngestion.BaselineQuery;
import ai.labs.eddi.configs.rag.IRestRagIngestion.EvaluationBaseline;
import ai.labs.eddi.configs.rag.IRestRagIngestion.GoldenQuery;
import ai.labs.eddi.configs.rag.IRestRagIngestion.RetrievalEvaluation;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceReferenceGuard;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector.Chunk;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import ai.labs.eddi.modules.rag.evaluation.RetrievalMetrics;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The retrieval benchmark: golden queries run through the search path and
 * scored.
 */
class RestRagIngestionEvaluationTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_ID = "src-1";
    private static final double DELTA = 1e-12;

    private IRestRagStore restRagStore;
    private KnowledgeBaseInspector inspector;
    private ResourceAccessGuard accessGuard;
    private RestRagIngestion rest;

    @BeforeEach
    void setUp() {
        restRagStore = mock(IRestRagStore.class);
        inspector = mock(KnowledgeBaseInspector.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestRagIngestion(restRagStore, mock(RagIngestionService.class),
                mock(RagSourceIngestionService.class), mock(IngestedFileService.class), inspector, accessGuard,
                mock(SpaceReferenceGuard.class));
        when(restRagStore.readRag(eq(KB_ID), anyInt())).thenReturn(knowledgeBase());
    }

    private static RagConfiguration knowledgeBase() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var source = new IngestionSource();
        source.setId(SOURCE_ID);
        source.setName("docs");
        source.setWeb(web);
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setMaxResults(7);
        config.setMinScore(0.4);
        config.setSources(List.of(source));
        return config;
    }

    /** A chunk ingested directly under {@code documentName}. */
    private static Chunk named(String documentName, double score) {
        return new Chunk(0, "text", score, Map.of("source", documentName));
    }

    private static GoldenQuery golden(String query, String... expected) {
        return new GoldenQuery(null, query, List.of(expected), null);
    }

    private static RetrievalEvaluation evaluation(GoldenQuery... queries) {
        return new RetrievalEvaluation(List.of(queries), null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queries(Response response) {
        return (List<Map<String, Object>>) body(response).get("queries");
    }

    private static String error(Response response) {
        return (String) body(response).get("error");
    }

    @Test
    @DisplayName("needs EDIT, like search, and costs nothing when refused")
    void needsEdit() {
        doThrow(new ForbiddenException()).when(accessGuard).requireAccess(eq(KB_ID), eq(AccessLevel.EDIT), anyString());

        assertThrows(ForbiddenException.class, () -> rest.evaluateRetrieval(KB_ID, 1, evaluation(golden("q", "a"))));
        verifyNoInteractions(inspector);
    }

    @Test
    @DisplayName("scores each query and averages them")
    void scoresAndAggregates() {
        when(inspector.search(eq(KB_ID), any(), isNull(), eq("refunds"), anyInt(), anyDouble()))
                .thenReturn(List.of(named("faq", 0.9), named("refund-policy", 0.8)));
        when(inspector.search(eq(KB_ID), any(), isNull(), eq("shipping"), anyInt(), anyDouble()))
                .thenReturn(List.of(named("faq", 0.7)));

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                evaluation(golden("refunds", "refund-policy"), golden("shipping", "shipping-guide")));

        assertEquals(200, response.getStatus());
        var perQuery = queries(response);
        assertEquals(2, perQuery.get(0).get("firstHitRank"));
        assertEquals(0.5, (double) perQuery.get(0).get("reciprocalRank"), DELTA);
        assertEquals(true, perQuery.get(0).get("hit"));
        assertNull(perQuery.get(1).get("firstHitRank"));
        assertEquals(List.of("shipping-guide"), perQuery.get(1).get("missing"));

        var summary = (RetrievalMetrics.Aggregate) body(response).get("summary");
        assertEquals(0.5, summary.hitRate(), DELTA);
        assertEquals(0.25, summary.mrr(), DELTA);
        assertEquals(0.5, summary.recall(), DELTA);
        assertNull(body(response).get("comparison"));

        @SuppressWarnings("unchecked")
        var retrieved = (List<Map<String, Object>>) perQuery.get(0).get("retrieved");
        assertEquals(false, retrieved.get(0).get("relevant"));
        assertEquals(true, retrieved.get(1).get("relevant"));
        assertEquals(List.of("refund-policy"), retrieved.get(1).get("ids"));
    }

    @Test
    @DisplayName("k and minScore default to the knowledge base's, and are what the search runs with")
    void defaultsFromKnowledgeBase() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        Response response = rest.evaluateRetrieval(KB_ID, 1, evaluation(golden("q", "a")));

        verify(inspector).search(eq(KB_ID), any(), isNull(), eq("q"), eq(7), eq(0.4));
        assertEquals(7, body(response).get("k"));
    }

    @Test
    @DisplayName("a query's own k overrides the request's")
    void perQueryK() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        rest.evaluateRetrieval(KB_ID, 1, new RetrievalEvaluation(List.of(new GoldenQuery(null, "q", List.of("a"), 3),
                golden("r", "a")), 10, 0.0, null, null, null));

        verify(inspector).search(eq(KB_ID), any(), isNull(), eq("q"), eq(3), eq(0.0));
        verify(inspector).search(eq(KB_ID), any(), isNull(), eq("r"), eq(10), eq(0.0));
    }

    @Test
    @DisplayName("sourceId narrows every query to that source")
    void narrowedToSource() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                new RetrievalEvaluation(List.of(golden("q", "a")), null, null, SOURCE_ID, null, null));

        assertEquals(200, response.getStatus());
        verify(inspector).search(eq(KB_ID), any(), any(IngestionSource.class), eq("q"), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("by default documentName, documentId and url all identify a document")
    void defaultMatchKeys() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                new Chunk(0, "t", 0.9, Map.of("documentId", "file-7", "title", "Guide")),
                new Chunk(0, "t", 0.8, Map.of("url", "https://example.com/a"))));

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                evaluation(golden("q", "file-7", "https://example.com/a", "Guide")));

        assertEquals(2.0 / 3, (double) queries(response).get(0).get("recall"), DELTA);
    }

    @Test
    @DisplayName("matchKeys replaces the default keys")
    void customMatchKeys() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new Chunk(0, "t", 0.9, Map.of("documentId", "file-7", "title", "Guide"))));

        Response response = rest.evaluateRetrieval(KB_ID, 1, new RetrievalEvaluation(
                List.of(golden("q", "file-7", "Guide")), null, null, null, List.of("title"), null));

        assertEquals(List.of("Guide"), queries(response).get(0).get("found"));
        assertEquals(List.of("title"), body(response).get("matchKeys"));
    }

    @Test
    @DisplayName("a baseline is diffed per query and in aggregate")
    void baseline() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(named("x", 0.9), named("a", 0.8)));
        var baseline = new EvaluationBaseline(List.of(
                new BaselineQuery("q1", "ignored text", 7, true, 1.0, 1.0),
                new BaselineQuery(null, "gone", 7, true, 1.0, 1.0)));

        Response response = rest.evaluateRetrieval(KB_ID, 1, new RetrievalEvaluation(
                List.of(new GoldenQuery("q1", "where is a", List.of("a"), null)), null, null, null, null, baseline));

        var comparison = (RetrievalMetrics.Comparison) body(response).get("comparison");
        assertNotNull(comparison);
        assertTrue(comparison.regressed());
        assertEquals(1, comparison.regressions());
        assertEquals(-0.5, comparison.mrrDelta(), DELTA);
        assertEquals(List.of("gone"), comparison.onlyInBaseline());
    }

    @Test
    @DisplayName("a baseline with one key twice is refused before any embedding call")
    void baselineDuplicates() {
        var baseline = new EvaluationBaseline(List.of(new BaselineQuery(null, "q", 5, true, 1.0, 1.0),
                new BaselineQuery(null, "q", 5, false, 0.0, 0.0)));

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                new RetrievalEvaluation(List.of(golden("q", "a")), null, null, null, null, baseline));

        assertEquals(400, response.getStatus());
        assertTrue(error(response).contains("baseline"), error(response));
        verifyNoInteractions(inspector);
    }

    @Test
    @DisplayName("a baseline query with neither id nor text is refused before any embedding call")
    void baselineUnkeyed() {
        var baseline = new EvaluationBaseline(List.of(new BaselineQuery(" ", null, 5, true, 1.0, 1.0)));

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                new RetrievalEvaluation(List.of(golden("q", "a")), null, null, null, null, baseline));

        assertEquals(400, response.getStatus());
        assertTrue(error(response).contains("baseline"), error(response));
        verifyNoInteractions(inspector);
    }

    @Test
    @DisplayName("more queries than the cap are refused before any embedding call")
    void overCap() {
        rest.maxEvaluationQueries = 2;

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                evaluation(golden("a", "x"), golden("b", "x"), golden("c", "x")));

        assertEquals(400, response.getStatus());
        assertTrue(error(response).contains("eddi.rag.evaluation.max-queries"), error(response));
        verifyNoInteractions(inspector);
    }

    @Test
    @DisplayName("exactly the cap is allowed")
    void atCap() {
        rest.maxEvaluationQueries = 2;
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        assertEquals(200, rest.evaluateRetrieval(KB_ID, 1, evaluation(golden("a", "x"), golden("b", "x"))).getStatus());
    }

    @Test
    @DisplayName("malformed requests are refused before any embedding call")
    void validation() {
        assertBadRequest(null, "At least one");
        assertBadRequest(new RetrievalEvaluation(List.of(), null, null, null, null, null), "At least one");
        assertBadRequest(evaluation(golden(" ", "a")), "no 'query'");
        assertBadRequest(evaluation(new GoldenQuery(null, "q", null, null)), "no 'expected'");
        assertBadRequest(evaluation(new GoldenQuery(null, "q", Arrays.asList(" ", null), null)), "no 'expected'");
        assertBadRequest(evaluation(new GoldenQuery(null, "q", List.of("a"), 0)), "between 1 and");
        assertBadRequest(evaluation(new GoldenQuery(null, "q", List.of("a"), 51)), "between 1 and");
        assertBadRequest(new RetrievalEvaluation(List.of(golden("q", "a")), 0, null, null, null, null), "'k'");
        assertBadRequest(new RetrievalEvaluation(List.of(golden("q", "a")), null, 1.5, null, null, null), "minScore");
        assertBadRequest(new RetrievalEvaluation(List.of(golden("q", "a")), null, Double.NaN, null, null, null),
                "minScore");
        assertBadRequest(new RetrievalEvaluation(List.of(golden("q", "a")), null, null, null, List.of(" "), null),
                "matchKeys");
        assertBadRequest(evaluation(golden("q", "a"), golden("q", "b")), "unique 'id'");
        assertBadRequest(evaluation(new GoldenQuery("same", "q", List.of("a"), null),
                new GoldenQuery("same", "r", List.of("a"), null)), "unique 'id'");
        assertBadRequest(evaluation(golden("x".repeat(RestRagIngestion.MAX_SEARCH_QUERY_LENGTH + 1), "a")),
                "longer than");
        assertBadRequest(evaluation(new GoldenQuery(null, "q",
                Collections.nCopies(RestRagIngestion.MAX_EXPECTED_PER_QUERY + 1, "a"), null)), "expects more");
        verifyNoInteractions(inspector);
    }

    private void assertBadRequest(RetrievalEvaluation evaluation, String messagePart) {
        Response response = rest.evaluateRetrieval(KB_ID, 1, evaluation);
        assertEquals(400, response.getStatus());
        assertTrue(error(response).contains(messagePart), error(response));
    }

    @Test
    @DisplayName("the same text under different ids is two queries")
    void sameTextDifferentIds() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        Response response = rest.evaluateRetrieval(KB_ID, 1, evaluation(new GoldenQuery("1", "q", List.of("a"), null),
                new GoldenQuery("2", "q", List.of("b"), null)));

        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("a missing version is a 400, not a 404")
    void versionRequired() {
        assertEquals(400, rest.evaluateRetrieval(KB_ID, null, evaluation(golden("q", "a"))).getStatus());
    }

    @Test
    @DisplayName("a failing provider is a 502 naming the query it failed at")
    void providerFailure() {
        when(inspector.search(any(), any(), any(), eq("ok"), anyInt(), anyDouble())).thenReturn(List.of());
        when(inspector.search(any(), any(), any(), eq("boom"), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("401 from provider"));

        Response response = rest.evaluateRetrieval(KB_ID, 1, evaluation(golden("ok", "a"), golden("boom", "a")));

        assertEquals(502, response.getStatus());
        assertEquals(1, body(response).get("queryIndex"));
        assertTrue(error(response).contains("401 from provider"));
    }

    @Test
    @DisplayName("a store that cannot filter by source is a 501")
    void unsupportedFilter() {
        when(inspector.search(any(), any(), any(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new UnsupportedOperationException("cannot filter"));

        Response response = rest.evaluateRetrieval(KB_ID, 1,
                new RetrievalEvaluation(List.of(golden("q", "a")), null, null, SOURCE_ID, null, null));

        assertEquals(501, response.getStatus());
        assertFalse(error(response).isBlank());
    }
}
