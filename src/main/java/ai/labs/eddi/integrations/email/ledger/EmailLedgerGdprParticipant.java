/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.IUserDirectoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.integrations.email.EmailThreadConversations;
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
 * mechanics, not data about them. A reply the person was copied on
 * ({@code replyAudience: thread}) is listed with {@code role: CC}; erasure
 * removes those entries with the rows
 * ({@link IEmailMessageLedger#deleteByRecipient}).
 */
@ApplicationScoped
public class EmailLedgerGdprParticipant implements IGdprParticipant {

    private final IEmailMessageLedger ledger;
    private final Instance<IUserDirectoryStore> directory;
    private final Instance<IConversationMemoryStore> conversations;

    @Inject
    public EmailLedgerGdprParticipant(IEmailMessageLedger ledger, Instance<IUserDirectoryStore> directory,
            Instance<IConversationMemoryStore> conversations) {
        this.ledger = ledger;
        this.directory = directory;
        this.conversations = conversations;
    }

    /**
     * Without the conversation store: rows naming a conversation are never erased
     * here (the step fails), since it cannot tell whether they still lead to a
     * thread conversation.
     */
    public EmailLedgerGdprParticipant(IEmailMessageLedger ledger, Instance<IUserDirectoryStore> directory) {
        this(ledger, directory, null);
    }

    @Override
    public String name() {
        return "emailLedger";
    }

    @Override
    public long erase(String userId) {
        Set<String> addresses = addressesOf(userId);
        // The rows are the only link to the thread conversations an address took part
        // in (email v2 plan §9.5): while one is still there — the identity step that
        // deletes them failed — the rows stay, so a retried erasure still reaches it.
        for (String address : addresses) {
            if (!EmailThreadConversations.threadConversations(conversations, EmailThreadConversations.namedBy(ledger, address)).isEmpty()) {
                throw new IllegalStateException("Thread conversations of this address are still stored; its email ledger rows are kept for the "
                        + "retry that erases them");
            }
        }
        long removed = 0;
        for (String address : addresses) {
            removed += ledger.deleteByRecipient(address);
        }
        return removed;
    }

    @Override
    public Object export(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String address : addressesOf(userId)) {
            // A reply the address was copied on (email v2 plan §9.5): which reply, when.
            for (var copy : ledger.findReplyRecipientsByAddress(address)) {
                if (copy.role() != IEmailMessageLedger.RecipientRole.CC) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recipient", copy.address());
                row.put("kind", IEmailMessageLedger.Kind.REPLY.name());
                row.put("direction", IEmailMessageLedger.Direction.OUT.name());
                row.put("role", copy.role().name());
                ledger.findById(copy.rowId()).ifPresent(reply -> {
                    row.put("state", reply.state().name());
                    row.put("messageId", reply.messageId());
                });
                row.put("conversationId", copy.conversationId());
                row.put("createdAt", text(copy.createdAt()));
                row.put("expiresAt", text(copy.expiresAt()));
                rows.add(row);
            }
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
