package ai.labs.eddi.modules.rules.impl;


import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class RuleGroup {
    private String name;
    private ExecutionStrategy executionStrategy = ExecutionStrategy.executeUntilFirstSuccess;
    private List<Rule> behaviorRules = new LinkedList<>();

    public enum ExecutionStrategy {
        executeAll,
        executeUntilFirstSuccess
    }

    public RuleGroup() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ExecutionStrategy getExecutionStrategy() {
        return executionStrategy;
    }

    public void setExecutionStrategy(ExecutionStrategy executionStrategy) {
        this.executionStrategy = executionStrategy;
    }

    public List<Rule> getRules() {
        return behaviorRules;
    }

    public void setRules(List<Rule> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}
