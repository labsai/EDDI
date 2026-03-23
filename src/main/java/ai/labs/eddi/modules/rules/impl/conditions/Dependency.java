package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.RuleGroup;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.rules.impl.RuleSet;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * @author ginccc
 */

public class Dependency implements IRuleCondition {
    public static final String ID = "dependency";

    private String reference;

    private final String referenceQualifier = "reference";
    private RuleSet behaviorSet;

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
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace)
            throws Rule.InfiniteLoopException, Rule.RuntimeException {

        //before we execute the behavior rules we make deep copies, so that we don't change the rules in conversation memory!
        List<Rule> filteredRules = new LinkedList<>();
        try {
            List<RuleGroup> behaviorGroups = behaviorSet.getRuleGroups();
            List<Rule> behaviorRules = new LinkedList<>();
            for (RuleGroup behaviorGroup : behaviorGroups) {
                behaviorRules.addAll(behaviorGroup.getRules());
            }
            filteredRules.addAll(cloneRules(behaviorRules, reference));
        } catch (CloneNotSupportedException e) {
            throw new Rule.RuntimeException(e.getLocalizedMessage(), e);
        }

        ExecutionState state = ExecutionState.NOT_EXECUTED;
        for (Rule behaviorRule : filteredRules) {
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
    public IRuleCondition clone() {
        Dependency clone = new Dependency();
        clone.setConfigs(getConfigs());
        clone.setContainingRuleSet(behaviorSet);
        return clone;
    }

    private List<Rule> cloneRules(List<Rule> behaviorRules, String filter) throws CloneNotSupportedException {
        List<Rule> clone = new LinkedList<>();
        for (Rule behaviorRule : behaviorRules) {
            if (behaviorRule.getName().equals(filter)) {
                clone.add(behaviorRule.clone());
            }
        }

        return clone;
    }

    @Override
    public void setContainingRuleSet(RuleSet behaviorSet) {
        this.behaviorSet = behaviorSet;
    }

    public Dependency() {
    }
}
