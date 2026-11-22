/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContestedMemoryMarkerTest {

    private static ConversationProperties withMarker(String... keys) {
        var properties = new ConversationProperties(null);
        Map<String, Object> marker = new LinkedHashMap<>();
        for (String key : keys) {
            marker.put(key, true);
        }
        properties.put(ContestedMemoryMarker.KEY, new Property(ContestedMemoryMarker.KEY, marker, Scope.conversation));
        return properties;
    }

    @Test
    void liftsTheWrittenKeyAndKeepsTheRest() {
        var properties = withMarker("lang", "city");

        ContestedMemoryMarker.lift(properties, Set.of("lang"));

        Property marker = properties.get(ContestedMemoryMarker.KEY);
        assertEquals(Map.of("city", true), marker.getValueObject());
        assertEquals(Scope.conversation, marker.getScope());
    }

    @Test
    void removesTheMarkerOnceItListsNothing() {
        var properties = withMarker("lang");

        ContestedMemoryMarker.lift(properties, List.of("lang"));

        assertFalse(properties.containsKey(ContestedMemoryMarker.KEY));
    }

    @Test
    void anUncontestedKeyChangesNothing() {
        var properties = withMarker("lang");
        Property before = properties.get(ContestedMemoryMarker.KEY);

        ContestedMemoryMarker.lift(properties, Set.of("color"));

        assertSame(before, properties.get(ContestedMemoryMarker.KEY));
    }

    /** A longTerm property of that name is a memory the user stored. */
    @Test
    void leavesALongTermMemoryOfTheSameNameAlone() {
        var properties = new ConversationProperties(null);
        Property memory = new Property(ContestedMemoryMarker.KEY, Map.of("lang", true), Scope.longTerm);
        properties.put(ContestedMemoryMarker.KEY, memory);

        ContestedMemoryMarker.lift(properties, Set.of("lang"));

        assertSame(memory, properties.get(ContestedMemoryMarker.KEY));
    }

    @Test
    void noMarkerAndNullInputsAreNoOps() {
        var properties = new ConversationProperties(null);

        ContestedMemoryMarker.lift(properties, Set.of("lang"));
        ContestedMemoryMarker.lift(null, Set.of("lang"));
        ContestedMemoryMarker.lift(properties, null);

        assertTrue(properties.isEmpty());
    }
}
