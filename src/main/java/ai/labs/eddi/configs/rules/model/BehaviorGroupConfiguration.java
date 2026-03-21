package ai.labs.eddi.configs.rules.model;


import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class BehaviorGroupConfiguration {
    private String name;
    private String executionStrategy;
    private List<BehaviorRuleConfiguration> behaviorRules = new LinkedList<>();

    public BehaviorGroupConfiguration() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExecutionStrategy() {
        return executionStrategy;
    }

    public void setExecutionStrategy(String executionStrategy) {
        this.executionStrategy = executionStrategy;
    }

    public List<BehaviorRuleConfiguration> getBehaviorRules() {
        return behaviorRules;
    }

    public void setBehaviorRules(List<BehaviorRuleConfiguration> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}

