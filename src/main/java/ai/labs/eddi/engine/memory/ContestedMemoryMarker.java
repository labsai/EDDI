/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The conversation-scoped {@code contestedMemories} property: the loaded
 * memories Dream marked {@code CONTESTED}, as {@code key -> true}. It is set
 * once, when the conversation loads the user's memories, and a key leaves it
 * when the conversation writes that key again — a write is a new statement of
 * the fact, which every store records as {@code ACCEPTED}.
 * <p>
 * Two paths write a key: the end-of-turn persistence of a changed
 * {@code longTerm} property, and the {@code rememberFact} tool, which writes to
 * the store directly and leaves the conversation property as it was. Both lift
 * the marker through {@link #lift}, so a fact the user confirmed through the
 * tool stops prompting for clarification for the rest of the conversation.
 */
public final class ContestedMemoryMarker {

    /** The property name. */
    public static final String KEY = "contestedMemories";

    private ContestedMemoryMarker() {
    }

    /**
     * Removes {@code writtenKeys} from the marker, and the marker itself once it
     * lists nothing. A {@code longTerm} property of the same name is a memory the
     * user stored, not this marker, and is left alone.
     */
    public static void lift(IConversationProperties properties, Collection<String> writtenKeys) {
        if (properties == null || writtenKeys == null || writtenKeys.isEmpty()) {
            return;
        }
        synchronized (properties) {
            Property marker = properties.get(KEY);
            if (marker == null || marker.getScope() == Scope.longTerm || marker.getValueObject() == null) {
                return;
            }
            Map<String, Object> remaining = new LinkedHashMap<>(marker.getValueObject());
            if (!remaining.keySet().removeAll(writtenKeys)) {
                return;
            }
            if (remaining.isEmpty()) {
                properties.remove(KEY);
            } else {
                properties.put(KEY, new Property(KEY, remaining, Scope.conversation));
            }
        }
    }
}
