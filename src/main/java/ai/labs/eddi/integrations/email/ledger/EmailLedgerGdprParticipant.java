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
 * recorded for that principal — which is why the directory participant runs
 * after this one ({@link IGdprParticipant#LAST}). A row whose recipient is an
 * address that belongs to nobody in the directory (a shared mailbox an agent
 * designer typed) is not personal data of this user and is left to retention
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
        Set<String> addresses = new LinkedHashSet<>();
        if (userId == null || userId.isBlank()) {
            return addresses;
        }
        AddressNormalizer.tryNormalize(userId).ifPresent(addresses::add);
        // An email channel identity (email:<integrationId>:<address>) names its
        // address.
        EmailUserIdentity.addressOf(userId).flatMap(AddressNormalizer::tryNormalize).ifPresent(addresses::add);
        if (directory != null && directory.isResolvable()) {
            Optional<DirectoryUser> entry = directory.get().find(userId);
            entry.map(DirectoryUser::email).flatMap(AddressNormalizer::tryNormalize).ifPresent(addresses::add);
        }
        return addresses;
    }

    @Override
    public int order() {
        return 0;
    }
}
