/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.GdprDeletionResult;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.memory.ConversationPurgeService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.ReplyRecipient;
import ai.labs.eddi.integrations.email.reply.EmailReplyThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * all; their conversations are reached by conversation id, like any anonymous
 * conversation.
 * <p>
 * <b>Thread conversations</b> ({@code replyAudience: thread}, email v2 plan
 * §9.5) run as {@code email-thread:} identities, which name no address and are
 * shared by everyone in the thread. They are reached <em>per conversation
 * id</em>, never through that identity: the ids the address's own ledger rows
 * name (its mails, the replies sent to it, the replies it was copied on), and
 * only where the conversation runs as that integration's thread identity
 * ({@link EmailThreadConversations}).
 * <ul>
 * <li><b>Export</b> lists, per such conversation, only the address's own mails
 * (their rendered input) and the text of the replies it was sent — not what
 * other people wrote (GDPR Art. 15(4)).</li>
 * <li><b>Erasure</b> permanently deletes each such conversation whole
 * ({@code ConversationPurgeService}) and writes one audit entry per deleted
 * conversation id: one person's turns cannot be cut out of a shared
 * conversation the agent keeps reasoning over, and the thread's other readers
 * still have the mails themselves. <i>Provisional</i> (v2 plan §13 Q4 is the
 * product owner's): it never touches another conversation of that identity, nor
 * the identity's audit entries.</li>
 * </ul>
 * A conversation store that cannot be read fails the step, and
 * {@code EmailLedgerGdprParticipant} keeps the ledger rows while any such
 * conversation remains, so a retried erasure still finds them.
 */
@ApplicationScoped
public class EmailIdentityGdprParticipant implements IGdprParticipant {

    private static final Logger LOGGER = Logger.getLogger(EmailIdentityGdprParticipant.class);

    private final Instance<GdprComplianceService> gdpr;
    private final EmailIntegrationDirectory directory;
    private final IEmailMessageLedger ledger;
    private final Instance<IConversationMemoryStore> conversations;
    private final Instance<ConversationPurgeService> purge;
    private final Instance<IConversationService> conversationService;
    private final Instance<AuditLedgerService> auditLedger;

    @Inject
    public EmailIdentityGdprParticipant(Instance<GdprComplianceService> gdpr, EmailIntegrationDirectory directory, IEmailMessageLedger ledger,
            Instance<IConversationMemoryStore> conversations, Instance<ConversationPurgeService> purge,
            Instance<IConversationService> conversationService, Instance<AuditLedgerService> auditLedger) {
        this.gdpr = gdpr;
        this.directory = directory;
        this.ledger = ledger;
        this.conversations = conversations;
        this.purge = purge;
        this.conversationService = conversationService;
        this.auditLedger = auditLedger;
    }

    /**
     * Without the conversation services: an address whose ledger rows name a
     * conversation cannot be erased or exported here (the step fails).
     */
    public EmailIdentityGdprParticipant(Instance<GdprComplianceService> gdpr, EmailIntegrationDirectory directory, IEmailMessageLedger ledger) {
        this(gdpr, directory, ledger, null, null, null, null);
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
        for (var conversation : threadConversationsOf(userId).entrySet()) {
            if (purge == null || !purge.isResolvable()) {
                throw new IllegalStateException("Thread conversations of this address cannot be deleted here");
            }
            try {
                purge.get().purge(conversation.getKey());
            } catch (IResourceStore.ResourceNotFoundException gone) {
                continue;
            } catch (IResourceStore.ResourceStoreException e) {
                throw new IllegalStateException("A thread conversation of this address could not be deleted", e);
            }
            removed++;
            auditErased(conversation.getKey(), conversation.getValue());
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
        Map<String, String> threads = threadConversationsOf(userId);
        if (!threads.isEmpty()) {
            String address = AddressNormalizer.tryNormalize(userId).orElseThrow();
            List<Map<String, Object>> exported = new ArrayList<>();
            for (var conversation : threads.entrySet()) {
                exported.add(exportOwnPart(address, conversation.getKey(), conversation.getValue()));
            }
            exports.put("threadConversations", exported);
        }
        return exports.isEmpty() ? null : exports;
    }

    /**
     * The email user ids of the address {@code userId} names; empty when it is not
     * an address, or is itself an email identity. Thread identities are never among
     * them: they are shared ({@link #threadConversationsOf}).
     */
    Set<String> emailUserIdsOf(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        Optional<String> address = addressOf(userId);
        if (address.isEmpty()) {
            return ids;
        }
        Set<String> integrations = new LinkedHashSet<>();
        for (EmailIntegration integration : directory.scan()) {
            integrations.add(integration.id());
        }
        for (LedgerEntry row : ledger.findByRecipient(address.get())) {
            if (row.direction() == IEmailMessageLedger.Direction.IN) {
                integrations.add(row.integrationId());
            }
        }
        for (String integrationId : integrations) {
            ids.add(EmailUserIdentity.perSender(integrationId, address.get()));
        }
        return ids;
    }

    /**
     * The thread conversations the address took part in, by id, with the
     * integration that names each.
     */
    Map<String, String> threadConversationsOf(String userId) {
        Optional<String> address = addressOf(userId);
        if (address.isEmpty()) {
            return Map.of();
        }
        return EmailThreadConversations.threadConversations(conversations, EmailThreadConversations.namedBy(ledger, address.get()));
    }

    private Optional<String> addressOf(String userId) {
        if (userId == null || EmailUserIdentity.isEmailIdentity(userId) || gdpr == null || !gdpr.isResolvable()) {
            return Optional.empty();
        }
        return AddressNormalizer.tryNormalize(userId);
    }

    /**
     * What one thread conversation holds of the address: its own mails (the
     * rendered input of each) and the replies it was sent (To or Cc), by Message-ID
     * — nothing anyone else wrote.
     */
    private Map<String, Object> exportOwnPart(String address, String conversationId, String integrationId) {
        if (conversationService == null || !conversationService.isResolvable()) {
            throw new IllegalStateException("Thread conversations of this address cannot be exported here");
        }
        SimpleConversationMemorySnapshot snapshot;
        try {
            snapshot = conversationService.get().readConversation(conversationId, true, false, List.of());
        } catch (Exception e) {
            throw new IllegalStateException("A thread conversation of this address could not be read for export", e);
        }
        List<? extends Map<String, Object>> outputs = snapshot == null ? List.of() : snapshot.getConversationOutputs();
        List<Map<String, Object>> mails = new ArrayList<>();
        List<Map<String, Object>> replies = new ArrayList<>();
        Set<String> replyRows = new LinkedHashSet<>();
        for (LedgerEntry row : ledger.findByRecipient(address)) {
            if (!conversationId.equals(row.conversationId())) {
                continue;
            }
            if (row.direction() == IEmailMessageLedger.Direction.IN && row.kind() == IEmailMessageLedger.Kind.TRIGGER) {
                int step = EmailReplyThread.mailStep(outputs, row.messageId());
                Map<String, Object> mail = new LinkedHashMap<>();
                mail.put("messageId", row.messageId());
                mail.put("receivedAt", row.createdAt().toString());
                mail.put("input", step < 0 ? null : outputs.get(step).get("input"));
                mails.add(mail);
            } else if (row.kind() == IEmailMessageLedger.Kind.REPLY) {
                replyRows.add(row.id());
            }
        }
        for (ReplyRecipient copy : ledger.findReplyRecipientsByAddress(address)) {
            if (conversationId.equals(copy.conversationId())) {
                replyRows.add(copy.rowId());
            }
        }
        for (String rowId : replyRows) {
            ledger.findById(rowId).filter(row -> row.state() == IEmailMessageLedger.State.SENT).ifPresent(row -> {
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("messageId", row.messageId());
                reply.put("role", address.equals(row.recipient()) ? "TO" : "CC");
                reply.put("sentAt", row.updatedAt() == null ? null : row.updatedAt().toString());
                reply.put("text", replyText(outputs, row.sourceRef()));
                replies.add(reply);
            });
        }
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("conversationId", conversationId);
        part.put("integrationId", integrationId);
        part.put("mails", mails);
        part.put("replies", replies);
        return part;
    }

    /**
     * The text of a reply rebuilt from its step; null for notices or a step gone.
     */
    private static String replyText(List<? extends Map<String, Object>> outputs, String sourceRef) {
        String[] ref = sourceRef == null ? new String[0] : sourceRef.split("\\|", 3);
        if (ref.length != 3 || !"reply".equals(ref[1])) {
            return null;
        }
        try {
            int step = Integer.parseInt(ref[2]);
            return step >= 0 && step < outputs.size() ? EmailReplyThread.replyText(outputs, step) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * One audit entry per deleted shared conversation: which, of which integration
     * — no address.
     */
    private void auditErased(String conversationId, String integrationId) {
        if (auditLedger == null || !auditLedger.isResolvable()) {
            return;
        }
        try {
            AuditLedgerService service = auditLedger.get();
            if (!service.isEnabled()) {
                return;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("integrationId", integrationId);
            detail.put("outcome", "thread_conversation_erased");
            detail.put("reason", "gdpr_erasure");
            boolean queued = service.submit(new AuditEntry(UUID.randomUUID().toString(), conversationId, null, null, null, null, -1, "email.gdpr",
                    "email", -1, 0L, Map.of(), detail, null, null, List.of(), 0.0, Instant.now(), null, null));
            if (!queued) {
                // The conversation is already gone and a retry would skip it, so the entry
                // cannot be regenerated: say so loudly rather than let the gap go unseen.
                LOGGER.warnf("The audit ledger dropped the erasure entry of thread conversation %s (queue full); the erasure itself succeeded",
                        conversationId);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not audit an erased thread conversation: %s", e.getMessage());
        }
    }

    private static long total(GdprDeletionResult result) {
        return result.memoriesDeleted() + result.conversationsDeleted();
    }

    @Override
    public int order() {
        // Before the email ledger participant (0) reads the rows that name the
        // integrations and conversations, and before the user directory (LAST).
        return -10;
    }
}
