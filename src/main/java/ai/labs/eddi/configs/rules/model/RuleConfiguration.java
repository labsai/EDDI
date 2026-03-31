package ai.labs.eddi.configs.rules.model;

import java.util.LinkedList;
import java.util.List;

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
        return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(actions, that.actions)
                && java.util.Objects.equals(conditions, that.conditions);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, actions, conditions);
    }
}
