/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.SenderPatterns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who besides the {@code To} reads a conversation-mode reply — the one pure
 * function that decides it (email v2 plan §9.3). The inbound processor calls it
 * before the turn runs, so the agent knows who will read its answer
 * ({@code mail.audience}); {@link EmailReplyRecipients#decide} calls it with
 * the same input on every send attempt.
 * <p>
 * <b>The rule.</b> {@code Cc} recipients exist only when the integration's
 * {@code replyAudience} is {@code thread} <em>and</em> the conversation runs as
 * this integration's {@code email-thread:} identity — a conversation started
 * under {@code sender} carries the sender's long-term memory and is never
 * copied to anyone, even after the setting changed. They are the answered
 * mail's {@code To} and {@code Cc} addresses, from the step's {@code mail} map
 * (written by the inbound processor from the <em>signed view</em>, so a
 * {@code Cc} added unsigned to a replayed mail is not in it):
 * <ol>
 * <li>normalised; one that does not parse is dropped;</li>
 * <li>minus the integration's own address, its aliases and their plus-addresses
 * (a reply there would come back, and an alias that is a list would mail all
 * its members);</li>
 * <li>minus the {@code To} itself;</li>
 * <li>minus anything matching {@code listAddresses};</li>
 * <li>only what matches {@code replyAllowedRecipients};</li>
 * <li>in header order ({@code To} before {@code Cc}), each once;</li>
 * <li>more than {@code maxReplyRecipients} → none at all, noted
 * {@code cc_capped}: a reply to the sender alone rather than to an arbitrary
 * part of the thread.</li>
 * </ol>
 * The model never names a recipient, and nothing here reads the mail body.
 */
public final class EmailReplyAudience {

    /** Why an address was not copied; reasons only, for logs and counters. */
    public static final String OWN = "own";
    public static final String INVALID = "invalid";
    public static final String LIST = "list";
    public static final String NOT_ALLOWED = "not_allowed";
    public static final String CC_CAPPED = "cc_capped";

    /**
     * The audience of one reply.
     *
     * @param to
     *            the one {@code To}
     * @param cc
     *            the {@code Cc} recipients, normalised, in header order
     * @param dropped
     *            why candidates were left out: a reason per dropped address, no
     *            addresses
     * @param capped
     *            whether {@code maxReplyRecipients} emptied the {@code Cc}
     */
    public record Audience(String to, List<String> cc, List<String> dropped, boolean capped) {
        public Audience {
            cc = cc == null ? List.of() : List.copyOf(cc);
            dropped = dropped == null ? List.of() : List.copyOf(dropped);
        }

        /** The {@code To} alone. */
        public static Audience senderOnly(String to) {
            return new Audience(to, List.of(), List.of(), false);
        }

        /** What the agent sees in {@code mail.audience}: who will read the answer. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("to", to);
            map.put("cc", cc);
            if (capped) {
                map.put("ccCapped", true);
            }
            return map;
        }

        /** No addresses: audiences reach log lines. */
        @Override
        public String toString() {
            return "Audience[cc=" + cc.size() + ", dropped=" + dropped.size() + (capped ? ", capped" : "") + "]";
        }
    }

    private EmailReplyAudience() {
    }

    /**
     * @param integrationId
     *            the integration the reply is sent from
     * @param settings
     *            its current settings
     * @param stepMail
     *            the answered mail's map (signed view)
     * @param to
     *            the {@code To} {@link EmailReplyRecipients} chose
     * @param conversationUserId
     *            the user id the conversation runs as
     */
    public static Audience compute(String integrationId, EmailChannelSettings settings, Map<String, Object> stepMail, String to,
                                   String conversationUserId) {
        if (settings == null || !settings.repliesToThread() || !EmailUserIdentity.isThreadIdentityOf(conversationUserId, integrationId)
                || stepMail == null) {
            return Audience.senderOnly(to);
        }
        List<String> dropped = new ArrayList<>();
        Set<String> cc = new LinkedHashSet<>();
        for (String key : List.of("to", "cc")) {
            if (!(stepMail.get(key) instanceof List<?> addresses)) {
                continue;
            }
            for (Object value : addresses) {
                String address = value instanceof String s ? AddressNormalizer.tryNormalize(s).orElse(null) : null;
                if (address == null) {
                    dropped.add(INVALID);
                } else if (EmailReplyRecipients.isOwn(settings, address)) {
                    dropped.add(OWN);
                } else if (address.equals(to) || cc.contains(address)) {
                    // The To, or already copied: no reason to record.
                    continue;
                } else if (SenderPatterns.matchesAny(address, settings.listAddresses())) {
                    dropped.add(LIST);
                } else if (!SenderPatterns.matchesAny(address, settings.replyAllowedRecipients())) {
                    dropped.add(NOT_ALLOWED);
                } else {
                    cc.add(address);
                }
            }
        }
        if (cc.size() > settings.maxReplyRecipients()) {
            dropped.add(CC_CAPPED);
            return new Audience(to, List.of(), dropped, true);
        }
        return new Audience(to, List.copyOf(cc), dropped, false);
    }

    /**
     * What a later attempt may send to: the {@code Cc} it was queued with,
     * intersected with what the current settings allow. Settings may narrow an
     * audience between attempts, never widen it.
     */
    public static List<String> narrow(Collection<String> stored, Collection<String> fresh) {
        List<String> kept = new ArrayList<>();
        for (String address : stored) {
            if (fresh.contains(address) && !kept.contains(address)) {
                kept.add(address);
            }
        }
        return List.copyOf(kept);
    }
}
