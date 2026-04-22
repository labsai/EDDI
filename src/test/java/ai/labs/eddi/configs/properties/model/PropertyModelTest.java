package ai.labs.eddi.configs.properties.model;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropertyModelTest {

    // ─── Property ──────────────────────────────────

    @Nested
    class PropertyTests {

        @Test
        void stringConstructor_setsFields() {
            var p = new Property("name", "value", Property.Scope.conversation);
            assertEquals("name", p.getName());
            assertEquals("value", p.getValueString());
            assertEquals(Property.Scope.conversation, p.getScope());
        }

        @Test
        void mapConstructor_setsFields() {
            var map = Map.of("key", (Object) "val");
            var p = new Property("name", map, Property.Scope.longTerm);
            assertEquals(map, p.getValueObject());
            assertEquals(Property.Scope.longTerm, p.getScope());
        }

        @Test
        void listConstructor_setsFields() {
            var list = List.of((Object) "a", "b");
            var p = new Property("items", list, Property.Scope.step);
            assertEquals(list, p.getValueList());
        }

        @Test
        void intConstructor_setsFields() {
            var p = new Property("count", 42, Property.Scope.conversation);
            assertEquals(42, p.getValueInt());
        }

        @Test
        void floatConstructor_setsFields() {
            var p = new Property("temp", 36.5f, Property.Scope.conversation);
            assertEquals(36.5f, p.getValueFloat());
        }

        @Test
        void booleanConstructor_setsFields() {
            var p = new Property("active", true, Property.Scope.longTerm);
            assertTrue(p.getValueBoolean());
        }

        @Test
        void defaultScope_isConversation() {
            var p = new Property();
            assertEquals(Property.Scope.conversation, p.getScope());
        }

        @Test
        void effectiveVisibility_null_defaultsSelf() {
            var p = new Property("name", "val", Property.Scope.longTerm);
            assertNull(p.getVisibility());
            assertEquals(Property.Visibility.self, p.effectiveVisibility());
        }

        @Test
        void effectiveVisibility_explicit_returnsSet() {
            var p = new Property("name", "val", Property.Scope.longTerm);
            p.setVisibility(Property.Visibility.global);
            assertEquals(Property.Visibility.global, p.effectiveVisibility());
        }

        @Test
        void equals_sameValues_true() {
            var p1 = new Property("n", "v", Property.Scope.step);
            var p2 = new Property("n", "v", Property.Scope.step);
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        void equals_differentName_false() {
            var p1 = new Property("n1", "v", Property.Scope.step);
            var p2 = new Property("n2", "v", Property.Scope.step);
            assertNotEquals(p1, p2);
        }

        @Test
        void equals_differentScope_false() {
            var p1 = new Property("n", "v", Property.Scope.step);
            var p2 = new Property("n", "v", Property.Scope.longTerm);
            assertNotEquals(p1, p2);
        }

        @Test
        void equals_differentVisibility_false() {
            var p1 = new Property("n", "v", null, null, null, null, null,
                    Property.Scope.longTerm, Property.Visibility.self);
            var p2 = new Property("n", "v", null, null, null, null, null,
                    Property.Scope.longTerm, Property.Visibility.global);
            assertNotEquals(p1, p2);
        }

        @Test
        void allScopeValues() {
            assertEquals(4, Property.Scope.values().length);
            assertNotNull(Property.Scope.step);
            assertNotNull(Property.Scope.conversation);
            assertNotNull(Property.Scope.longTerm);
            assertNotNull(Property.Scope.secret);
        }

        @Test
        void allVisibilityValues() {
            assertEquals(3, Property.Visibility.values().length);
            assertNotNull(Property.Visibility.self);
            assertNotNull(Property.Visibility.group);
            assertNotNull(Property.Visibility.global);
        }
    }

    // ─── PropertyInstruction ───────────────────────

    @Nested
    class PropertyInstructionTests {

        @Test
        void defaults() {
            var pi = new PropertyInstruction();
            assertEquals("", pi.getFromObjectPath());
            assertEquals("", pi.getToObjectPath());
            assertFalse(pi.getConvertToObject());
            assertTrue(pi.getOverride());
            assertFalse(pi.getRunOnValidationError());
            assertNull(pi.getHttpCodeValidator());
        }

        @Test
        void constructor_setsAllFields() {
            var validator = new HttpCodeValidator(List.of(200), List.of(500));
            var pi = new PropertyInstruction("from.path", "to.path", true, false, true, validator);

            assertEquals("from.path", pi.getFromObjectPath());
            assertEquals("to.path", pi.getToObjectPath());
            assertTrue(pi.getConvertToObject());
            assertFalse(pi.getOverride());
            assertTrue(pi.getRunOnValidationError());
            assertNotNull(pi.getHttpCodeValidator());
        }

        @Test
        void setters() {
            var pi = new PropertyInstruction();
            pi.setFromObjectPath("memory.current.output");
            pi.setToObjectPath("properties.result");
            pi.setConvertToObject(true);
            pi.setOverride(false);
            pi.setRunOnValidationError(true);
            pi.setHttpCodeValidator(HttpCodeValidator.DEFAULT);

            assertEquals("memory.current.output", pi.getFromObjectPath());
            assertEquals("properties.result", pi.getToObjectPath());
            assertTrue(pi.getConvertToObject());
            assertFalse(pi.getOverride());
            assertTrue(pi.getRunOnValidationError());
            assertNotNull(pi.getHttpCodeValidator());
        }

        @Test
        void equals_sameFields_true() {
            var pi1 = new PropertyInstruction("fp", "tp", false, true, false, null);
            pi1.setName("prop");
            pi1.setValueString("val");
            pi1.setScope(Property.Scope.conversation);

            var pi2 = new PropertyInstruction("fp", "tp", false, true, false, null);
            pi2.setName("prop");
            pi2.setValueString("val");
            pi2.setScope(Property.Scope.conversation);

            assertEquals(pi1, pi2);
            assertEquals(pi1.hashCode(), pi2.hashCode());
        }

        @Test
        void equals_differentFromPath_false() {
            var pi1 = new PropertyInstruction("path1", "", false, true, false, null);
            var pi2 = new PropertyInstruction("path2", "", false, true, false, null);
            assertNotEquals(pi1, pi2);
        }
    }
}
