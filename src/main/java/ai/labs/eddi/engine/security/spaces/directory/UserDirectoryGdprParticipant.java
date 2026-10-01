/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Erases and exports a person's user-directory entry — their name, username,
 * email and team memberships as last seen on their token.
 * <p>
 * Erasure removes the entry but not what it was used for: grants and owned
 * resources are keyed by the principal and stay, because deleting somebody's
 * work, or silently revoking access others were given to it, is not what an
 * erasure request asks for. The entry reappears, with whatever the token then
 * says, if the person signs in again.
 */
@ApplicationScoped
public class UserDirectoryGdprParticipant implements IGdprParticipant {

    private final UserDirectory directory;

    @Inject
    public UserDirectoryGdprParticipant(UserDirectory directory) {
        this.directory = directory;
    }

    @Override
    public String name() {
        return "userDirectory";
    }

    @Override
    public long erase(String userId) {
        return directory.erase(userId) ? 1 : 0;
    }

    @Override
    public Object export(String userId) {
        return directory.export(userId).orElse(null);
    }
}
