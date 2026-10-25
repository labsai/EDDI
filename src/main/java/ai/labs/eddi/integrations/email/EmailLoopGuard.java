/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.mail.AutoReplyDetector;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Refuses mail that is automated or part of a loop, before any turn (email plan
 * §13). Every refusal is recorded and counted; none is ever answered.
 * <ul>
 * <li>Automated mail by its own headers ({@link AutoReplyDetector}):
 * {@code Auto-Submitted}, {@code X-Autoreply}, {@code X-Autorespond},
 * {@code Precedence}, {@code List-Id}, {@code List-Unsubscribe}, null or daemon
 * return paths, delivery reports.</li>
 * <li>Mail from the integration's own address, or from the address of another
 * email integration of this deployment (two agents mailing each other).</li>
 * <li>Mail whose own Message-ID has the shape EDDI mints
 * ({@code <eddi.<uuid>@…>}) — something an EDDI deployment sent.</li>
 * <li>Mail whose {@code In-Reply-To} or {@code References} names a message the
 * ledger records as sent by <em>another</em> integration (or the system mailer)
 * — a reply to, or a forward of, EDDI's own mail. Replies to this integration's
 * own outbound mail are conversation mode's business (Phase 3) and are not
 * refused here.</li>
 * </ul>
 * The sender's headers can only make the guard refuse more, never less.
 */
public final class EmailLoopGuard {

    /** References looked up in the ledger per mail. */
    static final int MAX_REFERENCES_CHECKED = 20;

    private EmailLoopGuard() {
    }

    /**
     * @return the refusal reason ({@code loop:<signal>}), or empty when the mail
     *         may proceed
     */
    public static Optional<String> check(InboundMail mail, String integrationId, String ownAddress, Set<String> otherIntegrationAddresses,
                                         IEmailMessageLedger ledger) {
        AutoReplyDetector.Verdict verdict = AutoReplyDetector.detect(mail);
        if (verdict.automated()) {
            return Optional.of("loop:" + verdict.reason().name().toLowerCase(Locale.ROOT));
        }
        String from = mail.fromAddress() == null ? null : mail.fromAddress().toLowerCase(Locale.ROOT);
        if (from != null && from.equals(ownAddress)) {
            return Optional.of("loop:own_address");
        }
        if (from != null && otherIntegrationAddresses != null && otherIntegrationAddresses.contains(from)) {
            return Optional.of("loop:other_integration");
        }
        if (!mail.syntheticMessageId() && mail.messageId().toLowerCase(Locale.ROOT).startsWith("<eddi.")) {
            return Optional.of("loop:eddi_message_id");
        }
        List<String> referenced = new ArrayList<>();
        if (mail.inReplyTo() != null) {
            referenced.add(mail.inReplyTo());
        }
        referenced.addAll(mail.references());
        int checked = 0;
        for (String id : referenced) {
            if (checked++ >= MAX_REFERENCES_CHECKED) {
                break;
            }
            var sent = ledger.findOutboundByMessageId(id);
            if (sent.isPresent() && !sent.get().integrationId().equals(integrationId)) {
                return Optional.of("loop:refers_to_eddi_mail");
            }
        }
        return Optional.empty();
    }
}
