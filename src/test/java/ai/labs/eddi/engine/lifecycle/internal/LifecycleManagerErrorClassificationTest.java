/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LifecycleManager Error Classification Tests")
class LifecycleManagerErrorClassificationTest {

    // ==================== classifyError ====================

    @Nested
    @DisplayName("classifyError")
    class ClassifyErrorTests {

        @Test
        @DisplayName("returns 'timeout' for SocketTimeoutException")
        void socketTimeoutException() {
            assertEquals("timeout",
                    LifecycleManager.classifyError(new SocketTimeoutException("read timed out")));
        }

        @Test
        @DisplayName("returns 'timeout' for TimeoutException")
        void timeoutException() {
            assertEquals("timeout",
                    LifecycleManager.classifyError(new TimeoutException("operation timed out")));
        }

        @Test
        @DisplayName("returns 'transport' for ConnectException")
        void connectException() {
            assertEquals("transport",
                    LifecycleManager.classifyError(new ConnectException("Connection refused")));
        }

        @Test
        @DisplayName("returns 'transport' for UnknownHostException")
        void unknownHostException() {
            assertEquals("transport",
                    LifecycleManager.classifyError(new UnknownHostException("api.example.com")));
        }

        @Test
        @DisplayName("returns 'rate_limit' for message containing 'rate limit'")
        void rateLimitMessage() {
            assertEquals("rate_limit",
                    LifecycleManager.classifyError(new RuntimeException("Rate limit exceeded")));
        }

        @Test
        @DisplayName("returns 'rate_limit' for message containing '429'")
        void http429Message() {
            assertEquals("rate_limit",
                    LifecycleManager.classifyError(new RuntimeException("HTTP 429 Too Many Requests")));
        }

        @Test
        @DisplayName("returns 'content_filter' for message containing 'content_filter'")
        void contentFilterUnderscore() {
            assertEquals("content_filter",
                    LifecycleManager.classifyError(
                            new RuntimeException("Response blocked by content_filter policy")));
        }

        @Test
        @DisplayName("returns 'content_filter' for message containing 'content filter'")
        void contentFilterSpace() {
            assertEquals("content_filter",
                    LifecycleManager.classifyError(
                            new RuntimeException("Blocked by content filter")));
        }

        @Test
        @DisplayName("returns 'unknown' for generic RuntimeException")
        void unknownForGenericException() {
            assertEquals("unknown",
                    LifecycleManager.classifyError(new RuntimeException("something went wrong")));
        }

        @Test
        @DisplayName("walks cause chain — RuntimeException wrapping SocketTimeoutException")
        void walksCauseChain() {
            var root = new SocketTimeoutException("read timed out");
            var wrapped = new RuntimeException("LLM call failed", root);

            assertEquals("timeout", LifecycleManager.classifyError(wrapped));
        }

        @Test
        @DisplayName("walks cause chain — double-wrapped ConnectException")
        void walksDeepCauseChain() {
            var root = new ConnectException("Connection refused");
            var mid = new RuntimeException("transport error", root);
            var outer = new RuntimeException("task failed", mid);

            assertEquals("transport", LifecycleManager.classifyError(outer));
        }

        @Test
        @DisplayName("typed cause outranks a wrapper message — '429' wrapping a SocketTimeoutException is a timeout")
        void typedCauseOutranksWrapperMessage() {
            var root = new SocketTimeoutException("read timed out");
            var wrapped = new RuntimeException("HTTP 429 Too Many Requests", root);

            assertEquals("timeout", LifecycleManager.classifyError(wrapped));
        }

        @Test
        @DisplayName("returns 'rate_limit' for 'too many' in message")
        void tooManyMessage() {
            assertEquals("rate_limit",
                    LifecycleManager.classifyError(
                            new RuntimeException("Too many requests, slow down")));
        }

        @Test
        @DisplayName("returns 'unknown' for null message exception")
        void nullMessageException() {
            assertEquals("unknown",
                    LifecycleManager.classifyError(new RuntimeException((String) null)));
        }
    }

    // ==================== describeFailure ====================

    @Nested
    @DisplayName("describeFailure")
    class DescribeFailureTests {

        @Test
        @DisplayName("appends the innermost cause to the wrapper's message")
        void appendsInnermostCause() {
            var e = new RuntimeException("Streaming chat failed",
                    new IllegalStateException("middle", new IllegalArgumentException("model not found: claude-x")));

            assertEquals("Streaming chat failed: model not found: claude-x", LifecycleManager.describeFailure(e));
        }

        @Test
        @DisplayName("pulls the message out of a provider's JSON error body")
        void providerJson() {
            var e = new RuntimeException("Chat failed", new RuntimeException(
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"say \\\"hi\\\" first\"}}"));

            assertEquals("Chat failed: say \"hi\" first", LifecycleManager.describeFailure(e));
        }

        @Test
        @DisplayName("a JSON body as the only message is unwrapped too")
        void providerJsonAtTheTop() {
            assertEquals("quota exceeded",
                    LifecycleManager.describeFailure(new RuntimeException("{\"error\":{\"message\":\"quota exceeded\"}}")));
        }

        @Test
        @DisplayName("a cause that repeats the wrapper is not appended twice")
        void noRepetition() {
            var e = new RuntimeException("timeout after 30s", new RuntimeException("timeout after 30s"));

            assertEquals("timeout after 30s", LifecycleManager.describeFailure(e));
        }

        @Test
        @DisplayName("no message anywhere gives the class name")
        void noMessage() {
            assertEquals("IllegalStateException", LifecycleManager.describeFailure(new IllegalStateException()));
        }

        @Test
        @DisplayName("summarizeForAudit carries the innermost cause as well")
        void auditSummaryUnwraps() {
            var e = new RuntimeException("Streaming chat failed", new RuntimeException("`temperature` is deprecated"));

            assertEquals("Streaming chat failed: `temperature` is deprecated", LifecycleManager.summarizeForAudit(e));
        }
    }

    // ==================== summarizeForAudit ====================

    @Nested
    @DisplayName("summarizeForAudit")
    class SummarizeForAuditTests {

        @Test
        @DisplayName("returns exception message")
        void returnsMessage() {
            var result = LifecycleManager.summarizeForAudit(
                    new RuntimeException("Something failed"));

            assertEquals("Something failed", result);
        }

        @Test
        @DisplayName("truncates messages longer than 500 characters")
        void truncatesLongMessage() {
            String longMessage = "A".repeat(600);
            var result = LifecycleManager.summarizeForAudit(
                    new RuntimeException(longMessage));

            assertEquals(503, result.length()); // 500 chars + "..."
            assertTrue(result.endsWith("..."));
            assertEquals("A".repeat(500) + "...", result);
        }

        @Test
        @DisplayName("returns class name when message is null")
        void returnsClassNameForNullMessage() {
            var result = LifecycleManager.summarizeForAudit(
                    new RuntimeException((String) null));

            assertEquals("RuntimeException", result);
        }

        @Test
        @DisplayName("returns class name when message is blank")
        void returnsClassNameForBlankMessage() {
            var result = LifecycleManager.summarizeForAudit(
                    new RuntimeException("   "));

            assertEquals("RuntimeException", result);
        }

        @Test
        @DisplayName("redacts credentials — this summary reaches the audit ledger and admin SSE")
        void redactsCredentials() {
            var result = LifecycleManager.summarizeForAudit(
                    new RuntimeException("LLM call rejected key sk-abcdefghijklmnopqrstuvwxyz123456"));

            assertFalse(result.contains("sk-abcdefghijklmnopqrstuvwxyz123456"));
            assertTrue(result.contains("sk-<REDACTED>"));
        }

        @Test
        @DisplayName("redacts before truncating, so the cut cannot leave a secret fragment behind")
        void redactsBeforeTruncating() {
            // The key straddles the 500-char cut. Truncating first would leave an
            // 'sk-...' fragment too short to match the redaction pattern.
            String message = "x".repeat(480) + " sk-abcdefghijklmnopqrstuvwxyz123456";

            var result = LifecycleManager.summarizeForAudit(new RuntimeException(message));

            assertFalse(result.contains("sk-abcdefg"), "no secret fragment may survive the cut");
            assertTrue(result.contains("sk-<REDACTED>"));
        }

        @Test
        @DisplayName("preserves message exactly at 500 characters")
        void preservesExactly500Chars() {
            String exactMessage = "B".repeat(500);
            var result = LifecycleManager.summarizeForAudit(
                    new RuntimeException(exactMessage));

            assertEquals(500, result.length());
            assertEquals(exactMessage, result);
        }
    }
}
