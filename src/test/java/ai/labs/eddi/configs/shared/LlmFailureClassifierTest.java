/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Table-driven classification of realistic provider error bodies (fixtures
 * under {@code src/test/resources/llm-errors}), in the three shapes a failure
 * really arrives in: the bare {@link HttpException}, langchain4j's typed
 * wrapper around it (what {@code chatModel.chat()} throws), and that wrapper
 * again inside the {@link LifecycleException} {@code executeWithRetry} throws.
 */
class LlmFailureClassifierTest {

    private static final long NONE = -1L;

    static Stream<Arguments> providerErrors() {
        return Stream.of(
                // Gemini
                Arguments.of("gemini-429-per-minute", 429, FailureClass.RATE_LIMITED, 13_000L),
                Arguments.of("gemini-429-per-day", 429, FailureClass.QUOTA_EXHAUSTED, NONE),
                Arguments.of("gemini-429-fractional-delay", 429, FailureClass.RATE_LIMITED, 1_500L),
                Arguments.of("gemini-503-unavailable", 503, FailureClass.TRANSIENT, NONE),
                Arguments.of("gemini-500-internal", 500, FailureClass.TRANSIENT, NONE),
                Arguments.of("gemini-504-deadline", 504, FailureClass.TIMEOUT, NONE),
                Arguments.of("gemini-400-context-too-long", 400, FailureClass.CONTEXT_TOO_LONG, NONE),
                Arguments.of("gemini-400-invalid-argument", 400, FailureClass.BAD_REQUEST, NONE),
                Arguments.of("gemini-400-api-key-invalid", 400, FailureClass.AUTH, NONE),
                Arguments.of("gemini-404-model-not-found", 404, FailureClass.MODEL_NOT_FOUND, NONE),
                Arguments.of("gemini-403-permission-denied", 403, FailureClass.AUTH, NONE),
                // OpenAI
                Arguments.of("openai-429-insufficient-quota", 429, FailureClass.QUOTA_EXHAUSTED, NONE),
                Arguments.of("openai-429-rate-limit", 429, FailureClass.RATE_LIMITED, 20_000L),
                Arguments.of("openai-429-rate-limit-ms", 429, FailureClass.RATE_LIMITED, 250L),
                Arguments.of("openai-400-context-length", 400, FailureClass.CONTEXT_TOO_LONG, NONE),
                Arguments.of("openai-401-invalid-key", 401, FailureClass.AUTH, NONE),
                Arguments.of("openai-404-model-not-found", 404, FailureClass.MODEL_NOT_FOUND, NONE),
                Arguments.of("openai-400-bad-request", 400, FailureClass.BAD_REQUEST, NONE),
                // Anthropic
                Arguments.of("anthropic-529-overloaded", 529, FailureClass.TRANSIENT, NONE),
                Arguments.of("anthropic-429-rate-limit", 429, FailureClass.RATE_LIMITED, NONE),
                Arguments.of("anthropic-400-prompt-too-long", 400, FailureClass.CONTEXT_TOO_LONG, NONE),
                Arguments.of("anthropic-401-auth", 401, FailureClass.AUTH, NONE),
                Arguments.of("anthropic-500-api-error", 500, FailureClass.TRANSIENT, NONE),
                Arguments.of("anthropic-404-not-found", 404, FailureClass.MODEL_NOT_FOUND, NONE));
    }

    static Stream<Arguments> providerErrorsWithoutDelay() {
        return providerErrors().map(a -> Arguments.of(a.get()[0], a.get()[1], a.get()[2]));
    }

    @ParameterizedTest(name = "{0} (HTTP {1}) -> {2}")
    @MethodSource("providerErrors")
    void bareHttpException(String fixture, int status, FailureClass expected, long retryAfterMs) throws IOException {
        assertVerdict(new HttpException(status, body(fixture)), expected, retryAfterMs);
    }

    @ParameterizedTest(name = "{0} (HTTP {1}) -> {2}")
    @MethodSource("providerErrors")
    void langchain4jTypedWrapper(String fixture, int status, FailureClass expected, long retryAfterMs) throws IOException {
        assertVerdict(typed(new HttpException(status, body(fixture))), expected, retryAfterMs);
    }

    @ParameterizedTest(name = "{0} (HTTP {1}) -> {2}")
    @MethodSource("providerErrors")
    void insideLifecycleException(String fixture, int status, FailureClass expected, long retryAfterMs) throws IOException {
        var failure = new LifecycleException("Chat model execution failed after 3 attempts",
                new IllegalStateException("outer", typed(new HttpException(status, body(fixture)))));
        assertVerdict(failure, expected, retryAfterMs);
    }

    @ParameterizedTest(name = "{0} -> {2} retryable={2}")
    @MethodSource("providerErrorsWithoutDelay")
    void isRetryableErrorIsTheTransientRateLimitedTimeoutWrapper(String fixture, int status, FailureClass expected) throws IOException {
        boolean retryable = expected == FailureClass.TRANSIENT || expected == FailureClass.RATE_LIMITED || expected == FailureClass.TIMEOUT;
        assertEquals(retryable, RetryConfiguration.isRetryableError(typed(new HttpException(status, body(fixture)))), fixture);
    }

    // -------------------------------------------------- non-HTTP / edge shapes

    @Test
    @DisplayName("quota 429 is NOT retryable although a bare 429 is")
    void quotaIsNotRetryableButRateLimitIs() {
        assertTrue(RetryConfiguration.isRetryableError(new HttpException(429, "slow down")));
        assertFalse(RetryConfiguration.isRetryableError(new HttpException(429, "{\"error\":{\"code\":\"insufficient_quota\"}}")));
    }

    @Test
    @DisplayName("500 is retryable (it was not before)")
    void http500IsRetryable() {
        assertTrue(RetryConfiguration.isRetryableError(new HttpException(500, "upstream")));
        assertEquals(FailureClass.TRANSIENT, LlmFailureClassifier.classify(new HttpException(500, "upstream")).cls());
    }

    @Test
    @DisplayName("the provider hint is read from a message sentence when there is no RetryInfo")
    void retryInSentenceWithoutRetryInfo() {
        var failure = LlmFailureClassifier.classify(new HttpException(429, "Quota exceeded. Please retry in 2.5s."));
        assertEquals(FailureClass.RATE_LIMITED, failure.cls());
        assertEquals(2_500L, failure.retryAfterMs());
    }

    @Test
    @DisplayName("typed exceptions without a body classify by type")
    void typedWithoutBody() {
        assertEquals(FailureClass.AUTH, LlmFailureClassifier.classify(new AuthenticationException("nope")).cls());
        assertEquals(FailureClass.MODEL_NOT_FOUND, LlmFailureClassifier.classify(new ModelNotFoundException("nope")).cls());
        assertEquals(FailureClass.BAD_REQUEST, LlmFailureClassifier.classify(new InvalidRequestException("nope")).cls());
        assertEquals(FailureClass.CONTEXT_TOO_LONG,
                LlmFailureClassifier.classify(new InvalidRequestException("This model's maximum context length is 8192 tokens")).cls());
        assertEquals(FailureClass.RATE_LIMITED, LlmFailureClassifier.classify(new RateLimitException("slow down")).cls());
        assertEquals(FailureClass.TRANSIENT, LlmFailureClassifier.classify(new InternalServerException("boom")).cls());
    }

    @Test
    @DisplayName("timeouts of every flavour classify as TIMEOUT")
    void timeouts() {
        assertEquals(FailureClass.TIMEOUT, LlmFailureClassifier.classify(new SocketTimeoutException("read timed out")).cls());
        assertEquals(FailureClass.TIMEOUT, LlmFailureClassifier.classify(new HttpTimeoutException("request timed out")).cls());
        assertEquals(FailureClass.TIMEOUT, LlmFailureClassifier.classify(new TimeoutException("step timeout")).cls());
        assertEquals(FailureClass.TIMEOUT,
                LlmFailureClassifier.classify(new RuntimeException("wrapper", new SocketTimeoutException("read timed out"))).cls());
    }

    @Test
    @DisplayName("langchain4j's own TimeoutException (same simple name as the JDK's) is a TIMEOUT, not a generic transient")
    void langchain4jTimeoutException() throws Exception {
        // Reflection: both TimeoutExceptions cannot be imported into one file.
        Throwable providerTimeout = (Throwable) Class.forName("dev.langchain4j.exception.TimeoutException")
                .getConstructor(String.class).newInstance("provider timeout");
        assertEquals(FailureClass.TIMEOUT, LlmFailureClassifier.classify(providerTimeout).cls());
    }

    @Test
    @DisplayName("connection failures are TRANSIENT; unrecognised failures are UNKNOWN and not retryable")
    void transportAndUnknown() {
        assertEquals(FailureClass.TRANSIENT, LlmFailureClassifier.classify(new ConnectException("refused")).cls());
        assertEquals(FailureClass.TRANSIENT, LlmFailureClassifier.classify(new UnknownHostException("dns")).cls());
        var unknown = LlmFailureClassifier.classify(new IllegalArgumentException("bad input"));
        assertEquals(FailureClass.UNKNOWN, unknown.cls());
        assertFalse(unknown.isRetryable());
        assertEquals(FailureClass.UNKNOWN, LlmFailureClassifier.classify(null).cls());
    }

    @Test
    @DisplayName("outermost wins: a typed auth failure underneath a retryable-sounding wrapper is AUTH")
    void typedVerdictBeatsOuterMessage() {
        var wrapped = new RuntimeException("timeout while calling provider", new AuthenticationException("invalid api key"));
        assertEquals(FailureClass.AUTH, LlmFailureClassifier.classify(wrapped).cls());
    }

    @Test
    @DisplayName("an HTTP status/body beats a transient-sounding message above it")
    void httpBodyBeatsOuterMessage() throws IOException {
        var wrapped = new RuntimeException("rate limit hit, timeout", new HttpException(400, body("gemini-400-invalid-argument")));
        assertEquals(FailureClass.BAD_REQUEST, LlmFailureClassifier.classify(wrapped).cls());
    }

    @Test
    @DisplayName("a provider error JSON in a plain message (no HttpException) is still read")
    void jsonInPlainMessage() throws IOException {
        var failure = LlmFailureClassifier.classify(new RuntimeException("call failed: " + body("gemini-429-per-day")));
        assertEquals(FailureClass.QUOTA_EXHAUSTED, failure.cls());
    }

    @Test
    @DisplayName("a delay so large it overflows to infinity is capped at 24h, not read as short or thrown on")
    void overflowingDelayIsCapped() {
        String huge = "9".repeat(400);
        var fromMessage = LlmFailureClassifier.classify(new HttpException(429, "Please retry in " + huge + "s."));
        assertEquals(FailureClass.RATE_LIMITED, fromMessage.cls());
        assertEquals(86_400_000L, fromMessage.retryAfterMs());

        var fromRetryInfo = LlmFailureClassifier.classify(new HttpException(429,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\""
                        + huge + "s\"}]}}"));
        assertEquals(86_400_000L, fromRetryInfo.retryAfterMs());
    }

    @Test
    @DisplayName("a known non-429 status is not turned into RATE_LIMITED by rate-limit wording in the body")
    void bodySignalsDoNotOverrideAKnownStatus() {
        var failure = LlmFailureClassifier.classify(new HttpException(503,
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"upstream\"}}"));
        assertEquals(FailureClass.TRANSIENT, failure.cls());
        // ...while the same body with no HTTP status (embedded in a message) is still a
        // rate limit.
        assertEquals(FailureClass.RATE_LIMITED, LlmFailureClassifier
                .classify(new RuntimeException("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}")).cls());
    }

    @Test
    @DisplayName("an explicit 4xx status outranks transient-sounding body signals")
    void clientErrorBeatsTransientSounding() {
        var internal400 = LlmFailureClassifier.classify(new HttpException(400,
                "{\"error\":{\"type\":\"internal_error\",\"message\":\"bad field\"}}"));
        assertEquals(FailureClass.BAD_REQUEST, internal400.cls());
        assertFalse(internal400.isRetryable());

        var unavailable404 = LlmFailureClassifier.classify(new HttpException(404,
                "{\"error\":{\"code\":\"model_unavailable\",\"message\":\"gone\"}}"));
        assertEquals(FailureClass.MODEL_NOT_FOUND, unavailable404.cls());
        assertFalse(unavailable404.isRetryable());

        // ...while the same tokens with no status or a 5xx remain transient.
        assertEquals(FailureClass.TRANSIENT, LlmFailureClassifier.classify(new HttpException(502,
                "{\"error\":{\"type\":\"internal_error\"}}")).cls());
    }

    @Test
    @DisplayName("ContentFilteredException (an InvalidRequestException) is classified as content filtered, not a generic bad request")
    void contentFiltered() {
        var failure = LlmFailureClassifier.classify(new ContentFilteredException("blocked"));
        assertEquals(FailureClass.UNKNOWN, failure.cls());
        assertEquals("content filtered", failure.reason());
        assertFalse(failure.isRetryable());
    }

    @Test
    @DisplayName("a self-referential cause chain terminates")
    void cyclicChain() {
        var e = new RuntimeException("x") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(FailureClass.UNKNOWN, LlmFailureClassifier.classify(e).cls());
    }

    @Test
    @DisplayName("the reason never echoes the response body")
    void reasonDoesNotLeakBody() {
        var failure = LlmFailureClassifier
                .classify(new HttpException(400, "{\"error\":{\"message\":\"secret prompt text\",\"status\":\"INVALID_ARGUMENT\"}}"));
        assertFalse(failure.reason().contains("secret prompt text"));
        assertNull(failure.retryAfterMs());
    }

    // ----------------------------------------------------------------- helpers

    private static void assertVerdict(Throwable failure, FailureClass expected, long retryAfterMs) {
        LlmFailure verdict = LlmFailureClassifier.classify(failure);
        assertEquals(expected, verdict.cls(), verdict.reason());
        if (retryAfterMs == NONE) {
            assertNull(verdict.retryAfterMs(), "no retry delay expected");
        } else {
            assertEquals(retryAfterMs, verdict.retryAfterMs());
        }
    }

    /**
     * Mirrors langchain4j's {@code ExceptionMapper}: the status picks the type, the
     * HttpException is the cause.
     */
    private static RuntimeException typed(HttpException http) {
        int status = http.statusCode();
        if (status >= 500 && status < 600) {
            return new InternalServerException(http);
        }
        return switch (status) {
            case 401, 403 -> new AuthenticationException(http);
            case 404 -> new ModelNotFoundException(http);
            case 429 -> new RateLimitException(http);
            default -> status >= 400 && status < 500 ? new InvalidRequestException(http) : new RuntimeException(http);
        };
    }

    private static String body(String fixture) throws IOException {
        try (InputStream in = LlmFailureClassifierTest.class.getResourceAsStream("/llm-errors/" + fixture + ".json")) {
            if (in == null) {
                throw new IOException("missing fixture " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
