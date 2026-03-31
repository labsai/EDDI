package ai.labs.eddi.secrets.model;

import java.time.Instant;
import java.util.List;

/**
 * Database entity for an encrypted secret stored via envelope encryption. The
 * actual secret value is encrypted with the tenant's DEK (Data Encryption Key).
 * <p>
 * Secrets are scoped at the <b>tenant level</b> — identified by
 * {@code (tenantId, keyName)}. The {@code allowedAgents} field is for
 * visibility/documentation only (not enforced at resolution time).
 *
 * @author ginccc
 * @since 6.0.0
 */
public class EncryptedSecret {
    private String id;
    private String tenantId;
    private String keyName;
    /** Base64-encoded AES-256-GCM ciphertext (includes auth tag) */
    private String encryptedValue;
    /** Base64-encoded 12-byte initialization vector */
    private String iv;
    /** Reference to the DEK used for encryption */
    private String dekId;
    /**
     * SHA-256 hex digest of the plaintext (for integrity checks without decryption)
     */
    private String checksum;
    /** Human-readable description of what this secret is for */
    private String description;
    /**
     * Agent IDs allowed to use this secret, or ["*"] for all agents. For
     * visibility/documentation only — not enforced at resolution time.
     */
    private List<String> allowedAgents;
    private Instant createdAt;
    private Instant lastAccessedAt;
    /** When the secret value was last rotated (updated) */
    private Instant lastRotatedAt;

    public EncryptedSecret() {
    }

    public EncryptedSecret(String id, String tenantId, String keyName, String encryptedValue, String iv, String dekId, String checksum,
            String description, List<String> allowedAgents, Instant createdAt, Instant lastAccessedAt, Instant lastRotatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.keyName = keyName;
        this.encryptedValue = encryptedValue;
        this.iv = iv;
        this.dekId = dekId;
        this.checksum = checksum;
        this.description = description;
        this.allowedAgents = allowedAgents;
        this.createdAt = createdAt;
        this.lastAccessedAt = lastAccessedAt;
        this.lastRotatedAt = lastRotatedAt;
    }

    /** Composite storage key: tenantId/keyName */
    public String storageKey() {
        return tenantId + "/" + keyName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getEncryptedValue() {
        return encryptedValue;
    }

    public void setEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }

    public String getIv() {
        return iv;
    }

    public void setIv(String iv) {
        this.iv = iv;
    }

    public String getDekId() {
        return dekId;
    }

    public void setDekId(String dekId) {
        this.dekId = dekId;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getAllowedAgents() {
        return allowedAgents;
    }

    public void setAllowedAgents(List<String> allowedAgents) {
        this.allowedAgents = allowedAgents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Instant getLastRotatedAt() {
        return lastRotatedAt;
    }

    public void setLastRotatedAt(Instant lastRotatedAt) {
        this.lastRotatedAt = lastRotatedAt;
    }
}
