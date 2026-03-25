package ai.labs.eddi.secrets.model;

import java.time.Instant;

/**
 * Non-sensitive metadata about a stored secret. Plaintext values are NEVER
 * exposed through this record.
 *
 * @param tenantId
 *            the owning tenant
 * @param agentId
 *            the owning agent
 * @param keyName
 *            the secret key name
 * @param createdAt
 *            when the secret was first stored
 * @param lastAccessedAt
 *            when the secret was last resolved (null if never accessed)
 * @param checksum
 *            SHA-256 hex digest of the plaintext value (for integrity
 *            verification)
 */
public record SecretMetadata(String tenantId, String agentId, String keyName, Instant createdAt, Instant lastAccessedAt, String checksum) {
}
