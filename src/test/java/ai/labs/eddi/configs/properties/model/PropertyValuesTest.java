/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PropertyValuesTest {

    @Test
    @DisplayName("toProperty copies nested maps and lists, so writing into the property never reaches the configuration value")
    @SuppressWarnings("unchecked")
    void toPropertyDetachesNestedContainers() {
        Map<String, Object> nested = new HashMap<>(Map.of("city", "Vienna"));
        List<Object> tags = new ArrayList<>(List.of("a"));
        Map<String, Object> configured = new HashMap<>(Map.of("address", nested, "tags", tags));

        Property property = PropertyValues.toProperty("profile", configured, Property.Scope.conversation);
        ((Map<String, Object>) property.getValueObject().get("address")).put("city", "a secret of this conversation");
        ((List<Object>) property.getValueObject().get("tags")).add("leaked");

        assertEquals("Vienna", nested.get("city"));
        assertEquals(List.of("a"), tags);
    }

    @Test
    @DisplayName("copyOf copies nested containers as well")
    @SuppressWarnings("unchecked")
    void copyOfDetachesNestedContainers() {
        Map<String, Object> nested = new HashMap<>(Map.of("k", "v"));
        List<Object> list = new ArrayList<>(List.of(new HashMap<>(Map.of("x", 1))));
        Property original = new Property("p", new HashMap<>(Map.of("nested", nested)), Property.Scope.conversation);
        original.setValueList(list);

        Property copy = PropertyValues.copyOf(original);

        assertNotSame(nested, copy.getValueObject().get("nested"));
        ((Map<String, Object>) copy.getValueObject().get("nested")).put("k", "changed");
        ((Map<String, Object>) copy.getValueList().get(0)).put("x", 2);
        assertEquals("v", nested.get("k"));
        assertEquals(1, ((Map<String, Object>) list.get(0)).get("x"));
    }
}
