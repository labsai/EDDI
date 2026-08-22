/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import java.time.Instant;

/**
 * Database entity for the Data Encryption Key (DEK) used in envelope
 * encryption. The DEK itself is encrypted with the Master Key (KEK) from the
 * environment variable. Each tenant gets its own DEK for cryptographic
 * isolation.
 * <p>
 * A tenant holds one row per <b>generation</b>. Rotation adds a generation
 * rather than replacing a key, so a row sealed under any generation keeps
 * decrypting until it has been swept onto the newest one. Ciphertext names its
 * generation through {@link #dekId(String, int)}; {@link #generationOf} reads
 * that name back, treating everything written before generations existed as
 * generation 1. A generation below 1 never reaches a field of this class — see
 * {@link #setGeneration(int)} — so the name a row writes and the generation
 * that name reads back as are always the same one.
 */
public class EncryptedDek {

    /** The generation every pre-generation row is understood to be sealed under. */
    public static final int FIRST_GENERATION = 1;

    /**
     * Separates the tenant from the generation in a dekId. Tenant ids are
     * restricted to {@code [a-zA-Z0-9._-]} so it cannot collide, and the parse
     * takes the last occurrence anyway, so a laxer tenant id would still round
     * trip. Readable on purpose: {@code default#g3} tells an operator looking at a
     * database row exactly what it means.
     */
    private static final String GENERATION_MARKER = "#g";

    private String id;
    private String tenantId;
    /** Which generation of the tenant's DEK this row holds. */
    private int generation = FIRST_GENERATION;
    /** Base64-encoded AES-256-GCM ciphertext of the DEK */
    private String encryptedDek;
    /** Base64-encoded 12-byte initialization vector used to encrypt the DEK */
    private String iv;
    private Instant createdAt;

    public EncryptedDek() {
    }

    /** Generation 1, for callers that predate generations. */
    public EncryptedDek(String id, String tenantId, String encryptedDek, String iv, Instant createdAt) {
        this(id, tenantId, FIRST_GENERATION, encryptedDek, iv, createdAt);
    }

    public EncryptedDek(String id, String tenantId, int generation, String encryptedDek, String iv, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.generation = normalize(generation);
        this.encryptedDek = encryptedDek;
        this.iv = iv;
        this.createdAt = createdAt;
    }

    private static int normalize(int generation) {
        return Math.max(generation, FIRST_GENERATION);
    }

    /**
     * The name ciphertext carries to say which key sealed it. Every dekId written
     * anywhere in the system comes from here.
     */
    public static String dekId(String tenantId, int generation) {
        return tenantId + GENERATION_MARKER + generation;
    }

    /**
     * The generation a stored dekId names.
     * <p>
     * Anything that is not a generation name — null, blank, or the bare tenant id
     * that rows carried before generations existed — reads as generation 1. That
     * rule is what lets every already-stored row keep working with no migration of
     * ciphertext.
     */
    public static int generationOf(String tenantId, String dekId) {
        if (dekId == null || dekId.isBlank() || dekId.equals(tenantId)) {
            return FIRST_GENERATION;
        }
        int marker = dekId.lastIndexOf(GENERATION_MARKER);
        if (marker < 0) {
            return FIRST_GENERATION;
        }
        try {
            int generation = Integer.parseInt(dekId.substring(marker + GENERATION_MARKER.length()));
            return generation < FIRST_GENERATION ? FIRST_GENERATION : generation;
        } catch (NumberFormatException e) {
            return FIRST_GENERATION;
        }
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

    public int getGeneration() {
        return generation;
    }

    /**
     * Keeps the two halves of the dekId round trip agreeing.
     * <p>
     * {@link #generationOf} reads anything below {@link #FIRST_GENERATION} back as
     * generation 1, so a row allowed to hold 0 would seal ciphertext under the name
     * {@code tenant#g0} and later have it opened with generation 1's key — a
     * different key than sealed it. Every source of a below-1 generation means the
     * same thing (a row that predates generations), so it becomes 1 here, once, at
     * the model boundary rather than at each store that reads a row back.
     */
    public void setGeneration(int generation) {
        this.generation = normalize(generation);
    }

    /** This row's identity as ciphertext names it. */
    public String dekId() {
        return dekId(tenantId, generation);
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
