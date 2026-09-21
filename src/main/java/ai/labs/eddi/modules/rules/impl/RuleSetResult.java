/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import java.util.LinkedList;
import java.util.List;

/**
 * Outcome of evaluating a {@link RuleSet}. {@code RulesEvaluator} places every
 * evaluated rule into exactly one of the two lists below — there is no third
 * "dropped success" category.
 *
 * @author ginccc
 */
public class RuleSetResult {
    private List<Rule> successRules = new LinkedList<>();
    private List<Rule> failRules = new LinkedList<>();

    public RuleSetResult() {
    }

    public List<Rule> getSuccessRules() {
        return successRules;
    }

    public void setSuccessRules(List<Rule> successRules) {
        this.successRules = successRules;
    }

    public List<Rule> getFailRules() {
        return failRules;
    }

    public void setFailRules(List<Rule> failRules) {
        this.failRules = failRules;
    }

    @Override
    public String toString() {
        return "RuleSetResult(" + "successRules=" + successRules + ", failRules=" + failRules + ")";
    }
}
