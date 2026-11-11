/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * GDPR erasure of what Agent Health's administration keeps about a person: the
 * name of whoever last saved a health profile ({@code updatedBy}) and of
 * whoever created an owner report link ({@code createdBy}). Both are blanked;
 * the profile and the link stay, they belong to the agent. Nothing else of a
 * person is in either — a profile holds thresholds, a link a token hash.
 * <p>
 * Export answers {@code null}: the only personal datum is the caller's own
 * account name next to an agent's configuration, as for alert rules' authors.
 */
@ApplicationScoped
public class HealthAdminGdprParticipant implements IGdprParticipant {

    private final IHealthProfileStore profiles;
    private final IHealthReportLinkStore links;
    private final HealthProfiles profileCache;

    @Inject
    public HealthAdminGdprParticipant(IHealthProfileStore profiles, IHealthReportLinkStore links, HealthProfiles profileCache) {
        this.profiles = profiles;
        this.links = links;
        this.profileCache = profileCache;
    }

    @Override
    public String name() {
        return "agentHealthAdministration";
    }

    @Override
    public long erase(String userId) {
        long changed = profiles.clearAuthor(userId) + links.clearCreator(userId);
        if (changed > 0 && profileCache != null) {
            profileCache.invalidateAll();
        }
        return changed;
    }

    @Override
    public Object export(String userId) {
        return null;
    }
}
