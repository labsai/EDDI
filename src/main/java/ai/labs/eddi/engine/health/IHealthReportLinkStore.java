/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthReportLink;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owner report links — MongoDB {@code healthreportlinks}, PostgreSQL
 * {@code health_report_links}. A link is found by the hash of its token
 * (unique), listed and revoked by agent. Every method throws
 * {@link HealthStoreException} when the store fails.
 */
public interface IHealthReportLinkStore {

    /** Stores a new link. */
    void create(HealthReportLink link);

    /** The link whose token hashes to {@code tokenHash}, or empty. */
    Optional<HealthReportLink> findByTokenHash(String tokenHash);

    /** An agent's links, newest first. */
    List<HealthReportLink> listByAgent(String agentId);

    /** Revokes (deletes) one of an agent's links; whether it existed. */
    boolean delete(String agentId, String id);

    /** Deletes the links that expired before {@code before}. */
    long deleteExpiredBefore(Instant before);

    /**
     * GDPR erasure: blanks {@code createdBy} wherever it is {@code userId}.
     *
     * @return the links changed
     */
    long clearCreator(String userId);
}
