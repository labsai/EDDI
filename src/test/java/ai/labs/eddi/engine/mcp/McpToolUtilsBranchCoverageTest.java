/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("McpToolUtils — Branch Coverage")
class McpToolUtilsBranchCoverageTest {

    @Mock
    private SecurityIdentity identity;
    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private Principal principal;

    @BeforeEach
    void setUp() {
        openMocks(this);
    }

    // ─── requireRole ────────────────────────────────────────────────────

    @Nested
    @DisplayName("requireRole")
    class RequireRole {

        @Test
        @DisplayName("auth disabled → no check")
        void authDisabled() {
            assertDoesNotThrow(() -> McpToolUtils.requireRole(null, false, "admin"));
        }

        @Test
        @DisplayName("null identity → throws")
        void nullIdentity() {
            assertThrows(ForbiddenException.class,
                    () -> McpToolUtils.requireRole(null, true, "admin"));
        }

        @Test
        @DisplayName("anonymous identity → throws")
        void anonymousIdentity() {
            when(identity.isAnonymous()).thenReturn(true);
            assertThrows(ForbiddenException.class,
                    () -> McpToolUtils.requireRole(identity, true, "admin"));
        }

        @Test
        @DisplayName("no role → throws")
        void noRole() {
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.hasRole("admin")).thenReturn(false);
            assertThrows(ForbiddenException.class,
                    () -> McpToolUtils.requireRole(identity, true, "admin"));
        }

        @Test
        @DisplayName("has role → no exception")
        void hasRole() {
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.hasRole("admin")).thenReturn(true);
            assertDoesNotThrow(() -> McpToolUtils.requireRole(identity, true, "admin"));
        }
    }

    // ─── parseEnvironment ───────────────────────────────────────────────

    @Nested
    @DisplayName("parseEnvironment")
    class ParseEnvironmentTests {

        @Test
        @DisplayName("null → production")
        void nullEnv() {
            assertEquals(Environment.production, McpToolUtils.parseEnvironment(null));
        }

        @Test
        @DisplayName("blank → production")
        void blankEnv() {
            assertEquals(Environment.production, McpToolUtils.parseEnvironment("  "));
        }

        @Test
        @DisplayName("'test' → test")
        void testEnv() {
            assertEquals(Environment.test, McpToolUtils.parseEnvironment("test"));
        }

        @Test
        @DisplayName("'PRODUCTION' → production")
        void productionUpperCase() {
            assertEquals(Environment.production, McpToolUtils.parseEnvironment("PRODUCTION"));
        }

        @Test
        @DisplayName("invalid → production")
        void invalidEnv() {
            assertEquals(Environment.production, McpToolUtils.parseEnvironment("staging"));
        }

        @Test
        @DisplayName("whitespace-padded → works")
        void paddedEnv() {
            assertEquals(Environment.test, McpToolUtils.parseEnvironment("  test  "));
        }
    }

    // ─── parseIntOrDefault ──────────────────────────────────────────────

    @Nested
    @DisplayName("parseIntOrDefault")
    class ParseIntOrDefault {

        @Test
        @DisplayName("null → default")
        void nullValue() {
            assertEquals(42, McpToolUtils.parseIntOrDefault(null, 42));
        }

        @Test
        @DisplayName("blank → default")
        void blankValue() {
            assertEquals(42, McpToolUtils.parseIntOrDefault("  ", 42));
        }

        @Test
        @DisplayName("valid number → parsed")
        void validNumber() {
            assertEquals(100, McpToolUtils.parseIntOrDefault("100", 42));
        }

        @Test
        @DisplayName("non-number → default")
        void nonNumber() {
            assertEquals(42, McpToolUtils.parseIntOrDefault("abc", 42));
        }

        @Test
        @DisplayName("whitespace-padded number → parsed")
        void paddedNumber() {
            assertEquals(7, McpToolUtils.parseIntOrDefault("  7  ", 42));
        }
    }

    // ─── parseBooleanOrDefault ───────────────────────────────────────────

    @Nested
    @DisplayName("parseBooleanOrDefault")
    class ParseBooleanOrDefault {

        @Test
        @DisplayName("null → default")
        void nullValue() {
            assertTrue(McpToolUtils.parseBooleanOrDefault(null, true));
            assertFalse(McpToolUtils.parseBooleanOrDefault(null, false));
        }

        @Test
        @DisplayName("blank → default")
        void blankValue() {
            assertTrue(McpToolUtils.parseBooleanOrDefault("  ", true));
        }

        @Test
        @DisplayName("'true' → true")
        void trueValue() {
            assertTrue(McpToolUtils.parseBooleanOrDefault("true", false));
        }

        @Test
        @DisplayName("'TRUE' → true")
        void trueUpperCase() {
            assertTrue(McpToolUtils.parseBooleanOrDefault("TRUE", false));
        }

        @Test
        @DisplayName("'false' → false")
        void falseValue() {
            assertFalse(McpToolUtils.parseBooleanOrDefault("false", true));
        }

        @Test
        @DisplayName("other string → false")
        void otherString() {
            assertFalse(McpToolUtils.parseBooleanOrDefault("yes", true));
        }
    }

    // ─── errorJson ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("errorJson")
    class ErrorJson {

        @Test
        @DisplayName("simple message")
        void simpleMessage() {
            String result = McpToolUtils.errorJson("something failed");
            assertEquals("{\"error\":\"something failed\"}", result);
        }

        @Test
        @DisplayName("message with quotes")
        void messageWithQuotes() {
            String result = McpToolUtils.errorJson("got \"error\"");
            assertTrue(result.contains("\\\"error\\\""));
        }
    }

    // ─── escapeJsonString ───────────────────────────────────────────────

    @Nested
    @DisplayName("escapeJsonString")
    class EscapeJsonString {

        @Test
        @DisplayName("null → empty")
        void nullInput() {
            assertEquals("", McpToolUtils.escapeJsonString(null));
        }

        @Test
        @DisplayName("simple text → unchanged")
        void simpleText() {
            assertEquals("hello", McpToolUtils.escapeJsonString("hello"));
        }

        @Test
        @DisplayName("quote → escaped")
        void quoteEscaped() {
            assertEquals("say \\\"hi\\\"", McpToolUtils.escapeJsonString("say \"hi\""));
        }

        @Test
        @DisplayName("backslash → escaped")
        void backslashEscaped() {
            assertEquals("path\\\\file", McpToolUtils.escapeJsonString("path\\file"));
        }

        @Test
        @DisplayName("newline → \\n")
        void newlineEscaped() {
            assertEquals("line1\\nline2", McpToolUtils.escapeJsonString("line1\nline2"));
        }

        @Test
        @DisplayName("carriage return → \\r")
        void crEscaped() {
            assertEquals("a\\rb", McpToolUtils.escapeJsonString("a\rb"));
        }

        @Test
        @DisplayName("tab → \\t")
        void tabEscaped() {
            assertEquals("a\\tb", McpToolUtils.escapeJsonString("a\tb"));
        }

        @Test
        @DisplayName("backspace → \\b")
        void backspaceEscaped() {
            assertEquals("a\\bb", McpToolUtils.escapeJsonString("a\bb"));
        }

        @Test
        @DisplayName("form feed → \\f")
        void formFeedEscaped() {
            assertEquals("a\\fb", McpToolUtils.escapeJsonString("a\fb"));
        }

        @Test
        @DisplayName("control char < 0x20 → unicode escape")
        void controlCharEscaped() {
            String result = McpToolUtils.escapeJsonString("a\u0001b");
            assertEquals("a\\u0001b", result);
        }

        @Test
        @DisplayName("normal chars > 0x1f unchanged")
        void normalCharsUnchanged() {
            assertEquals("ABC 123 !@#", McpToolUtils.escapeJsonString("ABC 123 !@#"));
        }
    }

    // ─── extractIdFromLocation ──────────────────────────────────────────

    @Nested
    @DisplayName("extractIdFromLocation")
    class ExtractIdFromLocation {

        @Test
        @DisplayName("null → null")
        void nullLocation() {
            assertNull(McpToolUtils.extractIdFromLocation(null));
        }

        @Test
        @DisplayName("blank → null")
        void blankLocation() {
            assertNull(McpToolUtils.extractIdFromLocation("  "));
        }

        @Test
        @DisplayName("normal location with version")
        void normalWithVersion() {
            assertEquals("abc123", McpToolUtils.extractIdFromLocation("/store/resources/abc123?version=1"));
        }

        @Test
        @DisplayName("location without version")
        void noVersion() {
            assertEquals("myId", McpToolUtils.extractIdFromLocation("/store/resources/myId"));
        }

        @Test
        @DisplayName("trailing slash → null")
        void trailingSlash() {
            assertNull(McpToolUtils.extractIdFromLocation("/store/resources/"));
        }

        @Test
        @DisplayName("no slash → null")
        void noSlash() {
            // lastSlash < 0
            assertNull(McpToolUtils.extractIdFromLocation("noslash"));
        }
    }

    // ─── extractVersionFromLocation ──────────────────────────────────────

    @Nested
    @DisplayName("extractVersionFromLocation")
    class ExtractVersionFromLocation {

        @Test
        @DisplayName("null → 1")
        void nullLocation() {
            assertEquals(1, McpToolUtils.extractVersionFromLocation(null));
        }

        @Test
        @DisplayName("no version param → 1")
        void noVersion() {
            assertEquals(1, McpToolUtils.extractVersionFromLocation("/store/abc"));
        }

        @Test
        @DisplayName("version=3 → 3")
        void version3() {
            assertEquals(3, McpToolUtils.extractVersionFromLocation("/store/abc?version=3"));
        }

        @Test
        @DisplayName("version with trailing & → correctly parsed")
        void versionWithAmpersand() {
            assertEquals(5, McpToolUtils.extractVersionFromLocation("/store/abc?version=5&other=foo"));
        }

        @Test
        @DisplayName("invalid version → 1")
        void invalidVersion() {
            assertEquals(1, McpToolUtils.extractVersionFromLocation("/store/abc?version=xyz"));
        }
    }

    // ─── getRestStore ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getRestStore")
    class GetRestStore {

        @Test
        @DisplayName("success returns proxy")
        void success() throws Exception {
            var proxy = mock(IRestInterfaceFactory.class);
            doReturn(proxy).when(restInterfaceFactory).get(IRestInterfaceFactory.class);
            var result = McpToolUtils.getRestStore(restInterfaceFactory, IRestInterfaceFactory.class);
            assertNotNull(result);
        }

        @Test
        @DisplayName("factory exception wraps in RuntimeException")
        void factoryException() throws Exception {
            doThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new RuntimeException()))
                    .when(restInterfaceFactory).get(any());
            assertThrows(RuntimeException.class,
                    () -> McpToolUtils.getRestStore(restInterfaceFactory, IRestInterfaceFactory.class));
        }
    }
}
