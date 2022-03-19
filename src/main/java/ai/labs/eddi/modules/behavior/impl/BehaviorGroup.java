package ai.labs.eddi.modules.behavior.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
@Getter
@Setter
public class BehaviorGroup {
    private String name;
    private ExecutionStrategy executionStrategy = ExecutionStrategy.executeUntilFirstSuccess;
    private List<BehaviorRule> behaviorRules = new LinkedList<>();

    public enum ExecutionStrategy {
        executeAll,
        executeUntilFirstSuccess
    }
}
