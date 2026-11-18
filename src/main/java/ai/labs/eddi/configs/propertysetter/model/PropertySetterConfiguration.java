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

    /**
     * Whether the property step also honours a {@code properties*} context entry of
     * type {@code expressions} that a <em>client</em> sent with its input (for
     * example {@code property(role(admin), longTerm)}). Such an entry is applied
     * after {@code setOnActions} and overrides what they computed, so it is off by
     * default: only an entry the engine wrote itself is honoured. Turn it on only
     * when every caller of the agent is trusted to set conversation properties.
     */
    private boolean acceptClientProperties = false;

    public PropertySetterConfiguration() {
    }

    public boolean isAcceptClientProperties() {
        return acceptClientProperties;
    }

    public void setAcceptClientProperties(boolean acceptClientProperties) {
        this.acceptClientProperties = acceptClientProperties;
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
