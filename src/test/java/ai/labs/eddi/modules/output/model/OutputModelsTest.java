/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.model.types.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputModelsTest {

    // ==================== TextOutputItem ====================

    @Test
    void textOutputItem_defaultConstructor_setsType() {
        var item = new TextOutputItem();
        assertEquals("text", item.getType());
        assertNull(item.getText());
        assertEquals(0, item.getDelay());
    }

    @Test
    void textOutputItem_textConstructor() {
        var item = new TextOutputItem("Hello");
        assertEquals("text", item.getType());
        assertEquals("Hello", item.getText());
    }

    @Test
    void textOutputItem_fullConstructor() {
        var item = new TextOutputItem("Hello", 500);
        assertEquals("Hello", item.getText());
        assertEquals(500, item.getDelay());
    }

    @Test
    void textOutputItem_setters() {
        var item = new TextOutputItem();
        item.setText("World");
        item.setDelay(100);
        assertEquals("World", item.getText());
        assertEquals(100, item.getDelay());
    }

    @Test
    void textOutputItem_toString() {
        assertEquals("Hello", new TextOutputItem("Hello").toString());
    }

    @Test
    void textOutputItem_equalsAndHashCode() {
        var a = new TextOutputItem("Hello", 100);
        var b = new TextOutputItem("Hello", 100);
        var c = new TextOutputItem("World", 100);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertEquals(a, a);
    }

    // ==================== ButtonOutputItem ====================

    @Test
    void buttonOutputItem_defaultConstructor_setsType() {
        var item = new ButtonOutputItem();
        assertEquals("button", item.getType());
    }

    @Test
    void buttonOutputItem_fullConstructor() {
        var item = new ButtonOutputItem("submit", "Click me", Map.of("action", (Object) "submit_form"));
        assertEquals("button", item.getType());
        assertEquals("submit", item.getButtonType());
        assertEquals("Click me", item.getLabel());
        assertEquals("submit_form", item.getOnPress().get("action"));
    }

    @Test
    void buttonOutputItem_setters() {
        var item = new ButtonOutputItem();
        item.setButtonType("link");
        item.setLabel("Open");
        item.setOnPress(Map.of("url", (Object) "https://example.com"));
        assertEquals("link", item.getButtonType());
        assertEquals("Open", item.getLabel());
    }

    @Test
    void buttonOutputItem_equalsAndHashCode() {
        var a = new ButtonOutputItem("submit", "Go", Map.of());
        var b = new ButtonOutputItem("submit", "Go", Map.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ==================== QuickReply ====================

    @Test
    void quickReply_defaultConstructor() {
        var qr = new QuickReply();
        assertNull(qr.getValue());
        assertNull(qr.getExpressions());
        assertNull(qr.getIsDefault());
    }

    @Test
    void quickReply_fullConstructor() {
        var qr = new QuickReply("Yes", "confirmation(yes)", true);
        assertEquals("Yes", qr.getValue());
        assertEquals("confirmation(yes)", qr.getExpressions());
        assertTrue(qr.getIsDefault());
    }

    @Test
    void quickReply_setters() {
        var qr = new QuickReply();
        qr.setValue("No");
        qr.setExpressions("confirmation(no)");
        qr.setIsDefault(false);
        assertEquals("No", qr.getValue());
        assertEquals("confirmation(no)", qr.getExpressions());
        assertFalse(qr.getIsDefault());
    }

    @Test
    void quickReply_equalsAndHashCode() {
        var a = new QuickReply("Yes", "expr", true);
        var b = new QuickReply("Yes", "expr", true);
        var c = new QuickReply("No", "expr", false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void quickReply_toString() {
        var qr = new QuickReply("Yes", "expr", true);
        assertTrue(qr.toString().contains("Yes"));
        assertTrue(qr.toString().contains("expr"));
    }

    // ==================== OutputValue ====================

    @Test
    void outputValue_defaultConstructor() {
        var ov = new OutputValue();
        assertNull(ov.getValueAlternatives());
    }

    @Test
    void outputValue_withAlternatives() {
        var text = new TextOutputItem("Hello");
        var ov = new OutputValue(List.of(text));
        assertEquals(1, ov.getValueAlternatives().size());
        assertSame(text, ov.getValueAlternatives().get(0));
    }

    @Test
    void outputValue_setter() {
        var ov = new OutputValue();
        ov.setValueAlternatives(List.of(new TextOutputItem("Hi")));
        assertEquals(1, ov.getValueAlternatives().size());
    }

    @Test
    void outputValue_equalsAndHashCode() {
        var a = new OutputValue(List.of(new TextOutputItem("Hello")));
        var b = new OutputValue(List.of(new TextOutputItem("Hello")));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ==================== OutputEntry ====================

    @Test
    void outputEntry_constructor() {
        var entry = new OutputEntry("greet", 3,
                List.of(new OutputValue(List.of(new TextOutputItem("Hi")))),
                List.of(new QuickReply("Yes", "expr", true)));

        assertEquals("greet", entry.getAction());
        assertEquals(3, entry.getOccurred());
        assertEquals(1, entry.getOutputs().size());
        assertEquals(1, entry.getQuickReplies().size());
    }

    @Test
    void outputEntry_setters() {
        var entry = new OutputEntry("a", 0, List.of(), List.of());
        entry.setAction("updated");
        entry.setOccurred(5);
        entry.setOutputs(List.of(new OutputValue()));
        entry.setQuickReplies(List.of(new QuickReply()));
        assertEquals("updated", entry.getAction());
        assertEquals(5, entry.getOccurred());
    }

    @Test
    void outputEntry_compareTo_sortsByOccurred() {
        var e1 = new OutputEntry("a", 1, List.of(), List.of());
        var e2 = new OutputEntry("b", 5, List.of(), List.of());
        var e3 = new OutputEntry("c", 3, List.of(), List.of());

        var sorted = new java.util.ArrayList<>(List.of(e2, e3, e1));
        Collections.sort(sorted);
        assertEquals("a", sorted.get(0).getAction());
        assertEquals("c", sorted.get(1).getAction());
        assertEquals("b", sorted.get(2).getAction());
    }

    @Test
    void outputEntry_equalsAndHashCode() {
        var a = new OutputEntry("greet", 1, List.of(), List.of());
        var b = new OutputEntry("greet", 1, List.of(), List.of());
        var c = new OutputEntry("bye", 1, List.of(), List.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void outputEntry_toString() {
        var entry = new OutputEntry("greet", 1, List.of(), List.of());
        assertTrue(entry.toString().contains("greet"));
        assertTrue(entry.toString().contains("1"));
    }

    // ==================== Jackson polymorphic deserialization ====================

    @Test
    void outputItem_jacksonPolymorphism() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"text","text":"Hello","delay":0}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(TextOutputItem.class, item);
        assertEquals("Hello", ((TextOutputItem) item).getText());
    }

    @Test
    void outputItem_buttonPolymorphism() throws Exception {
        var mapper = new ObjectMapper();
        var json = """
                {"type":"button","buttonType":"submit","label":"Go","onPress":{}}
                """;
        var item = mapper.readValue(json, OutputItem.class);
        assertInstanceOf(ButtonOutputItem.class, item);
        assertEquals("Go", ((ButtonOutputItem) item).getLabel());
    }

    @Test
    void outputValue_jacksonRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var ov = new OutputValue(List.of(new TextOutputItem("Test")));
        var json = mapper.writeValueAsString(ov);
        var deserialized = mapper.readValue(json, OutputValue.class);
        assertEquals(1, deserialized.getValueAlternatives().size());
    }
}
