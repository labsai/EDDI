/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataTest {

    @Test
    void constructor_keyAndResult_setsFields() {
        var data = new Data<>("key1", "value1");
        assertEquals("key1", data.getKey());
        assertEquals("value1", data.getResult());
        assertNotNull(data.getTimestamp());
        assertFalse(data.isPublic());
        assertTrue(data.isCommitted());
    }

    @Test
    void constructor_withPossibleResults_setsAll() {
        var data = new Data<>("key", "a", List.of("a", "b", "c"));
        assertEquals("a", data.getResult());
        assertEquals(3, data.getPossibleResults().size());
    }

    @Test
    void constructor_withTimestamp_usesIt() {
        Date ts = new Date(1000L);
        var data = new Data<>("key", "val", List.of("val"), ts);
        assertEquals(ts, data.getTimestamp());
    }

    @Test
    void constructor_nullResult_choosesRandom() {
        var data = new Data<>("key", null, List.of("a", "b"));
        assertNotNull(data.getResult());
        assertTrue(List.of("a", "b").contains(data.getResult()));
    }

    @Test
    void constructor_nullResultEmptyList_returnsNull() {
        var data = new Data<>("key", null, List.of());
        assertNull(data.getResult());
    }

    @Test
    void setResult_updatesResult() {
        var data = new Data<>("key", "old");
        data.setResult("new");
        assertEquals("new", data.getResult());
    }

    @Test
    void setPublic_updatesFlag() {
        var data = new Data<>("key", "val");
        assertFalse(data.isPublic());
        data.setPublic(true);
        assertTrue(data.isPublic());
    }

    @Test
    void setCommitted_updatesFlag() {
        var data = new Data<>("key", "val");
        assertTrue(data.isCommitted());
        data.setCommitted(false);
        assertFalse(data.isCommitted());
    }

    @Test
    void setOriginWorkflowId_updatesId() {
        var data = new Data<>("key", "val");
        assertNull(data.getOriginWorkflowId());
        data.setOriginWorkflowId("wf-1");
        assertEquals("wf-1", data.getOriginWorkflowId());
    }

    @Test
    void equals_sameKey_returnsTrue() {
        var data1 = new Data<>("key", "val1");
        var data2 = new Data<>("key", "val2");
        assertEquals(data1, data2);
    }

    @Test
    void equals_differentKey_returnsFalse() {
        var data1 = new Data<>("key1", "val");
        var data2 = new Data<>("key2", "val");
        assertNotEquals(data1, data2);
    }

    @Test
    void hashCode_sameKey_sameHash() {
        var data1 = new Data<>("key", "val1");
        var data2 = new Data<>("key", "val2");
        assertEquals(data1.hashCode(), data2.hashCode());
    }

    @Test
    void toString_containsKeyAndResult() {
        var data = new Data<>("myKey", "myVal");
        String str = data.toString();
        assertTrue(str.contains("myKey"));
        assertTrue(str.contains("myVal"));
    }

    @Test
    void setPossibleResults_updatesResults() {
        var data = new Data<>("key", "a", List.of("a"));
        data.setPossibleResults(List.of("x", "y"));
        assertEquals(List.of("x", "y"), data.getPossibleResults());
    }
}
