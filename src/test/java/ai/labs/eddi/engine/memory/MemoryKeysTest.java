package ai.labs.eddi.engine.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MemoryKeys — constants registry and MemoryKey behavior.
 */
class MemoryKeysTest {

    @Test
    @DisplayName("INPUT key is public")
    void inputIsPublic() {
        assertTrue(MemoryKeys.INPUT.isPublic());
        assertEquals("input", MemoryKeys.INPUT.key());
    }

    @Test
    @DisplayName("INPUT_INITIAL key is private")
    void inputInitialIsPrivate() {
        assertFalse(MemoryKeys.INPUT_INITIAL.isPublic());
        assertEquals("input:initial", MemoryKeys.INPUT_INITIAL.key());
    }

    @Test
    @DisplayName("ACTIONS key is public")
    void actionsIsPublic() {
        assertTrue(MemoryKeys.ACTIONS.isPublic());
        assertEquals("actions", MemoryKeys.ACTIONS.key());
    }

    @Test
    @DisplayName("LANGCHAIN key is public")
    void langchainIsPublic() {
        assertTrue(MemoryKeys.LANGCHAIN.isPublic());
        assertEquals("langchain", MemoryKeys.LANGCHAIN.key());
    }

    @Test
    @DisplayName("SYSTEM_MESSAGE key is private")
    void systemMessageIsPrivate() {
        assertFalse(MemoryKeys.SYSTEM_MESSAGE.isPublic());
        assertEquals("systemMessage", MemoryKeys.SYSTEM_MESSAGE.key());
    }

    @Test
    @DisplayName("PROMPT key is private")
    void promptIsPrivate() {
        assertFalse(MemoryKeys.PROMPT.isPublic());
        assertEquals("prompt", MemoryKeys.PROMPT.key());
    }

    @Test
    @DisplayName("prefix constants are non-null")
    void prefixConstants() {
        assertEquals("output", MemoryKeys.OUTPUT_PREFIX);
        assertEquals("quickReplies", MemoryKeys.QUICK_REPLIES_PREFIX);
        assertEquals("httpCalls", MemoryKeys.HTTP_CALLS_PREFIX);
        assertEquals("context", MemoryKeys.CONTEXT_PREFIX);
    }

    @Test
    @DisplayName("INTENTS key is public")
    void intentsIsPublic() {
        assertTrue(MemoryKeys.INTENTS.isPublic());
        assertEquals("intents", MemoryKeys.INTENTS.key());
    }

    @Test
    @DisplayName("EXPRESSIONS_PARSED key is private")
    void expressionsParsedIsPrivate() {
        assertFalse(MemoryKeys.EXPRESSIONS_PARSED.isPublic());
        assertEquals("expressions:parsed", MemoryKeys.EXPRESSIONS_PARSED.key());
    }

    @Test
    @DisplayName("PROPERTIES_EXTRACTED key is public")
    void propertiesExtractedIsPublic() {
        assertTrue(MemoryKeys.PROPERTIES_EXTRACTED.isPublic());
        assertEquals("properties:extracted", MemoryKeys.PROPERTIES_EXTRACTED.key());
    }

    @Test
    @DisplayName("ATTACHMENTS key is public")
    void attachmentsIsPublic() {
        assertTrue(MemoryKeys.ATTACHMENTS.isPublic());
        assertEquals("attachments", MemoryKeys.ATTACHMENTS.key());
    }

    @Test
    @DisplayName("INPUT_NORMALIZED key is private")
    void inputNormalizedIsPrivate() {
        assertFalse(MemoryKeys.INPUT_NORMALIZED.isPublic());
        assertEquals("input:normalized", MemoryKeys.INPUT_NORMALIZED.key());
    }

    @Test
    @DisplayName("EXPRESSIONS_MATCHES key is private")
    void expressionsMatchesIsPrivate() {
        assertFalse(MemoryKeys.EXPRESSIONS_MATCHES.isPublic());
        assertEquals("expressions:matches", MemoryKeys.EXPRESSIONS_MATCHES.key());
    }
}
