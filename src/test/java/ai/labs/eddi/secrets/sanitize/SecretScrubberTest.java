package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretScrubberTest {

    private SecretScrubber scrubber;

    @BeforeEach
    void setUp() {
        scrubber = new SecretScrubber(new ObjectMapper());
    }

    @Test
    void scrubJson_knownSecretFieldNames() throws Exception {
        String json = """
                {
                    "apiKey": "sk-abc123secretValue",
                    "name": "My Config",
                    "password": "hunter2"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertFalse(scrubbed.contains("sk-abc123secretValue"));
        assertFalse(scrubbed.contains("hunter2"));
        assertTrue(scrubbed.contains("My Config"));
    }

    @Test
    void scrubJson_vaultReferences_passthrough() throws Exception {
        String json = """
                {
                    "apiKey": "${eddivault:default/bot1/openaiKey}",
                    "name": "My Config"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        // Vault references should be preserved (they're already safe)
        assertTrue(scrubbed.contains("${eddivault:default/bot1/openaiKey}"));
        assertTrue(scrubbed.contains("My Config"));
    }

    @Test
    void scrubJson_safeFields_untouched() throws Exception {
        String json = """
                {
                    "name": "My Bot",
                    "language": "en",
                    "greeting": "Hello, how can I help?"
                }
                """;

        String scrubbed = scrubber.scrubJson(json);

        assertTrue(scrubbed.contains("My Bot"));
        assertTrue(scrubbed.contains("en"));
        assertTrue(scrubbed.contains("Hello, how can I help?"));
    }

    @Test
    void scrubJson_nullAndEmpty() throws Exception {
        assertEquals("{}", scrubber.scrubJson("{}"));
        assertEquals("", scrubber.scrubJson(""));
    }
}
