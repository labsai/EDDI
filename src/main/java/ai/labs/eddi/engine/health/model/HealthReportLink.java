/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;
import java.util.List;

/**
 * An owner report link (H4b): a read-only view of one agent's health for
 * someone without Manager access. Only the SHA-256 of the link's token is
 * stored — the token itself is shown once, when the link is created, and cannot
 * be read back.
 *
 * @param id
 *            the link's own id, for listing and revoking (never the token)
 * @param tokenHash
 *            hex SHA-256 of the token
 * @param sections
 *            the sections the report shows
 * @param period
 *            {@code 7d}, {@code 30d} or {@code 90d}, ending the day it is read
 * @param createdBy
 *            who created it; blanked by GDPR erasure
 */
public record HealthReportLink(String id, String agentId, String tokenHash, List<String> sections, String period, Instant createdAt,
        String createdBy, Instant expiresAt) {

    public HealthReportLink {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
