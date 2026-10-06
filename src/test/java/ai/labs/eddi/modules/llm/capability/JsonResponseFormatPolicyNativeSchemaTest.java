/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4: the request carries the task's {@code responseSchema} as a native
 * {@code JSON_SCHEMA} format for the providers whose binding enforces one per
 * request, and nothing else changes for everyone else.
 */
@DisplayName("JsonResponseFormatPolicy — native schema (R4)")
class JsonResponseFormatPolicyNativeSchemaTest {

    private static final String SCHEMA = """
            {"type":"object","description":"the reply",
             "required":["htmlResponseText","mood"],
             "properties":{
               "htmlResponseText":{"type":"string","minLength":1,"description":"shown to the user"},
               "mood":{"type":"string","enum":["happy","sad"]},
               "count":{"type":"integer"},
               "tags":{"type":"array","items":{"type":"string"}}
             },
             "additionalProperties":false}""";

    private static ResponseFormat resolve(String provider, boolean tools, String override, String schema) {
        return JsonResponseFormatPolicy.of(true, provider, override, schema).resolve(tools);
    }

    @Nested
    @DisplayName("which providers get the schema")
    class Providers {

        @ParameterizedTest
        @ValueSource(strings = {"openai", "azure-openai", "mistral", "gemini"})
        @DisplayName("openai, azure-openai, mistral and gemini carry a JSON_SCHEMA-bearing JSON format")
        void nativeProviders(String provider) {
            ResponseFormat format = resolve(provider, false, null, SCHEMA);

            assertNotNull(format);
            assertEquals(ResponseFormatType.JSON, format.type());
            assertNotNull(format.jsonSchema(), provider + " should carry the schema");
            assertEquals("response", format.jsonSchema().name());
            assertTrue(JsonResponseFormatPolicy.supportsNativeSchema(provider));
        }

        @ParameterizedTest
        @ValueSource(strings = {"openai", "azure-openai", "mistral"})
        @DisplayName("tool-tolerant providers keep the schema when the request carries tools")
        void withTools(String provider) {
            assertNotNull(resolve(provider, true, null, SCHEMA).jsonSchema());
        }

        @Test
        @DisplayName("gemini with tools gets no format at all, schema or not")
        void geminiWithToolsUnchanged() {
            assertNull(resolve("gemini", true, null, SCHEMA));
        }

        @ParameterizedTest
        @ValueSource(strings = {"gemini-vertex", "xai", "deepseek", "groq", "anthropic", "ollama"})
        @DisplayName("providers without request-level schema support get schemaless JSON (or nothing) — never the schema")
        void absentForOthers(String provider) {
            ResponseFormat format = resolve(provider, false, null, SCHEMA);
            if (format != null) {
                assertSame(ResponseFormat.JSON, format, provider + " must not receive the schema");
                assertNull(format.jsonSchema());
            }
            assertFalse(JsonResponseFormatPolicy.supportsNativeSchema(provider));
        }

        @Test
        @DisplayName("jsonResponseFormat=off keeps even an openai request free of any format")
        void offWins() {
            assertNull(resolve("openai", false, "off", SCHEMA));
        }

        @Test
        @DisplayName("forcing JSON on for an unknown provider sends schemaless JSON, not the schema")
        void forcedOnUnknownProvider() {
            assertSame(ResponseFormat.JSON, resolve("some-gateway", false, "on", SCHEMA));
        }

        @Test
        @DisplayName("convertToObject=false never gets a format")
        void notRequested() {
            assertNull(JsonResponseFormatPolicy.of(false, "openai", null, SCHEMA).resolve(false));
        }
    }

    @Nested
    @DisplayName("fallback to schemaless JSON")
    class Fallback {

        @Test
        @DisplayName("no schema, blank schema and the 3-arg policy stay on schemaless JSON")
        void noSchema() {
            assertSame(ResponseFormat.JSON, resolve("openai", false, null, null));
            assertSame(ResponseFormat.JSON, resolve("openai", false, null, "  "));
            assertSame(ResponseFormat.JSON, JsonResponseFormatPolicy.of(true, "openai", null).resolve(false));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{not json", "[1,2]", "{\"type\":\"string\"}", "{\"type\":\"object\"}",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":[\"string\",\"null\"]}}}",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"array\"}}}",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"anyOf\":[{\"type\":\"string\"}]}}}"})
        @DisplayName("a schema that cannot be expressed faithfully falls back as a whole")
        void unconvertible(String schema) {
            assertSame(ResponseFormat.JSON, resolve("openai", false, null, schema));
        }
    }

    @Nested
    @DisplayName("conversion fidelity")
    class Conversion {

        @Test
        @DisplayName("object, required, enum, integer, array, description and additionalProperties survive")
        void converted() {
            var root = assertInstanceOf(JsonObjectSchema.class, resolve("openai", false, null, SCHEMA).jsonSchema().rootElement());

            assertEquals("the reply", root.description());
            assertEquals(List.of("htmlResponseText", "mood"), root.required());
            assertEquals(Boolean.FALSE, root.additionalProperties());
            assertEquals(4, root.properties().size());
            var text = assertInstanceOf(JsonStringSchema.class, root.properties().get("htmlResponseText"));
            assertEquals("shown to the user", text.description());
            var mood = assertInstanceOf(JsonEnumSchema.class, root.properties().get("mood"));
            assertEquals(List.of("happy", "sad"), mood.enumValues());
            assertInstanceOf(JsonIntegerSchema.class, root.properties().get("count"));
            var tags = assertInstanceOf(JsonArraySchema.class, root.properties().get("tags"));
            assertInstanceOf(JsonStringSchema.class, tags.items());
        }

        @Test
        @DisplayName("nested objects convert recursively")
        void nested() {
            String schema = "{\"type\":\"object\",\"properties\":{\"meta\":{\"type\":\"object\",\"required\":[\"id\"],"
                    + "\"properties\":{\"id\":{\"type\":\"string\"}}}}}";
            var root = assertInstanceOf(JsonObjectSchema.class, resolve("mistral", false, null, schema).jsonSchema().rootElement());
            var meta = assertInstanceOf(JsonObjectSchema.class, root.properties().get("meta"));
            assertEquals(List.of("id"), meta.required());
        }

        @Test
        @DisplayName("the converted request survives the real OpenAI request mapper")
        void acceptedByRequestBuilder() {
            // ChatRequest validates the format on build; this is the same object the
            // executors hand to the provider binding.
            var request = ChatRequest.builder()
                    .messages(UserMessage.from("hi"))
                    .responseFormat(resolve("openai", false, null, SCHEMA)).build();
            assertNotNull(request.responseFormat().jsonSchema());
        }
    }
}
