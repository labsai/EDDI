package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.memory.IConversationMemory;

import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IExtension extends Cloneable {
    String getId();

    Map<String, String> getValues();

    void setValues(Map<String, String> values);

    IExtension[] getChildren();

    void setChildren(IExtension... extensions);

    void setContainingBehaviorRuleSet(BehaviorSet behaviorSet);

    enum ExecutionState {
        SUCCESS,
        FAIL,
        NOT_EXECUTED,
        ERROR
    }

    ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace);

    ExecutionState getExecutionState();

    IExtension clone() throws CloneNotSupportedException;
}
