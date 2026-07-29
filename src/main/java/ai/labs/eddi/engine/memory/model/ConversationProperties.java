/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The live conversation properties.
 * <p>
 * <strong>Single source of truth:</strong> the map itself. {@link #toMap()} is
 * DERIVED on demand rather than maintained as a parallel structure — a parallel
 * map only stayed in sync for {@code put}/{@code putAll}, so any other
 * {@link Map} mutation ({@code clear()}, {@code remove()},
 * {@code entrySet().remove(...)}, {@code values().remove(...)}) silently left
 * stale values visible to templates. A checkpoint rollback
 * ({@code MemorySnapshotService.restoreProperties}) does exactly
 * {@code clear(); forEach(put)}, so post-checkpoint properties survived the
 * rollback in {@code {properties.x}}.
 */
public class ConversationProperties extends LinkedHashMap<String, Property> implements IConversationMemory.IConversationProperties {

    private static final String KEY_PROPERTIES = "properties";

    @JsonIgnore
    private final IConversationMemory conversationMemory;

    public ConversationProperties(IConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    @Override
    public Property put(String key, Property property) {
        Property previous = super.put(key, property);
        mirrorToCurrentStep(key, property);
        return previous;
    }

    @Override
    public void putAll(Map<? extends String, ? extends Property> map) {
        map.keySet().forEach(key -> put(key, map.get(key)));
    }

    /**
     * The template view of the properties ({@code {properties.x}}), derived from
     * the current map content on every call. Properties whose value is entirely
     * null are omitted.
     */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> propertiesMap = new LinkedHashMap<>();
        forEach((key, property) -> {
            if (property == null) {
                return;
            }
            Object value = extractValue(property);
            if (value != null) {
                propertiesMap.put(property.getName() != null ? property.getName() : key, value);
            }
        });
        return propertiesMap;
    }

    /**
     * Mirrors a property into the CURRENT step's data and conversation output — the
     * persisted projection of the property.
     * <p>
     * {@code scope: step} values get the conversation-output half ONLY. They must
     * stay resolvable through {@code {memory.current.properties.X}} during the turn
     * that set them — the documented contract is that step scope lives FOR the turn
     * and is cleared at the END of it — but the mirrored copies used to stay in the
     * conversation document forever and remained readable through
     * {@code {memory.last.properties.X}} on later turns. So the persisted step
     * {@code Data<>} (which nothing reads for templating) is skipped, and
     * {@code Conversation#removeOldInvalidProperties} strips the
     * conversation-output entry again when it drops the property at the end of the
     * turn — before the step is persisted.
     */
    private void mirrorToCurrentStep(String key, Property property) {
        if (conversationMemory == null || property == null) {
            return;
        }

        IConversationMemory.IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
        if (currentStep == null) {
            return;
        }
        if (property.getScope() != Scope.step) {
            String propertiesKey = KEY_PROPERTIES + ":" + key;
            currentStep.storeData(new Data<>(propertiesKey, Collections.singletonList(property)));
        }

        Object value = extractValue(property);
        if (value == null) {
            return;
        }
        Map<String, Object> propertyMap = new LinkedHashMap<>();
        // Same fallback as toMap(): Property is deserialized from stored configs and
        // may legally carry a null name, and the map key is the name the caller used.
        // Without this the two views of one property disagree — {properties.x} resolves
        // through toMap() while the conversation output gets a null key for the same
        // entry, which is both unusable to a client and inconsistent by construction.
        propertyMap.put(property.getName() != null ? property.getName() : key, value);
        currentStep.addConversationOutputMap(KEY_PROPERTIES, propertyMap);
    }

    /**
     * The single non-null value carried by a property, or {@code null} when it
     * carries none.
     */
    private static Object extractValue(Property property) {
        if (property.getValueString() != null) {
            return property.getValueString();
        }
        if (property.getValueObject() != null) {
            return property.getValueObject();
        }
        if (property.getValueList() != null) {
            return property.getValueList();
        }
        if (property.getValueInt() != null) {
            return property.getValueInt();
        }
        if (property.getValueFloat() != null) {
            return property.getValueFloat();
        }
        return property.getValueBoolean();
    }
}
