/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("openai-compatible (self-hosted) builder")
class SelfHostedOpenAiLanguageModelBuilderTest {

    private static final String URL = "http://vllm.inference.svc.cluster.local:8000/v1";
    private static final String MODEL = "ibm-granite/granite-4.0-h-small";

    private final OpenAILanguageModelBuilder delegate = new OpenAILanguageModelBuilder();
    private final SelfHostedOpenAiLanguageModelBuilder builder = new SelfHostedOpenAiLanguageModelBuilder(delegate);

    private static Map<String, String> endpoint(String... extra) {
        Map<String, String> p = new HashMap<>();
        p.put("baseUrl", URL);
        p.put("modelName", MODEL);
        for (int i = 0; i < extra.length; i += 2) {
            p.put(extra[i], extra[i + 1]);
        }
        return p;
    }

    /** Build through the real OpenAI mapping, onto the given HTTP client. */
    private ChatModel model(Map<String, String> params, HttpClientBuilder http) {
        return delegate.build(builder.prepare(params), Map.of(), http);
    }

    private static void sendAndFail(ChatModel model) {
        assertThrows(RuntimeException.class, () -> model.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build()));
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("baseUrl and modelName are required, and the message names each one")
        void requiredFields() {
            var noUrl = assertThrows(IllegalArgumentException.class, () -> builder.build(Map.of("modelName", MODEL)));
            assertTrue(noUrl.getMessage().contains("'baseUrl'"), noUrl.getMessage());

            var noModel = assertThrows(IllegalArgumentException.class, () -> builder.build(Map.of("baseUrl", URL)));
            assertTrue(noModel.getMessage().contains("'modelName'"), noModel.getMessage());

            var blank = assertThrows(IllegalArgumentException.class, () -> builder.build(Map.of("baseUrl", " ", "modelName", "")));
            assertTrue(blank.getMessage().contains("'baseUrl'") && blank.getMessage().contains("'modelName'"), blank.getMessage());
        }

        @Test
        @DisplayName("http:// in-cluster URLs are accepted")
        void httpAccepted() {
            assertEquals(URL, builder.prepare(endpoint()).get("baseUrl"));
            assertEquals("http://10.0.0.5:8080/v1", builder.prepare(endpoint("baseUrl", "http://10.0.0.5:8080/v1")).get("baseUrl"));
        }

        @Test
        @DisplayName("the cloud-metadata service is refused")
        void metadataRefused() {
            assertThrows(IllegalArgumentException.class, () -> builder.build(endpoint("baseUrl", "http://169.254.169.254/v1")));
            assertThrows(IllegalArgumentException.class, () -> builder.build(endpoint("baseUrl", "http://metadata.google.internal/v1")));
        }

        @Test
        @DisplayName("a non-http base URL is refused by name")
        void nonHttpRefused() {
            var e = assertThrows(IllegalArgumentException.class, () -> builder.build(endpoint("baseUrl", "file:///etc/passwd")));
            assertTrue(e.getMessage().contains("'baseUrl'"), e.getMessage());
        }

        @Test
        @DisplayName("a capability flag that is not a boolean does not stop the model from being built")
        void badFlag() {
            // A literal bad flag is a deploy error (OpenAiCompatibleConfigValidator); one
            // that only shows up once a template resolves must not fail the model build,
            // which would fail every turn. The flags shape requests, not the model.
            for (String key : List.of("jsonMode", "jsonModeWithTools", "vision")) {
                var prepared = builder.prepare(endpoint(key, "ture"));
                assertFalse(prepared.containsKey(key), key);
            }
        }

        @Test
        @DisplayName("flags are case-insensitive booleans")
        void goodFlags() {
            var prepared = builder.prepare(endpoint("jsonMode", "TRUE", "jsonModeWithTools", " false ", "vision", "True"));
            // this type's own keys never reach the OpenAI builder
            assertFalse(prepared.containsKey("jsonMode"));
            assertFalse(prepared.containsKey("jsonModeWithTools"));
            assertFalse(prepared.containsKey("vision"));
        }

        @Test
        @DisplayName("customParameters must be a JSON object")
        void customParametersStrict() {
            var e = assertThrows(IllegalArgumentException.class, () -> builder.build(endpoint("customParameters", "[1,2]")));
            assertTrue(e.getMessage().contains("'customParameters'"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> builder.build(endpoint("customParameters", "{not json")));
        }

        @Test
        @DisplayName("every own key is recognised, so none is warned about as unknown")
        void recognised() {
            assertTrue(builder.recognisedParameters().containsAll(OpenAiCompatibleEndpoint.OWN_KEYS));
            assertTrue(builder.recognisedParameters().containsAll(delegate.recognisedParameters()));
        }
    }

    @Nested
    @DisplayName("the request on the wire")
    class Wire {

        @Test
        @DisplayName("no apiKey: the request goes out with no Authorization header at all")
        void noAuthorizationWithoutKey() {
            for (Map<String, String> p : List.of(endpoint(), endpoint("apiKey", ""), endpoint("apiKey", "   "))) {
                RecordingHttpClient http = new RecordingHttpClient();
                sendAndFail(model(p, http));
                assertNull(http.header("Authorization"), "Authorization sent for " + p.get("apiKey"));
                assertTrue(http.request().url().startsWith(URL), http.request().url());
                assertEquals(MODEL, http.body().path("model").asText());
            }
        }

        @Test
        @DisplayName("with an apiKey it is sent as a bearer token")
        void bearerWithKey() {
            RecordingHttpClient http = new RecordingHttpClient();
            sendAndFail(model(endpoint("apiKey", "sk-local"), http));
            assertEquals("Bearer sk-local", http.header("Authorization"));
        }

        @Test
        @DisplayName("customParameters reach the body (vLLM chat_template_kwargs), and serverName and the flags do not")
        void extraBody() {
            RecordingHttpClient http = new RecordingHttpClient();
            sendAndFail(model(endpoint("customParameters", "{\"chat_template_kwargs\":{\"enable_thinking\":false}}", "serverName", "vllm",
                    "jsonMode", "true", "vision", "true"), http));
            JsonNode body = http.body();
            assertFalse(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
            assertFalse(body.has("serverName"));
            assertFalse(body.has("jsonMode"));
            assertFalse(body.has("vision"));
            assertFalse(body.has("response_format"), "the builder never bakes a response format in");
        }

        @Test
        @DisplayName("acceptance: a tool-calling turn completes against a stub server, with no response_format under default flags")
        void toolCallingTurn() {
            Map<String, String> params = endpoint("convertToObject", "true");
            StubServer server = new StubServer("""
                    {"id":"c1","object":"chat.completion","created":1,"model":"%s",
                     "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                       "tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\\"q\\":\\"eddi\\"}"}}]}}],
                     "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                    """.formatted(MODEL));
            ChatModel model = model(params, server);

            var policy = JsonResponseFormatPolicy.of(true, OpenAiCompatibleEndpoint.TYPE, null, null, params);
            var tool = ToolSpecification.builder().name("lookup").description("Look something up")
                    .parameters(JsonObjectSchema.builder().addStringProperty("q").build()).build();
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("find eddi")).toolSpecifications(tool)
                    .responseFormat(policy.resolve(true)).build();

            ChatResponse response = model.chat(request);

            assertEquals("lookup", response.aiMessage().toolExecutionRequests().getFirst().name());
            JsonNode sent = server.body();
            assertEquals("lookup", sent.path("tools").get(0).path("function").path("name").asText());
            assertFalse(sent.has("response_format"), "default flags must not send a response_format: " + sent);
            assertNull(server.header("Authorization"));
        }
    }

    /** Answers every request with one canned chat completion and records it. */
    private static final class StubServer implements HttpClient, HttpClientBuilder {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final String responseBody;
        private final List<HttpRequest> requests = new ArrayList<>();

        StubServer(String responseBody) {
            this.responseBody = responseBody;
        }

        JsonNode body() {
            try {
                return MAPPER.readTree(requests.getLast().body());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        String header(String name) {
            return requests.getLast().headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            requests.add(request);
            return SuccessfulHttpResponse.builder().statusCode(200).headers(Map.of("content-type", List.of("application/json")))
                    .body(responseBody).build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            throw new UnsupportedOperationException("streaming is not stubbed");
        }

        @Override
        public HttpClient build() {
            return this;
        }

        @Override
        public Duration connectTimeout() {
            return null;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return null;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            return this;
        }
    }
}
