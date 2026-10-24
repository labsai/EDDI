/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which addresses system notification mail may go to — the operator's answer to
 * "who may an agent author make this deployment email?".
 * <p>
 * The recipients of a HITL notification are chosen by whoever may edit an
 * agent, but the mail leaves through the operator's relay, from the operator's
 * sender address, carrying text an author and, through the tool arguments, an
 * end user can influence. Without a bound that is an open mailer for the
 * organisation's domain. So the operator names the recipient domains in
 * {@value #ALLOWED_DOMAINS}: an exact domain ({@code example.com}),
 * {@code *.domain} for its subdomains (not the domain itself), or {@code *} for
 * any. <b>Unset, it is the domain of {@code eddi.mail.smtp.from} and its
 * subdomains</b> — mail stays inside the organisation that runs EDDI unless the
 * operator widens it.
 * <p>
 * Checked twice: at save time ({@code HitlConfigValidation}, refused when a
 * mailer is configured) and at send time, where a recipient outside the policy
 * is never mailed (the ledger row ends {@code SUPPRESSED}).
 */
public final class NotifyRecipientPolicy {

    /** The operator's recipient-domain allowlist. */
    public static final String ALLOWED_DOMAINS = "eddi.mail.notify.allowed-recipient-domains";

    private static final String SMTP_HOST = "eddi.mail.smtp.host";
    private static final String SMTP_FROM = "eddi.mail.smtp.from";

    private final boolean any;
    private final List<String> exact;
    private final List<String> suffixes;
    private final String description;

    private NotifyRecipientPolicy(boolean any, List<String> exact, List<String> suffixes, String description) {
        this.any = any;
        this.exact = List.copyOf(exact);
        this.suffixes = List.copyOf(suffixes);
        this.description = description;
    }

    /**
     * The policy for {@code configured} entries, or — when there are none — the
     * {@code fromAddress}'s domain and its subdomains. With neither, nothing is
     * allowed.
     *
     * @throws IllegalArgumentException
     *             when an entry is not a domain, {@code *.domain} or {@code *}
     */
    public static NotifyRecipientPolicy of(List<String> configured, String fromAddress) {
        List<String> entries = new ArrayList<>();
        if (configured != null) {
            for (String entry : configured) {
                if (entry != null && !entry.isBlank()) {
                    entries.add(entry.strip());
                }
            }
        }
        if (entries.isEmpty()) {
            Optional<String> from = fromAddress == null ? Optional.empty() : AddressNormalizer.tryNormalize(fromAddress);
            if (from.isEmpty()) {
                return new NotifyRecipientPolicy(false, List.of(), List.of(), "no domain (eddi.mail.smtp.from is not set)");
            }
            String domain = from.get().substring(from.get().lastIndexOf('@') + 1);
            return new NotifyRecipientPolicy(false, List.of(domain), List.of("." + domain),
                    domain + " and its subdomains (the default: the domain of eddi.mail.smtp.from)");
        }
        boolean any = false;
        List<String> exact = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        for (String entry : entries) {
            if ("*".equals(entry)) {
                any = true;
                continue;
            }
            boolean wildcard = entry.startsWith("*.");
            String domain;
            try {
                domain = AddressNormalizer.normalizeDomain(wildcard ? entry.substring(2) : entry);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(ALLOWED_DOMAINS + " entry '" + entry + "' is not a domain, *.domain or *: " + e.getMessage(), e);
            }
            if (wildcard) {
                suffixes.add("." + domain);
            } else {
                exact.add(domain);
            }
        }
        return new NotifyRecipientPolicy(any, exact, suffixes, any ? "any domain (*)" : String.join(", ", entries));
    }

    /**
     * The policy the deployment's configuration states right now, and whether a
     * system mailer is configured at all — for save-time validation, which runs in
     * static code with no injection.
     */
    public static Current current() {
        Config config;
        try {
            config = ConfigProvider.getConfig();
        } catch (RuntimeException e) {
            return new Current(false, of(List.of(), null));
        }
        Optional<String> host = config.getOptionalValue(SMTP_HOST, String.class).filter(h -> !h.isBlank());
        Optional<String> from = config.getOptionalValue(SMTP_FROM, String.class);
        List<String> domains = config.getOptionalValues(ALLOWED_DOMAINS, String.class).orElse(List.of());
        return new Current(host.isPresent(), of(domains, from.orElse(null)));
    }

    /**
     * @param mailerConfigured
     *            whether {@code eddi.mail.smtp.host} is set
     * @param policy
     *            the recipient policy
     */
    public record Current(boolean mailerConfigured, NotifyRecipientPolicy policy) {
    }

    /** Whether {@code normalizedAddress} (lower-case, ASCII) may receive it. */
    public boolean allows(String normalizedAddress) {
        if (normalizedAddress == null || normalizedAddress.lastIndexOf('@') < 0) {
            return false;
        }
        if (any) {
            return true;
        }
        String domain = normalizedAddress.substring(normalizedAddress.lastIndexOf('@') + 1);
        if (exact.contains(domain)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (domain.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** What the policy allows, for messages and logs. */
    public String describe() {
        return description;
    }

    @Override
    public String toString() {
        return "NotifyRecipientPolicy[" + description + "]";
    }
}
