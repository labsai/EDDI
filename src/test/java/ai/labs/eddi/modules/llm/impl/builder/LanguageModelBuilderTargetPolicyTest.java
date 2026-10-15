/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every provider builder that accepts an endpoint runs it through the injected
 * {@link OutboundTargetPolicy}: with
 * {@code eddi.security.ssrf-protection.enabled} a private host is refused, with
 * it off (and in tests, where nothing is injected) only the metadata service
 * is. One parameterised case per builder so a builder that regains a bare
 * metadata check fails here by name.
 * <p>
 * Hostnames resolve through a fixed table — no DNS. {@code llm.corp.example} is
 * private, {@code api.example.com} public.
 */
@DisplayName("LLM builders honour the outbound target policy")
class LanguageModelBuilderTargetPolicyTest {

    private static final String PRIVATE_URL = "http://llm.corp.example:8080/v1";
    private static final String PUBLIC_URL = "https://api.example.com/v1";
    private static final String SELF_URL = "http://127.0.0.1:7070/v1";
    private static final String METADATA_URL = "http://169.254.169.254/v1";

    private static final UrlValidationUtils.HostResolver RESOLVER = host -> switch (host) {
        case "llm.corp.example" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 5})};
        case "api.example.com" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})};
        default -> throw new UnknownHostException(host);
    };

    private static final OutboundTargetPolicy ON = new OutboundTargetPolicy(true, new SelfUrlResolver(Optional.of("http://127.0.0.1:7070"), 7070),
            RESOLVER);
    private static final OutboundTargetPolicy OFF = new OutboundTargetPolicy(false, null, RESOLVER);

    /**
     * One row per builder: how to build with a given endpoint under a given policy.
     * The build must get as far as the endpoint check without a network — these
     * builders construct clients lazily, and Vertex is driven through its
     * {@code applyTo} seam because {@code build()} needs GCP credentials.
     */
    static Stream<Arguments> builders() {
        return Stream.of(
                row("openai", (Consumer<Case>) c -> {
                    var b = new OpenAILanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("apiKey", "k", "modelName", "m", "baseUrl", c.endpoint));
                }),
                row("anthropic", c -> {
                    var b = new AnthropicLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("apiKey", "k", "modelName", "m", "baseUrl", c.endpoint));
                }),
                row("ollama", c -> {
                    var b = new OllamaLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("model", "m", "baseUrl", c.endpoint));
                }),
                row("ollama-streaming", c -> {
                    var b = new OllamaLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.buildStreaming(params("model", "m", "baseUrl", c.endpoint));
                }),
                row("mistral", c -> {
                    var b = new MistralAiLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("apiKey", "k", "modelName", "m", "baseUrl", c.endpoint));
                }),
                row("azure-openai", c -> {
                    var b = new AzureOpenAiLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("apiKey", "k", "deploymentName", "d", "endpoint", c.endpoint));
                }),
                row("gemini", c -> {
                    var b = new GeminiLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("apiKey", "k", "modelName", "m", "baseUrl", c.endpoint));
                }),
                row("gemini-streaming", c -> {
                    var b = new GeminiLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.buildStreaming(params("apiKey", "k", "modelName", "m", "baseUrl", c.endpoint));
                }),
                row("huggingface", c -> {
                    var b = new HuggingFaceLanguageModelBuilder();
                    b.targetPolicy = c.policy;
                    b.build(params("accessToken", "t", "modelId", "m", "baseUrl", c.endpoint));
                }),
                row("vertex-gemini", c -> VertexGeminiLanguageModelBuilder.applyTo(VertexAiGeminiChatModel.builder(),
                        // Vertex takes a bare host or a URL; given with its scheme so the self origin
                        // can match. The static seam has no field to fall back on, hence orPermissive.
                        params("project", "p", "location", "us-central1", "modelName", "gemini-2.5-pro", "apiEndpoint",
                                c.endpoint.replaceFirst("/v1$", "")),
                        OutboundTargetPolicy.orPermissive(c.policy))));
    }

    private record Case(String endpoint, OutboundTargetPolicy policy) {
    }

    private static Arguments row(String name, Consumer<Case> build) {
        return Arguments.of(name, build);
    }

    private static Map<String, String> params(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("params() takes key/value pairs, got " + keyValues.length + " arguments");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @ParameterizedTest(name = "{0}: protection on refuses a private endpoint")
    @MethodSource("builders")
    void protectionOnRefusesPrivate(String name, Consumer<Case> build) {
        var e = assertThrows(IllegalArgumentException.class, () -> build.accept(new Case(PRIVATE_URL, ON)));
        assertTrue(e.getMessage().contains("private/internal"), name + ": " + e.getMessage());
    }

    @ParameterizedTest(name = "{0}: protection on allows a public endpoint")
    @MethodSource("builders")
    void protectionOnAllowsPublic(String name, Consumer<Case> build) {
        assertDoesNotThrow(() -> build.accept(new Case(PUBLIC_URL, ON)), name);
    }

    @ParameterizedTest(name = "{0}: protection on allows this deployment's own origin")
    @MethodSource("builders")
    void protectionOnAllowsSelf(String name, Consumer<Case> build) {
        assertDoesNotThrow(() -> build.accept(new Case(SELF_URL, ON)), name);
    }

    @ParameterizedTest(name = "{0}: protection off allows a private endpoint")
    @MethodSource("builders")
    void protectionOffAllowsPrivate(String name, Consumer<Case> build) {
        assertDoesNotThrow(() -> build.accept(new Case(PRIVATE_URL, OFF)), name);
    }

    @ParameterizedTest(name = "{0}: protection off still refuses the metadata service")
    @MethodSource("builders")
    void protectionOffRefusesMetadata(String name, Consumer<Case> build) {
        var e = assertThrows(IllegalArgumentException.class, () -> build.accept(new Case(METADATA_URL, OFF)));
        assertTrue(e.getMessage().contains("instance-metadata"), name + ": " + e.getMessage());
    }

    @ParameterizedTest(name = "{0}: nothing injected behaves as protection off")
    @MethodSource("builders")
    void nothingInjectedIsPermissive(String name, Consumer<Case> build) {
        assertDoesNotThrow(() -> build.accept(new Case(PRIVATE_URL, null)), name);
    }

    @Test
    @DisplayName("the OpenAI-compatible wrapper validates through its delegate")
    void compatibleProviderGoesThroughDelegate() {
        var delegate = new OpenAILanguageModelBuilder();
        delegate.targetPolicy = ON;
        var provider = OpenAiCompatibleProviders.all().get(0);
        var builder = new OpenAiCompatibleLanguageModelBuilder(provider, delegate);
        assertThrows(IllegalArgumentException.class, () -> builder.build(params("apiKey", "k", "modelName", "m", "baseUrl", PRIVATE_URL)));
    }
}
