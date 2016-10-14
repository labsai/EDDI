package io.sls.core.behavior.extensions;

import io.sls.core.behavior.BehaviorRule;
import io.sls.core.behavior.BehaviorSet;
import io.sls.memory.IConversationMemory;

import java.util.List;
import java.util.Map;

/**
 * User: jarisch
 * Date: 09.09.2010
 * Time: 19:42:37
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
