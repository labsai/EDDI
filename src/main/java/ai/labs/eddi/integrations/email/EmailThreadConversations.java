/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.ReplyRecipient;
import jakarta.enterprise.inject.Instance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The thread conversations ({@code replyAudience: thread}, email v2 plan §9.5)
 * an address took part in, for GDPR erasure and export. Such a conversation
 * runs as an {@code email-thread:} identity that names no address, so it is
 * found through the email ledger — the only link — and only by conversation id:
 * the identity is shared by everyone in the thread, and erasing or exporting it
 * would reach other people's mails.
 */
public final class EmailThreadConversations {

    private EmailThreadConversations() {
    }

    /**
     * Every conversation the address's ledger rows name, with the integration that
     * names it: the mails it sent, the replies sent to it, and the replies it was
     * copied on.
     */
    public static Map<String, String> namedBy(IEmailMessageLedger ledger, String address) {
        Map<String, String> named = new LinkedHashMap<>();
        for (LedgerEntry row : ledger.findByRecipient(address)) {
            boolean ownMail = row.direction() == IEmailMessageLedger.Direction.IN && row.kind() == IEmailMessageLedger.Kind.TRIGGER;
            boolean replyToIt = row.direction() == IEmailMessageLedger.Direction.OUT && row.kind() == IEmailMessageLedger.Kind.REPLY;
            if ((ownMail || replyToIt) && row.conversationId() != null) {
                named.putIfAbsent(row.conversationId(), row.integrationId());
            }
        }
        for (ReplyRecipient copy : ledger.findReplyRecipientsByAddress(address)) {
            if (copy.conversationId() != null) {
                named.putIfAbsent(copy.conversationId(), copy.integrationId());
            }
        }
        return named;
    }

    /**
     * The named conversations that still exist and run as their naming
     * integration's {@code email-thread:} identity, with that integration. A
     * conversation already gone is skipped.
     *
     * @throws IllegalStateException
     *             when there is no conversation store, or one cannot be read: the
     *             caller's step must fail, never report an erasure complete while a
     *             thread conversation may still be there
     */
    public static Map<String, String> threadConversations(Instance<IConversationMemoryStore> conversations, Map<String, String> named) {
        Map<String, String> found = new LinkedHashMap<>();
        if (named.isEmpty()) {
            return found;
        }
        if (conversations == null || !conversations.isResolvable()) {
            throw new IllegalStateException("The conversations named by this address's email ledger rows cannot be checked here");
        }
        IConversationMemoryStore store = conversations.get();
        for (var entry : named.entrySet()) {
            String userId;
            try {
                ConversationMemorySnapshot snapshot = store.loadConversationMemorySnapshot(entry.getKey());
                userId = snapshot == null ? null : snapshot.getUserId();
            } catch (IResourceStore.ResourceNotFoundException e) {
                continue;
            } catch (IResourceStore.ResourceStoreException e) {
                throw new IllegalStateException("A conversation named by this address's email ledger rows could not be read", e);
            }
            if (EmailUserIdentity.isThreadIdentityOf(userId, entry.getValue())) {
                found.put(entry.getKey(), entry.getValue());
            }
        }
        return found;
    }
}
