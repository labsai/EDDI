/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.AgentStartFailure;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;

/**
 * The behavior rules of an agent could not be evaluated (a dependency cycle, a
 * condition that failed). That is the rule set's own doing, so a failure on the
 * start turn is reported as {@link AgentStartFailure}.
 */
public class RuleEvaluationFailedException extends LifecycleException implements AgentStartFailure {
    public RuleEvaluationFailedException(String message, Exception cause) {
        super(message, cause);
    }
}
