/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

/**
 * The team-budget store could not answer (F6). Gates fail closed on it,
 * metering carries the spend forward; neither treats it as "zero spent".
 */
public class TeamBudgetAccountingUnavailableException extends RuntimeException {

    public TeamBudgetAccountingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public TeamBudgetAccountingUnavailableException(String message) {
        super(message);
    }
}
