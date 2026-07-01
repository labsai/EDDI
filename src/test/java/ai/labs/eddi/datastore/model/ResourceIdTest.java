/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResourceId} — POJO implementing IResourceId.
 */
@DisplayName("ResourceId")
class ResourceIdTest {

    // ==================== Constructor & Getters ====================

    @Test
    @DisplayName("constructor sets id and version")
    void constructorSetsFields() {
        var rid = new ResourceId("abc", 3);
        assertEquals("abc", rid.getId());
        assertEquals(3, rid.getVersion());
    }

    @Test
    @DisplayName("constructor accepts null values")
    void constructorNulls() {
        var rid = new ResourceId(null, null);
        assertNull(rid.getId());
        assertNull(rid.getVersion());
    }

    // ==================== Setters ====================

    @Test
    @DisplayName("setId changes the id")
    void setId() {
        var rid = new ResourceId("old", 1);
        rid.setId("new");
        assertEquals("new", rid.getId());
    }

    @Test
    @DisplayName("setVersion changes the version")
    void setVersion() {
        var rid = new ResourceId("id", 1);
        rid.setVersion(99);
        assertEquals(99, rid.getVersion());
    }

    // ==================== equals ====================

    @Nested
    @DisplayName("equals")
    class EqualsTests {

        @Test
        @DisplayName("same object — returns true (reflexive)")
        void sameObject() {
            var rid = new ResourceId("id1", 1);
            assertEquals(rid, rid);
        }

        @Test
        @DisplayName("null — returns false")
        void nullObject() {
            var rid = new ResourceId("id1", 1);
            assertNotEquals(null, rid);
        }

        @Test
        @DisplayName("different class — returns false")
        void differentClass() {
            var rid = new ResourceId("id1", 1);
            assertNotEquals("not a ResourceId", rid);
        }

        @Test
        @DisplayName("same id and version — returns true")
        void sameIdAndVersion() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId("id1", 1);
            assertEquals(a, b);
        }

        @Test
        @DisplayName("different id — returns false")
        void differentId() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId("id2", 1);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different version — returns false")
        void differentVersion() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId("id1", 2);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("both null id and version — returns true")
        void bothNulls() {
            var a = new ResourceId(null, null);
            var b = new ResourceId(null, null);
            assertEquals(a, b);
        }

        @Test
        @DisplayName("one null id — returns false")
        void oneNullId() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId(null, 1);
            assertNotEquals(a, b);
            assertNotEquals(b, a);
        }

        @Test
        @DisplayName("one null version — returns false")
        void oneNullVersion() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId("id1", null);
            assertNotEquals(a, b);
            assertNotEquals(b, a);
        }
    }

    // ==================== hashCode ====================

    @Nested
    @DisplayName("hashCode")
    class HashCodeTests {

        @Test
        @DisplayName("equal objects have same hashCode")
        void equalObjectsSameHashCode() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId("id1", 1);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different objects likely different hashCode")
        void differentObjectsDifferentHashCode() {
            var a = new ResourceId("id1", 1);
            var b = new ResourceId("id2", 2);
            // Not guaranteed, but extremely unlikely to collide
            assertNotEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("null fields hashCode does not throw")
        void nullFieldsHashCode() {
            var rid = new ResourceId(null, null);
            assertDoesNotThrow(rid::hashCode);
        }
    }

    // ==================== toString ====================

    @Test
    @DisplayName("toString contains id and version")
    void toStringContainsFields() {
        var rid = new ResourceId("abc", 42);
        String str = rid.toString();
        assertTrue(str.contains("abc"));
        assertTrue(str.contains("42"));
        assertTrue(str.contains("ResourceId"));
    }

    @Test
    @DisplayName("toString with nulls does not throw")
    void toStringWithNulls() {
        var rid = new ResourceId(null, null);
        String str = rid.toString();
        assertNotNull(str);
        assertTrue(str.contains("null"));
    }
}
