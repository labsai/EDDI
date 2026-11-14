/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpClientProvider;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.HttpClientOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every provider builder configures its synchronous and its streaming client
 * from the same parameters, and the two must not drift apart: a setting applied
 * only to one of them would make an agent behave differently depending on
 * whether the channel streams.
 * <p>
 * For each provider, both clients are built from parameter maps that between
 * them set every key in {@link ILanguageModelBuilder#recognisedParameters()},
 * and the test asserts that
 * <ul>
 * <li>both models report equal {@code defaultRequestParameters()}, which is
 * what each request is built from, and</li>
 * <li>where the provider's HTTP client can be replaced, the first request each
 * client sends is the same: URL, headers, JSON body (minus the streaming
 * switch) and the client timeouts.</li>
 * </ul>
 * Bedrock's AWS SDK client cannot be intercepted offline, so it is compared on
 * its request parameters only. Response-side switches ({@code returnThinking})
 * and logging flags leave no trace on a request and are not compared.
 */
@DisplayName("provider chat/streaming parity")
class ProviderChatStreamingParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Body fields only the streaming request carries. */
    private static final Set<String> STREAMING_ONLY_FIELDS = Set.of("stream", "stream_options");

    /**
     * Headers that differ between any two requests: the body is compared without
     * the streaming switch, so its length is not compared either, and the Azure SDK
     * stamps each request with the current time and a fresh id.
     */
    private static final Set<String> UNCOMPARED_HEADERS = Set.of("content-length", "date", "x-ms-client-request-id");

    /**
     * One provider: the parameter maps that together set every recognised key, and
     * how to build each client on a recording transport.
     */
    record Case(String name, Set<String> recognised, List<Map<String, String>> variants,
            Function<Map<String, String>, ChatModel> chat, Function<Map<String, String>, StreamingChatModel> streaming,
            Function<Map<String, String>, Sent> chatWire, Function<Map<String, String>, Sent> streamingWire) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** What reached the transport, normalised to what both clients share. */
    record Sent(String url, Map<String, List<String>> headers, JsonNode body, Duration connectTimeout, Duration readTimeout) {
    }

    // ==================== the cases ====================

    static Stream<Case> cases() {
        List<Case> cases = new ArrayList<>();

        var openAi = new OpenAILanguageModelBuilder();
        cases.add(httpCase("openai", openAi.recognisedParameters(), List.of(openAiParameters()),
                (p, http) -> openAi.build(p, Map.of(), http), (p, http) -> openAi.buildStreaming(p, Map.of(), http)));

        for (OpenAiCompatibleProvider provider : OpenAiCompatibleProviders.all()) {
            cases.add(compatibleCase(provider));
        }

        var anthropic = new AnthropicLanguageModelBuilder();
        cases.add(httpCase("anthropic", anthropic.recognisedParameters(), List.of(anthropicParameters()), anthropic::build,
                anthropic::buildStreaming));

        var mistral = new MistralAiLanguageModelBuilder();
        cases.add(httpCase("mistral", mistral.recognisedParameters(), List.of(mistralParameters()), mistral::build,
                mistral::buildStreaming));

        var ollama = new OllamaLanguageModelBuilder();
        cases.add(httpCase("ollama", ollama.recognisedParameters(), List.of(ollamaParameters()), ollama::build,
                ollama::buildStreaming));

        var gemini = new GeminiLanguageModelBuilder();
        Map<String, String> geminiBudget = geminiParameters();
        geminiBudget.remove("thinkingLevel");
        cases.add(httpCase("gemini", gemini.recognisedParameters(), List.of(geminiParameters(), geminiBudget), gemini::build,
                gemini::buildStreaming));

        var azure = new AzureOpenAiLanguageModelBuilder();
        Map<String, String> azureNonAzureKey = azureParameters();
        azureNonAzureKey.remove("apiKey");
        azureNonAzureKey.put("nonAzureApiKey", "sk-openai");
        cases.add(new Case("azure-openai", azure.recognisedParameters(), List.of(azureParameters(), azureNonAzureKey),
                p -> azure.build(p, new RecordingAzureClient()), p -> azure.buildStreaming(p, new RecordingAzureClient()), p -> {
                    RecordingAzureClient http = new RecordingAzureClient();
                    assertThrows(RuntimeException.class, () -> azure.build(p, http).chat(turn()));
                    return http.sent();
                }, p -> {
                    RecordingAzureClient http = new RecordingAzureClient();
                    awaitStreamingTurn(azure.buildStreaming(p, http));
                    return http.sent();
                }));

        // The AWS SDK client cannot be intercepted offline: request parameters only.
        var bedrock = new BedrockLanguageModelBuilder();
        Map<String, String> bedrockBudget = bedrockParameters();
        bedrockBudget.remove("reasoningEffort");
        cases.add(new Case("bedrock", bedrock.recognisedParameters(), List.of(bedrockParameters(), bedrockBudget), bedrock::build,
                bedrock::buildStreaming, null, null));

        return cases.stream();
    }

    /**
     * A provider whose two clients both take a langchain4j
     * {@code HttpClientBuilder}.
     */
    private static Case httpCase(String name, Set<String> recognised, List<Map<String, String>> variants,
                                 BiFunction<Map<String, String>, RecordingHttpClient, ChatModel> chat,
                                 BiFunction<Map<String, String>, RecordingHttpClient, StreamingChatModel> streaming) {
        return new Case(name, recognised, variants, p -> chat.apply(p, new RecordingHttpClient()),
                p -> streaming.apply(p, new RecordingHttpClient()), p -> {
                    RecordingHttpClient http = new RecordingHttpClient();
                    return sentByChat(chat.apply(p, http), http);
                }, p -> {
                    RecordingHttpClient http = new RecordingHttpClient();
                    return sentByStreaming(streaming.apply(p, http), http);
                });
    }

    /**
     * An OpenAI-compatible provider runs its preset on top of the OpenAI builder;
     * the delegate is pointed at the recording transport.
     */
    private static Case compatibleCase(OpenAiCompatibleProvider provider) {
        Map<String, String> p = openAiParameters();
        p.put(OpenAiCompatibleLanguageModelBuilder.KEY_REGION, "eu");
        for (ModelParameterCatalog.Param param : ModelParameterCatalog.defaultCatalog().params(provider.id())) {
            p.put(param.key(), validValue(param));
        }
        Set<String> recognised = new OpenAiCompatibleLanguageModelBuilder(provider, new OpenAILanguageModelBuilder())
                .recognisedParameters();
        return httpCase(provider.id(), recognised, List.of(p),
                (params, http) -> new OpenAiCompatibleLanguageModelBuilder(provider, recordingOpenAi(http)).build(params),
                (params, http) -> new OpenAiCompatibleLanguageModelBuilder(provider, recordingOpenAi(http)).buildStreaming(params));
    }

    private static OpenAILanguageModelBuilder recordingOpenAi(RecordingHttpClient http) {
        return new OpenAILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters, Map<String, Object> customParameters) {
                return build(parameters, customParameters, http);
            }

            @Override
            public StreamingChatModel buildStreaming(Map<String, String> parameters, Map<String, Object> customParameters) {
                return buildStreaming(parameters, customParameters, http);
            }
        };
    }

    /** A value the catalog accepts for {@code param}. */
    private static String validValue(ModelParameterCatalog.Param param) {
        if (param.enumValues() != null && !param.enumValues().isEmpty()) {
            return param.enumValues().getFirst();
        }
        return switch (param.type()) {
            case "double" -> "0.5";
            case "int" -> "3";
            case "boolean" -> "true";
            case "stringList" -> "[\"x\"]";
            case "json" -> "{\"x\":1}";
            default -> "x";
        };
    }

    // ==================== the parameter maps ====================

    private static Map<String, String> params(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("params() takes key/value pairs, but got " + keyValues.length + " arguments");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, String> openAiParameters() {
        return params("apiKey", "sk-test", "modelName", "gpt-4o", "baseUrl", "https://llm.example.com/v1", "timeout", "12345",
                "logRequests", "true", "logResponses", "true", "responseFormat", "json", "temperature", "0.3", "maxTokens", "900",
                "maxCompletionTokens", "1000", "topP", "0.8", "frequencyPenalty", "0.1", "presencePenalty", "0.2",
                "stopSequences", "[\"END\"]", "seed", "42", "logitBias", "{\"50256\":-100}", "reasoningEffort", "low",
                "verbosity", "high", "serviceTier", "flex", "parallelToolCalls", "false", "strictTools", "true",
                "strictJsonSchema", "true", "store", "true", "metadata", "{\"team\":\"eddi\"}", "user", "u-1",
                "safetyIdentifier", "s-1", "organizationId", "org-1", "projectId", "proj-1", "promptCacheKey", "cache-1",
                "promptCacheRetention", "24h", "returnThinking", "true", "sendThinking", "true",
                "customHeaders", "{\"X-Trace\":\"abc\"}", "customQueryParams", "{\"tenant\":\"t1\"}",
                "customParameters", "{\"foo\":{\"bar\":1}}");
    }

    private static Map<String, String> anthropicParameters() {
        return params("apiKey", "sk-ant", "modelName", "claude-sonnet-4-5", "baseUrl", "https://anthropic.example.com/v1/",
                "timeout", "12345", "logRequests", "true", "logResponses", "true", "temperature", "1", "maxTokens", "5000",
                "topP", "0.9", "topK", "40", "stopSequences", "DONE,HALT", "thinkingType", "enabled", "thinkingBudgetTokens", "2048",
                "thinkingDisplay", "summarized", "returnThinking", "true", "sendThinking", "true", "effort", "medium",
                "cacheSystemMessages", "true", "cacheTools", "true", "cacheAutomatically", "true", "cacheTtl", "1h",
                "disableParallelToolUse", "true", "strictTools", "true", "userId", "u-1", "version", "2023-06-01",
                "beta", "context-1m-2025-08-07", "customHeaders", "{\"X-Trace\":\"abc\"}",
                "customParameters", "{\"service_tier\":\"auto\"}");
    }

    private static Map<String, String> mistralParameters() {
        return params("apiKey", "k", "modelName", "mistral-large-latest", "baseUrl", "https://mistral.example.com/v1",
                "timeout", "12345", "logRequests", "true", "logResponses", "true", "temperature", "0.4", "maxTokens", "800",
                "topP", "0.6", "seed", "99", "safePrompt", "true", "stopSequences", "X,Y", "frequencyPenalty", "0.3",
                "presencePenalty", "0.4", "strictJsonSchema", "true", "returnThinking", "true", "sendThinking", "true",
                "customHeaders", "{\"X-Trace\":\"abc\"}");
    }

    private static Map<String, String> ollamaParameters() {
        return params("model", "llama3", "baseUrl", "http://localhost:11434", "timeout", "12345", "logRequests", "true",
                "logResponses", "true", "temperature", "0.2", "maxTokens", "300", "topP", "0.7", "topK", "20", "think", "false",
                "returnThinking", "true", "numCtx", "8192", "minP", "0.05", "repeatPenalty", "1.1", "repeatLastN", "64",
                "seed", "3", "stopSequences", "[\"<|end|>\"]", "truncate", "false",
                "customHeaders", "{\"Authorization\":\"Bearer proxy-token\"}");
    }

    private static Map<String, String> geminiParameters() {
        return params("apiKey", "g-key", "modelName", "gemini-2.5-flash", "baseUrl", "https://gemini.example.com/v1beta",
                "timeout", "12345", "logRequestsAndResponses", "true", "temperature", "0.6", "maxOutputTokens", "700",
                "allowCodeExecution", "true", "returnThinking", "true", "sendThinking", "true", "topP", "0.9", "topK", "30",
                "seed", "7", "frequencyPenalty", "0.1", "presencePenalty", "0.2", "stopSequences", "STOP",
                "thinkingLevel", "low", "thinkingBudget", "1024", "allowGoogleSearch", "true", "allowGoogleMaps", "true",
                "allowUrlContext", "true", "safetySettings", "{\"HARASSMENT\":\"BLOCK_ONLY_HIGH\"}", "mediaResolution", "low",
                "cachedContentName", "cachedContents/abc", "customHeaders", "{\"X-Trace\":\"abc\"}");
    }

    private static Map<String, String> azureParameters() {
        return params("apiKey", "azure-key", "endpoint", "https://my-resource.openai.azure.com/", "deploymentName", "prod-chat",
                "serviceVersion", "2025-01-01-preview", "baseModel", "gpt-4o", "timeout", "12345", "logRequestsAndResponses", "true",
                "temperature", "0.3", "maxTokens", "900", "maxCompletionTokens", "1000", "topP", "0.8", "stopSequences", "A,B",
                "seed", "12345678901", "frequencyPenalty", "0.2", "presencePenalty", "0.1", "user", "u-1",
                "reasoningEffort", "low", "logitBias", "{\"50256\":-100}", "strictJsonSchema", "true",
                "customHeaders", "{\"X-Trace\":\"abc\"}");
    }

    private static Map<String, String> bedrockParameters() {
        return params("modelId", "anthropic.claude-sonnet-4-5", "region", "us-east-1", "timeout", "12345", "temperature", "0.5",
                "maxTokens", "4000", "topP", "0.9", "stopSequences", "END", "reasoningEffort", "medium", "reasoningBudgetTokens", "2048",
                "promptCachePlacement", "after_system", "promptCacheTtl", "5m", "guardrailId", "g-1", "guardrailVersion", "1",
                "guardrailStreamMode", "async", "serviceTier", "flex", "additionalModelRequestFields", "{\"top_k\":5}",
                "requestMetadata", "{\"team\":\"eddi\"}", "returnThinking", "true", "sendThinking", "true",
                "customHeaders", "{\"X-Trace\":\"abc\"}");
    }

    // ==================== the assertions ====================

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the parameter maps exercise every recognised parameter")
    void everyRecognisedParameterIsExercised(Case c) {
        Set<String> exercised = new HashSet<>();
        c.variants().forEach(v -> exercised.addAll(v.keySet()));
        Set<String> missing = new HashSet<>(c.recognised());
        missing.removeAll(exercised);
        assertTrue(missing.isEmpty(), c.name() + " recognises parameters this test does not set, so their parity is unchecked: "
                + missing);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("both clients report the same default request parameters")
    void sameDefaultRequestParameters(Case c) {
        for (Map<String, String> variant : c.variants()) {
            var chat = c.chat().apply(new HashMap<>(variant)).defaultRequestParameters();
            var streaming = c.streaming().apply(new HashMap<>(variant)).defaultRequestParameters();

            assertNotNull(chat, c.name());
            assertEquals(chat, streaming, c.name() + ": the streaming client's request parameters differ");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("both clients send the same request")
    void sameRequestOnTheWire(Case c) {
        if (c.chatWire() == null) {
            return; // Bedrock: the AWS SDK client cannot be intercepted offline
        }
        for (Map<String, String> variant : c.variants()) {
            Sent chat = c.chatWire().apply(new HashMap<>(variant));
            Sent streaming = c.streamingWire().apply(new HashMap<>(variant));

            assertEquals(chat.url(), streaming.url(), c.name() + ": URL");
            assertEquals(chat.headers(), streaming.headers(), c.name() + ": headers");
            assertEquals(chat.body(), streaming.body(), c.name() + ": body");
            assertEquals(chat.connectTimeout(), streaming.connectTimeout(), c.name() + ": connect timeout");
            assertEquals(chat.readTimeout(), streaming.readTimeout(), c.name() + ": read timeout");
        }
    }

    // ==================== the turn and its capture ====================

    /**
     * A turn that gives every setting something to act on: a system message (cache
     * flags), a tool (strict tools, tool caching), and an earlier answer with
     * reasoning (sendThinking).
     */
    private static ChatRequest turn() {
        return ChatRequest.builder()
                .messages(List.of(SystemMessage.from("You are terse."), UserMessage.from("hello"),
                        AiMessage.builder().text("Hi.").thinking("The user greeted me.").build(), UserMessage.from("look it up")))
                .toolSpecifications(ToolSpecification.builder().name("lookup").description("Looks a thing up")
                        .parameters(JsonObjectSchema.builder().addStringProperty("q").required("q").build()).build())
                .build();
    }

    private static Sent sentByChat(ChatModel model, RecordingHttpClient http) {
        assertThrows(RuntimeException.class, () -> model.chat(turn()));
        return sent(http);
    }

    private static Sent sentByStreaming(StreamingChatModel model, RecordingHttpClient http) {
        awaitStreamingTurn(model);
        return sent(http);
    }

    /** Runs one streaming turn and waits until the recorded failure arrives. */
    private static void awaitStreamingTurn(StreamingChatModel model) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        model.chat(turn(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                done.complete(null);
            }

            @Override
            public void onError(Throwable error) {
                done.complete(null);
            }
        });
        try {
            done.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the streaming turn did not finish", e);
        }
    }

    private static Sent sent(RecordingHttpClient http) {
        Map<String, List<String>> headers = new TreeMap<>();
        http.request().headers().forEach((name, values) -> {
            if (!UNCOMPARED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name.toLowerCase(Locale.ROOT), values);
            }
        });
        return new Sent(sameMethod(http.request().url()), headers, withoutStreamingFields(http.body()), http.connectTimeout(),
                http.readTimeout());
    }

    /**
     * Gemini names the streaming method differently
     * ({@code :streamGenerateContent?alt=sse}); everything else of the URL must
     * match.
     */
    private static String sameMethod(String url) {
        return url.replace(":streamGenerateContent", ":generateContent").replace("?alt=sse&", "?").replace("?alt=sse", "");
    }

    private static JsonNode withoutStreamingFields(JsonNode body) {
        ObjectNode copy = body.deepCopy();
        STREAMING_ONLY_FIELDS.forEach(copy::remove);
        return copy;
    }

    // ==================== Azure's transport ====================

    /**
     * The Azure SDK's counterpart of {@link RecordingHttpClient}: it records the
     * request and the client options, and answers 400 so no retry policy waits.
     */
    private static final class RecordingAzureClient implements HttpClientProvider, HttpClient {

        private final List<HttpRequest> requests = new ArrayList<>();
        private HttpClientOptions options;

        @Override
        public HttpClient createInstance() {
            return this;
        }

        @Override
        public HttpClient createInstance(HttpClientOptions clientOptions) {
            this.options = clientOptions;
            return this;
        }

        @Override
        public synchronized Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            return Mono.just(new BadRequest(request));
        }

        synchronized Sent sent() {
            if (requests.isEmpty()) {
                throw new AssertionError("no request reached the Azure HTTP client");
            }
            HttpRequest request = requests.getFirst();
            Map<String, List<String>> headers = new TreeMap<>();
            for (HttpHeader header : request.getHeaders()) {
                String name = header.getName().toLowerCase(Locale.ROOT);
                if (!UNCOMPARED_HEADERS.contains(name)) {
                    headers.put(name, header.getValuesList());
                }
            }
            JsonNode body;
            try {
                body = MAPPER.readTree(request.getBodyAsBinaryData().toString());
            } catch (Exception e) {
                throw new AssertionError("Azure request body is not JSON", e);
            }
            Duration timeout = options == null ? null : options.getResponseTimeout();
            Duration readTimeout = options == null ? null : options.getReadTimeout();
            return new Sent(request.getUrl().toString(), headers, withoutStreamingFields(body), timeout, readTimeout);
        }
    }

    /** A 400 with a JSON error body. */
    private static final class BadRequest extends HttpResponse {

        private static final byte[] BODY = "{\"error\":{\"code\":\"recorded\",\"message\":\"recorded\"}}"
                .getBytes(StandardCharsets.UTF_8);

        BadRequest(HttpRequest request) {
            super(request);
        }

        @Override
        public int getStatusCode() {
            return 400;
        }

        @Override
        public String getHeaderValue(String name) {
            return getHeaders().getValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders().set("Content-Type", "application/json");
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.just(ByteBuffer.wrap(BODY));
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(BODY);
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(BODY, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(BODY, charset));
        }
    }
}
