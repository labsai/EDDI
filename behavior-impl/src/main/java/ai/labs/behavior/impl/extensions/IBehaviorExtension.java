package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.behavior.impl.BehaviorSet;
import ai.labs.memory.IConversationMemory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IBehaviorExtension extends Cloneable {
    String EXTENSION_PREFIX = "ai.labs.behavior.extension.";

    String getId();

    default Map<String, String> getValues() {
        return Collections.emptyMap();
    }

    default void setValues(Map<String, String> values) {
        // not implemented
    }

    default IBehaviorExtension[] getChildren() {
        return new IBehaviorExtension[0];
    }

    default void setChildren(IBehaviorExtension... extensions) {
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

    ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) throws BehaviorRule.InfiniteLoopException;

    ExecutionState getExecutionState();

    IBehaviorExtension clone() throws CloneNotSupportedException;
}
