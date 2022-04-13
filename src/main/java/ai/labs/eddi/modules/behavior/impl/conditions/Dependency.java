package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.behavior.impl.BehaviorGroup;
import ai.labs.eddi.modules.behavior.impl.BehaviorRule;
import ai.labs.eddi.modules.behavior.impl.BehaviorSet;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * @author ginccc
 */

@NoArgsConstructor
public class Dependency implements IBehaviorCondition {
    public static final String ID = "dependency";

    private String reference;

    private final String referenceQualifier = "reference";
    private BehaviorSet behaviorSet;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        HashMap<String, String> configs = new HashMap<>();
        configs.put(referenceQualifier, reference);
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(referenceQualifier)) {
                reference = configs.get(referenceQualifier);
            }
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws BehaviorRule.InfiniteLoopException, BehaviorRule.RuntimeException {

        //before we execute the behavior rules we make deep copies, so that we don't change the rules in conversation memory!
        List<BehaviorRule> filteredBehaviorRules = new LinkedList<>();
        try {
            List<BehaviorGroup> behaviorGroups = behaviorSet.getBehaviorGroups();
            List<BehaviorRule> behaviorRules = new LinkedList<>();
            for (BehaviorGroup behaviorGroup : behaviorGroups) {
                behaviorRules.addAll(behaviorGroup.getBehaviorRules());
            }
            filteredBehaviorRules.addAll(cloneBehaviorRules(behaviorRules, reference));
        } catch (CloneNotSupportedException e) {
            throw new BehaviorRule.RuntimeException(e.getLocalizedMessage(), e);
        }

        ExecutionState state = ExecutionState.NOT_EXECUTED;
        for (BehaviorRule behaviorRule : filteredBehaviorRules) {
            state = behaviorRule.execute(memory, trace);
            if (state == ExecutionState.ERROR || state == ExecutionState.SUCCESS) {
                break;
            } else {
                state = ExecutionState.FAIL;
            }
        }

        if (state == ExecutionState.NOT_EXECUTED) {
            state = ExecutionState.FAIL;
        }

        return state;
    }

    @Override
    public IBehaviorCondition clone() {
        Dependency clone = new Dependency();
        clone.setConfigs(getConfigs());
        clone.setContainingBehaviorRuleSet(behaviorSet);
        return clone;
    }

    private List<BehaviorRule> cloneBehaviorRules(List<BehaviorRule> behaviorRules, String filter) throws CloneNotSupportedException {
        List<BehaviorRule> clone = new LinkedList<>();
        for (BehaviorRule behaviorRule : behaviorRules) {
            if (behaviorRule.getName().equals(filter)) {
                clone.add(behaviorRule.clone());
            }
        }

        return clone;
    }

    @Override
    public void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        this.behaviorSet = behaviorSet;
    }
}
