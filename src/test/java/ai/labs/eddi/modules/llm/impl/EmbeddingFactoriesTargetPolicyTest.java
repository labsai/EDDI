/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EmbeddingModelFactory} and {@link EmbeddingStoreFactory} hold every
 * endpoint and host they are given to the {@link OutboundTargetPolicy}: with
 * {@code eddi.security.ssrf-protection.enabled} a private address is refused
 * before any client is built — before pgvector would connect and
 * {@code CREATE TABLE} there, before a MongoDB client dials EDDI's own database
 * — and with it off only the metadata service is, as before.
 * <p>
 * No DNS: {@code store.corp.example} resolves to a private address,
 * {@code vectors.example.com} to a public one.
 */
@DisplayName("embedding factories honour the outbound target policy")
class EmbeddingFactoriesTargetPolicyTest {

    private static final String PRIVATE_HOST = "store.corp.example";
    private static final String PUBLIC_HOST = "vectors.example.com";

    private static final UrlValidationUtils.HostResolver RESOLVER = host -> switch (host) {
        case PRIVATE_HOST -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 7})};
        case PUBLIC_HOST -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})};
        default -> throw new UnknownHostException(host);
    };

    private static final OutboundTargetPolicy ON = new OutboundTargetPolicy(true, new SelfUrlResolver(Optional.of("http://127.0.0.1:7070"), 7070),
            RESOLVER);
    private static final OutboundTargetPolicy OFF = new OutboundTargetPolicy(false, null, RESOLVER);

    private GlobalVariableResolver gvr;
    private SecretResolver sr;

    @BeforeEach
    void setUp() {
        gvr = mock(GlobalVariableResolver.class);
        sr = mock(SecretResolver.class);
        when(gvr.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sr.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RagConfiguration model(String provider, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }

    private static RagConfiguration store(String type, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setStoreNamespace("id");
        config.setStoreType(type);
        config.setStoreParameters(params);
        return config;
    }

    @Nested
    @DisplayName("EmbeddingModelFactory")
    class Models {

        @Test
        @DisplayName("protection on: a private baseUrl is refused for openai, mistral, ollama and gemini, an endpoint for azure and vertex")
        void onRefusesPrivate() {
            var factory = new EmbeddingModelFactory(gvr, sr, ON);
            String privateUrl = "http://" + PRIVATE_HOST + ":11434";
            for (String provider : new String[]{"openai", "mistral", "ollama", "gemini"}) {
                var e = assertThrows(IllegalArgumentException.class,
                        () -> factory.getOrCreate(model(provider, Map.of("apiKey", "k", "baseUrl", privateUrl)), EmbeddingInputType.DOCUMENT),
                        provider);
                assertTrue(e.getMessage().contains("private/internal"), provider + ": " + e.getMessage());
            }
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(
                    model("azure-openai", Map.of("apiKey", "k", "endpoint", privateUrl)), EmbeddingInputType.DOCUMENT));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(
                    model("vertex", Map.of("project", "p", "endpoint", PRIVATE_HOST + ":443")), EmbeddingInputType.DOCUMENT));
        }

        @Test
        @DisplayName("protection on: ollama's default baseUrl is localhost, so it is refused too — the operator must name a public endpoint")
        void onRefusesOllamaDefault() {
            var factory = new EmbeddingModelFactory(gvr, sr, ON);
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(model("ollama", Map.of()), EmbeddingInputType.DOCUMENT));
        }

        @Test
        @DisplayName("protection on: a public endpoint and this deployment's own origin pass")
        void onAllowsPublicAndSelf() {
            var factory = new EmbeddingModelFactory(gvr, sr, ON);
            assertNotNull(factory.getOrCreate(model("openai", Map.of("apiKey", "k", "baseUrl", "https://" + PUBLIC_HOST + "/v1")),
                    EmbeddingInputType.DOCUMENT));
            assertNotNull(factory.getOrCreate(model("ollama", Map.of("baseUrl", "http://127.0.0.1:7070")), EmbeddingInputType.DOCUMENT));
        }

        @Test
        @DisplayName("protection off (and the two-argument constructor): a private endpoint builds, the metadata service does not")
        void offAllowsPrivate() {
            var factory = new EmbeddingModelFactory(gvr, sr, OFF);
            assertNotNull(factory.getOrCreate(model("openai", Map.of("apiKey", "k", "baseUrl", "http://" + PRIVATE_HOST + "/v1")),
                    EmbeddingInputType.DOCUMENT));
            var legacy = new EmbeddingModelFactory(gvr, sr);
            assertNotNull(legacy.getOrCreate(model("ollama", Map.of("baseUrl", "http://" + PRIVATE_HOST + ":11434")), EmbeddingInputType.DOCUMENT));
            var e = assertThrows(IllegalArgumentException.class,
                    () -> legacy.getOrCreate(model("ollama", Map.of("baseUrl", "http://169.254.169.254")), EmbeddingInputType.DOCUMENT));
            assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("EmbeddingStoreFactory")
    class Stores {

        @Test
        @DisplayName("protection on: a private pgvector host is refused before any connection or CREATE TABLE")
        void onRefusesPgvector() {
            var factory = new EmbeddingStoreFactory(gvr, sr, ON);
            var e = assertThrows(IllegalArgumentException.class,
                    () -> factory.getOrCreate("kb-pg", store("pgvector", Map.of("host", PRIVATE_HOST, "password", "pw"))));
            assertTrue(e.getMessage().startsWith("pgvector storeParameters.host refused"), e.getMessage());
            assertTrue(e.getMessage().contains("private/internal"), e.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> factory.getOrCreate("kb-pg2", store("pgvector", Map.of("host", "10.0.0.9", "password", "pw"))));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate("kb-pg3", store("pgvector", Map.of("password", "pw"))),
                    "the default host is localhost");
        }

        @Test
        @DisplayName("protection on: every host in a MongoDB connection string is checked — EDDI's own database is private")
        void onRefusesMongoHosts() {
            var factory = new EmbeddingStoreFactory(gvr, sr, ON);
            var e = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate("kb-mongo",
                    store("mongodb-atlas", Map.of("connectionString", "mongodb://" + PUBLIC_HOST + ":27017," + PRIVATE_HOST + ":27017/eddi"))));
            assertTrue(e.getMessage().startsWith("mongodb-atlas storeParameters.host refused"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate("kb-mongo2",
                    store("mongodb-atlas", Map.of("connectionString", "mongodb://127.0.0.1:27017/eddi"))));
        }

        @Test
        @DisplayName("protection on: private Qdrant, Elasticsearch and Chroma addresses are refused")
        void onRefusesOtherStores() {
            var factory = new EmbeddingStoreFactory(gvr, sr, ON);
            var qdrant = assertThrows(IllegalArgumentException.class,
                    () -> factory.getOrCreate("kb-q", store("qdrant", Map.of("host", PRIVATE_HOST))));
            assertTrue(qdrant.getMessage().startsWith("qdrant storeParameters.host refused"), qdrant.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> factory.getOrCreate("kb-es", store("elasticsearch", Map.of("serverUrl", "http://" + PRIVATE_HOST + ":9200"))));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate("kb-es2", store("elasticsearch", Map.of())),
                    "the default serverUrl is localhost");
            assertThrows(IllegalArgumentException.class,
                    () -> factory.getOrCreate("kb-ch", store("chroma", Map.of("baseUrl", "http://" + PRIVATE_HOST + ":8000"))));
        }

        @Test
        @DisplayName("protection on: a public Elasticsearch URL builds")
        void onAllowsPublic() {
            var factory = new EmbeddingStoreFactory(gvr, sr, ON);
            assertNotNull(factory.getOrCreate("kb-es-pub", store("elasticsearch", Map.of("serverUrl", "https://" + PUBLIC_HOST + ":9200"))));
        }

        @Test
        @DisplayName("protection off (and the two-argument constructor): a private Elasticsearch builds, the metadata service is refused")
        void offAllowsPrivate() {
            var factory = new EmbeddingStoreFactory(gvr, sr, OFF);
            assertNotNull(factory.getOrCreate("kb-es-priv", store("elasticsearch", Map.of("serverUrl", "http://" + PRIVATE_HOST + ":9200"))));
            var legacy = new EmbeddingStoreFactory(gvr, sr);
            assertDoesNotThrow(
                    () -> legacy.getOrCreate("kb-es-legacy", store("elasticsearch", Map.of("serverUrl", "http://" + PRIVATE_HOST + ":9200"))));
            var e = assertThrows(IllegalArgumentException.class,
                    () -> legacy.getOrCreate("kb-q-meta", store("qdrant", Map.of("host", "169.254.169.254"))));
            assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
        }
    }
}
