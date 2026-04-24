/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EddiTemplateExtensions — UUID, JSON, and encoder utilities.
 */
class EddiTemplateExtensionsTest {

    // ==================== UUID Utils ====================

    @Nested
    @DisplayName("uuidUtils")
    class UuidUtilsTests {

        @Test
        @DisplayName("generateUUID returns valid UUID format")
        void generateUUID() {
            String uuid = EddiTemplateExtensions.generateUUID();
            assertNotNull(uuid);
            assertFalse(uuid.isEmpty());
            // UUID format: 8-4-4-4-12 hex digits
            assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }

        @Test
        @DisplayName("generateUUID returns unique values")
        void generateUUID_unique() {
            assertNotEquals(EddiTemplateExtensions.generateUUID(), EddiTemplateExtensions.generateUUID());
        }

        @Test
        @DisplayName("extractId from standard URI")
        void extractId_standard() {
            String result = EddiTemplateExtensions.extractId(
                    "eddi://ai.labs.agents/agentstore/agents/abc-123?version=1");
            assertEquals("abc-123", result);
        }

        @Test
        @DisplayName("extractId from URI without query string")
        void extractId_noQuery() {
            String result = EddiTemplateExtensions.extractId("/store/resources/myId");
            assertEquals("myId", result);
        }

        @Test
        @DisplayName("extractId from null returns empty")
        void extractId_null() {
            assertEquals("", EddiTemplateExtensions.extractId(null));
        }

        @Test
        @DisplayName("extractId from empty returns empty")
        void extractId_empty() {
            assertEquals("", EddiTemplateExtensions.extractId(""));
        }

        @Test
        @DisplayName("extractId from URI ending in slash returns empty")
        void extractId_trailingSlash() {
            assertEquals("", EddiTemplateExtensions.extractId("/store/"));
        }

        @Test
        @DisplayName("extractVersion from standard URI")
        void extractVersion_standard() {
            String result = EddiTemplateExtensions.extractVersion(
                    "eddi://ai.labs.agents/agentstore/agents/abc?version=3");
            assertEquals("3", result);
        }

        @Test
        @DisplayName("extractVersion with multiple params")
        void extractVersion_multipleParams() {
            String result = EddiTemplateExtensions.extractVersion(
                    "/resource?version=5&format=json");
            assertEquals("5", result);
        }

        @Test
        @DisplayName("extractVersion from null returns empty")
        void extractVersion_null() {
            assertEquals("", EddiTemplateExtensions.extractVersion(null));
        }

        @Test
        @DisplayName("extractVersion from empty returns empty")
        void extractVersion_empty() {
            assertEquals("", EddiTemplateExtensions.extractVersion(""));
        }

        @Test
        @DisplayName("extractVersion when no version param returns empty")
        void extractVersion_noParam() {
            assertEquals("", EddiTemplateExtensions.extractVersion("/resource?format=json"));
        }
    }

    // ==================== JSON Utils ====================

    @Nested
    @DisplayName("json")
    class JsonTests {

        @Test
        @DisplayName("serialize object to JSON string")
        void serialize() throws IOException {
            String json = EddiTemplateExtensions.serialize(Map.of("key", "value"));
            assertNotNull(json);
            assertTrue(json.contains("\"key\""));
            assertTrue(json.contains("\"value\""));
        }

        @Test
        @DisplayName("deserialize JSON string to object")
        void deserialize() throws IOException {
            Object result = EddiTemplateExtensions.deserialize("{\"name\":\"EDDI\"}");
            assertNotNull(result);
            assertInstanceOf(Map.class, result);
        }

        @Test
        @DisplayName("serialize null")
        void serialize_null() throws IOException {
            String json = EddiTemplateExtensions.serialize(null);
            assertEquals("null", json);
        }
    }

    // ==================== Encoder Utils ====================

    @Nested
    @DisplayName("encoder")
    class EncoderTests {

        @Test
        @DisplayName("base64 encodes correctly")
        void base64() {
            String result = EddiTemplateExtensions.base64("Hello World");
            assertEquals(Base64.getEncoder().encodeToString("Hello World".getBytes(UTF_8)), result);
        }

        @Test
        @DisplayName("base64Url encodes correctly")
        void base64Url() {
            String result = EddiTemplateExtensions.base64Url("Hello+World/Test");
            assertEquals(Base64.getUrlEncoder().encodeToString("Hello+World/Test".getBytes(UTF_8)), result);
        }

        @Test
        @DisplayName("base64Mime encodes correctly")
        void base64Mime() {
            String result = EddiTemplateExtensions.base64Mime("Short");
            assertEquals(Base64.getMimeEncoder().encodeToString("Short".getBytes(UTF_8)), result);
        }

        @Test
        @DisplayName("base64 with empty string")
        void base64_empty() {
            String result = EddiTemplateExtensions.base64("");
            assertEquals(Base64.getEncoder().encodeToString("".getBytes(UTF_8)), result);
        }
    }
}
