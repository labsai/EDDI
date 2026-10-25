/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Addresses an email integration must not send to any more (email plan §12,
 * §13): a reply to them bounced permanently. MongoDB and PostgreSQL twins,
 * selected by {@code eddi.datastore.type}.
 * <p>
 * <b>Per integration.</b> A hard bounce says something about one mailbox's
 * relationship with one address; an entry suppresses replies from that
 * integration only.
 * <p>
 * <b>No address stored.</b> An entry holds the SHA-256 of the normalised
 * address and its domain — enough to answer "is this address suppressed?" and
 * to clear an entry for someone who knows the address, not enough to read a
 * list of correspondents out of the store. GDPR erasure removes the entries of
 * an erased address ({@link #deleteByAddress}); export lists them
 * ({@link #findByAddress}).
 * <p>
 * <b>Never expires.</b> An entry stays until an administrator clears it
 * ({@code POST /channelstore/channels/{id}/email-suppressions/clear}), the
 * integration is deleted, or the address is erased.
 */
public interface IEmailSuppressionStore {

    /**
     * One suppressed address.
     *
     * @param integrationId
     *            the email integration
     * @param addressHash
     *            {@code sha256:<hex>} of the normalised address
     * @param domain
     *            the address's domain, for an administrator's overview
     * @param reason
     *            why, e.g. {@code bounce:5.1.1}
     * @param createdAt
     *            when it was suppressed
     */
    record Suppression(String integrationId, String addressHash, String domain, String reason, Instant createdAt) {
        public Suppression {
            Objects.requireNonNull(integrationId, "integrationId");
            Objects.requireNonNull(addressHash, "addressHash");
        }
    }

    /**
     * Suppresses {@code normalizedAddress} for {@code integrationId}; nothing
     * changes when it already is.
     *
     * @return whether a new entry was written
     */
    boolean suppress(String integrationId, String normalizedAddress, String reason, Instant now);

    /**
     * Whether {@code normalizedAddress} is suppressed for {@code integrationId}.
     */
    boolean isSuppressed(String integrationId, String normalizedAddress);

    /** Up to {@code limit} entries of one integration, newest first. */
    List<Suppression> list(String integrationId, int limit);

    /**
     * Clears one entry.
     *
     * @return whether there was one
     */
    boolean remove(String integrationId, String normalizedAddress);

    /** Removes every entry of a deleted integration. */
    long removeAll(String integrationId);

    /** The entries of one address across integrations, for GDPR export. */
    List<Suppression> findByAddress(String normalizedAddress);

    /** Removes the entries of one address, for GDPR erasure. */
    long deleteByAddress(String normalizedAddress);

    /** {@code sha256:<hex>} of the lower-cased address. */
    static String hash(String normalizedAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedAddress.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The domain part of an address, lower-case, or null. */
    static String domainOf(String normalizedAddress) {
        int at = normalizedAddress == null ? -1 : normalizedAddress.lastIndexOf('@');
        return at < 0 ? null : normalizedAddress.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
