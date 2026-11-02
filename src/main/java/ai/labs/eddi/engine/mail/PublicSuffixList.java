/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Public Suffix List, for finding a domain's organisational domain (RFC
 * 7489 §3.2) when checking DMARC alignment.
 * <p>
 * The list is bundled as {@code /mail/public_suffix_list.dat} and read once;
 * nothing is fetched at runtime. It is the Public Suffix List project's file,
 * unmodified, under the Mozilla Public License 2.0
 * ({@code licenses/MPL-2.0.txt}). Which upstream commit it is, and its SHA-256,
 * are recorded in {@code /mail/public_suffix_list.properties}; {@code
 * scripts/update-public-suffix-list.py} refreshes both.
 * <p>
 * Both sections of the list are used, ICANN and private: {@code github.io} is a
 * public suffix, so {@code alice.github.io} and {@code mallory.github.io} are
 * different organisations and must not align. Rules in Unicode are converted to
 * their ASCII (IDNA) form, the form {@link AddressNormalizer} gives every
 * address. The matching algorithm is the list's own (publicsuffix.org/list): an
 * exception rule ({@code !city.kawasaki.jp}) prevails over everything, then the
 * matching rule with the most labels, a wildcard ({@code *.kawasaki.jp})
 * matching exactly one label; with no match, the rule is {@code *}, so an
 * unknown top-level domain is a public suffix of its own.
 * <p>
 * Immutable and thread-safe.
 */
public final class PublicSuffixList {

    /** Where the bundled list lives on the class path. */
    public static final String RESOURCE = "/mail/public_suffix_list.dat";

    private static final int MAX_DOMAIN_LENGTH = 253;

    private static final class Holder {
        private static final PublicSuffixList BUNDLED = loadBundled();
    }

    private final Set<String> rules;
    private final Set<String> exceptions;

    private PublicSuffixList(Set<String> rules, Set<String> exceptions) {
        this.rules = Set.copyOf(rules);
        this.exceptions = Set.copyOf(exceptions);
    }

    /** The bundled list, loaded on first use. */
    public static PublicSuffixList bundled() {
        return Holder.BUNDLED;
    }

    private static PublicSuffixList loadBundled() {
        try (InputStream in = PublicSuffixList.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The Public Suffix List " + RESOURCE + " is missing from the class path.");
            }
            PublicSuffixList list = parse(in);
            if (list.size() == 0) {
                throw new IllegalStateException("The Public Suffix List " + RESOURCE + " holds no rules.");
            }
            return list;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the Public Suffix List " + RESOURCE, e);
        }
    }

    /**
     * Parses a list in the publicsuffix.org format: one rule per line, {@code //}
     * comments, blank lines ignored, only the text up to the first whitespace
     * counts.
     */
    public static PublicSuffixList parse(InputStream in) throws IOException {
        Set<String> rules = new HashSet<>();
        Set<String> exceptions = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String rule = line.strip();
                if (rule.isEmpty() || rule.startsWith("//")) {
                    continue;
                }
                int space = indexOfWhitespace(rule);
                if (space > 0) {
                    rule = rule.substring(0, space);
                }
                boolean exception = rule.startsWith("!");
                String body = exception ? rule.substring(1) : rule;
                String ascii = toAsciiRule(body);
                if (ascii == null) {
                    continue;
                }
                (exception ? exceptions : rules).add(ascii);
            }
        }
        return new PublicSuffixList(rules, exceptions);
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String toAsciiRule(String rule) {
        boolean wildcard = rule.startsWith("*.");
        String rest = wildcard ? rule.substring(2) : rule;
        String ascii = toAscii(rest);
        if (ascii == null) {
            return null;
        }
        return wildcard ? "*." + ascii : ascii;
    }

    /** Lower-case ASCII (IDNA) form, or null when the name is not a domain. */
    static String toAscii(String domain) {
        if (domain == null) {
            return null;
        }
        String name = domain.strip();
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty() || name.length() > MAX_DOMAIN_LENGTH * 4) {
            return null;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(name, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (ascii.isEmpty() || ascii.length() > MAX_DOMAIN_LENGTH || ascii.startsWith(".") || ascii.contains("..")) {
            return null;
        }
        return ascii;
    }

    /** Number of rules, exceptions included. */
    public int size() {
        return rules.size() + exceptions.size();
    }

    /**
     * The public suffix of a domain ({@code co.uk} for {@code mail.example.co.uk}),
     * or null when the name is not a domain.
     */
    public String publicSuffix(String domain) {
        String ascii = toAscii(domain);
        if (ascii == null) {
            return null;
        }
        String[] labels = ascii.split("\\.", -1);
        int suffixLabels = suffixLabelCount(labels);
        return join(labels, labels.length - suffixLabels);
    }

    /**
     * The organisational domain (RFC 7489 §3.2): the public suffix plus one label —
     * {@code example.co.uk} for {@code mail.example.co.uk}. A name that is itself a
     * public suffix is its own organisational domain (so {@code co.uk} aligns with
     * nothing but {@code co.uk}). Null when the name is not a domain.
     */
    public String organizationalDomain(String domain) {
        String ascii = toAscii(domain);
        if (ascii == null) {
            return null;
        }
        String[] labels = ascii.split("\\.", -1);
        int suffixLabels = suffixLabelCount(labels);
        if (suffixLabels >= labels.length) {
            return ascii;
        }
        return join(labels, labels.length - suffixLabels - 1);
    }

    /** How many trailing labels form the public suffix; at least one. */
    private int suffixLabelCount(String[] labels) {
        int n = labels.length;
        // An exception rule prevails over every other rule; its suffix is the rule
        // minus its leftmost label.
        for (int i = 0; i < n; i++) {
            if (exceptions.contains(join(labels, i))) {
                return n - i - 1;
            }
        }
        // Otherwise the matching rule with the most labels: scanning from the
        // longest candidate, the first hit is it.
        for (int i = 0; i < n; i++) {
            if (rules.contains(join(labels, i))) {
                return n - i;
            }
            if (i + 1 < n && rules.contains("*." + join(labels, i + 1))) {
                return n - i;
            }
        }
        // The implicit rule "*".
        return 1;
    }

    private static String join(String[] labels, int from) {
        return String.join(".", Arrays.asList(labels).subList(from, labels.length));
    }
}
