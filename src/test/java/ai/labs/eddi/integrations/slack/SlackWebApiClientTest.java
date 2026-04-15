package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlackWebApiClientTest {

    @Test
    void escapeJson_handlesNull() {
        assertEquals("", SlackWebApiClient.escapeJson(null));
    }

    @Test
    void escapeJson_handlesPlainText() {
        assertEquals("hello world", SlackWebApiClient.escapeJson("hello world"));
    }

    @Test
    void escapeJson_escapesQuotes() {
        assertEquals("say \\\"hello\\\"", SlackWebApiClient.escapeJson("say \"hello\""));
    }

    @Test
    void escapeJson_escapesBackslashes() {
        assertEquals("path\\\\to\\\\file", SlackWebApiClient.escapeJson("path\\to\\file"));
    }

    @Test
    void escapeJson_escapesNewlines() {
        assertEquals("line1\\nline2", SlackWebApiClient.escapeJson("line1\nline2"));
    }

    @Test
    void escapeJson_escapesCarriageReturns() {
        assertEquals("line1\\rline2", SlackWebApiClient.escapeJson("line1\rline2"));
    }

    @Test
    void escapeJson_escapesTabs() {
        assertEquals("col1\\tcol2", SlackWebApiClient.escapeJson("col1\tcol2"));
    }

    @Test
    void escapeJson_handlesAllSpecialChars() {
        String input = "He said \"hello\"\npath\\file\ttab";
        String expected = "He said \\\"hello\\\"\\npath\\\\file\\ttab";
        assertEquals(expected, SlackWebApiClient.escapeJson(input));
    }
}
