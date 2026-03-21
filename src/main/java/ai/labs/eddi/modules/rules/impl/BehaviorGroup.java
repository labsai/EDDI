package ai.labs.eddi.modules.rules.impl;


import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorGroup {
    private String name;
    private ExecutionStrategy executionStrategy = ExecutionStrategy.executeUntilFirstSuccess;
    private List<BehaviorRule> behaviorRules = new LinkedList<>();

    public enum ExecutionStrategy {
        executeAll,
        executeUntilFirstSuccess
    }

    public BehaviorGroup() {
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

    public List<BehaviorRule> getBehaviorRules() {
        return behaviorRules;
    }

    public void setBehaviorRules(List<BehaviorRule> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}
