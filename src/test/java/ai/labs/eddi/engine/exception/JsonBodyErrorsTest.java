/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message a sender gets for a request body the framework could not read:
 * the JSON path or position, what belongs there, and never a Java type name.
 */
class JsonBodyErrorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public enum Colour {
        RED, BLUE
    }

    public static class Body {
        public String name;
        public int count;
        public long big;
        public boolean flag;
        public double ratio;
        public Colour colour;
        public List<String> tags;
        public Map<String, String> labels;
        public Body child;
        public List<Body> children;
        public Shape shape;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(@JsonSubTypes.Type(value = Circle.class, name = "circle"))
    public abstract static class Shape {
    }

    public static class Circle extends Shape {
        public double radius;
    }

    private static String describe(String json) {
        JsonProcessingException e = assertThrows(JsonProcessingException.class, () -> MAPPER.readValue(json, Body.class));
        String message = JsonBodyErrors.describe(e);
        assertFalse(message.contains("com.fasterxml") || message.contains("ai.labs"), message);
        return message;
    }

    @Test
    void anUnknownFieldIsNamedWithItsPath() {
        assertTrue(describe("{\"nmae\":\"x\"}").startsWith("Unknown field 'nmae'"));
        String nested = describe("{\"child\":{\"colr\":1}}");
        assertTrue(nested.startsWith("Unknown field 'colr' at child"), nested);
        String inArray = describe("{\"children\":[{\"x\":1}]}");
        assertTrue(inArray.startsWith("Unknown field 'x' at children[0]"), inArray);
    }

    @Test
    void aWrongShapeNamesWhatBelongsThere() {
        assertEquals("Cannot read the request body at count: expected a whole number here.", describe("{\"count\":\"many\"}"));
        assertTrue(describe("{\"big\":[1]}").endsWith("at big: expected a whole number here."));
        assertEquals("Cannot read the request body at flag: expected true or false here.", describe("{\"flag\":\"maybe\"}"));
        assertTrue(describe("{\"ratio\":\"half\"}").startsWith("Cannot read the request body at ratio: expected a"));
        assertEquals("Cannot read the request body at colour: expected one of [BLUE, RED].", describe("{\"colour\":\"GREEN\"}"));
        assertEquals("Cannot read the request body at tags: expected a JSON array here.", describe("{\"tags\":{\"a\":1}}"));
        assertEquals("Cannot read the request body at labels: expected a JSON object here.", describe("{\"labels\":[1]}"));
        assertEquals("Cannot read the request body at name: expected a string here.", describe("{\"name\":{\"a\":1}}"));
        assertTrue(describe("{\"child\":[1]}").startsWith("Cannot read the request body at child: "));
        assertTrue(describe("[1]").startsWith("Cannot read the request body: "), "a root mismatch has no path");
    }

    @Test
    void aPolymorphicValueNamesTheTypeField() {
        assertEquals("Cannot read the request body at shape: 'square' is not a known 'type'.", describe("{\"shape\":{\"type\":\"square\"}}"));
        assertEquals("Cannot read the request body at shape: expected an object with a 'type' field.", describe("{\"shape\":{\"radius\":1}}"));
    }

    @Test
    void anEmptyBodyIsCalledEmpty() {
        assertEquals("The request body is empty; a JSON document is required.", describe(""));
    }

    @Test
    void aSyntaxErrorNamesItsPosition() {
        String message = describe("{\n  \"name\": tru\n}");
        assertTrue(message.startsWith("The request body is not valid JSON at line 2, column "), message);
        assertTrue(message.contains(": Unrecognized token"), message);
    }

    @Test
    void aWrappedParserErrorIsDescribedByItsCause() {
        var cause = new JsonParseException(null, "Unexpected character\nsecond line");
        var wrapped = JsonMappingException.from((JsonParser) null, "wrapper", cause);
        assertEquals("The request body is not valid JSON: Unexpected character", JsonBodyErrors.describe(wrapped));
    }

    @Test
    void parserMessagesAreTrimmedForTheSender() {
        String source = JsonBodyErrors.describe(new JsonParseException(null, "Unexpected end (for Object starting at [Source: x])"));
        assertEquals("The request body is not valid JSON: Unexpected end", source);
        String longOne = JsonBodyErrors.describe(new JsonParseException(null, "x".repeat(300)));
        assertTrue(longOne.endsWith("x…"), longOne);
        assertEquals("The request body is not valid JSON: ".length() + 201, longOne.length());
        assertEquals("The request body is not valid JSON.", JsonBodyErrors.describe(new JsonParseException(null, " ")));
    }

    @Test
    void parserLimitsAndOtherFailuresHaveFixedMessages() {
        assertTrue(JsonBodyErrors.describe(new StreamConstraintsException("Document nesting depth exceeds")).startsWith(
                "The request body exceeds the JSON parser's limits"));
        assertEquals("The request body could not be read as JSON.",
                JsonBodyErrors.describe(new JsonGenerationException("generation", (JsonGenerator) null)));
    }
}
