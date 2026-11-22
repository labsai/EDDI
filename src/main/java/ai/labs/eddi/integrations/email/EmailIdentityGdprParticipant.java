/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.GdprDeletionResult;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.ReplyRecipient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Erasure and export of what the email channel holds about a mail address
 * (email plan §17).
 * <p>
 * An email correspondent's conversations, memories and properties are stored
 * under {@code email:<integrationId>:<address>} — one user id per integration
 * they wrote to. An erasure request for the <em>address</em> (the person knows
 * their address, not EDDI's ids) therefore runs the full erasure cascade once
 * for every such id: the email integrations that exist now, plus every
 * integration an inbound ledger row of that address names (covering
 * integrations deleted since, within the ledger's retention). The inbound
 * ledger rows themselves (the address is their {@code recipient}) are erased by
 * {@code EmailLedgerGdprParticipant}.
 * <p>
 * Nothing is expanded for an id that already is an email identity, so the
 * nested cascades do not recurse. The one-off {@code email-anon:} ids of
 * unauthenticated senders hold no memory and are not linked to an address at
 * all; their * conversations are reached by conversation id, like any anonymous
 * conversation.
 * <p>
 * <b>Thread conversations</b> ({@code replyAudience: thread}, email v2 plan
 * §9.5) run as {@code email-thread:} identities, which name no address. They
 * are found through the ledger instead: the conversations its inbound rows from
 * the address and its reply-recipient entries for it name. Each one whose user
 * id is a thread identity of that row's integration is erased (or exported)
 * whole, through the same cascade — the address's mails cannot be told apart
 * from the rest of a shared thread, and the thread's other readers still have
 * it in their mailboxes. <i>Provisional</i>: the plan left delete-versus-redact
 * to the product owner (v2 plan §13 Q4); this follows the v3 plan's rule of
 * cascading over the conversations the address's ledger rows name. A
 * conversation of any other identity is never reached this way.
 */
@ApplicationScoped
public class EmailIdentityGdprParticipant implements IGdprParticipant {

    private final Instance<GdprComplianceService> gdpr;
    private final EmailIntegrationDirectory directory;
    private final IEmailMessageLedger ledger;
    private final Instance<IConversationMemoryStore> conversations;

    @Inject
    public EmailIdentityGdprParticipant(Instance<GdprComplianceService> gdpr, EmailIntegrationDirectory directory, IEmailMessageLedger ledger,
            Instance<IConversationMemoryStore> conversations) {
        this.gdpr = gdpr;
        this.directory = directory;
        this.ledger = ledger;
        this.conversations = conversations;
    }

    /** Without the conversation store: no thread conversations are reached. */
    public EmailIdentityGdprParticipant(Instance<GdprComplianceService> gdpr, EmailIntegrationDirectory directory, IEmailMessageLedger ledger) {
        this(gdpr, directory, ledger, null);
    }

    @Override
    public String name() {
        return "emailIdentities";
    }

    @Override
    public long erase(String userId) {
        long removed = 0;
        int incomplete = 0;
        for (String emailUserId : emailUserIdsOf(userId)) {
            GdprDeletionResult result = gdpr.get().deleteUserData(emailUserId);
            removed += result == null ? 0 : total(result);
            if (result != null && result.failedSteps() != null && !result.failedSteps().isEmpty()) {
                incomplete++;
            }
        }
        if (incomplete > 0) {
            // Surfaces as this step's failure in the outer result, so the erasure is not
            // reported complete while an email identity's data is still there.
            throw new IllegalStateException("The erasure of " + incomplete + " email identities of this address was incomplete");
        }
        return removed;
    }

    @Override
    public Object export(String userId) {
        Map<String, Object> exports = new LinkedHashMap<>();
        for (String emailUserId : emailUserIdsOf(userId)) {
            exports.put(emailUserId, gdpr.get().exportUserData(emailUserId));
        }
        return exports.isEmpty() ? null : exports;
    }

    /**
     * The email user ids of the address {@code userId} names; empty when it is not
     * an address, or is itself an email identity.
     */
    Set<String> emailUserIdsOf(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        if (userId == null || EmailUserIdentity.isEmailIdentity(userId) || gdpr == null || !gdpr.isResolvable()) {
            return ids;
        }
        Optional<String> address = AddressNormalizer.tryNormalize(userId);
        if (address.isEmpty()) {
            return ids;
        }
        Set<String> integrations = new LinkedHashSet<>();
        for (EmailIntegration integration : directory.scan()) {
            integrations.add(integration.id());
        }
        // Conversation id -> the integration whose ledger row names it.
        Map<String, String> named = new LinkedHashMap<>();
        for (LedgerEntry row : ledger.findByRecipient(address.get())) {
            if (row.direction() == IEmailMessageLedger.Direction.IN) {
                integrations.add(row.integrationId());
                if (row.conversationId() != null) {
                    named.putIfAbsent(row.conversationId(), row.integrationId());
                }
            }
        }
        for (ReplyRecipient copy : ledger.findReplyRecipientsByAddress(address.get())) {
            if (copy.conversationId() != null) {
                named.putIfAbsent(copy.conversationId(), copy.integrationId());
            }
        }
        for (String integrationId : integrations) {
            ids.add(EmailUserIdentity.perSender(integrationId, address.get()));
        }
        ids.addAll(threadIdentities(named));
        return ids;
    }

    /**
     * The {@code email-thread:} user ids of the named conversations — only where
     * the conversation's own user id is a thread identity of the integration whose
     * ledger row named it. A ledger pointer to anything else (a per-sender or
     * shared conversation, another integration's thread) adds nothing.
     */
    private Set<String> threadIdentities(Map<String, String> named) {
        Set<String> ids = new LinkedHashSet<>();
        if (named.isEmpty() || conversations == null || !conversations.isResolvable()) {
            return ids;
        }
        IConversationMemoryStore store = conversations.get();
        for (var entry : named.entrySet()) {
            String userId;
            try {
                ConversationMemorySnapshot snapshot = store.loadConversationMemorySnapshot(entry.getKey());
                userId = snapshot == null ? null : snapshot.getUserId();
            } catch (Exception e) {
                // Gone (erased already, or retention) or unreadable: nothing to reach here.
                continue;
            }
            if (EmailUserIdentity.isThreadIdentityOf(userId, entry.getValue())) {
                ids.add(userId);
            }
        }
        return ids;
    }

    private static long total(GdprDeletionResult result) {
        return result.memoriesDeleted() + result.conversationsDeleted();
    }

    @Override
    public int order() {
        // Before the email ledger participant (0) reads the rows that name the
        // integrations, and before the user directory (LAST).
        return -10;
    }
}
