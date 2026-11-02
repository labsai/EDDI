/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.AddressNormalizer;

import java.util.List;

/**
 * Matches a sender against {@code senderAllowlist} / {@code senderBlocklist}
 * entries: {@code user@domain} (exact, after normalisation), {@code *@domain}
 * (that domain only) or {@code *@*.domain} (its subdomains, not the domain
 * itself). An entry that does not parse matches nothing — save-time validation
 * ({@link EmailChannelSettings.SenderPatternSyntax}) refuses it, so that only
 * happens for a document written around the validation.
 * <p>
 * The address matched is the mail's {@code From}. Without
 * {@code requireAuthentication} that is a claim anyone can make, so an
 * allowlist on an unauthenticated integration filters mistakes, not attackers.
 */
public final class SenderPatterns {

    private SenderPatterns() {
    }

    /** Whether {@code normalizedSender} matches any of {@code entries}. */
    public static boolean matchesAny(String normalizedSender, List<String> entries) {
        if (normalizedSender == null || entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (matches(normalizedSender, entry)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String normalizedSender, String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        int at = normalizedSender.lastIndexOf('@');
        if (at <= 0) {
            return false;
        }
        String senderDomain = normalizedSender.substring(at + 1);
        String trimmed = entry.trim();
        try {
            if (trimmed.startsWith("*@*.")) {
                String domain = AddressNormalizer.normalizeDomain(trimmed.substring(4));
                return senderDomain.endsWith("." + domain);
            }
            if (trimmed.startsWith("*@")) {
                return senderDomain.equals(AddressNormalizer.normalizeDomain(trimmed.substring(2)));
            }
            return normalizedSender.equals(AddressNormalizer.normalize(trimmed));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
