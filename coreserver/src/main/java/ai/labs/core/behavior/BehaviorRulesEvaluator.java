package ai.labs.core.behavior;

import ai.labs.core.behavior.extensions.IExtension;
import ai.labs.memory.IConversationMemory;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;

/**
 * @author ginccc
 */
@Slf4j
public class BehaviorRulesEvaluator {
    private BehaviorSet behaviorSet;

    public BehaviorRulesEvaluator() {
    }

    public void setBehaviorSet(BehaviorSet behaviorSet) {
        this.behaviorSet = behaviorSet;
    }

    public BehaviorSetResult evaluate(IConversationMemory memory) throws BehaviorRuleExecutionException {
        RuntimeUtilities.checkNotNull(behaviorSet, "behaviorSet");

        BehaviorSetResult resultSet = new BehaviorSetResult();

        IExtension.ExecutionState state;
        for (BehaviorGroup behaviorGroup : behaviorSet.getBehaviorGroups()) {
            if (!resultSet.getSuccessRules().isEmpty()) {
                //if one rule of one group applied, we do not check further
                break;
            }

            for (BehaviorRule behaviorRule : behaviorGroup.getBehaviorRules()) {
                if (behaviorRule.getExtensions().isEmpty()) {
                    state = IExtension.ExecutionState.SUCCESS;
                } else {
                    state = behaviorRule.execute(memory, new LinkedList<>());
                }

                if (state == IExtension.ExecutionState.SUCCESS) {
                    boolean alreadyExists = false;
                    for (BehaviorRule rule : resultSet.getSuccessRules()) {
                        if (rule.getName().equals(behaviorRule.getName())) {
                            alreadyExists = true;
                            break;
                        }
                    }

                    if (!alreadyExists) {
                        resultSet.getSuccessRules().add(behaviorRule);
                    }

                    //if one rule of this group applied, we do not check further
                    break;
                } else if (state == IExtension.ExecutionState.ERROR) {
                    String msg = String.format("An Error has occurred while evaluating Behavior Rule: %s",
                            behaviorRule.getName());
                    log.error(msg);
                    throw new BehaviorRuleExecutionException(msg);
                } else
                    resultSet.getFailRules().add(behaviorRule);
            }
        }

        return resultSet;
    }

    class BehaviorRuleExecutionException extends Exception {
        private BehaviorRuleExecutionException(String message) {
            super(message);
        }
    }
}
