/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EDDI's {@code RetryConfiguration.executeWithRetry} is the single retry owner.
 * langchain4j clients retry on their own (two retries by default), which
 * stacked underneath it: three configured attempts became nine provider calls.
 * <p>
 * Gemini is asserted behaviourally — a fake HTTP client that always answers 503
 * counts the calls one {@code chat} makes. The other builders have no HTTP
 * seam, so they are asserted through the client's own {@code maxRetries} field,
 * which is what the retry loop reads.
 */
@DisplayName("Provider clients do not retry on their own")
class NoLibraryRetriesTest {

    @Test
    @DisplayName("Gemini: one chat() against a 503 makes exactly one HTTP call")
    void geminiMakesASingleHttpCall() {
        CountingFailingClient client = new CountingFailingClient(503);
        ChatModel model = new GeminiLanguageModelBuilder().build(new HashMap<>(Map.of("apiKey", "test-key", "modelName", "gemini-2.0-flash")),
                client);

        assertThrows(RuntimeException.class, () -> model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build()));

        assertEquals(1, client.calls.get(), "the library must not retry underneath executeWithRetry");
    }

    @Test
    @DisplayName("OpenAI, Anthropic, Mistral, Ollama, Gemini and Bedrock clients are built with zero retries")
    void clientsAreBuiltWithoutRetries() throws Exception {
        assertEquals(0, retries(new OpenAILanguageModelBuilder().build(Map.of("apiKey", "k", "modelName", "gpt-4o")), "maxRetries"));
        assertEquals(0, retries(new AnthropicLanguageModelBuilder().build(Map.of("apiKey", "k", "modelName", "claude-x")), "maxRetries"));
        assertEquals(0, retries(new MistralAiLanguageModelBuilder().build(Map.of("apiKey", "k", "modelName", "mistral-large")), "maxRetries"));
        assertEquals(0, retries(new OllamaLanguageModelBuilder().build(Map.of("model", "llama3", "baseUrl", "http://localhost:11434")),
                "maxRetries"));
        assertEquals(0, retries(new GeminiLanguageModelBuilder().build(Map.of("apiKey", "k", "modelName", "gemini-2.0-flash")), "maximumRetries"));
        assertEquals(0, retries(new BedrockLanguageModelBuilder().build(Map.of("modelId", "anthropic.claude-v2", "region", "us-east-1")),
                "maxRetries"));
    }

    private static int retries(ChatModel model, String field) throws Exception {
        Field f = model.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return ((Number) f.get(model)).intValue();
    }

    private static final class CountingFailingClient implements HttpClient, HttpClientBuilder {
        final AtomicInteger calls = new AtomicInteger();
        private final int status;

        CountingFailingClient(int status) {
            this.status = status;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            calls.incrementAndGet();
            throw new HttpException(status, "{\"error\":{\"code\":" + status + ",\"status\":\"UNAVAILABLE\"}}");
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            calls.incrementAndGet();
            listener.onError(new HttpException(status, "unavailable"));
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
