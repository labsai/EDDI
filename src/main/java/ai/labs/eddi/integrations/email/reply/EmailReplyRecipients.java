/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who a conversation-mode reply goes to — the one place that decides it (email
 * plan §9). Every reply, inline, retried, rebuilt after a crash, a pause notice
 * or a continuation, passes through {@link #decide}; nothing else can name a
 * recipient, and the model never does.
 * <p>
 * <b>The rule.</b> The authenticated {@code From} of the mail the reply answers
 * — never its {@code Cc}, never another recipient, and the {@code Reply-To}
 * only with {@code honorReplyTo: true} and exactly one valid {@code Reply-To}.
 * A reply needs all of:
 * <ol>
 * <li>the integration is in conversation mode now;</li>
 * <li>the answered mail is this integration's inbound {@code TRIGGER} row and
 * reached a turn ({@code TURN_SUBMITTED} or {@code DONE}) — a refused mail
 * never gets one;</li>
 * <li>the conversation runs as an authenticated email identity of this
 * integration — {@code email:<id>:<address>} or {@code email:<id>}, which are
 * minted only for a DKIM-aligned pass — never {@code email-anon:} or anyone
 * else;</li>
 * <li>the mail's step in conversation memory (written by the inbound processor,
 * not by the sender) is the same mail, with the verdict {@code aligned-pass}
 * and the same {@code From} as the ledger row;</li>
 * <li>under {@code identityMode: perSender} with
 * {@code threadParticipants: originalSenderOnly}, the conversation's identity
 * is that very address;</li>
 * <li>the recipient is not the integration's own address.</li>
 * </ol>
 * Unauthenticated mail cannot reach a turn in conversation mode at all
 * ({@code requireAuthentication} is forced on), so rule 3 and 4 are the second
 * and third line, not the first.
 */
public final class EmailReplyRecipients {

    /** The verdict the inbound processor records for an authenticated sender. */
    static final String ALIGNED_PASS = "aligned-pass";

    /**
     * The decision.
     *
     * @param recipient
     *            the one normalised address to send to, or null
     * @param refusal
     *            why not, a stable code, or null
     */
    public record Decision(String recipient, String refusal) {
        public boolean allowed() {
            return recipient != null && refusal == null;
        }

        static Decision refuse(String code) {
            return new Decision(null, code);
        }

        @Override
        public String toString() {
            return allowed() ? "Decision[allowed]" : "Decision[refused " + refusal + "]";
        }
    }

    private EmailReplyRecipients() {
    }

    /**
     * @param integrationId
     *            the integration the reply would be sent from
     * @param settings
     *            its current settings
     * @param inbound
     *            the ledger row of the mail being answered
     * @param stepMail
     *            the mail map of that mail's step (conversation memory, or the
     *            processor's own map for a group discussion)
     * @param conversationUserId
     *            the user id the conversation runs as
     */
    public static Decision decide(String integrationId, EmailChannelSettings settings, LedgerEntry inbound, Map<String, Object> stepMail,
                                  String conversationUserId) {
        if (settings == null || !settings.replies()) {
            return Decision.refuse("not_conversation_mode");
        }
        if (inbound == null || inbound.kind() != Kind.TRIGGER || inbound.direction() != Direction.IN
                || !Objects.equals(integrationId, inbound.integrationId())) {
            return Decision.refuse("no_inbound_mail");
        }
        if (inbound.state() != State.TURN_SUBMITTED && inbound.state() != State.DONE) {
            return Decision.refuse("mail_not_run");
        }
        String sender = inbound.recipient() == null ? null : AddressNormalizer.tryNormalize(inbound.recipient()).orElse(null);
        if (sender == null) {
            return Decision.refuse("no_sender");
        }
        if (!isAuthenticatedIdentity(conversationUserId, integrationId)) {
            return Decision.refuse("unauthenticated_identity");
        }
        if (stepMail == null || !inbound.messageId().equals(stepMail.get("messageId"))) {
            return Decision.refuse("mail_step_missing");
        }
        if (!ALIGNED_PASS.equals(stepMail.get("authentication"))) {
            return Decision.refuse("unauthenticated_sender");
        }
        String stepFrom = stepMail.get("from") instanceof String from ? AddressNormalizer.tryNormalize(from).orElse(null) : null;
        if (!sender.equals(stepFrom)) {
            return Decision.refuse("sender_mismatch");
        }
        if (settings.identityMode() == EmailChannelSettings.IdentityMode.PER_SENDER
                && settings.threadParticipants() == EmailChannelSettings.ThreadParticipants.ORIGINAL_SENDER_ONLY
                && !EmailUserIdentity.perSender(integrationId, sender).equals(conversationUserId)) {
            return Decision.refuse("identity_mismatch");
        }
        String recipient = settings.honorReplyTo() ? singleReplyTo(stepMail).orElse(sender) : sender;
        if (recipient.equals(settings.address())) {
            return Decision.refuse("own_address");
        }
        return new Decision(recipient, null);
    }

    /**
     * {@code email:<integrationId>} or {@code email:<integrationId>:<address>} —
     * never {@code email-anon:}, never another integration's, never a person's own
     * principal.
     */
    static boolean isAuthenticatedIdentity(String userId, String integrationId) {
        if (userId == null || integrationId == null || userId.startsWith(EmailUserIdentity.ANONYMOUS_PREFIX)) {
            return false;
        }
        String shared = EmailUserIdentity.integration(integrationId);
        if (userId.equals(shared)) {
            return true;
        }
        return userId.startsWith(shared + ":") && EmailUserIdentity.addressOf(userId).isPresent();
    }

    /**
     * The one valid {@code Reply-To} address of the step's mail, if exactly one.
     */
    static Optional<String> singleReplyTo(Map<String, Object> stepMail) {
        if (!(stepMail.get("replyTo") instanceof List<?> replyTo) || replyTo.size() != 1 || !(replyTo.get(0) instanceof String only)) {
            return Optional.empty();
        }
        return AddressNormalizer.tryNormalize(only);
    }
}
