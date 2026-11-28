/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MentionExtractor.MentionHit;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether a mail <em>addresses</em> the integration (email v2 plan §7.2) — what
 * {@code actWhen: addressed} runs on. A mail is addressed when any of:
 * <ol>
 * <li><b>Reply to EDDI</b>: its {@code In-Reply-To} (that header only, not
 * {@code References}) is the Message-ID of a reply this integration sent.</li>
 * <li><b>Mention</b>: the sender's own text mentions a target's handle
 * ({@code MentionExtractor}).</li>
 * <li><b>EDDI in To, newly</b>: one of the integration's own addresses is in
 * {@code To} (not {@code Cc}) and the mail starts a thread (no
 * {@code In-Reply-To}) or its parent never reached this integration. An Outlook
 * reply-all keeps every To recipient in To, so on a thread EDDI already
 * received a human-to-human reply-all is <em>not</em> addressed by this
 * rule.</li>
 * </ol>
 * <b>A noise filter, not a security boundary.</b> Rules 1 and 3 read
 * {@code In-Reply-To}, which Gmail does not sign, so an authenticated, allowed
 * sender can make a mail look addressed. Who may make an agent act at all is
 * decided by authentication, the sender lists and per-target
 * {@code allowedSenders}; addressing only keeps EDDI quiet on threads it was
 * not asked into. The mail it reads is the signed view
 * ({@link InboundMail#signedView()}): a {@code To} instance the signature does
 * not cover is not there.
 * <p>
 * Ledger rows expire after {@code eddi.email.ledger-retention}: a parent older
 * than that looks new, and a reply-all to it counts as addressed.
 */
public final class EmailAddressing {

    /** Why a mail counts as addressed, as the mail context and docs name it. */
    public enum Why {
        REPLY_TO_EDDI("reply_to_eddi"), MENTION("mention"), TO_NEW("to_new"), TO_THREAD_START("to_thread_start"), NONE("none");

        private final String id;

        Why(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** The verdict. */
    public record Addressing(boolean addressed, Why why) {

        /** {@code mail.addressing} of the agent's context. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("addressed", addressed);
            map.put("why", why.id());
            return map;
        }
    }

    /**
     * The addresses that are the integration's own for addressing: its
     * {@code address} and every plus-address of it, its {@code aliases}, and the
     * targets' {@code addresses} (all normalised).
     */
    public record OwnAddresses(String address, Set<String> exact) {

        public OwnAddresses {
            exact = exact == null ? Set.of() : Set.copyOf(exact);
        }

        public static OwnAddresses of(EmailChannelSettings settings, ChannelIntegrationConfiguration integration) {
            Set<String> exact = new LinkedHashSet<>();
            if (settings.address() != null) {
                exact.add(settings.address());
            }
            exact.addAll(settings.aliases());
            if (integration != null && integration.getTargets() != null) {
                for (ChannelTarget target : integration.getTargets()) {
                    if (target != null && target.getAddresses() != null) {
                        for (String value : target.getAddresses()) {
                            AddressNormalizer.tryNormalize(value).ifPresent(exact::add);
                        }
                    }
                }
            }
            return new OwnAddresses(settings.address(), exact);
        }

        /** Whether {@code recipient} is one of them. */
        public boolean contains(MailAddress recipient) {
            if (recipient == null || recipient.address() == null) {
                return false;
            }
            String normalized = AddressNormalizer.tryNormalize(recipient.address()).orElse(null);
            return normalized != null && (exact.contains(normalized) || EmailChannelSettings.isPlusAddressOf(normalized, address));
        }
    }

    private EmailAddressing() {
    }

    /**
     * Evaluates the three rules in order; the first that holds is the reason.
     *
     * @param mail
     *            the signed view of the mail
     * @param mentions
     *            the mentions the extractor found in it
     */
    public static Addressing evaluate(String integrationId, InboundMail mail, OwnAddresses own, IEmailMessageLedger ledger,
                                      List<MentionHit> mentions) {
        String parent = mail.inReplyTo();
        if (parent != null && ledger.findByMessage(integrationId, Direction.OUT, parent).filter(row -> row.kind() == Kind.REPLY).isPresent()) {
            return new Addressing(true, Why.REPLY_TO_EDDI);
        }
        if (mentions != null && !mentions.isEmpty()) {
            return new Addressing(true, Why.MENTION);
        }
        if (mail.to().stream().anyMatch(own::contains)) {
            if (parent == null) {
                return new Addressing(true, Why.TO_THREAD_START);
            }
            if (ledger.findByMessage(integrationId, Direction.IN, parent).isEmpty()) {
                return new Addressing(true, Why.TO_NEW);
            }
        }
        return new Addressing(false, Why.NONE);
    }
}
