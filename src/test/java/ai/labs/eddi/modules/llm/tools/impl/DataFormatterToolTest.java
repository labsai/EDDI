/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataFormatterToolTest {

    private DataFormatterTool dataFormatterTool;

    @BeforeEach
    void setUp() {
        dataFormatterTool = new DataFormatterTool();
    }

    @Test
    void testFormatJson_ValidJson() {
        String json = "{\"name\":\"John\",\"age\":30}";
        String result = dataFormatterTool.formatJson(json);
        assertNotNull(result);
        assertTrue(result.contains("Valid JSON"));
        assertTrue(result.contains("name"));
        assertTrue(result.contains("John"));
    }

    @Test
    void testFormatJson_ValidJsonArray() {
        String json = "[{\"id\":1},{\"id\":2}]";
        String result = dataFormatterTool.formatJson(json);
        assertNotNull(result);
        assertTrue(result.contains("Valid JSON"));
    }

    @Test
    void testFormatJson_InvalidJson() {
        String json = "{name: John}"; // Missing quotes
        String result = dataFormatterTool.formatJson(json);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("Invalid JSON"));
    }

    @Test
    void testFormatJson_EmptyString() {
        String json = "";
        String result = dataFormatterTool.formatJson(json);
        // Empty string returns error from Jackson
        assertNotNull(result);
        // Just verify it returns something, could be error or empty
    }

    @Test
    void testJsonToXml_SimpleObject() {
        String json = "{\"name\":\"John\",\"age\":30}";
        String result = dataFormatterTool.jsonToXml(json);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("<name>") || result.contains("name"));
    }

    @Test
    void testJsonToXml_Array() {
        String json = "[{\"id\":1},{\"id\":2}]";
        String result = dataFormatterTool.jsonToXml(json);
        assertNotNull(result);
        // Jackson XML mapper cannot convert arrays directly (needs root element)
        assertTrue(result.startsWith("Error"), "Array conversion should fail without root element: " + result);
    }

    @Test
    void testJsonToXml_InvalidJson() {
        String json = "{invalid}";
        String result = dataFormatterTool.jsonToXml(json);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testXmlToJson_SimpleXml() {
        String xml = "<person><name>John</name><age>30</age></person>";
        String result = dataFormatterTool.xmlToJson(xml);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("name") || result.contains("John"));
    }

    @Test
    void testXmlToJson_InvalidXml() {
        String xml = "<person><name>John</name>"; // Unclosed tag
        String result = dataFormatterTool.xmlToJson(xml);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCsvToJson_WithHeaders() {
        String csv = "name,age\nJohn,30\nJane,25";
        String result = dataFormatterTool.csvToJson(csv);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("John") || result.contains("30"));
    }

    @Test
    void testCsvToJson_SingleRow() {
        String csv = "name,age\nJohn,30";
        String result = dataFormatterTool.csvToJson(csv);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("John"));
    }

    @Test
    void testCsvToJson_EmptyData() {
        String csv = "name,age";
        String result = dataFormatterTool.csvToJson(csv);
        assertNotNull(result);
        // Empty CSV should produce empty array
        assertTrue(result.contains("[]") || result.contains("[ ]"));
    }

    @Test
    void testCsvToJson_InvalidCsv() {
        String csv = "name,age\nJohn"; // Missing value
        String result = dataFormatterTool.csvToJson(csv);
        // May succeed with null or empty value, or fail - both acceptable
        assertNotNull(result);
    }

    @Test
    void testExtractJsonValue_SimpleField() {
        String json = "{\"name\":\"John\",\"age\":30}";
        String result = dataFormatterTool.extractJsonValue(json, "name");
        assertNotNull(result);
        assertTrue(result.contains("John") || result.equals("John"));
    }

    @Test
    void testExtractJsonValue_NestedField() {
        String json = "{\"user\":{\"name\":\"John\",\"age\":30}}";
        String result = dataFormatterTool.extractJsonValue(json, "user.name");
        assertNotNull(result);
        assertTrue(result.contains("John") || result.equals("John"));
    }

    @Test
    void testExtractJsonValue_ArrayElement() {
        String json = "{\"items\":[{\"name\":\"Item1\"},{\"name\":\"Item2\"}]}";
        String result = dataFormatterTool.extractJsonValue(json, "items[0].name");
        assertNotNull(result);
        assertTrue(result.contains("Item1") || result.equals("Item1"));
    }

    @Test
    void testExtractJsonValue_InvalidPath() {
        String json = "{\"name\":\"John\"}";
        String result = dataFormatterTool.extractJsonValue(json, "nonexistent.field");
        // May return null, empty, or error message
        assertNotNull(result);
    }

    @Test
    void testExtractJsonValue_InvalidJson() {
        String json = "{invalid}";
        String result = dataFormatterTool.extractJsonValue(json, "name");
        assertTrue(result.startsWith("Error") || result.equals("null"));
    }

    @Test
    void testFormatJson_NestedObject() {
        String json = "{\"user\":{\"name\":\"John\",\"address\":{\"city\":\"NYC\"}}}";
        String result = dataFormatterTool.formatJson(json);
        assertNotNull(result);
        assertTrue(result.contains("Valid JSON"));
        assertTrue(result.contains("city"));
    }

    @Test
    void testJsonToXml_NestedObject() {
        String json = "{\"user\":{\"name\":\"John\"}}";
        String result = dataFormatterTool.jsonToXml(json);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testXmlToJson_WithAttributes() {
        String xml = "<person name=\"John\" age=\"30\"/>";
        String result = dataFormatterTool.xmlToJson(xml);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testCsvToJson_WithCommasInValues() {
        String csv = "name,description\n\"John Doe\",\"A, B, C\"";
        String result = dataFormatterTool.csvToJson(csv);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testFormatJson_WithNumbers() {
        String json = "{\"age\":30,\"height\":1.75,\"isStudent\":false}";
        String result = dataFormatterTool.formatJson(json);
        assertNotNull(result);
        assertTrue(result.contains("Valid JSON"));
        assertTrue(result.contains("30"));
        assertTrue(result.contains("1.75"));
    }

    @Test
    void testFormatJson_WithNull() {
        String json = "{\"name\":\"John\",\"middleName\":null}";
        String result = dataFormatterTool.formatJson(json);
        assertNotNull(result);
        assertTrue(result.contains("Valid JSON"));
    }

    @Test
    void testCsvToJson_MultipleRows() {
        String csv = "id,name\n1,John\n2,Jane\n3,Bob";
        String result = dataFormatterTool.csvToJson(csv);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("John"));
        assertTrue(result.contains("Jane"));
        assertTrue(result.contains("Bob"));
    }

    // === validateXml tests ===

    @Test
    void testValidateXml_ValidXml() {
        String xml = "<root><item>test</item></root>";
        String result = dataFormatterTool.validateXml(xml);
        assertNotNull(result);
        assertTrue(result.contains("Valid XML"));
    }

    @Test
    void testValidateXml_InvalidXml() {
        String xml = "<root><unclosed>";
        String result = dataFormatterTool.validateXml(xml);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testValidateXml_EmptyString() {
        String result = dataFormatterTool.validateXml("");
        // Jackson XML mapper treats empty string as error
        assertNotNull(result);
    }

    // === minifyJson tests ===

    @Test
    void testMinifyJson_FormattedJson() {
        String json = "{\n  \"name\": \"John\",\n  \"age\": 30\n}";
        String result = dataFormatterTool.minifyJson(json);
        assertNotNull(result);
        assertFalse(result.contains("Error"));
        assertFalse(result.contains("\n"), "Minified JSON should not contain newlines");
        assertTrue(result.contains("\"name\":\"John\"") || result.contains("\"name\" : \"John\""));
    }

    @Test
    void testMinifyJson_AlreadyMinified() {
        String json = "{\"key\":\"value\"}";
        String result = dataFormatterTool.minifyJson(json);
        assertEquals(json, result);
    }

    @Test
    void testMinifyJson_InvalidJson() {
        String result = dataFormatterTool.minifyJson("{invalid json}");
        assertTrue(result.startsWith("Error"));
    }

    // === extractJsonValue with numeric value ===

    @Test
    void testExtractJsonValue_NumericField() {
        String json = "{\"count\": 42}";
        String result = dataFormatterTool.extractJsonValue(json, "count");
        assertEquals("42", result);
    }
}
