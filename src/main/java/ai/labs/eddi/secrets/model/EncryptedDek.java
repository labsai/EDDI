/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import java.time.Instant;

/**
 * Database entity for the Data Encryption Key (DEK) used in envelope
 * encryption. The DEK itself is encrypted with the Master Key (KEK) from the
 * environment variable. Each tenant gets its own DEK for cryptographic
 * isolation.
 */
public class EncryptedDek {
    private String id;
    private String tenantId;
    /** Base64-encoded AES-256-GCM ciphertext of the DEK */
    private String encryptedDek;
    /** Base64-encoded 12-byte initialization vector used to encrypt the DEK */
    private String iv;
    private Instant createdAt;

    public EncryptedDek() {
    }

    public EncryptedDek(String id, String tenantId, String encryptedDek, String iv, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.encryptedDek = encryptedDek;
        this.iv = iv;
        this.createdAt = createdAt;
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

    public String getEncryptedDek() {
        return encryptedDek;
    }
    public void setEncryptedDek(String encryptedDek) {
        this.encryptedDek = encryptedDek;
    }

    public String getIv() {
        return iv;
    }
    public void setIv(String iv) {
        this.iv = iv;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
