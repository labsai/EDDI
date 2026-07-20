/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
