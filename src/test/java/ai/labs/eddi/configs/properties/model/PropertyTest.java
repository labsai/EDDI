package ai.labs.eddi.configs.properties.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Property} — constructors, effectiveVisibility,
 * equality.
 */
class PropertyTest {

    @Nested
    @DisplayName("constructors")
    class Constructors {

        @Test
        @DisplayName("string constructor")
        void stringConstructor() {
            var prop = new Property("name", "value", Property.Scope.conversation);
            assertEquals("name", prop.getName());
            assertEquals("value", prop.getValueString());
            assertEquals(Property.Scope.conversation, prop.getScope());
        }

        @Test
        @DisplayName("map constructor")
        void mapConstructor() {
            var map = Map.<String, Object>of("k", "v");
            var prop = new Property("name", map, Property.Scope.longTerm);
            assertEquals(map, prop.getValueObject());
        }

        @Test
        @DisplayName("list constructor")
        void listConstructor() {
            var list = List.<Object>of("a", "b");
            var prop = new Property("name", list, Property.Scope.step);
            assertEquals(list, prop.getValueList());
        }

        @Test
        @DisplayName("integer constructor")
        void intConstructor() {
            var prop = new Property("count", 42, Property.Scope.conversation);
            assertEquals(42, prop.getValueInt());
        }

        @Test
        @DisplayName("float constructor")
        void floatConstructor() {
            var prop = new Property("ratio", 0.5f, Property.Scope.conversation);
            assertEquals(0.5f, prop.getValueFloat());
        }

        @Test
        @DisplayName("boolean constructor")
        void booleanConstructor() {
            var prop = new Property("flag", true, Property.Scope.conversation);
            assertTrue(prop.getValueBoolean());
        }

        @Test
        @DisplayName("full constructor with visibility")
        void fullConstructor() {
            var prop = new Property("n", "v", null, null, null, null, null,
                    Property.Scope.longTerm, Property.Visibility.global);
            assertEquals(Property.Visibility.global, prop.getVisibility());
        }
    }

    @Nested
    @DisplayName("effectiveVisibility")
    class EffectiveVisibility {

        @Test
        @DisplayName("null visibility defaults to self")
        void nullDefaultsSelf() {
            var prop = new Property("name", "value", Property.Scope.longTerm);
            assertEquals(Property.Visibility.self, prop.effectiveVisibility());
        }

        @Test
        @DisplayName("explicit visibility is returned")
        void explicitVisibility() {
            var prop = new Property();
            prop.setVisibility(Property.Visibility.group);
            assertEquals(Property.Visibility.group, prop.effectiveVisibility());
        }

        @Test
        @DisplayName("global visibility is returned")
        void globalVisibility() {
            var prop = new Property();
            prop.setVisibility(Property.Visibility.global);
            assertEquals(Property.Visibility.global, prop.effectiveVisibility());
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("same fields should be equal")
        void equal() {
            var p1 = new Property("n", "v", Property.Scope.conversation);
            var p2 = new Property("n", "v", Property.Scope.conversation);
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        @DisplayName("different name should not be equal")
        void differentName() {
            var p1 = new Property("a", "v", Property.Scope.conversation);
            var p2 = new Property("b", "v", Property.Scope.conversation);
            assertNotEquals(p1, p2);
        }

        @Test
        @DisplayName("different scope should not be equal")
        void differentScope() {
            var p1 = new Property("n", "v", Property.Scope.conversation);
            var p2 = new Property("n", "v", Property.Scope.longTerm);
            assertNotEquals(p1, p2);
        }

        @Test
        @DisplayName("different visibility should not be equal")
        void differentVisibility() {
            var p1 = new Property("n", "v", null, null, null, null, null,
                    Property.Scope.longTerm, Property.Visibility.self);
            var p2 = new Property("n", "v", null, null, null, null, null,
                    Property.Scope.longTerm, Property.Visibility.global);
            assertNotEquals(p1, p2);
        }
    }

    @Nested
    @DisplayName("scope enum")
    class ScopeEnum {

        @Test
        @DisplayName("should have all expected values")
        void allValues() {
            var values = Property.Scope.values();
            assertEquals(4, values.length);
            assertNotNull(Property.Scope.valueOf("step"));
            assertNotNull(Property.Scope.valueOf("conversation"));
            assertNotNull(Property.Scope.valueOf("longTerm"));
            assertNotNull(Property.Scope.valueOf("secret"));
        }
    }

    @Nested
    @DisplayName("default scope")
    class DefaultScope {

        @Test
        @DisplayName("default constructor should default to conversation scope")
        void defaultScope() {
            var prop = new Property();
            assertEquals(Property.Scope.conversation, prop.getScope());
        }
    }
}
