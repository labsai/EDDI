package ai.labs.eddi.secrets.model;

import java.time.Instant;

/**
 * Database entity for an encrypted secret stored via envelope encryption.
 * The actual secret value is encrypted with the tenant's DEK (Data Encryption Key).
 */
public class EncryptedSecret {
    private String id;
    private String tenantId;
    private String agentId;
    private String keyName;
    /** Base64-encoded AES-256-GCM ciphertext (includes auth tag) */
    private String encryptedValue;
    /** Base64-encoded 12-byte initialization vector */
    private String iv;
    /** Reference to the DEK used for encryption */
    private String dekId;
    /** SHA-256 hex digest of the plaintext (for integrity checks without decryption) */
    private String checksum;
    private Instant createdAt;
    private Instant lastAccessedAt;

    public EncryptedSecret() {
    }

    public EncryptedSecret(String id, String tenantId, String agentId, String keyName,
                           String encryptedValue, String iv, String dekId, String checksum,
                           Instant createdAt, Instant lastAccessedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.keyName = keyName;
        this.encryptedValue = encryptedValue;
        this.iv = iv;
        this.dekId = dekId;
        this.checksum = checksum;
        this.createdAt = createdAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }

    public String getEncryptedValue() { return encryptedValue; }
    public void setEncryptedValue(String encryptedValue) { this.encryptedValue = encryptedValue; }

    public String getIv() { return iv; }
    public void setIv(String iv) { this.iv = iv; }

    public String getDekId() { return dekId; }
    public void setDekId(String dekId) { this.dekId = dekId; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
}
