/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Removes Agent Health's administrative rows of a deleted agent — called by the
 * agent store after a delete went through.
 * <ul>
 * <li>Its <b>owner report links</b> are revoked on every delete, soft or
 * permanent: a link to an agent that is gone already answers 404, and one must
 * not come back with a restored agent nobody re-shared.</li>
 * <li>Its <b>health profile</b> and <b>alert states</b> go only with a
 * permanent delete — a soft-deleted agent can be restored, with its
 * thresholds.</li>
 * </ul>
 * Never throws: a failure is logged, and the delete stands. (The daily rows,
 * turn rows and outcomes are kept for the retention, like a deleted agent's
 * conversations.)
 */
@ApplicationScoped
public class HealthAgentCleanup {

    private static final Logger LOGGER = Logger.getLogger(HealthAgentCleanup.class);

    private final IHealthReportLinkStore links;
    private final IHealthProfileStore profiles;
    private final IHealthAlertStateStore alertStates;
    private final HealthProfiles profileCache;

    @Inject
    public HealthAgentCleanup(IHealthReportLinkStore links, IHealthProfileStore profiles, IHealthAlertStateStore alertStates,
            HealthProfiles profileCache) {
        this.links = links;
        this.profiles = profiles;
        this.alertStates = alertStates;
        this.profileCache = profileCache;
    }

    /** The agent was deleted ({@code permanent}: with all its versions). */
    public void agentDeleted(String agentId, boolean permanent) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        try {
            long revoked = links.deleteByAgent(agentId);
            if (revoked > 0) {
                LOGGER.infof("[HEALTH] Revoked %d owner report link(s) of deleted agent %s", revoked, sanitize(agentId));
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not revoke the owner report links of deleted agent %s: %s", sanitize(agentId), sanitize(e.getMessage()));
        }
        if (!permanent) {
            return;
        }
        try {
            profiles.delete(agentId);
            if (profileCache != null) {
                profileCache.invalidate(agentId);
            }
            alertStates.deleteByAgent(agentId);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not delete the health profile or alert states of deleted agent %s: %s", sanitize(agentId),
                    sanitize(e.getMessage()));
        }
    }
}
