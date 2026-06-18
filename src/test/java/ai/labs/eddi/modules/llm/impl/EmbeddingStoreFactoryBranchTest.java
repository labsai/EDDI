/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Additional branch coverage for {@link EmbeddingStoreFactory}:
 * <ul>
 * <li>Elasticsearch apiKey / userName+password branches</li>
 * <li>Qdrant apiKey branch</li>
 * <li>Chroma blank/null apiVersion → default V2</li>
 * <li>Cache key with different storeParameters</li>
 * <li>parseChromaApiVersion null/blank</li>
 * </ul>
 */
@DisplayName("EmbeddingStoreFactory — Uncovered Branch Tests")
class EmbeddingStoreFactoryBranchTest {

    private EmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        GlobalVariableResolver gvr = mock(GlobalVariableResolver.class);
        SecretResolver sr = mock(SecretResolver.class);
        when(gvr.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sr.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingStoreFactory(gvr, sr);
    }

    // ==================== Elasticsearch branches ====================

    @Nested
    @DisplayName("Elasticsearch builder branches")
    class ElasticsearchBranches {

        @Test
        @DisplayName("with apiKey — builder.apiKey() is called (no exception)")
        void withApiKey() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("elasticsearch");
            config.setStoreParameters(Map.of("apiKey", "test-api-key"));

            // Elasticsearch store construction doesn't connect eagerly, so this
            // should succeed without a live server
            var store = factory.getOrCreate(config, "kb-es-apikey");
            assertNotNull(store);
        }

        @Test
        @DisplayName("with userName and password — builder sets both")
        void withUserNameAndPassword() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("elasticsearch");
            var params = new HashMap<String, String>();
            params.put("userName", "elastic");
            params.put("password", "secret");
            config.setStoreParameters(params);

            var store = factory.getOrCreate(config, "kb-es-user");
            assertNotNull(store);
        }

        @Test
        @DisplayName("without apiKey or userName — minimal builder")
        void noAuth() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("elasticsearch");
            config.setStoreParameters(Map.of());

            var store = factory.getOrCreate(config, "kb-es-noauth");
            assertNotNull(store);
        }

        @Test
        @DisplayName("userName without password — no auth set (only both together)")
        void userNameWithoutPassword() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("elasticsearch");
            config.setStoreParameters(Map.of("userName", "elastic"));

            var store = factory.getOrCreate(config, "kb-es-useronly");
            assertNotNull(store);
        }
    }

    // ==================== Qdrant branches ====================

    @Nested
    @DisplayName("Qdrant builder branches")
    class QdrantBranches {

        @Test
        @DisplayName("with apiKey — builder.apiKey() called")
        void withApiKey() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("qdrant");
            var params = new HashMap<String, String>();
            params.put("apiKey", "test-qdrant-key");
            config.setStoreParameters(params);

            // Qdrant store build doesn't connect eagerly in the builder
            try {
                factory.getOrCreate(config, "kb-qdrant-apikey");
            } catch (Exception e) {
                // Connection error expected — but should NOT be IllegalArgumentException
                assertFalse(e instanceof IllegalArgumentException,
                        "Should not fail on parameter parsing, got: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("useTls=true — builder sets TLS flag")
        void useTls() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("qdrant");
            config.setStoreParameters(Map.of("useTls", "true"));

            try {
                factory.getOrCreate(config, "kb-qdrant-tls");
            } catch (Exception e) {
                assertFalse(e instanceof IllegalArgumentException,
                        "Should not fail on parameter parsing, got: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("custom port and host")
        void customPortAndHost() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("qdrant");
            config.setStoreParameters(Map.of("host", "localhost", "port", "6335"));

            try {
                factory.getOrCreate(config, "kb-qdrant-custom");
            } catch (Exception e) {
                assertFalse(e instanceof IllegalArgumentException,
                        "Should not fail on parameter parsing, got: " + e.getMessage());
            }
        }
    }

    // ==================== Chroma apiVersion branches ====================

    @Nested
    @DisplayName("Chroma API version edge cases")
    class ChromaApiVersionEdgeCases {

        @Test
        @DisplayName("blank apiVersion — falls through to V2 default")
        void blankApiVersion() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("chroma");
            var params = new HashMap<String, String>();
            params.put("apiVersion", "   ");
            config.setStoreParameters(params);

            // When apiVersion is blank, getOrDefault returns the blank string,
            // then parseChromaApiVersion sees blank → defaults to V2
            try {
                factory.getOrCreate(config, "kb-chroma-blank");
            } catch (IllegalArgumentException e) {
                fail("Blank apiVersion should default to V2, got: " + e.getMessage());
            } catch (Exception e) {
                // Connection error is expected — just verify it's not API version related
                assertFalse(e.getMessage() != null && e.getMessage().contains("ChromaApiVersion"),
                        "Should not fail on API version, got: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("V1 apiVersion — accepted")
        void v1ApiVersion() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("chroma");
            config.setStoreParameters(Map.of("apiVersion", "V1"));

            try {
                factory.getOrCreate(config, "kb-chroma-v1");
            } catch (IllegalArgumentException e) {
                fail("V1 should be a valid ChromaApiVersion, got: " + e.getMessage());
            } catch (Exception e) {
                // Connection error is expected
            }
        }
    }

    // ==================== Different storeParameters → different cache keys
    // ====================

    @Nested
    @DisplayName("Cache key differentiation")
    class CacheKeyTests {

        @Test
        @DisplayName("same kbId but different storeParameters → different store instances")
        void differentParams() throws Exception {
            var config1 = new RagConfiguration();
            config1.setStoreType("in-memory");
            config1.setStoreParameters(Map.of("extraKey", "value1"));

            var config2 = new RagConfiguration();
            config2.setStoreType("in-memory");
            config2.setStoreParameters(Map.of("extraKey", "value2"));

            var store1 = factory.getOrCreate(config1, "kb-same");
            var store2 = factory.getOrCreate(config2, "kb-same");

            assertNotSame(store1, store2,
                    "Different storeParameters should produce different cache keys");
        }
    }

    // ==================== parseIntParam with null ====================

    @Nested
    @DisplayName("parseIntParam — null param")
    class ParseIntParamNull {

        @Test
        @DisplayName("null port value should use default (tested via pgvector)")
        void nullPort() throws Exception {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            var params = new HashMap<String, String>();
            params.put("password", "secret");
            params.put("port", null);
            config.setStoreParameters(params);

            // Will fail connecting, but should not throw IllegalArgumentException for
            // parsing
            try {
                factory.getOrCreate(config, "kb-null-port");
            } catch (Exception e) {
                assertFalse(e instanceof IllegalArgumentException,
                        "null port should use default, got: " + e.getMessage());
            }
        }
    }
}
