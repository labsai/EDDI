/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.secrets.SecretResolver;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The embedding request has to go where the configuration says.
 *
 * <p>
 * The {@code openai} provider was built from {@code model} and {@code apiKey}
 * only, so a knowledge base pointed at a private OpenAI-compatible endpoint
 * with {@code baseUrl} sent every document to api.openai.com. These tests stand
 * up a local endpoint and assert the request actually arrives there — building
 * the model without error proves nothing, because the defective builder built
 * fine too.
 */
@DisplayName("embedding providers honour the configured endpoint")
class EmbeddingModelFactoryEndpointTest {

    private static final String OPENAI_RESPONSE = "{\"object\":\"list\",\"model\":\"m\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
            + "\"embedding\":[0.1,0.2,0.3]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private EmbeddingModelFactory factory;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " auth=" + auth + " " + body);
            byte[] response = OPENAI_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var secretResolver = mock(SecretResolver.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> resolvingVault(inv.getArgument(0)));
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingModelFactory(globalVariableResolver, secretResolver);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** {@code ${vault:k}} resolves to {@code vault-value-for-k}. */
    private static Map<String, String> resolvingVault(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> value != null && value.startsWith("${vault:")
                ? "vault-value-for-" + value.substring(8, value.length() - 1)
                : value);
        return resolved;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static RagConfiguration config(String provider, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }

    @Test
    @DisplayName("vertex: an endpoint that is the cloud metadata service is refused, as for every other endpoint")
    void vertexEndpointIsHeldToTheMetadataGuard() {
        var params = Map.of("project", "p", "endpoint", "169.254.169.254:443");
        var e = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config("vertex", params), EmbeddingInputType.DOCUMENT));
        assertTrue(e.getMessage().contains("metadata"), e.getMessage());
    }

    @Test
    @DisplayName("openai: baseUrl receives the embedding request, with the vault-resolved key and the modelName alias")
    void openAiSendsToTheConfiguredBaseUrl() {
        var model = factory.getOrCreate(config("openai", Map.of("baseUrl", baseUrl(), "apiKey", "${vault:private-key}",
                "modelName", "private-embed", "timeout", "5000")), EmbeddingInputType.DOCUMENT);

        var embedding = model.embed("hello").content();

        assertEquals(3, embedding.dimension());
        assertEquals(1, requests.size(), requests.toString());
        String request = requests.get(0);
        assertTrue(request.startsWith("POST /v1/embeddings"), request);
        assertTrue(request.contains("auth=Bearer vault-value-for-private-key"), request);
        assertTrue(request.contains("\"private-embed\""), request);
    }

    @Test
    @DisplayName("mistral: baseUrl receives the embedding request")
    void mistralSendsToTheConfiguredBaseUrl() {
        var model = factory.getOrCreate(config("mistral", Map.of("baseUrl", baseUrl(), "apiKey", "k")),
                EmbeddingInputType.DOCUMENT);

        model.embed("hello");

        assertEquals(1, requests.size(), requests.toString());
        assertTrue(requests.get(0).startsWith("POST /v1/embeddings"), requests.get(0));
    }

    @Test
    @DisplayName("a provider that cannot take an endpoint refuses one rather than falling back to its public default")
    void endpointTheProviderCannotHonourIsRefused() {
        var bedrock = config("bedrock", Map.of("baseUrl", baseUrl()));

        var e = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(bedrock, EmbeddingInputType.DOCUMENT));
        assertTrue(e.getMessage().contains("baseUrl"), e.getMessage());

        var azureWithBaseUrl = config("azure-openai", Map.of("baseUrl", baseUrl(), "apiKey", "k"));
        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(azureWithBaseUrl, EmbeddingInputType.DOCUMENT));
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    @DisplayName("the same refusal at save time")
    void validateRefusesAnIgnoredEndpoint() {
        var bedrock = config("bedrock", Map.of("endpoint", "https://example.org"));

        var e = assertThrows(IllegalArgumentException.class, bedrock::validate);
        assertTrue(e.getMessage().contains("endpoint"), e.getMessage());
    }

    @Test
    @DisplayName("model and modelName set to different values is refused")
    void conflictingModelNamesAreRefused() {
        var config = config("openai", Map.of("model", "a", "modelName", "b", "apiKey", "k"));

        assertThrows(IllegalArgumentException.class, config::validate);
        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
    }

    @Test
    @DisplayName("a timeout that is not a positive number of milliseconds is refused")
    void badTimeoutIsRefused() {
        var config = config("openai", Map.of("baseUrl", baseUrl(), "apiKey", "k", "timeout", "soon"));

        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
    }

    @Test
    @DisplayName("a vault reference in a numeric field is not echoed back in its resolved form")
    void resolvedSecretInNumericFieldIsNotEchoed() {
        // The error reaches RAG search responses and ingestion status: quoting the
        // resolved value would hand an editor the plaintext the vault API withholds.
        var timeout = config("openai", Map.of("baseUrl", baseUrl(), "apiKey", "k", "timeout", "${vault:victim}"));
        var e1 = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(timeout, EmbeddingInputType.DOCUMENT));
        assertNoPlaintext(e1);

        var dimensions = config("openai", Map.of("baseUrl", baseUrl(), "apiKey", "k", "dimensions", "${vault:victim}"));
        var e2 = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(dimensions, EmbeddingInputType.DOCUMENT));
        assertNoPlaintext(e2);

        var model = config("openai", Map.of("baseUrl", baseUrl(), "apiKey", "k", "model", "${vault:victim}", "modelName", "x"));
        var e3 = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(model, EmbeddingInputType.DOCUMENT));
        assertNoPlaintext(e3);
    }

    private static void assertNoPlaintext(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage());
            assertTrue(!message.contains("vault-value-for-victim"), message);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mistral", "gemini"})
    @DisplayName("mistral, gemini: the cached client re-checks its destination before every request (DNS rebinding)")
    void cachedClientRechecksTheDestination(String provider) {
        // The build-time check passes; every later one — the transport's, per request
        // — refuses, as it would once the hostname's DNS answer turned private.
        var checks = new AtomicInteger();
        var policy = mock(OutboundTargetPolicy.class);
        doAnswer(inv -> {
            if (checks.incrementAndGet() > 1) {
                throw new IllegalArgumentException("now resolves to a private address");
            }
            return null;
        }).when(policy).validateUrl(anyString());
        var secretResolver = mock(SecretResolver.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var guarded = new EmbeddingModelFactory(globalVariableResolver, secretResolver, policy);

        var model = guarded.getOrCreate(config(provider, Map.of("baseUrl", baseUrl(), "apiKey", "k", "maxRetries", "0")),
                EmbeddingInputType.DOCUMENT);

        assertThrows(RuntimeException.class, () -> model.embed("hello"));
        assertTrue(checks.get() > 1, "the transport checked the request URL");
        assertEquals(List.of(), requests, "nothing reached the endpoint");
    }
}
