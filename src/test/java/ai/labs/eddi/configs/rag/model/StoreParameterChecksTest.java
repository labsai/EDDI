/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can only fail later is refused when the knowledge base is saved, where
 * the author is looking: every case below was accepted with a 201 and failed at
 * the first ingestion.
 */
@DisplayName("Store settings are checked at save time")
class StoreParameterChecksTest {

    private static RagConfiguration pgvector(Map<String, String> params) {
        RagConfiguration config = new RagConfiguration();
        config.setName("kb");
        config.setStoreType("pgvector");
        config.setStoreParameters(params);
        return config;
    }

    private static String refusal(RagConfiguration config) {
        return assertThrows(IllegalArgumentException.class, config::validate).getMessage();
    }

    @Test
    @DisplayName("an unknown storeType is refused, and every supported one is accepted")
    void storeType() {
        RagConfiguration config = new RagConfiguration();
        config.setStoreType("nope");
        assertTrue(refusal(config).contains("Unsupported storeType 'nope'"));

        for (String supported : StoreParameterChecks.SUPPORTED_STORE_TYPES) {
            RagConfiguration ok = new RagConfiguration();
            ok.setStoreType(supported);
            assertDoesNotThrow(ok::validate, supported);
        }
    }

    @Test
    @DisplayName("a host carrying a port, a path or a list is refused; a name or an address is not")
    void host() {
        assertTrue(refusal(pgvector(Map.of("host", "localhost:55432"))).contains("storeParameters.host"));
        assertTrue(refusal(pgvector(Map.of("host", "db.example.com/eddi?x=y"))).contains("storeParameters.host"));
        assertTrue(refusal(pgvector(Map.of("host", "a.com,b.com"))).contains("storeParameters.host"));
        assertTrue(refusal(pgvector(Map.of("host", "db:5432"))).contains("storeParameters.host"));
        for (String fine : new String[]{"localhost", "db.example.com", "10.0.0.5", "pg_vector-1", "[::1]", "::1"}) {
            assertDoesNotThrow(() -> pgvector(Map.of("host", fine)).validate(), fine);
        }
    }

    @Test
    @DisplayName("a port that is not a port, and a vector size that is not a size, are refused")
    void numbers() {
        for (String bad : new String[]{"abc", "0", "70000", "-1", "5432.5"}) {
            assertTrue(refusal(pgvector(Map.of("port", bad))).contains("storeParameters.port"), bad);
        }
        for (String bad : new String[]{"abc", "0", "-3", "100000"}) {
            assertTrue(refusal(pgvector(Map.of("dimension", bad))).contains("storeParameters.dimension"), bad);
        }
        assertDoesNotThrow(() -> pgvector(Map.of("port", "5432", "dimension", "1536")).validate());
        assertDoesNotThrow(() -> pgvector(Map.of("dimension", "16000")).validate());
    }

    @Test
    @DisplayName("a value that is a reference is judged when it is resolved, not now")
    void referencesAreNotJudged() {
        Map<String, String> params = new HashMap<>();
        params.put("port", "${vars:pg-port}");
        params.put("dimension", "${vars:dim}");
        params.put("host", "${vars:pg-host}");
        assertDoesNotThrow(() -> pgvector(params).validate());
    }

    @Test
    @DisplayName("an Elasticsearch or Chroma URL must be http(s); so must an embedding endpoint")
    void urls() {
        RagConfiguration es = new RagConfiguration();
        es.setStoreType("elasticsearch");
        es.setStoreParameters(Map.of("serverUrl", "file:///etc/passwd"));
        assertTrue(refusal(es).contains("storeParameters.serverUrl"));

        RagConfiguration chroma = new RagConfiguration();
        chroma.setStoreType("chroma");
        chroma.setStoreParameters(Map.of("baseUrl", "localhost:8000"));
        assertTrue(refusal(chroma).contains("storeParameters.baseUrl"));

        RagConfiguration embedding = new RagConfiguration();
        embedding.setEmbeddingProvider("ollama");
        embedding.setEmbeddingParameters(Map.of("baseUrl", "file:///etc/passwd"));
        assertTrue(refusal(embedding).contains("embeddingParameters.baseUrl"));

        embedding.setEmbeddingParameters(Map.of("baseUrl", "http://ollama:11434"));
        assertDoesNotThrow(embedding::validate);
    }

    @Test
    @DisplayName("a pgvector table named like one of EDDI's own is refused at save, schema-qualified or not")
    void eddiTablesAreRefused() {
        assertTrue(refusal(pgvector(Map.of("table", "resources"))).contains("one of EDDI's own tables"));
        assertTrue(refusal(pgvector(Map.of("table", "public.Conversation_Memories"))).contains("one of EDDI's own tables"));
        assertDoesNotThrow(() -> pgvector(Map.of("table", "team_docs")).validate());
    }

    @Test
    @DisplayName("what an interactive save refuses is listed for the lenient paths, and clamping brings retrieval settings into range")
    void legacyHelpers() {
        RagConfiguration old = pgvector(Map.of("host", "db:5432"));
        old.setMinScore(1.5);
        old.setMaxResults(0);
        old.setName(" ");

        assertEquals(4, old.findStrictProblems().size(), old.findStrictProblems().toString());
        assertDoesNotThrow(old::validateLenient);
        assertTrue(old.clampRetrievalSettings());
        assertEquals(1.0, old.getMinScore());
        assertEquals(5, old.getMaxResults());
        assertFalse(old.clampRetrievalSettings());
    }

    @Test
    @DisplayName("a blank name and out-of-range retrieval settings are refused")
    void retrievalSettings() {
        RagConfiguration blank = new RagConfiguration();
        blank.setName("");
        assertTrue(refusal(blank).contains("name"));

        RagConfiguration none = new RagConfiguration();
        none.setMaxResults(-1);
        assertTrue(refusal(none).contains("maxResults"));

        RagConfiguration score = new RagConfiguration();
        score.setMinScore(7.0);
        assertTrue(refusal(score).contains("minScore"));
        score.setMinScore(Double.NaN);
        assertTrue(refusal(score).contains("minScore"));

        RagConfiguration fine = new RagConfiguration();
        fine.setMaxResults(1);
        fine.setMinScore(0.0);
        assertDoesNotThrow(fine::validate);
    }

    @Test
    @DisplayName("an unknown store type does not echo control characters or an unbounded value")
    void messagesAreBounded() {
        RagConfiguration config = new RagConfiguration();
        config.setStoreType("x\ny".repeat(100));
        String message = refusal(config);
        assertFalse(message.contains("\n"));
        assertTrue(message.length() < 300, message);
    }

    @Test
    @DisplayName("nothing is judged for an in-memory store or absent parameters")
    void nothingToJudge() {
        assertNull(StoreParameterChecks.findProblem("in-memory", null));
        assertNull(StoreParameterChecks.findProblem(null, Map.of("port", "abc")));
        assertNull(StoreParameterChecks.findProblem("pgvector", Map.of()));
        assertEquals(6, StoreParameterChecks.SUPPORTED_STORE_TYPES.size());
    }
}
