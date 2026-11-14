/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.IUserDirectoryStore;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Erases and exports the email ledger rows addressed to a person.
 * <p>
 * A ledger row names its counterpart by address — the recipient of an outbound
 * row, the sender of an inbound one — and an erasure request names a user id.
 * The addresses taken to be the person's are the user id itself when it is an
 * address, the address of an email channel identity
 * ({@code email:<integrationId>:<address>}), and the email the user directory
 * recorded for that principal when the identity provider marked it verified —
 * which is why the directory participant runs after this one
 * ({@link IGdprParticipant#LAST}). A row whose recipient is an address that
 * belongs to nobody in the directory (a shared mailbox an agent designer typed)
 * is not personal data of this user and is left to retention
 * ({@code eddi.email.ledger-retention}).
 * <p>
 * The export lists what a row says about the person — when, which message, its
 * state — and leaves out the claim token and idempotency digest, which are
 * mechanics, not data about them.
 */
@ApplicationScoped
public class EmailLedgerGdprParticipant implements IGdprParticipant {

    private final IEmailMessageLedger ledger;
    private final Instance<IUserDirectoryStore> directory;

    @Inject
    public EmailLedgerGdprParticipant(IEmailMessageLedger ledger, Instance<IUserDirectoryStore> directory) {
        this.ledger = ledger;
        this.directory = directory;
    }

    @Override
    public String name() {
        return "emailLedger";
    }

    @Override
    public long erase(String userId) {
        long removed = 0;
        for (String address : addressesOf(userId)) {
            removed += ledger.deleteByRecipient(address);
        }
        return removed;
    }

    @Override
    public Object export(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String address : addressesOf(userId)) {
            for (LedgerEntry entry : ledger.findByRecipient(address)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recipient", entry.recipient());
                row.put("kind", entry.kind().name());
                row.put("direction", entry.direction().name());
                row.put("state", entry.state().name());
                row.put("messageId", entry.messageId());
                row.put("conversationId", entry.conversationId());
                row.put("attempts", entry.attempts());
                row.put("createdAt", text(entry.createdAt()));
                row.put("updatedAt", text(entry.updatedAt()));
                row.put("expiresAt", text(entry.expiresAt()));
                rows.add(row);
            }
        }
        return rows.isEmpty() ? null : rows;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** The normalised addresses that identify {@code userId}. */
    Set<String> addressesOf(String userId) {
        return resolve(userId).addresses().keySet();
    }

    /**
     * Where each address of {@code userId} came from, for the compliance record —
     * the sources and counts only, never the addresses, which the ledger must not
     * hold for an erased person.
     */
    @Override
    public Map<String, Object> auditBasis(String userId) {
        Resolution resolution = resolve(userId);
        if (resolution.addresses().isEmpty() && !resolution.unverifiedDirectoryEmailSkipped()) {
            return Map.of();
        }
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("emailAddresses", resolution.addresses().size());
        basis.put("emailAddressSources", List.copyOf(new LinkedHashSet<>(resolution.addresses().values())));
        basis.put("unverifiedDirectoryEmailSkipped", resolution.unverifiedDirectoryEmailSkipped());
        return basis;
    }

    /**
     * Tells the caller that an unverified directory address was skipped — without
     * the address, which the response must not carry. Many identity providers never
     * set {@code email_verified}, so for a genuine user this is the case where
     * their ledger and suppression rows are still there after the erasure; left to
     * the audit ledger alone it was a silent gap.
     */
    @Override
    public List<String> notices(String userId, Operation operation) {
        if (!resolve(userId).unverifiedDirectoryEmailSkipped()) {
            return List.of();
        }
        // There is no address-scoped erase or export of the email ledger; a request
        // under the address as user id is the full cascade for whatever principal
        // that string names, so the notice spells out what it would reach.
        if (operation == Operation.EXPORT) {
            return List.of(UNVERIFIED_DIRECTORY_EMAIL + ": the user directory records an e-mail address for this user that the identity "
                    + "provider has not marked verified, so the email ledger rows and email suppression entries recorded under that "
                    + "address were not exported. Before exporting under the address, confirm out of band that it belongs to this "
                    + "person and that no user account uses the address itself as its user id: an export under the address returns "
                    + "everything EDDI keys by that user id, which would be that account's data.");
        }
        return List.of(UNVERIFIED_DIRECTORY_EMAIL + ": the user directory records an e-mail address for this user that the identity "
                + "provider has not marked verified, so the email ledger rows and email suppression entries recorded under that "
                + "address were not erased. Before any re-run, confirm out of band that the address belongs to this person and "
                + "that no user account uses the address itself as its user id: an erasure under the address deletes everything "
                + "EDDI keys by that user id, that account included. Suppression entries alone can be cleared per email "
                + "integration (POST /channelstore/channels/{id}/email-suppressions/clear); ledger rows have no address-scoped "
                + "erase and otherwise expire with eddi.email.ledger-retention.");
    }

    /**
     * Leading code of the unverified-address notice, stable for clients to match
     * on.
     */
    public static final String UNVERIFIED_DIRECTORY_EMAIL = "unverifiedDirectoryEmail";

    /**
     * The addresses of {@code userId}, each with its source:
     * {@value #SOURCE_USER_ID} (the id the operator named is itself an address),
     * {@value #SOURCE_EMAIL_IDENTITY} (an email channel identity, which an
     * integration mints only for a DKIM-aligned sender) or
     * {@value #SOURCE_DIRECTORY_VERIFIED}.
     * <p>
     * The directory's address counts only when the identity provider said it was
     * verified ({@code email_verified}). An unverified claim is whatever the
     * account holder typed into their profile, so honouring it let one user's
     * erasure delete, and their export disclose, the ledger and suppression rows of
     * an address that belongs to someone else. Such an address is skipped (fail
     * closed) and the skip is reported, so the operator can handle that address as
     * its own request.
     */
    Resolution resolve(String userId) {
        Map<String, String> addresses = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) {
            return new Resolution(addresses, false);
        }
        AddressNormalizer.tryNormalize(userId).ifPresent(address -> addresses.putIfAbsent(address, SOURCE_USER_ID));
        // An email channel identity (email:<integrationId>:<address>) names its
        // address.
        EmailUserIdentity.addressOf(userId).flatMap(AddressNormalizer::tryNormalize)
                .ifPresent(address -> addresses.putIfAbsent(address, SOURCE_EMAIL_IDENTITY));
        boolean unverifiedSkipped = false;
        if (directory != null && directory.isResolvable()) {
            Optional<DirectoryUser> entry = directory.get().find(userId);
            Optional<String> recorded = entry.map(DirectoryUser::email).flatMap(AddressNormalizer::tryNormalize);
            if (recorded.isPresent()) {
                if (entry.get().emailVerified()) {
                    addresses.putIfAbsent(recorded.get(), SOURCE_DIRECTORY_VERIFIED);
                } else {
                    unverifiedSkipped = !addresses.containsKey(recorded.get());
                }
            }
        }
        return new Resolution(addresses, unverifiedSkipped);
    }

    static final String SOURCE_USER_ID = "userId";
    static final String SOURCE_EMAIL_IDENTITY = "emailIdentity";
    static final String SOURCE_DIRECTORY_VERIFIED = "directoryVerified";

    /**
     * @param addresses
     *            normalised address to its source, in resolution order
     * @param unverifiedDirectoryEmailSkipped
     *            the directory recorded an address not otherwise named, and it was
     *            not verified
     */
    record Resolution(Map<String, String> addresses, boolean unverifiedDirectoryEmailSkipped) {
    }

    @Override
    public int order() {
        return 0;
    }
}
