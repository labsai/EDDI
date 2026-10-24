/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the addresses a message carries into the one form EDDI compares.
 * <p>
 * The rules (email plan §9) are deliberately few:
 * <ul>
 * <li><b>Lower-case the whole address.</b> RFC 5321 lets a local part be case
 * sensitive, but no mainstream provider makes {@code Alice@} and {@code alice@}
 * different people, and treating them as two would split one correspondent's
 * memory in half.</li>
 * <li><b>IDN → ASCII</b> for the domain, so {@code bücher.example} and
 * {@code xn--bcher-kva.example} are one domain.</li>
 * <li><b>No dot or {@code +tag} folding.</b> That is Gmail's rule, not the
 * internet's; applied everywhere it merges people who are distinct.</li>
 * <li><b>Refuse what is not an addr-spec</b> instead of repairing it: no domain
 * literals ({@code [192.0.2.1]}), no single-label or numeric-TLD domains, no
 * control characters, no unbalanced quotes. An address that is only "probably"
 * an address is not something to key an identity on.</li>
 * </ul>
 * A non-ASCII local part (RFC 6532) is accepted here; whether one can be
 * <em>sent</em> to is the renderer's decision (it cannot without SMTPUTF8).
 */
public final class AddressNormalizer {

    private static final int MAX_ADDRESS_LENGTH = 254;
    private static final int MAX_LOCAL_LENGTH = 64;
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final int MAX_DISPLAY_NAME_LENGTH = 256;

    /**
     * RFC 5322 atext, plus any non-ASCII code point (RFC 6532 UTF8-non-ascii).
     * Controls are excluded separately.
     */
    private static final Pattern DOT_ATOM = Pattern
            .compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~\\-\\u0080-\\x{10FFFF}]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~\\-\\u0080-\\x{10FFFF}]+)*");

    /** A quoted local part: printable ASCII and escapes, no bare quote. */
    private static final Pattern QUOTED_LOCAL = Pattern.compile("\"(?:[\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]|\\\\[\\x20-\\x7E])*\"");

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private AddressNormalizer() {
    }

    /**
     * Normalises one bare addr-spec ({@code local@domain}, no display name, no
     * angle brackets).
     *
     * @throws IllegalArgumentException
     *             naming the rule the input broke — never echoing the input, which
     *             is personal data
     */
    public static String normalize(String addrSpec) {
        if (addrSpec == null) {
            throw new IllegalArgumentException("address is missing");
        }
        String candidate = addrSpec.strip();
        if (candidate.isEmpty() || candidate.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("address is empty or longer than " + MAX_ADDRESS_LENGTH + " characters");
        }
        if (containsControl(candidate)) {
            throw new IllegalArgumentException("address contains a control character");
        }
        int at = candidate.lastIndexOf('@');
        if (at <= 0 || at == candidate.length() - 1) {
            throw new IllegalArgumentException("address has no local part or no domain");
        }
        String local = candidate.substring(0, at);
        String domain = candidate.substring(at + 1);
        if (local.length() > MAX_LOCAL_LENGTH) {
            throw new IllegalArgumentException("local part is longer than " + MAX_LOCAL_LENGTH + " characters");
        }
        if (!DOT_ATOM.matcher(local).matches() && !QUOTED_LOCAL.matcher(local).matches()) {
            throw new IllegalArgumentException("local part is not a dot-atom or a quoted string");
        }
        return local.toLowerCase(Locale.ROOT) + "@" + normalizeDomain(domain);
    }

    /** {@link #normalize(String)} without the exception. */
    public static Optional<String> tryNormalize(String addrSpec) {
        try {
            return Optional.of(normalize(addrSpec));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The ASCII, lower-case form of a mail domain.
     *
     * @throws IllegalArgumentException
     *             for a domain literal, an invalid label, a single label or a
     *             numeric top-level label
     */
    public static String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain is missing");
        }
        String candidate = domain.strip();
        if (candidate.startsWith("[")) {
            throw new IllegalArgumentException("domain literals are not accepted");
        }
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("domain is not a valid internationalised domain name");
        }
        if (ascii.isEmpty() || ascii.length() > MAX_DOMAIN_LENGTH) {
            throw new IllegalArgumentException("domain is empty or longer than " + MAX_DOMAIN_LENGTH + " characters");
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("domain has a single label");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("domain has an invalid label");
            }
        }
        if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
            // No top-level domain is numeric; a numeric one is an IP address in
            // disguise (192.0.2.1 parses as four labels).
            throw new IllegalArgumentException("domain has a numeric top-level label");
        }
        return ascii;
    }

    /**
     * Parses one header value that should hold exactly one mailbox ({@code From},
     * {@code Sender}).
     *
     * @return the mailbox, or empty when there is none, it is invalid, or there is
     *         more than one — the caller cannot tell which of several was meant
     */
    public static Optional<MailAddress> parseSingle(String headerValue) {
        Parsed parsed = parseList(headerValue);
        if (parsed.addresses().size() != 1 || parsed.invalid() > 0) {
            return Optional.empty();
        }
        return Optional.of(parsed.addresses().get(0));
    }

    /**
     * Parses an address-list header ({@code To}, {@code Cc}, {@code Reply-To}),
     * leniently: an entry that is not a valid mailbox is counted and skipped, the
     * rest are kept. Groups are flattened into their members.
     */
    public static Parsed parseList(String headerValue) {
        List<MailAddress> addresses = new ArrayList<>();
        if (headerValue == null || headerValue.isBlank()) {
            return new Parsed(addresses, 0);
        }
        InternetAddress[] parsed;
        try {
            parsed = InternetAddress.parseHeader(headerValue, false);
        } catch (AddressException e) {
            return new Parsed(addresses, 1);
        }
        int invalid = 0;
        for (InternetAddress entry : parsed) {
            invalid += collect(entry, addresses);
        }
        return new Parsed(addresses, invalid);
    }

    private static int collect(InternetAddress entry, List<MailAddress> into) {
        if (entry.isGroup()) {
            int invalid = 0;
            try {
                InternetAddress[] members = entry.getGroup(false);
                if (members != null) {
                    for (InternetAddress member : members) {
                        if (!member.isGroup()) {
                            invalid += collect(member, into);
                        }
                    }
                }
            } catch (AddressException e) {
                invalid++;
            }
            return invalid;
        }
        Optional<String> normalized = tryNormalize(entry.getAddress());
        if (normalized.isEmpty()) {
            return 1;
        }
        into.add(new MailAddress(normalized.get(), cleanDisplayName(entry.getPersonal())));
        return 0;
    }

    /**
     * A display name, decoded when the library could not, with controls replaced
     * and its length capped. It is shown to the agent, so a name that smuggles line
     * breaks must not be able to forge a header-like line.
     */
    static String cleanDisplayName(String personal) {
        if (personal == null) {
            return null;
        }
        String decoded = personal.contains("=?") ? EncodedWords.decode(personal) : personal;
        StringBuilder cleaned = new StringBuilder(Math.min(decoded.length(), MAX_DISPLAY_NAME_LENGTH));
        for (int i = 0; i < decoded.length() && cleaned.length() < MAX_DISPLAY_NAME_LENGTH; i++) {
            char c = decoded.charAt(i);
            cleaned.append(Character.isISOControl(c) || c == (char) 0x2028 || c == (char) 0x2029 ? ' ' : c);
        }
        String result = cleaned.toString().strip().replaceAll("\\s{2,}", " ");
        return result.isEmpty() ? null : result;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c) && c != ' ' || c == (char) 0x2028 || c == (char) 0x2029) {
                return true;
            }
        }
        return false;
    }

    /**
     * The result of parsing an address list.
     *
     * @param addresses
     *            the valid mailboxes, in header order
     * @param invalid
     *            how many entries were skipped as invalid
     */
    public record Parsed(List<MailAddress> addresses, int invalid) {
        public Parsed {
            addresses = List.copyOf(addresses);
        }
    }
}
