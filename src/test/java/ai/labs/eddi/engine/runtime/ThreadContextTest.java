/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ThreadContext} — the thread-local key-value store.
 */
class ThreadContextTest {

    @AfterEach
    void cleanup() {
        ThreadContext.remove();
    }

    @Nested
    @DisplayName("put and get")
    class PutGet {

        @Test
        @DisplayName("should store and retrieve value")
        void basicPutGet() {
            ThreadContext.put("key1", "value1");
            assertEquals("value1", ThreadContext.get("key1"));
        }

        @Test
        @DisplayName("should return null for unknown key")
        void unknownKey() {
            assertNull(ThreadContext.get("nonexistent"));
        }

        @Test
        @DisplayName("put with null value should remove key")
        void putNullRemoves() {
            ThreadContext.put("key", "value");
            ThreadContext.put("key", null);
            assertNull(ThreadContext.get("key"));
        }

        @Test
        @DisplayName("put with null key should throw")
        void nullKeyThrows() {
            assertThrows(IllegalArgumentException.class, () -> ThreadContext.put(null, "value"));
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("should remove and return value")
        void removeReturnsValue() {
            ThreadContext.put("key", "value");
            Object removed = ThreadContext.remove("key");
            assertEquals("value", removed);
            assertNull(ThreadContext.get("key"));
        }

        @Test
        @DisplayName("should return null for unknown key")
        void removeUnknown() {
            assertNull(ThreadContext.remove("nonexistent"));
        }
    }

    @Nested
    @DisplayName("getResources / setResources")
    class Resources {

        @Test
        @DisplayName("getResources should return copy of current state")
        void getResourcesCopy() {
            ThreadContext.put("a", "1");
            Map<Object, Object> resources = ThreadContext.getResources();
            assertEquals("1", resources.get("a"));
            // Modifying copy should not affect original
            resources.put("b", "2");
            assertNull(ThreadContext.get("b"));
        }

        @Test
        @DisplayName("setResources should replace all values")
        void setResources() {
            ThreadContext.put("old", "value");
            Map<Object, Object> newMap = new HashMap<>();
            newMap.put("new", "value");
            ThreadContext.setResources(newMap);
            assertNull(ThreadContext.get("old"));
            assertEquals("value", ThreadContext.get("new"));
        }

        @Test
        @DisplayName("setResources with null should be no-op")
        void setNullResources() {
            ThreadContext.put("key", "value");
            ThreadContext.setResources(null);
            assertEquals("value", ThreadContext.get("key"));
        }

        @Test
        @DisplayName("setResources with empty map should be no-op")
        void setEmptyResources() {
            ThreadContext.put("key", "value");
            ThreadContext.setResources(new HashMap<>());
            assertEquals("value", ThreadContext.get("key"));
        }
    }

    @Nested
    @DisplayName("bind Subject")
    class BindSubject {

        @Test
        @DisplayName("binding non-null subject should store it")
        void bindSubject() {
            Subject subject = new Subject();
            ThreadContext.bind(subject);
            // Verify via getResources that something was stored
            assertFalse(ThreadContext.getResources().isEmpty());
        }

        @Test
        @DisplayName("binding null subject should be no-op")
        void bindNull() {
            ThreadContext.bind(null);
            assertTrue(ThreadContext.getResources().isEmpty());
        }
    }

    @Nested
    @DisplayName("remove() clears all")
    class RemoveAll {

        @Test
        @DisplayName("should clear all thread-local data")
        void clearAll() {
            ThreadContext.put("a", "1");
            ThreadContext.put("b", "2");
            ThreadContext.remove();
            // After remove(), getResources() returns fresh empty map
            assertTrue(ThreadContext.getResources().isEmpty());
        }
    }
}
