/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.propertysetter.model;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.properties.model.SetOnActions;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class PropertySetterConfiguration {
    private List<SetOnActions> setOnActions = new LinkedList<>();

    public PropertySetterConfiguration() {
    }

    public List<SetOnActions> getSetOnActions() {
        return setOnActions;
    }

    public void setSetOnActions(List<SetOnActions> setOnActions) {
        this.setOnActions = setOnActions;
    }

    /**
     * Whether any instruction of this configuration is {@code scope: "secret"} —
     * that is, whether this agent expects to be told secrets in a turn's input. Not
     * a bean property: Jackson serialises getters, and this is derived.
     */
    public boolean hasSecretInstructions() {
        if (setOnActions == null) {
            return false;
        }
        return setOnActions.stream()
                .filter(Objects::nonNull)
                .map(SetOnActions::getSetProperties)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .anyMatch(instruction -> instruction != null && instruction.getScope() == Property.Scope.secret);
    }
}
