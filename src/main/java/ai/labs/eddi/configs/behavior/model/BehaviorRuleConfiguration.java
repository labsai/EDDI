package ai.labs.eddi.configs.behavior.model;


import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class BehaviorRuleConfiguration {
    private String name = "";
    private List<String> actions = new LinkedList<>();
    private List<BehaviorRuleConditionConfiguration> conditions = new LinkedList<>();

    public BehaviorRuleConfiguration() {
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

    public List<BehaviorRuleConditionConfiguration> getConditions() {
        return conditions;
    }

    public void setConditions(List<BehaviorRuleConditionConfiguration> conditions) {
        this.conditions = conditions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BehaviorRuleConfiguration that = (BehaviorRuleConfiguration) o;
        return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(actions, that.actions) && java.util.Objects.equals(conditions, that.conditions);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, actions, conditions);
    }
}
