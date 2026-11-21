/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.refs;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.integrations.teams.TeamsUserIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Erases and exports the Teams conversation references that name a user (their
 * personal chats with a bot). Only {@code teams:…} user ids have any.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsReferenceGdprParticipant implements IGdprParticipant {

    private final ITeamsConversationReferenceStore store;

    @Inject
    public TeamsReferenceGdprParticipant(ITeamsConversationReferenceStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "teamsConversationReferences";
    }

    @Override
    public long erase(String userId) {
        return TeamsUserIdentity.isTeamsPrincipal(userId) ? store.deleteByUser(userId) : 0;
    }

    @Override
    public Object export(String userId) {
        if (!TeamsUserIdentity.isTeamsPrincipal(userId)) {
            return null;
        }
        List<ITeamsConversationReferenceStore.TeamsConversationReference> references = store.findByUser(userId);
        return references.isEmpty() ? null : references;
    }
}
