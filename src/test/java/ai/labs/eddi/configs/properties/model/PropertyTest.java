/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @DisplayName("autoVaulted provenance marker")
    class AutoVaulted {

        /**
         * EDDI's global serialization inclusion ({@code SerializationCustomizer}),
         * which is what decides whether an unset marker reaches the conversation
         * document.
         */
        private ObjectMapper mapper() {
            return new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        }

        @Test
        @DisplayName("unset by default — nothing but autoVaultSecret marks a property")
        void unsetByDefault() {
            assertNull(new Property("n", "v", Property.Scope.conversation).getAutoVaulted());
        }

        @Test
        @DisplayName("a marked property round-trips through JSON")
        void roundTrips() throws Exception {
            var prop = new Property("apiKey", "${vault:agent1.apiKey}", Property.Scope.conversation);
            prop.setAutoVaulted(Boolean.TRUE);

            var json = mapper().writeValueAsString(prop);
            assertTrue(json.contains("\"autoVaulted\":true"), json);
            assertEquals(Boolean.TRUE, mapper().readValue(json, Property.class).getAutoVaulted());
        }

        @Test
        @DisplayName("an unmarked property does not carry the field at all")
        void unmarkedIsNotWritten() throws Exception {
            var json = mapper().writeValueAsString(new Property("n", "v", Property.Scope.conversation));
            assertFalse(json.contains("autoVaulted"), json);
        }

        @Test
        @DisplayName("a conversation document written before the field existed still reads, unmarked")
        void legacyDocumentDeserializes() throws Exception {
            // The backward-compatibility case: every conversation and user-memory
            // document already in MongoDB. The read must not fail, and the property must
            // come back unmarked — which ConfigReferenceGuard refuses rather than
            // trusts, because an unmarked property and one written from conversation
            // data are the same thing here.
            var legacy = "{\"name\":\"apiKey\",\"valueString\":\"${vault:agent1.apiKey}\",\"scope\":\"conversation\"}";

            var prop = mapper().readValue(legacy, Property.class);

            assertEquals("${vault:agent1.apiKey}", prop.getValueString());
            assertNull(prop.getAutoVaulted());
        }

        @Test
        @DisplayName("a different marker should not be equal")
        void differentMarker() {
            var p1 = new Property("n", "v", Property.Scope.conversation);
            var p2 = new Property("n", "v", Property.Scope.conversation);
            p2.setAutoVaulted(Boolean.TRUE);
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
