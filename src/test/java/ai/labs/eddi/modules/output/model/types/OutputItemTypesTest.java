/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model.types;

import ai.labs.eddi.modules.output.model.OutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputItemTypesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- TextOutputItem ---

    @Test
    void textOutputItem_constructorSetsFields() {
        var item = new TextOutputItem("Hello", 500);
        assertEquals("text", item.getType());
        assertEquals("Hello", item.getText());
        assertEquals(500, item.getDelay());
    }

    @Test
    void textOutputItem_defaultConstructorSetsType() {
        var item = new TextOutputItem();
        assertEquals("text", item.getType());
    }

    @Test
    void textOutputItem_equalsByContent() {
        assertEquals(new TextOutputItem("Hi", 0), new TextOutputItem("Hi", 0));
        assertNotEquals(new TextOutputItem("Hi", 0), new TextOutputItem("Bye", 0));
    }

    @Test
    void textOutputItem_toStringReturnsText() {
        assertEquals("Hello", new TextOutputItem("Hello").toString());
    }

    @Test
    void textOutputItem_jacksonRoundTrip() throws Exception {
        var item = new TextOutputItem("Hello", 200);
        String json = mapper.writeValueAsString(item);
        OutputItem deserialized = mapper.readValue(json, OutputItem.class);

        assertInstanceOf(TextOutputItem.class, deserialized);
        assertEquals("Hello", ((TextOutputItem) deserialized).getText());
        assertEquals(200, ((TextOutputItem) deserialized).getDelay());
    }

    // --- ImageOutputItem ---

    @Test
    void imageOutputItem_constructorSetsFields() {
        var item = new ImageOutputItem("https://img.png", "alt text");
        assertEquals("image", item.getType());
        assertEquals("https://img.png", item.getUri());
        assertEquals("alt text", item.getAlt());
    }

    @Test
    void imageOutputItem_equalsByContent() {
        assertEquals(new ImageOutputItem("u", "a"), new ImageOutputItem("u", "a"));
        assertNotEquals(new ImageOutputItem("u1", "a"), new ImageOutputItem("u2", "a"));
    }

    @Test
    void imageOutputItem_jacksonRoundTrip() throws Exception {
        var item = new ImageOutputItem("https://img.png", "desc");
        String json = mapper.writeValueAsString(item);
        OutputItem deserialized = mapper.readValue(json, OutputItem.class);

        assertInstanceOf(ImageOutputItem.class, deserialized);
        assertEquals("https://img.png", ((ImageOutputItem) deserialized).getUri());
    }

    // --- ButtonOutputItem ---

    @Test
    void buttonOutputItem_constructorSetsFields() {
        var item = new ButtonOutputItem("submit", "Click me", Map.of("action", "go"));
        assertEquals("button", item.getType());
        assertEquals("submit", item.getButtonType());
        assertEquals("Click me", item.getLabel());
        assertEquals(Map.of("action", "go"), item.getOnPress());
    }

    @Test
    void buttonOutputItem_jacksonRoundTrip() throws Exception {
        var item = new ButtonOutputItem("link", "Open", Map.of("url", "https://example.com"));
        String json = mapper.writeValueAsString(item);
        OutputItem deserialized = mapper.readValue(json, OutputItem.class);

        assertInstanceOf(ButtonOutputItem.class, deserialized);
        assertEquals("Open", ((ButtonOutputItem) deserialized).getLabel());
    }

    // --- QuickReplyOutputItem ---

    @Test
    void quickReplyOutputItem_defaultType() {
        var item = new QuickReplyOutputItem();
        assertEquals("quickReply", item.getType());
    }

    @Test
    void quickReplyOutputItem_jacksonRoundTrip() throws Exception {
        var item = new QuickReplyOutputItem();
        item.setValue("option1");
        item.setExpressions("exp1");
        String json = mapper.writeValueAsString(item);
        OutputItem deserialized = mapper.readValue(json, OutputItem.class);

        assertInstanceOf(QuickReplyOutputItem.class, deserialized);
        assertEquals("option1", ((QuickReplyOutputItem) deserialized).getValue());
    }

    // --- AgentFaceOutputItem ---

    @Test
    void agentFaceOutputItem_defaultType() {
        var item = new AgentFaceOutputItem();
        assertEquals("agentFace", item.getType());
    }

    // --- ApplicationLinkOutputItem ---

    @Test
    void applicationLinkOutputItem_defaultType() {
        var item = new ApplicationLinkOutputItem();
        assertEquals("applicationLink", item.getType());
    }

    // --- InputFieldOutputItem ---

    @Test
    void inputFieldOutputItem_defaultType() {
        var item = new InputFieldOutputItem();
        assertEquals("inputField", item.getType());
    }

    // --- OtherOutputItem ---

    @Test
    void otherOutputItem_typeSetViaSetType() {
        var item = new OtherOutputItem();
        // OtherOutputItem has no constructor calling initType()
        // Type is normally set by Jackson during deserialization
        assertNull(item.getType());
        item.setType("other");
        assertEquals("other", item.getType());
    }

    @Test
    void otherOutputItem_mapOperations() {
        var item = new OtherOutputItem();
        item.put("custom", "data");
        assertEquals("data", item.get("custom"));
        assertEquals(1, item.size());
        assertFalse(item.isEmpty());
        assertTrue(item.containsKey("custom"));
        assertTrue(item.containsValue("data"));
    }

    @Test
    void otherOutputItem_equalsByMap() {
        var item1 = new OtherOutputItem();
        item1.put("k", "v");
        var item2 = new OtherOutputItem();
        item2.put("k", "v");
        assertEquals(item1, item2);
    }
}
