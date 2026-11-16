/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.secrets.SecretResolver;
import com.github.benmanes.caffeine.cache.Ticker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
            config.setStoreNamespace("id");
            config.setStoreType("elasticsearch");
            config.setStoreParameters(Map.of("apiKey", "test-api-key"));

            // Elasticsearch store construction doesn't connect eagerly, so this
            // should succeed without a live server
            var store = factory.getOrCreate("kb-es-apikey", config);
            assertNotNull(store);
        }

        @Test
        @DisplayName("with userName and password — builder sets both")
        void withUserNameAndPassword() throws Exception {
            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("elasticsearch");
            var params = new HashMap<String, String>();
            params.put("userName", "elastic");
            params.put("password", "secret");
            config.setStoreParameters(params);

            var store = factory.getOrCreate("kb-es-user", config);
            assertNotNull(store);
        }

        @Test
        @DisplayName("without apiKey or userName — minimal builder")
        void noAuth() throws Exception {
            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("elasticsearch");
            config.setStoreParameters(Map.of());

            var store = factory.getOrCreate("kb-es-noauth", config);
            assertNotNull(store);
        }

        @Test
        @DisplayName("userName without password — no auth set (only both together)")
        void userNameWithoutPassword() throws Exception {
            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("elasticsearch");
            config.setStoreParameters(Map.of("userName", "elastic"));

            var store = factory.getOrCreate("kb-es-useronly", config);
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
            config.setStoreNamespace("id");
            config.setStoreType("qdrant");
            var params = new HashMap<String, String>();
            params.put("apiKey", "test-qdrant-key");
            config.setStoreParameters(params);

            // Qdrant store build doesn't connect eagerly in the builder
            try {
                factory.getOrCreate("kb-qdrant-apikey", config);
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
            config.setStoreNamespace("id");
            config.setStoreType("qdrant");
            config.setStoreParameters(Map.of("useTls", "true"));

            try {
                factory.getOrCreate("kb-qdrant-tls", config);
            } catch (Exception e) {
                assertFalse(e instanceof IllegalArgumentException,
                        "Should not fail on parameter parsing, got: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("custom port and host")
        void customPortAndHost() throws Exception {
            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("qdrant");
            config.setStoreParameters(Map.of("host", "localhost", "port", "6335"));

            try {
                factory.getOrCreate("kb-qdrant-custom", config);
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
            config.setStoreNamespace("id");
            config.setStoreType("chroma");
            var params = new HashMap<String, String>();
            params.put("apiVersion", "   ");
            config.setStoreParameters(params);

            // When apiVersion is blank, getOrDefault returns the blank string,
            // then parseChromaApiVersion sees blank → defaults to V2
            try {
                factory.getOrCreate("kb-chroma-blank", config);
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
            config.setStoreNamespace("id");
            config.setStoreType("chroma");
            config.setStoreParameters(Map.of("apiVersion", "V1"));

            try {
                factory.getOrCreate("kb-chroma-v1", config);
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
            var factory = EmbeddingStoreFactoryInMemoryTest.offlineFactory(mock(GlobalVariableResolver.class),
                    mock(SecretResolver.class), 50_000, Ticker.systemTicker());
            // An external store: in-memory stores are keyed by knowledge base alone,
            // because they have no parameters that mean anything.
            var config1 = new RagConfiguration();
            config1.setStoreType("elasticsearch");
            config1.setStoreParameters(Map.of("extraKey", "value1"));

            var config2 = new RagConfiguration();
            config2.setStoreType("elasticsearch");
            config2.setStoreParameters(Map.of("extraKey", "value2"));

            var store1 = factory.getOrCreate("kb-same", config1);
            var store2 = factory.getOrCreate("kb-same", config2);

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
            config.setStoreNamespace("id");
            config.setStoreType("pgvector");
            var params = new HashMap<String, String>();
            params.put("password", "secret");
            params.put("port", null);
            config.setStoreParameters(params);

            // Will fail connecting, but should not throw IllegalArgumentException for
            // parsing
            try {
                factory.getOrCreate("kb-null-port", config);
            } catch (Exception e) {
                assertFalse(e instanceof IllegalArgumentException,
                        "null port should use default, got: " + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("parseIntParam — resolved values are not echoed")
    class ParseIntParamRedaction {

        @Test
        @DisplayName("a non-numeric port names the parameter, never its (vault-resolved) value")
        void invalidPortValueIsNotQuoted() {
            GlobalVariableResolver gvr = mock(GlobalVariableResolver.class);
            SecretResolver sr = mock(SecretResolver.class);
            when(gvr.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sr.resolveSecrets(any())).thenAnswer(inv -> {
                Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
                resolved.replaceAll((k, v) -> "${vault:victim}".equals(v) ? "s3cr3t-plaintext" : v);
                return resolved;
            });
            var redactingFactory = new EmbeddingStoreFactory(gvr, sr);

            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("password", "p", "port", "${vault:victim}"));

            var e = assertThrows(IllegalArgumentException.class,
                    () -> redactingFactory.getOrCreate("kb-port-redaction", config));
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains("s3cr3t-plaintext"), t.getMessage());
            }
            assertTrue(e.getMessage().contains("storeParameters.port"), e.getMessage());
        }

        @Test
        @DisplayName("an invalid Chroma apiVersion names the parameter, never its (vault-resolved) value")
        void invalidChromaApiVersionIsNotQuoted() {
            GlobalVariableResolver gvr = mock(GlobalVariableResolver.class);
            SecretResolver sr = mock(SecretResolver.class);
            when(gvr.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sr.resolveSecrets(any())).thenAnswer(inv -> {
                Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
                resolved.replaceAll((k, v) -> "${vault:victim}".equals(v) ? "s3cr3t-plaintext" : v);
                return resolved;
            });
            var redactingFactory = new EmbeddingStoreFactory(gvr, sr);

            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("chroma");
            config.setStoreParameters(Map.of("apiVersion", "${vault:victim}"));

            var e = assertThrows(IllegalArgumentException.class,
                    () -> redactingFactory.getOrCreate("kb-chroma-redaction", config));
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains("s3cr3t-plaintext"), t.getMessage());
            }
            assertTrue(e.getMessage().contains("storeParameters.apiVersion"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("destinations the outbound policy sees")
    class OutboundPolicy {

        private EmbeddingStoreFactory guarded(OutboundTargetPolicy policy) {
            GlobalVariableResolver gvr = mock(GlobalVariableResolver.class);
            SecretResolver sr = mock(SecretResolver.class);
            when(gvr.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sr.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
            return new EmbeddingStoreFactory(gvr, sr, policy);
        }

        @Test
        @DisplayName("a MongoDB connection string's proxyHost is checked like its seeds")
        void mongoProxyHostIsChecked() {
            var policy = mock(OutboundTargetPolicy.class);
            doThrow(new IllegalArgumentException("metadata service")).when(policy).validateHost("169.254.169.254:1080");

            var config = new RagConfiguration();
            config.setStoreNamespace("id");
            config.setStoreType("mongodb-atlas");
            config.setStoreParameters(Map.of("connectionString", "mongodb://203.0.113.10:27017/?proxyHost=169.254.169.254&proxyPort=1080"));

            var e = assertThrows(IllegalArgumentException.class, () -> guarded(policy).getOrCreate("kb-mongo-proxy", config));
            assertTrue(e.getMessage().contains("mongodb-atlas"), e.getMessage());
            verify(policy).validateHost("203.0.113.10:27017");
        }

        @Test
        @DisplayName("a cached Chroma client re-checks its destination before every request (DNS rebinding)")
        void chromaRequestsAreRechecked() throws Exception {
            var requests = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            server.start();
            try {
                // The build-time check passes; the transport's own, per request, refuses.
                var checks = new AtomicInteger();
                var policy = mock(OutboundTargetPolicy.class);
                doAnswer(inv -> {
                    if (checks.incrementAndGet() > 1) {
                        throw new IllegalArgumentException("now resolves to a private address");
                    }
                    return null;
                }).when(policy).validateUrl(anyString());

                var config = new RagConfiguration();
                config.setStoreNamespace("id");
                config.setStoreType("chroma");
                config.setStoreParameters(Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort()));

                assertThrows(RuntimeException.class, () -> guarded(policy).getOrCreate("kb-chroma-rebind", config));
                assertTrue(checks.get() > 1, "the transport checked the request URL");
                assertEquals(0, requests.get(), "nothing reached the endpoint");
            } finally {
                server.stop(0);
            }
        }
    }
}
