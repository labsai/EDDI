/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GlobalVariable} — record defaults, serialization,
 * equality, tenant scoping.
 */
class GlobalVariableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fullConstructor() {
        var gv = new GlobalVariable("myTenant", "default-model", "gpt-4.1", "The default LLM model", false);
        assertEquals("myTenant", gv.tenantId());
        assertEquals("default-model", gv.key());
        assertEquals("gpt-4.1", gv.value());
        assertEquals("The default LLM model", gv.description());
        assertFalse(gv.exportable());
    }

    @Test
    void compactConstructorDefaultsTenantAndExportable() {
        var gv = new GlobalVariable(null, "x", "y", null, null);
        assertEquals(GlobalVariable.DEFAULT_TENANT, gv.tenantId(), "null tenantId should default to 'default'");
        assertTrue(gv.exportable(), "null exportable should default to true");
    }

    @Test
    void convenienceConstructor_keyValue() {
        var gv = new GlobalVariable("key", "val");
        assertEquals(GlobalVariable.DEFAULT_TENANT, gv.tenantId());
        assertEquals("key", gv.key());
        assertEquals("val", gv.value());
        assertNull(gv.description());
        assertTrue(gv.exportable());
    }

    @Test
    void convenienceConstructor_tenantKeyValue() {
        var gv = new GlobalVariable("tenant1", "key", "val");
        assertEquals("tenant1", gv.tenantId());
        assertEquals("key", gv.key());
        assertEquals("val", gv.value());
        assertNull(gv.description());
        assertTrue(gv.exportable());
    }

    @Test
    void equality() {
        var a = new GlobalVariable("default", "k", "v", "d", true);
        var b = new GlobalVariable("default", "k", "v", "d", true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequality_differentTenant() {
        var a = new GlobalVariable("t1", "k", "v");
        var b = new GlobalVariable("t2", "k", "v");
        assertNotEquals(a, b);
    }

    @Test
    void inequality_differentValue() {
        var a = new GlobalVariable("k", "v1");
        var b = new GlobalVariable("k", "v2");
        assertNotEquals(a, b);
    }

    @Test
    void jsonSerialization() throws Exception {
        var gv = new GlobalVariable("default", "model", "gpt-4.1", "default model", true);
        String json = MAPPER.writeValueAsString(gv);
        assertTrue(json.contains("\"key\":\"model\""));
        assertTrue(json.contains("\"value\":\"gpt-4.1\""));
        assertTrue(json.contains("\"tenantId\":\"default\""));

        var deserialized = MAPPER.readValue(json, GlobalVariable.class);
        assertEquals(gv, deserialized);
    }

    @Test
    void jsonNullDescription() throws Exception {
        var gv = new GlobalVariable("k", "v");
        String json = MAPPER.writeValueAsString(gv);
        // @JsonInclude(NON_NULL) should exclude null description
        assertFalse(json.contains("\"description\""), "null description should be excluded from JSON");
    }
}
