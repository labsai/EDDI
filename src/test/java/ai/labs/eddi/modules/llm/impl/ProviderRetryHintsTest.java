/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ProviderRetryHints} reads from a provider's refusal, starting
 * from what langchain4j actually hands over.
 */
class ProviderRetryHintsTest {

    static final String PER_MINUTE_QUOTA = "EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier";

    static String fixture(String name) {
        try (InputStream in = ProviderRetryHintsTest.class.getResourceAsStream("/llm-errors/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A 429 as langchain4j's exception mapper delivers it. */
    static RateLimitException rateLimited(String body) {
        return new RateLimitException(new HttpException(429, body));
    }

    @Nested
    @DisplayName("what langchain4j carries")
    class Library {

        /**
         * The plan listed this as unverified: is the HttpException's message the whole
         * body, RetryInfo and QuotaFailure included? A real GoogleAiEmbeddingModel, on
         * an HTTP client that answers the live 429, says yes — and that maxRetries(0)
         * is a single attempt that still maps to RateLimitException.
         */
        @Test
        @DisplayName("a Gemini 429 arrives as RateLimitException over an HttpException whose message is the full body")
        void geminiModelSurfacesTheBody() {
            String body = fixture("gemini-429-embed-per-minute");
            AtomicInteger calls = new AtomicInteger();
            var model = GoogleAiEmbeddingModel.builder()
                    .apiKey("test-key")
                    .modelName("gemini-embedding-2")
                    .maxRetries(0)
                    .httpClientBuilder(new AnsweringBuilder(429, body, calls))
                    .build();

            var thrown = assertThrows(RateLimitException.class, () -> model.embed("hello"));

            assertEquals(1, calls.get(), "maxRetries(0) makes exactly one request");
            var http = assertInstanceOf(HttpException.class, thrown.getCause());
            assertEquals(429, http.statusCode());
            assertEquals(body, http.getMessage(), "the message is the response body, verbatim");

            var hint = ProviderRetryHints.parse(thrown).orElseThrow();
            assertEquals(Duration.ofSeconds(26), hint.retryAfter());
            assertFalse(hint.daily());
            assertEquals(PER_MINUTE_QUOTA, hint.quotaId());
        }
    }

    @Nested
    @DisplayName("the delay")
    class Delay {

        @Test
        @DisplayName("RetryInfo.retryDelay \"26s\" is read, and wins over the 26.18 s in the message")
        void retryInfoDelay() {
            var hint = ProviderRetryHints.parse(rateLimited(fixture("gemini-429-embed-per-minute"))).orElseThrow();
            assertEquals(Duration.ofSeconds(26), hint.retryAfter());
            assertEquals(Duration.ofSeconds(26), hint.waitOrFallback());
        }

        @Test
        @DisplayName("a fractional retryDelay is kept to the millisecond, rounded up")
        void fractionalDelay() {
            var hint = ProviderRetryHints.parse(rateLimited(fixture("gemini-429-fractional-delay"))).orElseThrow();
            assertEquals(Duration.ofMillis(1500), hint.retryAfter());
            assertFalse(hint.daily());
        }

        @Test
        @DisplayName("OpenAI's \"try again in 20ms\" is a short per-minute wait")
        void openAiMessageDelay() {
            var hint = ProviderRetryHints.parse(rateLimited(fixture("openai-429-rate-limit-ms"))).orElseThrow();
            assertTrue(hint.retryAfter().toMillis() < 1000, "got " + hint.retryAfter());
            assertFalse(hint.daily());
        }

        @Test
        @DisplayName("a 429 whose body says nothing usable falls back to 30 s")
        void garbageBody() {
            var hint = ProviderRetryHints.parse(rateLimited("<html>Too Many Requests</html>{not json")).orElseThrow();
            assertNull(hint.retryAfter());
            assertEquals(ProviderRetryHints.FALLBACK_DELAY, hint.waitOrFallback());
            assertFalse(hint.daily());
            assertNull(hint.quotaId());
        }
    }

    @Nested
    @DisplayName("minute or day")
    class Window {

        @Test
        @DisplayName("a PerDay quota id is a daily exhaustion, whatever the delay says")
        void perDayQuotaId() {
            var hint = ProviderRetryHints.parse(rateLimited(fixture("gemini-429-embed-per-day"))).orElseThrow();
            assertTrue(hint.daily());
        }

        @Test
        @DisplayName("a PerMinute quota id is per-minute even when the delay is long")
        void perMinuteQuotaIdWinsOverALongDelay() {
            String body = fixture("gemini-429-embed-per-minute").replace("\"26s\"", "\"900s\"");
            var hint = ProviderRetryHints.parse(rateLimited(body)).orElseThrow();
            assertEquals(Duration.ofSeconds(900), hint.retryAfter());
            assertFalse(hint.daily(), "the quota id says minute");
        }

        @Test
        @DisplayName("with no quota id, a delay over 5 minutes counts as daily")
        void longDelayWithoutQuotaId() {
            var hint = ProviderRetryHints.parse(rateLimited("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\","
                    + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"600s\"}]}}"))
                    .orElseThrow();
            assertTrue(hint.daily());
        }

        @Test
        @DisplayName("with no quota id, a delay of 5 minutes or less is per-minute")
        void shortDelayWithoutQuotaId() {
            var hint = ProviderRetryHints.parse(rateLimited("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\","
                    + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"300s\"}]}}"))
                    .orElseThrow();
            assertFalse(hint.daily());
        }

        @Test
        @DisplayName("OpenAI's insufficient_quota is a spent balance: daily")
        void insufficientQuota() {
            var hint = ProviderRetryHints.parse(rateLimited(fixture("openai-429-insufficient-quota"))).orElseThrow();
            assertTrue(hint.daily());
        }
    }

    @Nested
    @DisplayName("not a throttle")
    class NotAThrottle {

        @Test
        @DisplayName("an authentication failure is no hint")
        void auth() {
            assertTrue(ProviderRetryHints.parse(new AuthenticationException(new HttpException(401,
                    fixture("openai-401-invalid-key")))).isEmpty());
        }

        @Test
        @DisplayName("a provider outage is no hint: it is the caller's transient failure")
        void outage() {
            assertTrue(ProviderRetryHints.parse(new InternalServerException(new HttpException(503,
                    fixture("gemini-503-unavailable")))).isEmpty());
        }

        @Test
        @DisplayName("null is no hint")
        void none() {
            assertTrue(ProviderRetryHints.parse(null).isEmpty());
        }
    }

    /** An HTTP client that answers every request with one status and body. */
    static final class AnsweringBuilder implements HttpClientBuilder {
        private final int status;
        private final String body;
        private final AtomicInteger calls;
        private Duration connectTimeout;
        private Duration readTimeout;

        AnsweringBuilder(int status, String body, AtomicInteger calls) {
            this.status = status;
            this.body = body;
            this.calls = calls;
        }

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            this.readTimeout = timeout;
            return this;
        }

        @Override
        public HttpClient build() {
            return new HttpClient() {
                @Override
                public SuccessfulHttpResponse execute(HttpRequest request) {
                    calls.incrementAndGet();
                    // What JdkHttpClient.execute throws for a non-2xx answer.
                    throw new HttpException(status, body);
                }

                @Override
                public void execute(HttpRequest request, ServerSentEventParser parser,
                                    ServerSentEventListener listener) {
                    throw new UnsupportedOperationException("not streamed");
                }
            };
        }
    }
}
