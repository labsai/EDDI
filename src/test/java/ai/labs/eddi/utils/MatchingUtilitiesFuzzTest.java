package ai.labs.eddi.utils;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-guided fuzz tests for {@link MatchingUtilities#executeValuePath}.
 * <p>
 * This method is the core of EDDI's DynamicValueMatcher condition — it
 * evaluates user-controlled value paths and comparison operators against
 * conversation memory. Fuzzing ensures that no crafted
 * valuePath/equals/contains combination can crash the system or cause ReDoS via
 * the underlying PathNavigator.
 * <p>
 * In CI, these run as standard JUnit regression tests. Run with Jazzer agent
 * for real coverage-guided fuzzing:
 * {@code mvn test -pl . -Dtest=MatchingUtilitiesFuzzTest -Djazzer.instrument=ai.labs.eddi.utils.*}
 */
class MatchingUtilitiesFuzzTest {

    private static final Map<String, Object> CONVERSATION_DATA = buildConversationData();

    private static Map<String, Object> buildConversationData() {
        Map<String, Object> data = new HashMap<>();
        data.put("memory", Map.of(
                "current", Map.of(
                        "input", "hello",
                        "output", "world",
                        "httpCalls", Map.of(
                                "weather", List.of(
                                        Map.of("temp", 22.5, "city", "Vienna"),
                                        Map.of("temp", 18.0, "city", "Berlin"))))));
        data.put("properties", Map.of(
                "language", "en",
                "count", 5,
                "active", true,
                "tags", List.of("premium", "beta", "eu")));
        data.put("context", Map.of(
                "channel", "web",
                "empty", ""));
        return data;
    }

    // ── Fuzz Tests ────────────────────────────────────────────────

    @FuzzTest(maxDuration = "120s")
    void fuzzExecuteValuePath(FuzzedDataProvider data) {
        String valuePath = data.consumeString(300);
        String equals = data.consumeBoolean() ? data.consumeString(100) : null;
        String contains = data.consumeBoolean() ? data.consumeString(100) : null;

        // Must return a boolean and never throw
        boolean result = MatchingUtilities.executeValuePath(CONVERSATION_DATA, valuePath, equals, contains);
        // result is either true or false — that's the only valid contract
    }

    @FuzzTest(maxDuration = "120s")
    void fuzzWithEmptyData(FuzzedDataProvider data) {
        String valuePath = data.consumeString(200);
        String equals = data.consumeString(50);

        // Empty conversation data should never crash
        boolean result = MatchingUtilities.executeValuePath(Map.of(), valuePath, equals, null);
        assertFalse(result, "Empty data should never match");
    }

    // ── Regression Tests ──────────────────────────────────────────

    @Test
    void regressionValidEqualsMatch() {
        assertTrue(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "memory.current.input", "hello", null));
    }

    @Test
    void regressionValidContainsMatch() {
        assertTrue(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "memory.current.input", null, "ell"));
    }

    @Test
    void regressionListContains() {
        assertTrue(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "properties.tags", null, "premium"));
    }

    @Test
    void regressionBooleanProperty() {
        assertTrue(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "properties.active", null, null));
    }

    @Test
    void regressionNonExistentPath() {
        assertFalse(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "nonexistent.path.here", "anything", null));
    }

    @Test
    void regressionNullPath() {
        assertFalse(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, null, null, null));
    }

    @Test
    void regressionEmptyPath() {
        assertFalse(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "", null, null));
    }

    @Test
    void regressionArrayIndexPath() {
        assertTrue(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "memory.current.httpCalls.weather[0].city", "Vienna", null));
    }

    @Test
    void regressionInjectionAttempts() {
        // None of these should crash
        assertFalse(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "${7*7}", "49", null));
        assertFalse(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "{{constructor.constructor('return this')()}}", null, null));
        assertFalse(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "properties.__proto__", null, null));
    }

    @Test
    void regressionExistenceCheck() {
        // No equals/contains: should return true if value exists and is non-null
        assertTrue(MatchingUtilities.executeValuePath(
                CONVERSATION_DATA, "properties.language", null, null));
    }
}
