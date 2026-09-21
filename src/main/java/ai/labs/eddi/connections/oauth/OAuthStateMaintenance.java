/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.connections.ConnectionsConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Deletes in-flight authorization states once they can no longer be redeemed.
 * <p>
 * {@link IOAuthStateStore#deleteExpired()} existed with no caller. On MongoDB
 * that was survivable — a TTL index sweeps the collection — but the PostgreSQL
 * table has no equivalent, so every started-and-abandoned account link stayed
 * forever. Each row holds a live PKCE code verifier, and users abandon consent
 * screens constantly: an unbounded, permanently growing table of credential
 * material that nothing ever reads again.
 * <p>
 * The rows are already unusable by then — {@code claim} checks
 * {@code expiresAt} itself and does not rely on this running. This is
 * retention, not enforcement, which is why failing is a log line rather than
 * anything louder.
 */
@ApplicationScoped
public class OAuthStateMaintenance {

    private static final Logger LOGGER = Logger.getLogger(OAuthStateMaintenance.class);

    private final IOAuthStateStore stateStore;
    private final ConnectionsConfig connectionsConfig;

    @Inject
    public OAuthStateMaintenance(IOAuthStateStore stateStore, ConnectionsConfig connectionsConfig) {
        this.stateStore = stateStore;
        this.connectionsConfig = connectionsConfig;
    }

    /**
     * Hourly is ample: a state lives ten minutes, so the table holds at most an
     * hour of abandoned flows. {@code delayed} keeps it off the startup path, where
     * a slow database would delay readiness for a cleanup that has waited this long
     * already.
     */
    @Scheduled(every = "${eddi.connections.state-sweep-interval:1h}", delayed = "5m", identity = "connections-oauth-state-retention")
    void sweepExpiredStates() {
        if (!connectionsConfig.isEnabled()) {
            return;
        }
        try {
            int deleted = stateStore.deleteExpired();
            if (deleted > 0) {
                LOGGER.debugf("Deleted %d expired OAuth state(s)", deleted);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Failed to sweep expired OAuth states; they remain unusable either way and the next sweep will retry");
        }
    }
}
