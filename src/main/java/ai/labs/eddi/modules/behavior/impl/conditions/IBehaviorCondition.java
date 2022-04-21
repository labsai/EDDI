package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.behavior.impl.BehaviorRule;
import ai.labs.eddi.modules.behavior.impl.BehaviorSet;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IBehaviorCondition extends Cloneable {
    String CONDITION_PREFIX = "ai.labs.behavior.conditions.";

    String getId();

    default Map<String, String> getConfigs() {
        return Collections.emptyMap();
    }

    default void setConfigs(Map<String, String> configs) {
        // not implemented
    }

    default List<IBehaviorCondition> getConditions() {
        return Collections.emptyList();
    }

    default void setConditions(List<IBehaviorCondition> conditions) {
        // not implemented
    }

    default void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        // not implemented
    }

    enum ExecutionState {
        SUCCESS,
        FAIL,
        NOT_EXECUTED,
        ERROR
    }

    ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws BehaviorRule.InfiniteLoopException, BehaviorRule.RuntimeException;

    IBehaviorCondition clone() throws CloneNotSupportedException;
}
