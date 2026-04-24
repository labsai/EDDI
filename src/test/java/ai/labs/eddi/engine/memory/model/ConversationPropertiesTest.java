/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationPropertiesTest {

    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private ConversationProperties properties;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        properties = new ConversationProperties(memory);
    }

    // ==================== put ====================

    @Test
    void put_stringProperty_storesInMapAndMemory() {
        var prop = new Property("name", "Alice", Property.Scope.conversation);
        properties.put("name", prop);

        assertEquals(prop, properties.get("name"));
        assertEquals("Alice", properties.toMap().get("name"));
        verify(currentStep).storeData(any(IData.class));
        verify(currentStep).addConversationOutputMap(eq("properties"), anyMap());
    }

    @Test
    void put_intProperty() {
        var prop = new Property("age", 30, Property.Scope.conversation);
        properties.put("age", prop);

        assertEquals(30, properties.toMap().get("age"));
    }

    @Test
    void put_booleanProperty() {
        var prop = new Property("active", true, Property.Scope.conversation);
        properties.put("active", prop);

        assertEquals(true, properties.toMap().get("active"));
    }

    @Test
    void put_objectProperty() {
        Map<String, Object> obj = Map.of("key", "value");
        var prop = new Property("settings", obj, Property.Scope.longTerm);
        properties.put("settings", prop);

        assertEquals(obj, properties.toMap().get("settings"));
    }

    @Test
    void put_listProperty() {
        List<Object> list = List.of("a", "b", "c");
        var prop = new Property("tags", list, Property.Scope.conversation);
        properties.put("tags", prop);

        assertEquals(list, properties.toMap().get("tags"));
    }

    @Test
    void put_floatProperty() {
        var prop = new Property("score", 0.95f, Property.Scope.step);
        properties.put("score", prop);

        assertEquals(0.95f, properties.toMap().get("score"));
    }

    @Test
    void put_overwriteExistingProperty() {
        properties.put("name", new Property("name", "Alice", Property.Scope.conversation));
        properties.put("name", new Property("name", "Bob", Property.Scope.conversation));

        assertEquals("Bob", properties.toMap().get("name"));
        assertEquals(1, properties.size());
    }

    // ==================== putAll ====================

    @Test
    void putAll_delegatesToPut() {
        var p1 = new Property("a", "1", Property.Scope.conversation);
        var p2 = new Property("b", "2", Property.Scope.conversation);
        properties.putAll(Map.of("a", p1, "b", p2));

        assertEquals(2, properties.size());
        assertEquals("1", properties.toMap().get("a"));
        assertEquals("2", properties.toMap().get("b"));
    }

    // ==================== toMap ====================

    @Test
    void toMap_emptyByDefault() {
        assertTrue(properties.toMap().isEmpty());
    }

    @Test
    void toMap_returnsPropertiesMapReference() {
        properties.put("x", new Property("x", "val", Property.Scope.conversation));
        Map<String, Object> map = properties.toMap();
        assertEquals("val", map.get("x"));
    }

    // ==================== null memory ====================

    @Test
    void put_nullMemory_stillStoresInHashMap() {
        var props = new ConversationProperties(null);
        var prop = new Property("key", "value", Property.Scope.conversation);
        props.put("key", prop);

        assertEquals(prop, props.get("key"));
        // toMap won't have the value since memory-backed storage is skipped
    }
}
