package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SlackEventHandlerTest {

    @Test
    void stripBotMention_removesMention() {
        assertEquals("hello world", SlackEventHandler.stripBotMention("<@U0123BOTID> hello world"));
    }

    @Test
    void stripBotMention_removesMultipleSpaces() {
        assertEquals("test", SlackEventHandler.stripBotMention("<@U0123BOTID>   test"));
    }

    @Test
    void stripBotMention_noMention_returnsOriginal() {
        assertEquals("no mention here", SlackEventHandler.stripBotMention("no mention here"));
    }

    @Test
    void stripBotMention_onlyMention_returnsEmpty() {
        assertEquals("", SlackEventHandler.stripBotMention("<@U0123BOTID>"));
    }

    @Test
    void stripBotMention_mentionInMiddle_onlyStripsPrefix() {
        // Only the leading mention should be stripped
        assertEquals("hello <@U999> world",
                SlackEventHandler.stripBotMention("<@U0123BOTID> hello <@U999> world"));
    }

    @ParameterizedTest
    @CsvSource({
            "'<@UBOT123> what is EDDI?', 'what is EDDI?'",
            "'<@U0A1B2C3D> ', ''",
            "'plain text', 'plain text'"
    })
    void stripBotMention_parameterized(String input, String expected) {
        assertEquals(expected, SlackEventHandler.stripBotMention(input));
    }
}
