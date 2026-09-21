/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentTest {

    @Test
    void constructor_setsDefaults() {
        var att = new Attachment("image/png", "photo.png", 1024, "gridfs://abc123");
        assertNotNull(att.getId());
        assertEquals("image/png", att.getMimeType());
        assertEquals("photo.png", att.getFileName());
        assertEquals(1024, att.getSizeBytes());
        assertEquals("gridfs://abc123", att.getStorageRef());
        assertNotNull(att.getCreatedAt());
        assertNotNull(att.getMetadata());
    }

    @Test
    void matchesMimeType_exactMatch() {
        var att = new Attachment("image/png", "photo.png", 1024, "ref");
        assertTrue(att.matchesMimeType("image/png"));
        assertFalse(att.matchesMimeType("image/jpeg"));
    }

    @Test
    void matchesMimeType_wildcardMatch() {
        var att = new Attachment("image/png", "photo.png", 1024, "ref");
        assertTrue(att.matchesMimeType("image/*"));
        assertFalse(att.matchesMimeType("audio/*"));
    }

    @Test
    void matchesMimeType_globalWildcard() {
        var att = new Attachment("application/pdf", "doc.pdf", 2048, "ref");
        assertTrue(att.matchesMimeType("*/*"));
    }

    @Test
    void matchesMimeType_nullSafe() {
        var att = new Attachment("image/png", "photo.png", 1024, "ref");
        assertFalse(att.matchesMimeType(null));

        var att2 = new Attachment();
        assertFalse(att2.matchesMimeType("image/*"));
    }

    @Test
    void metadata_canBePopulated() {
        var att = new Attachment("image/png", "photo.png", 1024, "ref");
        att.setMetadata(Map.of("width", "1920", "height", "1080"));
        assertEquals("1920", att.getMetadata().get("width"));
        assertEquals("1080", att.getMetadata().get("height"));
    }

    // ==================== ContentSource ====================

    @Test
    void contentSource_storedTakesPrecedence() {
        var att = new Attachment("image/png", "photo.png", 1024, "gridfs://abc");
        att.setUrl("https://example.com/pic.png");
        att.setBase64Data("iVBOR...");
        assertEquals(Attachment.ContentSource.STORED, att.getContentSource());
    }

    @Test
    void contentSource_urlWhenNoStorageRef() {
        var att = new Attachment();
        att.setUrl("https://example.com/pic.png");
        att.setBase64Data("iVBOR...");
        assertEquals(Attachment.ContentSource.URL, att.getContentSource());
    }

    @Test
    void contentSource_base64WhenNoStorageRefOrUrl() {
        var att = new Attachment();
        att.setBase64Data("iVBOR...");
        assertEquals(Attachment.ContentSource.BASE64, att.getContentSource());
    }

    @Test
    void contentSource_noneWhenEmpty() {
        var att = new Attachment();
        assertEquals(Attachment.ContentSource.NONE, att.getContentSource());
    }

    // ==================== URL and Base64 fields ====================

    @Test
    void url_getterSetter() {
        var att = new Attachment();
        assertNull(att.getUrl());
        att.setUrl("https://example.com/file.png");
        assertEquals("https://example.com/file.png", att.getUrl());
    }

    @Test
    void base64Data_getterSetter() {
        var att = new Attachment();
        assertNull(att.getBase64Data());
        att.setBase64Data("iVBORw0KGgo=");
        assertEquals("iVBORw0KGgo=", att.getBase64Data());
    }

    // ==================== Serialization (no payload persisted)
    // ====================

    @Test
    void serialization_neverIncludesBase64Payload() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();

        var att = new Attachment("image/png", "photo.png", 1024, "gridfs://abc");
        var secretPayload = "SECRETiVBORw0KGgoAAAANSUhEUgAAPAYLOAD";
        att.setBase64Data(secretPayload);

        String json = mapper.writeValueAsString(att);

        // The raw base64 payload must NOT leak into persisted JSON — the transient
        // keyword alone does not stop Jackson's getter-based serialization.
        assertFalse(json.contains(secretPayload),
                "base64 payload must not be serialized into persisted JSON: " + json);
        assertFalse(json.contains("base64Data"),
                "base64Data property must be absent from persisted JSON: " + json);

        // Metadata that behavior rules match on must still be present.
        assertTrue(json.contains("image/png"));
        assertTrue(json.contains("photo.png"));
        assertTrue(json.contains("gridfs://abc"));
    }

    @Test
    void serialization_roundTripPreservesMetadataButNotPayload() throws Exception {
        // Mirror EDDI's persistence mapper: attachments have round-tripped through
        // Mongo since 6.0.0 despite the derived, setter-less contentSource getter,
        // so the store mapper ignores unknown properties on read.
        var mapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var att = new Attachment("application/pdf", "doc.pdf", 2048, "pg://xyz");
        att.setBase64Data("JVBERi0xLjQ=");

        String json = mapper.writeValueAsString(att);
        var restored = mapper.readValue(json, Attachment.class);

        assertEquals("application/pdf", restored.getMimeType());
        assertEquals("doc.pdf", restored.getFileName());
        assertEquals(2048, restored.getSizeBytes());
        assertEquals("pg://xyz", restored.getStorageRef());
        assertNull(restored.getBase64Data(), "payload must not survive persistence round-trip");
    }
}
