package io.sls.core.behavior;

import io.sls.core.behavior.extensions.IExtension;
import io.sls.memory.IConversationMemory;
import io.sls.utilities.RuntimeUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

/**
 * User: jarisch
 * Date: 21.01.2010
 * Time: 15:57:33
 */
public class BehaviorRulesEvaluator {
    private BehaviorSet behaviorSet;
    private Logger logger = LoggerFactory.getLogger(this.getClass());

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
                    state = behaviorRule.execute(memory, new LinkedList<BehaviorRule>());
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
                    logger.error(msg);
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
