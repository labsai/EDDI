/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LlmConfiguration.ResponseValidation Tests")
class ResponseValidationTest {

    // ==================== Default Values ====================

    @Nested
    @DisplayName("default values")
    class DefaultValuesTests {

        @Test
        @DisplayName("enabled defaults to false")
        void enabledDefaultsFalse() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertFalse(rv.isEnabled());
        }

        @Test
        @DisplayName("onEmpty defaults to 'warn'")
        void onEmptyDefaultsWarn() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertEquals("warn", rv.getOnEmpty());
        }

        @Test
        @DisplayName("onTruncation defaults to 'warn'")
        void onTruncationDefaultsWarn() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertEquals("warn", rv.getOnTruncation());
        }

        @Test
        @DisplayName("onContentFilter defaults to 'warn'")
        void onContentFilterDefaultsWarn() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertEquals("warn", rv.getOnContentFilter());
        }

        @Test
        @DisplayName("onRefusal defaults to 'ignore'")
        void onRefusalDefaultsIgnore() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertEquals("ignore", rv.getOnRefusal());
        }

        @Test
        @DisplayName("onStreamingTimeout defaults to 'warn'")
        void onStreamingTimeoutDefaultsWarn() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertEquals("warn", rv.getOnStreamingTimeout());
        }
    }

    // ==================== Getters and Setters ====================

    @Nested
    @DisplayName("getters and setters")
    class GettersSettersTests {

        @Test
        @DisplayName("enabled setter works")
        void setEnabled() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setEnabled(true);
            assertTrue(rv.isEnabled());
        }

        @Test
        @DisplayName("onEmpty setter works")
        void setOnEmpty() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setOnEmpty("error");
            assertEquals("error", rv.getOnEmpty());
        }

        @Test
        @DisplayName("onTruncation setter works")
        void setOnTruncation() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setOnTruncation("retry");
            assertEquals("retry", rv.getOnTruncation());
        }

        @Test
        @DisplayName("onContentFilter setter works")
        void setOnContentFilter() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setOnContentFilter("fallback");
            assertEquals("fallback", rv.getOnContentFilter());
        }

        @Test
        @DisplayName("onRefusal setter works")
        void setOnRefusal() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setOnRefusal("error");
            assertEquals("error", rv.getOnRefusal());
        }

        @Test
        @DisplayName("onStreamingTimeout setter works")
        void setOnStreamingTimeout() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setOnStreamingTimeout("retry");
            assertEquals("retry", rv.getOnStreamingTimeout());
        }
    }

    // ==================== Task-level fields ====================

    @Nested
    @DisplayName("Task responseValidation and streamingTimeoutSeconds")
    class TaskLevelTests {

        @Test
        @DisplayName("streamingTimeoutSeconds defaults to null")
        void streamingTimeoutDefaultsNull() {
            var task = new LlmConfiguration.Task();
            assertNull(task.getStreamingTimeoutSeconds());
        }

        @Test
        @DisplayName("streamingTimeoutSeconds getter/setter works")
        void streamingTimeoutSetterGetter() {
            var task = new LlmConfiguration.Task();
            task.setStreamingTimeoutSeconds(60);
            assertEquals(60, task.getStreamingTimeoutSeconds());
        }

        @Test
        @DisplayName("responseValidation defaults to null on Task")
        void responseValidationDefaultsNull() {
            var task = new LlmConfiguration.Task();
            assertNull(task.getResponseValidation());
        }

        @Test
        @DisplayName("responseValidation getter/setter works")
        void responseValidationSetterGetter() {
            var task = new LlmConfiguration.Task();
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setEnabled(true);
            rv.setOnEmpty("error");

            task.setResponseValidation(rv);

            assertNotNull(task.getResponseValidation());
            assertTrue(task.getResponseValidation().isEnabled());
            assertEquals("error", task.getResponseValidation().getOnEmpty());
        }
    }

    // ==================== Jackson Serialization ====================

    @Nested
    @DisplayName("Jackson serialization")
    class JacksonSerializationTests {

        @Test
        @DisplayName("ResponseValidation round-trip preserves all fields")
        void roundTrip() throws Exception {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setEnabled(true);
            rv.setOnEmpty("error");
            rv.setOnTruncation("retry");
            rv.setOnContentFilter("fallback");
            rv.setOnRefusal("warn");
            rv.setOnStreamingTimeout("error");

            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsString(rv);
            var deserialized = mapper.readValue(json, LlmConfiguration.ResponseValidation.class);

            assertTrue(deserialized.isEnabled());
            assertEquals("error", deserialized.getOnEmpty());
            assertEquals("retry", deserialized.getOnTruncation());
            assertEquals("fallback", deserialized.getOnContentFilter());
            assertEquals("warn", deserialized.getOnRefusal());
            assertEquals("error", deserialized.getOnStreamingTimeout());
        }

        @Test
        @DisplayName("default values survive round-trip")
        void defaultsRoundTrip() throws Exception {
            var rv = new LlmConfiguration.ResponseValidation();

            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsString(rv);
            var deserialized = mapper.readValue(json, LlmConfiguration.ResponseValidation.class);

            assertFalse(deserialized.isEnabled());
            assertEquals("warn", deserialized.getOnEmpty());
            assertEquals("warn", deserialized.getOnTruncation());
            assertEquals("warn", deserialized.getOnContentFilter());
            assertEquals("ignore", deserialized.getOnRefusal());
            assertEquals("warn", deserialized.getOnStreamingTimeout());
        }
    }

    /**
     * Four of the five validation triggers are provider-signalled or structural —
     * empty text, a length finish reason, a content-filter flag, a streaming
     * timeout — so they work for any language and any provider. {@code onRefusal}
     * is the only one whose trigger is a language guess, and it used to be a guess
     * in English only, hard-coded in {@code LlmTask}. A German- or
     * Japanese-language agent that set {@code onRefusal} therefore had a guardrail
     * that could never fire: no retry, no warning, no metric.
     */
    @Nested
    @DisplayName("refusal patterns")
    class RefusalPatternsTests {

        @Test
        @DisplayName("defaults to the four prefixes that used to be hard-coded")
        void defaultsToTheFormerLiterals() {
            var rv = new LlmConfiguration.ResponseValidation();
            assertEquals(List.of("i'm sorry, i can't", "i cannot", "i'm not able to", "as an ai"), rv.getRefusalPatterns(),
                    "an existing config that never mentions refusalPatterns must behave exactly as before");
        }

        @Test
        @DisplayName("a configured list replaces the defaults")
        void aConfiguredListReplacesTheDefaults() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setRefusalPatterns(List.of("es tut mir leid", "das kann ich nicht"));

            assertEquals(List.of("es tut mir leid", "das kann ich nicht"), rv.getRefusalPatterns());
            assertFalse(rv.getRefusalPatterns().contains("i cannot"),
                    "a German agent's list must not silently retain the English prefixes it replaced");
        }

        /**
         * The prefixes over-match in the other direction too: a legitimate answer
         * opening "I cannot confirm that from the data provided" was classified as a
         * refusal and, under {@code onRefusal: "error"}, failed the turn. An empty list
         * is how a deployment turns the heuristic off, so it has to survive.
         */
        @Test
        @DisplayName("an explicit empty list disables detection rather than restoring the defaults")
        void anEmptyListDisablesDetection() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setRefusalPatterns(List.of());
            assertTrue(rv.getRefusalPatterns().isEmpty(), "an empty list means 'do not detect refusals' and must not fall back");
        }

        @Test
        @DisplayName("null restores the defaults")
        void nullRestoresTheDefaults() {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setRefusalPatterns(List.of("only this"));
            rv.setRefusalPatterns(null);
            assertEquals(LlmConfiguration.ResponseValidation.DEFAULT_REFUSAL_PATTERNS, rv.getRefusalPatterns());
        }

        @Test
        @DisplayName("the list survives a JSON round-trip")
        void survivesRoundTrip() throws Exception {
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setRefusalPatterns(List.of("申し訳ありませんが"));

            var mapper = new ObjectMapper();
            var deserialized = mapper.readValue(mapper.writeValueAsString(rv), LlmConfiguration.ResponseValidation.class);

            assertEquals(List.of("申し訳ありませんが"), deserialized.getRefusalPatterns());
        }

        /**
         * The stored list is not the caller's list. Handing back the internal one would
         * let a config edit mutate a live task's policy.
         */
        @Test
        @DisplayName("the configured list is copied, not aliased")
        void theListIsCopied() {
            var mutable = new ArrayList<String>();
            mutable.add("first");
            var rv = new LlmConfiguration.ResponseValidation();
            rv.setRefusalPatterns(mutable);

            mutable.add("added later");

            assertEquals(List.of("first"), rv.getRefusalPatterns(), "mutating the caller's list must not change the stored policy");
        }
    }
}
