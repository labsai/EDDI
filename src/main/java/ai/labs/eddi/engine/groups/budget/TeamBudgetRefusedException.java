/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;

/**
 * A discussion, follow-up or continuation was refused because the team (or the
 * tenant) has no budget left this month, or because the budget could not be
 * read (F6). A {@link GroupDiscussionException}, so every existing REST catch
 * block answers it as a 409 with the message as text.
 */
public class TeamBudgetRefusedException extends GroupDiscussionException {

    private final transient TeamBudgetSnapshot snapshot;
    private final String reason;

    /**
     * @param reason
     *            {@code exhausted}, {@code tenant} or {@code unavailable}
     * @param snapshot
     *            the budget at refusal time, null when it could not be read
     */
    public TeamBudgetRefusedException(String message, String reason, TeamBudgetSnapshot snapshot) {
        super(message);
        this.reason = reason;
        this.snapshot = snapshot;
    }

    public String reason() {
        return reason;
    }

    public TeamBudgetSnapshot snapshot() {
        return snapshot;
    }
}
