/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart;
import ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.Content;
import ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConversationLog — toString, equals/hashCode, and inner types.
 */
class ConversationLogTest {

    @Test
    @DisplayName("defaults — empty messages list")
    void defaults() {
        var log = new ConversationLog();
        assertNotNull(log.getMessages());
        assertTrue(log.getMessages().isEmpty());
    }

    @Test
    @DisplayName("constructor with messages")
    void constructorWithMessages() {
        var part = new ConversationPart("user", List.of(new Content(ContentType.text, "Hi")));
        var log = new ConversationLog(List.of(part));
        assertEquals(1, log.getMessages().size());
    }

    @Test
    @DisplayName("toString formats role: content")
    void toStringFormat() {
        var parts = new LinkedList<ConversationPart>();
        parts.add(new ConversationPart("user", List.of(new Content(ContentType.text, "Hello"))));
        parts.add(new ConversationPart("assistant", List.of(new Content(ContentType.text, "Hi there"))));
        var log = new ConversationLog(parts);

        String result = log.toString();
        assertTrue(result.contains("user:"));
        assertTrue(result.contains("assistant:"));
    }

    @Test
    @DisplayName("toObject returns messages list")
    void toObject() {
        var log = new ConversationLog();
        assertSame(log.getMessages(), log.toObject());
    }

    @Test
    @DisplayName("equals — same messages")
    void equalsTrue() {
        var log1 = new ConversationLog(new LinkedList<>());
        var log2 = new ConversationLog(new LinkedList<>());
        assertEquals(log1, log2);
    }

    @Test
    @DisplayName("equals — same reference")
    void equalsSameRef() {
        var log = new ConversationLog();
        assertEquals(log, log);
    }

    @Test
    @DisplayName("equals — null returns false")
    void equalsNull() {
        assertNotEquals(null, new ConversationLog());
    }

    @Test
    @DisplayName("equals — different type returns false")
    void equalsDifferentType() {
        assertNotEquals("string", new ConversationLog());
    }

    @Test
    @DisplayName("hashCode consistency")
    void hashCodeConsistency() {
        var log1 = new ConversationLog(new LinkedList<>());
        var log2 = new ConversationLog(new LinkedList<>());
        assertEquals(log1.hashCode(), log2.hashCode());
    }

    @Test
    @DisplayName("setMessages replaces list")
    void setMessages() {
        var log = new ConversationLog();
        var part = new ConversationPart("system", List.of(new Content(ContentType.text, "Setup")));
        log.setMessages(List.of(part));
        assertEquals(1, log.getMessages().size());
    }

    // ==================== ConversationPart ====================

    @Nested
    @DisplayName("ConversationPart")
    class ConversationPartTests {

        @Test
        void noArgConstructor() {
            var part = new ConversationPart();
            assertNull(part.getRole());
            assertNull(part.getContent());
        }

        @Test
        void allArgsConstructor() {
            var content = List.of(new Content(ContentType.text, "msg"));
            var part = new ConversationPart("user", content);
            assertEquals("user", part.getRole());
            assertEquals(1, part.getContent().size());
        }

        @Test
        void setters() {
            var part = new ConversationPart();
            part.setRole("assistant");
            part.setContent(List.of());
            assertEquals("assistant", part.getRole());
            assertTrue(part.getContent().isEmpty());
        }

        @Test
        void equalsAndHashCode() {
            var c = List.of(new Content(ContentType.text, "hi"));
            var p1 = new ConversationPart("user", c);
            var p2 = new ConversationPart("user", c);
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        void equalsSameRef() {
            var p = new ConversationPart("user", List.of());
            assertEquals(p, p);
        }

        @Test
        void equalsNull() {
            assertNotEquals(null, new ConversationPart("user", List.of()));
        }

        @Test
        void toStringContainsRole() {
            var p = new ConversationPart("user", List.of());
            assertTrue(p.toString().contains("user"));
        }
    }

    // ==================== Content ====================

    @Nested
    @DisplayName("Content")
    class ContentTests {

        @Test
        void noArgConstructor() {
            var c = new Content();
            assertNull(c.getType());
            assertNull(c.getValue());
        }

        @Test
        void allArgsConstructor() {
            var c = new Content(ContentType.image, "data:image/png;base64,abc");
            assertEquals(ContentType.image, c.getType());
            assertEquals("data:image/png;base64,abc", c.getValue());
        }

        @Test
        void setters() {
            var c = new Content();
            c.setType(ContentType.pdf);
            c.setValue("doc.pdf");
            assertEquals(ContentType.pdf, c.getType());
            assertEquals("doc.pdf", c.getValue());
        }

        @Test
        void equalsAndHashCode() {
            var c1 = new Content(ContentType.text, "hello");
            var c2 = new Content(ContentType.text, "hello");
            assertEquals(c1, c2);
            assertEquals(c1.hashCode(), c2.hashCode());
        }

        @Test
        void equalsSameRef() {
            var c = new Content(ContentType.text, "x");
            assertEquals(c, c);
        }

        @Test
        void equalsNull() {
            assertNotEquals(null, new Content(ContentType.text, "x"));
        }

        @Test
        void equalsDifferentType() {
            assertNotEquals("string", new Content(ContentType.text, "x"));
        }

        @Test
        void toStringContainsType() {
            var c = new Content(ContentType.audio, "file.mp3");
            assertTrue(c.toString().contains("audio"));
        }
    }

    // ==================== ContentType ====================

    @Test
    @DisplayName("ContentType enum has all values")
    void contentTypeValues() {
        assertEquals(5, ContentType.values().length);
        assertNotNull(ContentType.valueOf("text"));
        assertNotNull(ContentType.valueOf("image"));
        assertNotNull(ContentType.valueOf("pdf"));
        assertNotNull(ContentType.valueOf("audio"));
        assertNotNull(ContentType.valueOf("video"));
    }
}
