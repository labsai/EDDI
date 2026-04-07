package ai.labs.eddi.engine.memory.model;

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
}
