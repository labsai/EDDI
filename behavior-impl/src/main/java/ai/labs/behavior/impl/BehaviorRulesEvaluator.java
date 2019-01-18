package ai.labs.behavior.impl;

import ai.labs.behavior.impl.extensions.IBehaviorExtension;
import ai.labs.memory.IConversationMemory;
import ai.labs.utilities.RuntimeUtilities;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;

/**
 * @author ginccc
 */
@Slf4j
@AllArgsConstructor
class BehaviorRulesEvaluator {
    private BehaviorSet behaviorSet;

    BehaviorSetResult evaluate(IConversationMemory memory) throws BehaviorRuleExecutionException, InterruptedException {
        RuntimeUtilities.checkNotNull(behaviorSet, "behaviorSet");

        BehaviorSetResult resultSet = new BehaviorSetResult();

        IBehaviorExtension.ExecutionState state;
        for (BehaviorGroup behaviorGroup : behaviorSet.getBehaviorGroups()) {
            for (BehaviorRule behaviorRule : behaviorGroup.getBehaviorRules()) {
                throwExceptionIfInterrupted();
                if (behaviorRule.getExtensions().isEmpty()) {
                    state = IBehaviorExtension.ExecutionState.SUCCESS;
                } else {
                    try {
                        state = behaviorRule.execute(memory, new LinkedList<>());
                    } catch (BehaviorRule.InfiniteLoopException e) {
                        throw new BehaviorRuleExecutionException(e.getLocalizedMessage(), e);
                    }
                }

                if (state == IBehaviorExtension.ExecutionState.SUCCESS) {
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
                } else if (state == IBehaviorExtension.ExecutionState.ERROR) {
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

    private void throwExceptionIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Execution was interrupted!");
        }
    }

    class BehaviorRuleExecutionException extends Exception {
        private BehaviorRuleExecutionException(String message) {
            super(message);
        }

        BehaviorRuleExecutionException(String message, Exception e) {
            super(message, e);
        }
    }
}
