package ai.labs.eddi.modules.behavior.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition;
import ai.labs.eddi.utils.RuntimeUtilities;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;

/**
 * @author ginccc
 */

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
class BehaviorRulesEvaluator {
    private BehaviorSet behaviorSet;

    boolean appendActions;
    boolean expressionsAsActions;

    BehaviorSetResult evaluate(IConversationMemory memory) throws BehaviorRuleExecutionException, InterruptedException {
        RuntimeUtilities.checkNotNull(behaviorSet, "behaviorSet");

        BehaviorSetResult resultSet = new BehaviorSetResult();

        IBehaviorCondition.ExecutionState state;
        for (BehaviorGroup behaviorGroup : behaviorSet.getBehaviorGroups()) {
            for (BehaviorRule behaviorRule : behaviorGroup.getBehaviorRules()) {
                throwExceptionIfInterrupted();
                if (behaviorRule.getConditions().isEmpty()) {
                    state = IBehaviorCondition.ExecutionState.SUCCESS;
                } else {
                    try {
                        state = behaviorRule.execute(memory, new LinkedList<>());
                    } catch (BehaviorRule.InfiniteLoopException | BehaviorRule.RuntimeException e) {
                        throw new BehaviorRuleExecutionException(e.getLocalizedMessage(), e);
                    }
                }

                if (state == IBehaviorCondition.ExecutionState.SUCCESS) {
                    resultSet.getSuccessRules().add(behaviorRule);

                    boolean continueLoopingOnSuccess;
                    switch (behaviorGroup.getExecutionStrategy()) {
                        case executeAll:
                            continueLoopingOnSuccess = true;
                            break;
                        default:
                        case executeUntilFirstSuccess:
                            continueLoopingOnSuccess = false;
                    }

                    if (!continueLoopingOnSuccess) {
                        break;
                    }
                } else if (state == IBehaviorCondition.ExecutionState.ERROR) {
                    String msg = String.format("An Error has occurred while evaluating Behavior Rule: %s",
                            behaviorRule.getName());
                    throw new BehaviorRuleExecutionException(msg);
                } else {
                    resultSet.getFailRules().add(behaviorRule);
                }
            }
        }

        return resultSet;
    }

    private void throwExceptionIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Execution was interrupted!");
        }
    }

    static class BehaviorRuleExecutionException extends Exception {
        private BehaviorRuleExecutionException(String message) {
            super(message);
        }

        BehaviorRuleExecutionException(String message, Exception e) {
            super(message, e);
        }
    }
}
