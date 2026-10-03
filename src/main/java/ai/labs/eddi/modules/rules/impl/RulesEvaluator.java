/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import ai.labs.eddi.modules.rules.impl.conditions.TemplateDataScope;
import ai.labs.eddi.utils.RuntimeUtilities;

import java.util.LinkedList;

/**
 * @author ginccc
 */

class RulesEvaluator {
    private final RuleSet behaviorSet;
    private final boolean appendActions;
    private final boolean expressionsAsActions;

    /**
     * Evaluates the rule set inside a {@link TemplateDataScope}, so the conditions
     * that need the template data share one conversion of the memory.
     */
    RuleSetResult evaluate(IConversationMemory memory) throws RuleExecutionException, InterruptedException {
        RuntimeUtilities.checkNotNull(behaviorSet, "behaviorSet");
        try {
            return TemplateDataScope.call(memory, () -> evaluateRules(memory));
        } catch (RuleExecutionException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuleExecutionException(e.getLocalizedMessage(), e);
        }
    }

    private RuleSetResult evaluateRules(IConversationMemory memory) throws RuleExecutionException, InterruptedException {

        RuleSetResult resultSet = new RuleSetResult();

        IRuleCondition.ExecutionState state;
        for (RuleGroup behaviorGroup : behaviorSet.getRuleGroups()) {
            for (Rule behaviorRule : behaviorGroup.getRules()) {
                throwExceptionIfInterrupted();
                if (behaviorRule.getConditions().isEmpty()) {
                    state = IRuleCondition.ExecutionState.SUCCESS;
                } else {
                    try {
                        state = behaviorRule.execute(memory, new LinkedList<>());
                    } catch (Rule.InfiniteLoopException | Rule.RuntimeException e) {
                        throw new RuleExecutionException(e.getLocalizedMessage(), e);
                    }
                }

                if (state == IRuleCondition.ExecutionState.SUCCESS) {
                    resultSet.getSuccessRules().add(behaviorRule);

                    boolean continueLoopingOnSuccess;
                    switch (behaviorGroup.getExecutionStrategy()) {
                        case executeAll :
                            continueLoopingOnSuccess = true;
                            break;
                        default :
                        case executeUntilFirstSuccess :
                            continueLoopingOnSuccess = false;
                    }

                    if (!continueLoopingOnSuccess) {
                        break;
                    }
                } else if (state == IRuleCondition.ExecutionState.ERROR) {
                    String msg = String.format("An Error has occurred while evaluating Behavior Rule: %s", behaviorRule.getName());
                    throw new RuleExecutionException(msg);
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

    static class RuleExecutionException extends Exception {
        private RuleExecutionException(String message) {
            super(message);
        }

        RuleExecutionException(String message, Exception e) {
            super(message, e);
        }
    }

    public RulesEvaluator(RuleSet behaviorSet, boolean appendActions, boolean expressionsAsActions) {
        this.behaviorSet = behaviorSet;
        this.appendActions = appendActions;
        this.expressionsAsActions = expressionsAsActions;
    }

    RuleSet getRuleSet() {
        return behaviorSet;
    }

    public boolean isAppendActions() {
        return appendActions;
    }

    public boolean isExpressionsAsActions() {
        return expressionsAsActions;
    }
}
