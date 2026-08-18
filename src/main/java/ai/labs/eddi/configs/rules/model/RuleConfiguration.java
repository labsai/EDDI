/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.model;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */

public class RuleConfiguration {
    private String name = "";
    private List<String> actions = new LinkedList<>();
    private List<RuleConditionConfiguration> conditions = new LinkedList<>();

    public RuleConfiguration() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public List<RuleConditionConfiguration> getConditions() {
        return conditions;
    }

    public void setConditions(List<RuleConditionConfiguration> conditions) {
        this.conditions = conditions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        RuleConfiguration that = (RuleConfiguration) o;
        return Objects.equals(name, that.name) && Objects.equals(actions, that.actions)
                && Objects.equals(conditions, that.conditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, actions, conditions);
    }
}
