package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CallbackMatcher}.
 *
 * @since 6.0.0
 */
@DisplayName("CallbackMatcher")
class CallbackMatcherTest {

    @Test
    @DisplayName("replaceMatches — single match replaced")
    void singleMatch() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("testRegex"));
        String result = matcher.replaceMatches("This is the test: testRegex!", m -> "works");
        assertEquals("This is the test: works!", result);
    }

    @Test
    @DisplayName("replaceMatches — two occurrences replaced")
    void twoOccurrences() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("testRegex"));
        String result = matcher.replaceMatches("This is the test: testRegex + testRegex!", m -> "works");
        assertEquals("This is the test: works + works!", result);
    }

    @Test
    @DisplayName("replaceMatches — no match returns original")
    void noMatch() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("zzz"));
        String result = matcher.replaceMatches("hello world", m -> "bar");
        assertEquals("hello world", result);
    }

    @Test
    @DisplayName("replaceMatches — null callback return skips replacement")
    void nullCallbackReturn() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("\\bfoo\\b"));
        String result = matcher.replaceMatches("hello foo world foo", m -> null);
        assertEquals("hello foo world foo", result);
    }

    @Test
    @DisplayName("replaceMatches — replacement longer than match, offset tracked")
    void longerReplacement() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("a"));
        String result = matcher.replaceMatches("aba", m -> "LONGER");
        assertEquals("LONGERbLONGER", result);
    }

    @Test
    @DisplayName("replaceMatches — replacement shorter than match, offset tracked")
    void shorterReplacement() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("longword"));
        String result = matcher.replaceMatches("start longword end longword done", m -> "s");
        assertEquals("start s end s done", result);
    }

    @Test
    @DisplayName("replaceMatches — empty replacement removes matches")
    void emptyReplacement() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("\\s+"));
        String result = matcher.replaceMatches("a b c", m -> "");
        assertEquals("abc", result);
    }

    @Test
    @DisplayName("replaceMatches — EDDI URI pattern replaces URIs in JSON")
    void eddiUriPattern() throws Exception {
        Pattern EDDI_URI_PATTERN = Pattern.compile("\"eddi://ai.labs..*?\"");
        var matcher = new CallbackMatcher(EDDI_URI_PATTERN);
        String json = "{\"ref\": \"eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc?version=1\"}";
        String result = matcher.replaceMatches(json,
                m -> "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/NEW?version=2\"");
        assertTrue(result.contains("NEW?version=2"));
        assertFalse(result.contains("abc?version=1"));
    }

    @Test
    @DisplayName("replaceMatches — empty input returns empty")
    void emptyInput() throws Exception {
        var matcher = new CallbackMatcher(Pattern.compile("test"));
        String result = matcher.replaceMatches("", m -> "x");
        assertEquals("", result);
    }

    @Test
    @DisplayName("CallbackMatcherException — wraps cause")
    void exceptionWraps() {
        var cause = new RuntimeException("test");
        var ex = new CallbackMatcher.CallbackMatcherException("msg", cause);
        assertEquals("msg", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
