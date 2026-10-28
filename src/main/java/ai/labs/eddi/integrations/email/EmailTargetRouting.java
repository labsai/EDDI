/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the target of an inbound mail (email plan §11 "Routing").
 * <ol>
 * <li>The <b>plus-tag</b> of the address the mail was sent to:
 * {@code invoices+urgent@contoso.com} on the integration
 * {@code invoices@contoso.com} is {@code urgent}. Only To and Cc addresses
 * whose local part and domain are the integration's own count, so a tag on some
 * other recipient routes nothing.</li>
 * <li>Otherwise a <b>word of the subject</b> (letters and digits, compared
 * case-insensitively; the first {@value #MAX_SUBJECT_WORDS} words).</li>
 * <li>Otherwise the default target.</li>
 * </ol>
 * A target's {@code triggers} are matched exactly (case-insensitive). Where
 * several targets match the same source, the first in configuration order wins.
 * Observers are never routed to (they are refused at save time anyway).
 */
public final class EmailTargetRouting {

    static final int MAX_SUBJECT_WORDS = 50;

    private EmailTargetRouting() {
    }

    /**
     * The target for {@code mail}; null when nothing matches and there is no usable
     * default.
     */
    public static ChannelTarget route(ChannelIntegrationConfiguration integration, String integrationAddress, InboundMail mail) {
        if (integration == null || integration.getTargets() == null) {
            return null;
        }
        for (String tag : plusTags(integrationAddress, mail)) {
            ChannelTarget hit = byTrigger(integration, tag);
            if (hit != null) {
                return hit;
            }
        }
        for (String word : subjectWords(mail.subject())) {
            ChannelTarget hit = byTrigger(integration, word);
            if (hit != null) {
                return hit;
            }
        }
        String defaultName = integration.getDefaultTargetName();
        for (ChannelTarget target : integration.getTargets()) {
            if (target != null && !target.isObserveMode() && target.getName() != null && target.getName().equalsIgnoreCase(defaultName)) {
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
     * The plus-tags of the integration's own address among To and Cc, lower-cased.
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
