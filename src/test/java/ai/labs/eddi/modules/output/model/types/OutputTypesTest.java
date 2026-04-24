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

class OutputTypesTest {

    // ==================== ImageOutputItem ====================

    @Test
    void imageOutputItem_defaultConstructor() {
        var item = new ImageOutputItem();
        assertEquals("image", item.getType());
        assertNull(item.getUri());
        assertNull(item.getAlt());
    }

    @Test
    void imageOutputItem_fullConstructor() {
        var item = new ImageOutputItem("https://example.com/img.png", "A logo");
        assertEquals("image", item.getType());
        assertEquals("https://example.com/img.png", item.getUri());
        assertEquals("A logo", item.getAlt());
    }

    @Test
    void imageOutputItem_setters() {
        var item = new ImageOutputItem();
        item.setUri("/images/logo.svg");
        item.setAlt("Logo");
        assertEquals("/images/logo.svg", item.getUri());
        assertEquals("Logo", item.getAlt());
    }

    @Test
    void imageOutputItem_equalsAndHashCode() {
        var a = new ImageOutputItem("uri", "alt");
        var b = new ImageOutputItem("uri", "alt");
        var c = new ImageOutputItem("other", "alt");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    // ==================== QuickReplyOutputItem ====================

    @Test
    void quickReplyOutputItem_defaultConstructor() {
        var item = new QuickReplyOutputItem();
        assertEquals("quickReply", item.getType());
    }

    @Test
    void quickReplyOutputItem_fullConstructor() {
        var item = new QuickReplyOutputItem("Yes", "confirm(yes)", false);
        assertEquals("quickReply", item.getType());
        assertEquals("Yes", item.getValue());
        assertEquals("confirm(yes)", item.getExpressions());
    }

    @Test
    void quickReplyOutputItem_setters() {
        var item = new QuickReplyOutputItem();
        item.setValue("No");
        item.setExpressions("confirm(no)");
        assertEquals("No", item.getValue());
        assertEquals("confirm(no)", item.getExpressions());
    }

    // ==================== AgentFaceOutputItem ====================

    @Test
    void agentFaceOutputItem_defaultConstructor() {
        var item = new AgentFaceOutputItem();
        assertEquals("agentFace", item.getType());
        assertNull(item.getUri());
        assertNull(item.getAlt());
        assertEquals(0, item.getDelay());
    }

    @Test
    void agentFaceOutputItem_fullConstructor() {
        var item = new AgentFaceOutputItem("face.png", "happy face", 300);
        assertEquals("face.png", item.getUri());
        assertEquals("happy face", item.getAlt());
        assertEquals(300, item.getDelay());
    }

    @Test
    void agentFaceOutputItem_setters() {
        var item = new AgentFaceOutputItem();
        item.setUri("sad.png");
        item.setAlt("sad face");
        item.setDelay(500);
        assertEquals("sad.png", item.getUri());
        assertEquals("sad face", item.getAlt());
        assertEquals(500, item.getDelay());
    }

    @Test
    void agentFaceOutputItem_equalsAndHashCode() {
        var a = new AgentFaceOutputItem("face.png", "alt", 100);
        var b = new AgentFaceOutputItem("face.png", "alt", 100);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ==================== ApplicationLinkOutputItem ====================

    @Test
    void applicationLinkOutputItem_defaultConstructor() {
        var item = new ApplicationLinkOutputItem();
        assertEquals("applicationLink", item.getType());
        assertNull(item.getPath());
        assertNull(item.getLabel());
        assertEquals(0, item.getDelay());
    }

    @Test
    void applicationLinkOutputItem_pathConstructor() {
        var item = new ApplicationLinkOutputItem("https://eddi.labs.ai");
        assertEquals("https://eddi.labs.ai", item.getPath());
    }

    @Test
    void applicationLinkOutputItem_setters() {
        var item = new ApplicationLinkOutputItem();
        item.setPath("https://example.com");
        item.setLabel("Example");
        item.setDelay(200);
        assertEquals("https://example.com", item.getPath());
        assertEquals("Example", item.getLabel());
        assertEquals(200, item.getDelay());
    }

    @Test
    void applicationLinkOutputItem_toString() {
        var item = new ApplicationLinkOutputItem();
        item.setPath("/page");
        item.setLabel("Page");
        item.setDelay(0);
        assertEquals("/page;Page;0", item.toString());
    }

    @Test
    void applicationLinkOutputItem_equalsAndHashCode() {
        var a = new ApplicationLinkOutputItem("path");
        a.setLabel("lbl");
        var b = new ApplicationLinkOutputItem("path");
        b.setLabel("lbl");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ==================== InputFieldOutputItem ====================

    @Test
    void inputFieldOutputItem_defaultConstructor() {
        var item = new InputFieldOutputItem();
        assertEquals("inputField", item.getType());
    }

    @Test
    void inputFieldOutputItem_setters() {
        var item = new InputFieldOutputItem();
        item.setSubType("email");
        item.setPlaceholder("Enter email");
        item.setLabel("Email");
        item.setDefaultValue("user@example.com");
        assertEquals("email", item.getSubType());
        assertEquals("Enter email", item.getPlaceholder());
        assertEquals("Email", item.getLabel());
        assertEquals("user@example.com", item.getDefaultValue());
    }

    @Test
    void inputFieldOutputItem_equalsAndHashCode() {
        var a = new InputFieldOutputItem();
        a.setSubType("text");
        a.setLabel("Name");
        var b = new InputFieldOutputItem();
        b.setSubType("text");
        b.setLabel("Name");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ==================== OtherOutputItem ====================

    @Test
    void otherOutputItem_mapOperations() {
        var item = new OtherOutputItem();
        assertTrue(item.isEmpty());
        assertEquals(0, item.size());

        item.put("key1", "val1");
        item.put("key2", "val2");
        assertEquals(2, item.size());
        assertFalse(item.isEmpty());
        assertEquals("val1", item.get("key1"));
        assertTrue(item.containsKey("key1"));
        assertTrue(item.containsValue("val1"));
    }

    @Test
    void otherOutputItem_remove() {
        var item = new OtherOutputItem();
        item.put("k", "v");
        assertEquals("v", item.remove("k"));
        assertTrue(item.isEmpty());
    }

    @Test
    void otherOutputItem_putAll() {
        var item = new OtherOutputItem();
        item.putAll(Map.of("a", "1", "b", "2"));
        assertEquals(2, item.size());
    }

    @Test
    void otherOutputItem_clear() {
        var item = new OtherOutputItem();
        item.put("a", "1");
        item.clear();
        assertTrue(item.isEmpty());
    }

    @Test
    void otherOutputItem_keySetValuesEntrySet() {
        var item = new OtherOutputItem();
        item.put("x", "y");
        assertEquals(1, item.keySet().size());
        assertEquals(1, item.values().size());
        assertEquals(1, item.entrySet().size());
    }

    @Test
    void otherOutputItem_equalsAndHashCode() {
        var a = new OtherOutputItem();
        a.put("k", "v");
        var b = new OtherOutputItem();
        b.put("k", "v");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ==================== Jackson polymorphic ====================

    @Test
    void jackson_imageDeserialization() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"image","uri":"http://img.com/a.png","alt":"pic"}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(ImageOutputItem.class, item);
    }

    @Test
    void jackson_quickReplyDeserialization() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"quickReply","value":"Yes","expressions":"confirm(yes)"}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(QuickReplyOutputItem.class, item);
    }

    @Test
    void jackson_inputFieldDeserialization() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"inputField","subType":"email","placeholder":"Enter email","label":"Email"}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(InputFieldOutputItem.class, item);
    }

    @Test
    void jackson_agentFaceDeserialization() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"agentFace","uri":"face.png","alt":"happy","delay":0}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(AgentFaceOutputItem.class, item);
    }

    @Test
    void jackson_applicationLinkDeserialization() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"applicationLink","path":"/page","label":"Page","delay":0}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(ApplicationLinkOutputItem.class, item);
    }
}
