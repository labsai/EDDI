/*
 * Copyright EDDI contributors
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
 * Secrets are stored at the <b>tenant level</b>. Which agents may use one is
 * governed by {@code allowedAgents}, checked when an agent is deployed (see
 * {@link ai.labs.eddi.secrets.VaultGrantGate}) rather than when a secret is
 * resolved, so a misconfiguration surfaces before the agent serves traffic
 * rather than in the middle of a live conversation.
 * <p>
 * What a violation costs depends on {@code eddi.vault.grant-enforcement}: under
 * {@code enforce} (the default) the deployment is blocked, under {@code warn}
 * it is logged and allowed, and under {@code off} the check does not run at
 * all.
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
 *            all agents. {@code null} or empty means unrestricted, not "deny
 *            all". Enforced at deployment time, not at resolution time.
 *
 * @author ginccc
 * @since 6.0.0
 */
public record SecretMetadata(String tenantId, String keyName, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastAccessedAt, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastRotatedAt,
        String checksum, String description, List<String> allowedAgents) {
}
