/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MentionExtractor;
import ai.labs.eddi.engine.mail.MentionExtractor.MentionHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Picks the target of an inbound mail (email plan §11, v2 plan §7.4), in this
 * order:
 * <ol>
 * <li>A target whose {@code addresses} holds a <b>To</b> recipient (first in
 * header order), then a <b>Cc</b> recipient.</li>
 * <li>The <b>plus-tag</b> of the integration's own address, To before Cc:
 * {@code invoices+urgent@contoso.com} on {@code invoices@contoso.com} is
 * {@code urgent}, matched against {@code triggers}. A tag on some other
 * recipient routes nothing.</li>
 * <li>A <b>mention</b> of a target's handle in the sender's own text, in the
 * order the extractor found them.</li>
 * <li>A <b>word of the subject</b> (letters and digits, case-insensitive; the
 * first {@value #MAX_SUBJECT_WORDS}) matched against {@code triggers}.</li>
 * <li>The default target.</li>
 * </ol>
 * The mail is the signed view ({@link InboundMail#signedView()}): under an
 * aligned pass an unsigned {@code To}/{@code Cc} instance or {@code Subject} is
 * not there, so it routes nothing. Where several targets match the same source,
 * the first in configuration order wins. Observers are never routed to (they
 * are refused at save time anyway).
 */
public final class EmailTargetRouting {

    static final int MAX_SUBJECT_WORDS = 50;

    /** Which rule chose the target. */
    public enum Source {
        ADDRESS_TO("address_to"), ADDRESS_CC("address_cc"), PLUS_TAG("plus_tag"), MENTION("mention"), SUBJECT("subject"), DEFAULT("default");

        private final String id;

        Source(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /**
         * Whether the sender named the target explicitly — an address, a plus-tag or a
         * mention — rather than reaching it by a subject word or by default.
         */
        public boolean explicit() {
            return this == ADDRESS_TO || this == ADDRESS_CC || this == PLUS_TAG || this == MENTION;
        }
    }

    /**
     * The routing decision.
     *
     * @param target
     *            the chosen target, or null when nothing matches and there is no
     *            usable default
     * @param source
     *            the rule that chose it (null with no target)
     * @param alsoAddressed
     *            names of the other distinct targets the mail addressed by address,
     *            plus-tag or mention
     */
    public record RouteDecision(ChannelTarget target, Source source, List<String> alsoAddressed) {

        public RouteDecision {
            alsoAddressed = alsoAddressed == null ? List.of() : List.copyOf(alsoAddressed);
        }

        /** {@code mail.route} of the agent's context. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("target", target == null ? null : target.getName());
            map.put("source", source == null ? null : source.id());
            map.put("alsoAddressed", alsoAddressed);
            return map;
        }
    }

    private EmailTargetRouting() {
    }

    /** The routing decision for {@code mail} (the signed view). */
    public static RouteDecision route(ChannelIntegrationConfiguration integration, String integrationAddress, InboundMail mail,
                                      List<MentionHit> mentions) {
        if (integration == null || integration.getTargets() == null) {
            return new RouteDecision(null, null, List.of());
        }
        // Every target the mail names explicitly, in rule order, with its source.
        List<ChannelTarget> named = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        for (MailAddress recipient : mail.to()) {
            add(named, sources, byAddress(integration, recipient), Source.ADDRESS_TO);
        }
        for (MailAddress recipient : mail.cc()) {
            add(named, sources, byAddress(integration, recipient), Source.ADDRESS_CC);
        }
        for (String tag : plusTags(integrationAddress, mail)) {
            add(named, sources, byTrigger(integration, tag), Source.PLUS_TAG);
        }
        for (MentionHit hit : mentions == null ? List.<MentionHit>of() : mentions) {
            add(named, sources, byName(integration, hit.target()), Source.MENTION);
        }
        ChannelTarget target = null;
        Source source = null;
        if (!named.isEmpty()) {
            target = named.get(0);
            source = sources.get(0);
        } else {
            for (String word : subjectWords(mail.subject())) {
                target = byTrigger(integration, word);
                if (target != null) {
                    source = Source.SUBJECT;
                    break;
                }
            }
            if (target == null) {
                target = byName(integration, integration.getDefaultTargetName());
                source = target == null ? null : Source.DEFAULT;
            }
        }
        Set<String> also = new LinkedHashSet<>();
        for (ChannelTarget other : named) {
            if (other != target) {
                also.add(other.getName());
            }
        }
        return new RouteDecision(target, source, new ArrayList<>(also));
    }

    /**
     * Lower-case handle to target name, over every addressable target's
     * {@code mentions} — what the extractor looks for.
     */
    public static Map<String, String> mentionHandles(ChannelIntegrationConfiguration integration) {
        Map<String, String> handles = new LinkedHashMap<>();
        if (integration == null || integration.getTargets() == null) {
            return handles;
        }
        for (ChannelTarget target : integration.getTargets()) {
            if (target == null || target.isObserveMode() || target.getMentions() == null || target.getName() == null) {
                continue;
            }
            for (String mention : target.getMentions()) {
                String handle = MentionExtractor.normalizeHandle(mention);
                if (handle != null) {
                    handles.putIfAbsent(handle, target.getName());
                }
            }
        }
        return handles;
    }

    private static void add(List<ChannelTarget> named, List<Source> sources, ChannelTarget target, Source source) {
        if (target != null && !named.contains(target)) {
            named.add(target);
            sources.add(source);
        }
    }

    private static ChannelTarget byAddress(ChannelIntegrationConfiguration integration, MailAddress recipient) {
        String address = recipient == null || recipient.address() == null ? null : AddressNormalizer.tryNormalize(recipient.address()).orElse(null);
        if (address == null) {
            return null;
        }
        for (ChannelTarget target : integration.getTargets()) {
            if (target == null || target.isObserveMode() || target.getAddresses() == null) {
                continue;
            }
            for (String value : target.getAddresses()) {
                if (value != null && address.equals(AddressNormalizer.tryNormalize(value).orElse(null))) {
                    return target;
                }
            }
        }
        return null;
    }

    private static ChannelTarget byName(ChannelIntegrationConfiguration integration, String name) {
        if (name == null) {
            return null;
        }
        for (ChannelTarget target : integration.getTargets()) {
            if (target != null && !target.isObserveMode() && target.getName() != null && target.getName().equalsIgnoreCase(name)) {
                return target;
            }
        }
        return null;
    }

    private static ChannelTarget byTrigger(ChannelIntegrationConfiguration integration, String token) {
        for (ChannelTarget target : integration.getTargets()) {
            if (target == null || target.isObserveMode() || target.getTriggers() == null) {
                continue;
            }
            for (String trigger : target.getTriggers()) {
                if (trigger != null && trigger.trim().toLowerCase(Locale.ROOT).equals(token)) {
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * The plus-tags of the integration's own address among To and Cc (To first),
     * lower-cased.
     */
    static Set<String> plusTags(String integrationAddress, InboundMail mail) {
        Set<String> tags = new LinkedHashSet<>();
        if (integrationAddress == null || integrationAddress.indexOf('@') <= 0) {
            return tags;
        }
        int at = integrationAddress.lastIndexOf('@');
        String local = integrationAddress.substring(0, at);
        String domain = integrationAddress.substring(at + 1);
        List<MailAddress> recipients = new ArrayList<>(mail.to());
        recipients.addAll(mail.cc());
        for (MailAddress recipient : recipients) {
            String address = recipient.address().toLowerCase(Locale.ROOT);
            int recipientAt = address.lastIndexOf('@');
            if (recipientAt <= 0 || !address.substring(recipientAt + 1).equals(domain)) {
                continue;
            }
            String recipientLocal = address.substring(0, recipientAt);
            if (recipientLocal.startsWith(local + "+") && recipientLocal.length() > local.length() + 1) {
                tags.add(recipientLocal.substring(local.length() + 1));
            }
        }
        return tags;
    }

    /** Letter-and-digit words of a subject, lower-cased, in order, capped. */
    static List<String> subjectWords(String subject) {
        List<String> words = new ArrayList<>();
        if (subject == null || subject.isEmpty()) {
            return words;
        }
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < subject.length() && words.size() < MAX_SUBJECT_WORDS; i++) {
            char c = subject.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                word.append(Character.toLowerCase(c));
            } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty() && words.size() < MAX_SUBJECT_WORDS) {
            words.add(word.toString());
        }
        return words;
    }
}
