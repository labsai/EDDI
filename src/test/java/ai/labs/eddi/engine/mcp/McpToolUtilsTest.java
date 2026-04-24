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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpToolUtils — shared MCP utility methods.
 */
class McpToolUtilsTest {

    // --- parseEnvironment ---

    @Test
    void parseEnvironment_validValues() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("production"));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("production"));
        assertEquals(Environment.test, McpToolUtils.parseEnvironment("test"));
    }

    @Test
    void parseEnvironment_caseInsensitive() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("UNRESTRICTED"));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("Production"));
    }

    @Test
    void parseEnvironment_nullOrBlank_defaultsToProduction() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment(null));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment(""));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("   "));
    }

    @Test
    void parseEnvironment_invalid_defaultsToProduction() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("invalid"));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("production"));
    }

    // --- parseIntOrDefault ---

    @Test
    void parseIntOrDefault_validValues() {
        assertEquals(42, McpToolUtils.parseIntOrDefault("42", 0));
        assertEquals(0, McpToolUtils.parseIntOrDefault("0", 99));
        assertEquals(-1, McpToolUtils.parseIntOrDefault("-1", 0));
    }

    @Test
    void parseIntOrDefault_nullOrBlank_returnsDefault() {
        assertEquals(20, McpToolUtils.parseIntOrDefault(null, 20));
        assertEquals(20, McpToolUtils.parseIntOrDefault("", 20));
        assertEquals(20, McpToolUtils.parseIntOrDefault("  ", 20));
    }

    @Test
    void parseIntOrDefault_invalidNumber_returnsDefault() {
        assertEquals(10, McpToolUtils.parseIntOrDefault("abc", 10));
        assertEquals(10, McpToolUtils.parseIntOrDefault("1.5", 10));
    }

    // --- parseBooleanOrDefault ---

    @Test
    void parseBooleanOrDefault_trueValues() {
        assertTrue(McpToolUtils.parseBooleanOrDefault("true", false));
        assertTrue(McpToolUtils.parseBooleanOrDefault("TRUE", false));
        assertTrue(McpToolUtils.parseBooleanOrDefault("True", false));
    }

    @Test
    void parseBooleanOrDefault_falseValues() {
        assertFalse(McpToolUtils.parseBooleanOrDefault("false", true));
        assertFalse(McpToolUtils.parseBooleanOrDefault("anything", true));
    }

    @Test
    void parseBooleanOrDefault_nullOrBlank_returnsDefault() {
        assertTrue(McpToolUtils.parseBooleanOrDefault(null, true));
        assertFalse(McpToolUtils.parseBooleanOrDefault("", false));
        assertTrue(McpToolUtils.parseBooleanOrDefault("  ", true));
    }

    // --- escapeJsonString ---

    @Test
    void escapeJsonString_null_returnsEmpty() {
        assertEquals("", McpToolUtils.escapeJsonString(null));
    }

    @Test
    void escapeJsonString_noSpecialChars() {
        assertEquals("hello world", McpToolUtils.escapeJsonString("hello world"));
    }

    @Test
    void escapeJsonString_quotes() {
        assertEquals("say \\\"hello\\\"", McpToolUtils.escapeJsonString("say \"hello\""));
    }

    @Test
    void escapeJsonString_backslash() {
        assertEquals("path\\\\to\\\\file", McpToolUtils.escapeJsonString("path\\to\\file"));
    }

    @Test
    void escapeJsonString_specialChars() {
        assertEquals("line1\\nline2\\ttab\\rreturn", McpToolUtils.escapeJsonString("line1\nline2\ttab\rreturn"));
    }

    @Test
    void escapeJsonString_controlChars() {
        assertEquals("\\u0000\\u001f", McpToolUtils.escapeJsonString("\u0000\u001f"));
    }

    // --- errorJson ---

    @Test
    void errorJson_simpleMessage() {
        String result = McpToolUtils.errorJson("something went wrong");
        assertEquals("{\"error\":\"something went wrong\"}", result);
    }

    @Test
    void errorJson_messageWithQuotes() {
        String result = McpToolUtils.errorJson("value \"foo\" is invalid");
        assertEquals("{\"error\":\"value \\\"foo\\\" is invalid\"}", result);
    }

    @Test
    void errorJson_messageWithNewlines() {
        String result = McpToolUtils.errorJson("line1\nline2");
        assertEquals("{\"error\":\"line1\\nline2\"}", result);
    }

    // --- requireRole ---

    @Test
    void requireRole_authDisabled_neverThrows() {
        assertDoesNotThrow(() -> McpToolUtils.requireRole(null, false, "eddi-admin"));
    }

    @Test
    void requireRole_authEnabled_nullIdentity_throws() {
        assertThrows(ForbiddenException.class,
                () -> McpToolUtils.requireRole(null, true, "eddi-admin"));
    }

    @Test
    void requireRole_authEnabled_anonymousIdentity_throws() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        assertThrows(ForbiddenException.class,
                () -> McpToolUtils.requireRole(identity, true, "eddi-admin"));
    }

    @Test
    void requireRole_authEnabled_lacksRole_throws() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        assertThrows(ForbiddenException.class,
                () -> McpToolUtils.requireRole(identity, true, "eddi-admin"));
    }

    @Test
    void requireRole_authEnabled_hasRole_passes() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        assertDoesNotThrow(() -> McpToolUtils.requireRole(identity, true, "eddi-admin"));
    }

    // --- extractIdFromLocation ---

    @Test
    void extractIdFromLocation_withVersionQuery() {
        assertEquals("abc123", McpToolUtils.extractIdFromLocation("/store/resources/abc123?version=1"));
    }

    @Test
    void extractIdFromLocation_withoutQuery() {
        assertEquals("abc123", McpToolUtils.extractIdFromLocation("/store/resources/abc123"));
    }

    @Test
    void extractIdFromLocation_nullOrBlank_returnsNull() {
        assertNull(McpToolUtils.extractIdFromLocation(null));
        assertNull(McpToolUtils.extractIdFromLocation(""));
        assertNull(McpToolUtils.extractIdFromLocation("  "));
    }

    @Test
    void extractIdFromLocation_trailingSlash_returnsNull() {
        assertNull(McpToolUtils.extractIdFromLocation("/store/resources/"));
    }

    // --- extractVersionFromLocation ---

    @Test
    void extractVersionFromLocation_present() {
        assertEquals(3, McpToolUtils.extractVersionFromLocation("/store/resources/abc?version=3"));
    }

    @Test
    void extractVersionFromLocation_multipleParams() {
        assertEquals(5, McpToolUtils.extractVersionFromLocation("/store/resources/abc?version=5&other=val"));
    }

    @Test
    void extractVersionFromLocation_missing_returns1() {
        assertEquals(1, McpToolUtils.extractVersionFromLocation("/store/resources/abc"));
    }

    @Test
    void extractVersionFromLocation_null_returns1() {
        assertEquals(1, McpToolUtils.extractVersionFromLocation(null));
    }

    @Test
    void extractVersionFromLocation_invalidNumber_returns1() {
        assertEquals(1, McpToolUtils.extractVersionFromLocation("/store/resources/abc?version=xyz"));
    }

    // --- getRestStore ---

    @Test
    void getRestStore_success() throws Exception {
        var factory = mock(IRestInterfaceFactory.class);
        var proxy = mock(Runnable.class);
        when(factory.get(Runnable.class)).thenReturn(proxy);
        assertSame(proxy, McpToolUtils.getRestStore(factory, Runnable.class));
    }

    @Test
    void getRestStore_factoryException_wrapsInRuntime() throws Exception {
        var factory = mock(IRestInterfaceFactory.class);
        when(factory.get(any())).thenThrow(new RestInterfaceFactory.RestInterfaceFactoryException("fail", new Exception("cause")));
        assertThrows(RuntimeException.class, () -> McpToolUtils.getRestStore(factory, Runnable.class));
    }
}
