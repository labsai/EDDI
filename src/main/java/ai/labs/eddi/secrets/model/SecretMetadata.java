/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

/**
 * Non-sensitive metadata about a stored secret. Plaintext values are NEVER
 * exposed through this record.
 * <p>
 * Secrets are scoped at the <b>tenant level</b>, not per-agent. Access control
 * is via configuration authorship — the admin who writes the agent config
 * decides which vault references to include. The {@code allowedAgents} field is
 * for <b>visibility and documentation only</b>, helping admins track which
 * agents use which secrets.
 *
 * @param tenantId
 *            the owning tenant
 * @param keyName
 *            the secret key name
 * @param createdAt
 *            when the secret was first stored
 * @param lastAccessedAt
 *            when the secret was last resolved (null if never accessed)
 * @param lastRotatedAt
 *            when the secret value was last updated (null if never rotated)
 * @param checksum
 *            SHA-256 hex digest of the plaintext value (for integrity
 *            verification without decryption)
 * @param description
 *            human-readable description of what this secret is for (e.g.
 *            "OpenAI API key for production")
 * @param allowedAgents
 *            list of agent IDs allowed to use this secret, or {@code ["*"]} for
 *            all agents. This is for <b>visibility only</b> — enforcement is
 *            via configuration authorship, not runtime resolution.
 *
 * @author ginccc
 * @since 6.0.0
 */
public record SecretMetadata(String tenantId, String keyName, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastAccessedAt, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastRotatedAt,
        String checksum, String description, List<String> allowedAgents) {
}
