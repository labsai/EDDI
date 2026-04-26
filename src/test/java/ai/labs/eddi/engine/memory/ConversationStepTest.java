/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author ginccc
 */
@DisplayName("ConversationStep")
public class ConversationStepTest {
    private static IConversationMemory.IWritableConversationStep conversationStep;

    @BeforeEach
    public void setUp() {
        conversationStep = new ConversationStep(new ConversationOutput());
    }

    @Test
    public void testPlain() {
        // assert
        Assertions.assertNull(conversationStep.getData("whatever"));
        final List<IData<Void>> allData = conversationStep.getAllData("void");
        Assertions.assertNotNull(allData);
        Assertions.assertEquals(0, allData.size());
        Assertions.assertNotNull(conversationStep.getAllElements());
        Assertions.assertEquals(0, conversationStep.getAllElements().size());
        Assertions.assertNotNull(conversationStep.getAllKeys());
        Assertions.assertEquals(0, conversationStep.getAllKeys().size());
        Assertions.assertTrue(conversationStep.isEmpty());
        Assertions.assertEquals(0, conversationStep.size());
    }

    @Test
    public void testGetData() {
        // setup
        final var data = new Data<>("testKey", new LinkedList<>());
        conversationStep.storeData(data);

        // assert
        Assertions.assertNotNull(conversationStep.getData("testKey"));
        Assertions.assertEquals(data, conversationStep.getData("testKey"));
    }

    @Test
    public void testGetAllData() {
        // setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        final var data2 = new Data<>("testKey2", new LinkedList<>());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        // test
        final var allData = conversationStep.getAllData("testKey");

        // assert
        Assertions.assertNotNull(allData);
        Assertions.assertEquals(2, allData.size());
        Assertions.assertEquals(data1, allData.get(0));
        Assertions.assertEquals(data2, allData.get(1));
    }

    @Test
    public void testGetAllKeys() {
        // setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        final var data2 = new Data<>("testKey2", new LinkedList<>());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        // test
        final Set<String> allKeys = conversationStep.getAllKeys();

        // assert
        Assertions.assertNotNull(allKeys);
        Assertions.assertEquals("testKey1", allKeys.toArray()[0]);
        Assertions.assertEquals("testKey2", allKeys.toArray()[1]);

    }

    @Test
    public void testGetAllElements() {
        // setup
        final var data1 = new Data<>("testKey1", "testData1");
        final var data2 = new Data<>("testKey2", "testData2");
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        // test
        final var allData = conversationStep.getAllElements();

        // assert
        Assertions.assertNotNull(allData);
        Assertions.assertEquals("testData1", allData.toArray(new IData[2])[0].getResult());
        Assertions.assertEquals("testData2", allData.toArray(new IData[2])[1].getResult());
    }

    @Test
    public void testSize() {
        // setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        conversationStep.storeData(data1);

        // assert
        Assertions.assertEquals(1, conversationStep.size());
    }

    @Test
    public void testIsEmpty() {
        // setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        conversationStep.storeData(data1);

        // assert
        Assertions.assertFalse(conversationStep.isEmpty());
    }

    @Test
    public void testEquals_identicalData_shouldBeEqual() {
        // setup — two DISTINCT instances with the same data
        final var data = new Data<>("testKey", new LinkedList<>());
        conversationStep.storeData(data);

        ConversationStep otherStep = new ConversationStep(new ConversationOutput());
        otherStep.storeData(data);

        // assert — compare two different objects, not the same reference
        Assertions.assertEquals(conversationStep, otherStep);
        Assertions.assertEquals(otherStep, conversationStep);
    }

    @Test
    public void testEquals_differentData_shouldNotBeEqual() {
        // setup — two instances with different data
        conversationStep.storeData(new Data<>("keyA", "valueA"));

        ConversationStep otherStep = new ConversationStep(new ConversationOutput());
        otherStep.storeData(new Data<>("keyB", "valueB"));

        // assert
        Assertions.assertNotEquals(conversationStep, otherStep);
    }

    @Test
    public void testEquals_sameReference_shouldBeEqual() {
        conversationStep.storeData(new Data<>("key", "value"));
        Assertions.assertEquals(conversationStep, conversationStep);
    }

    @Test
    public void testRemoveData() {
        final var data1 = new Data<>("prefix:key1", "val1");
        final var data2 = new Data<>("prefix:key2", "val2");
        final var data3 = new Data<>("other:key3", "val3");
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);
        conversationStep.storeData(data3);

        conversationStep.removeData("prefix");

        Assertions.assertEquals(1, conversationStep.size());
        Assertions.assertNotNull(conversationStep.getData("other:key3"));
    }

    @Test
    public void testSnapshotDataIdentities() {
        final var data = new Data<>("key", "value");
        conversationStep.storeData(data);

        var snapshot = ((ConversationStep) conversationStep).snapshotDataIdentities();
        Assertions.assertEquals(1, snapshot.size());
        Assertions.assertSame(data, snapshot.get("key"));

        // Snapshot is a defensive copy — modifying it should not affect the step
        snapshot.clear();
        Assertions.assertEquals(1, conversationStep.size());
    }

    @Test
    public void testSnapshotOutputKeys() {
        conversationStep.addConversationOutputObject("key1", "val1");
        conversationStep.addConversationOutputString("key2", "val2");

        var keys = ((ConversationStep) conversationStep).snapshotOutputKeys();
        Assertions.assertTrue(keys.contains("key1"));
        Assertions.assertTrue(keys.contains("key2"));
    }

    @Test
    public void testGetLatestDataReverseScan() {
        // Store multiple data entries and verify getLatestData returns the last one
        // with matching prefix
        conversationStep.storeData(new Data<>("output:text:greet", "Hello"));
        conversationStep.storeData(new Data<>("output:text:farewell", "Bye"));

        IData<String> latest = conversationStep.getLatestData("output");
        // getLatestData reverses elements and returns first match
        Assertions.assertNotNull(latest);
        Assertions.assertEquals("output:text:farewell", latest.getKey());
    }

    @Test
    public void testGetLatestDataNoMatch() {
        conversationStep.storeData(new Data<>("actions", List.of("greet")));
        IData<String> latest = conversationStep.getLatestData("nonexistent");
        Assertions.assertNull(latest);
    }

    @Test
    public void testSetWithMemoryKey() {
        var key = MemoryKey.ofPublic("input");
        conversationStep.set(key, "hello world");

        IData<String> data = conversationStep.getData("input");
        Assertions.assertNotNull(data);
        Assertions.assertEquals("hello world", data.getResult());
        Assertions.assertTrue(data.isPublic());
    }

    @Test
    public void testGetWithMemoryKey() {
        var key = MemoryKey.<String>of("internal:data");
        conversationStep.set(key, "secret");

        String result = conversationStep.get(key);
        Assertions.assertEquals("secret", result);
    }

    @Test
    public void testGetWithMemoryKeyReturnsNullIfMissing() {
        var key = MemoryKey.<String>of("missing:key");
        String result = conversationStep.get(key);
        Assertions.assertNull(result);
    }

    @Test
    public void testAddConversationOutputList() {
        conversationStep.addConversationOutputList("items", List.of("a", "b"));
        conversationStep.addConversationOutputList("items", List.of("c"));

        var output = conversationStep.getConversationOutput();
        @SuppressWarnings("unchecked")
        var items = (List<Object>) output.get("items");
        Assertions.assertEquals(3, items.size());
    }

    @Test
    public void testAddConversationOutputMap() {
        conversationStep.addConversationOutputMap("data", java.util.Map.of("k1", "v1"));
        conversationStep.addConversationOutputMap("data", java.util.Map.of("k2", "v2"));

        var output = conversationStep.getConversationOutput();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) output.get("data");
        Assertions.assertEquals("v1", data.get("k1"));
        Assertions.assertEquals("v2", data.get("k2"));
    }

    @Test
    public void testReplaceConversationOutputObject() {
        conversationStep.addConversationOutputList("items", List.of("old"));
        conversationStep.replaceConversationOutputObject("items", "old", "new");

        var output = conversationStep.getConversationOutput();
        @SuppressWarnings("unchecked")
        var items = (List<Object>) output.get("items");
        Assertions.assertTrue(items.contains("new"));
        Assertions.assertFalse(items.contains("old"));
    }

    @Test
    public void testToString() {
        conversationStep.storeData(new Data<>("input", "hello"));
        String str = conversationStep.toString();
        Assertions.assertTrue(str.contains("ConversationStep"));
    }
}
