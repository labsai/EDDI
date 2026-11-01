/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message-ID handling: reading the ids out of {@code Message-ID},
 * {@code In-Reply-To} and {@code References}, and minting EDDI's own.
 * <p>
 * EDDI's ids are random ({@code <eddi.<uuid>@<domain>>}, email plan §11): a
 * thread is mapped by looking the id up in the ledger, so the id needs to be
 * unique, not meaningful, and a random one leaks nothing about the conversation
 * it belongs to.
 */
public final class MessageIds {

    /**
     * Longest id accepted, angle brackets included. RFC 5322 has no limit; real ids
     * are far below this.
     */
    public static final int MAX_ID_LENGTH = 250;

    /** References kept when reading a header: the root plus the most recent. */
    public static final int MAX_PARSED_REFERENCES = 100;

    /**
     * Printable ASCII without space, angle brackets or quotes, on both sides of the
     * {@code @}.
     */
    private static final Pattern BRACKETED = Pattern.compile("<([\\x21-\\x7E&&[^<>\"@]]+@[\\x21-\\x7E&&[^<>\"]]+)>");

    private static final Pattern BARE = Pattern.compile("(?<![\\x21-\\x7E])([\\x21-\\x7E&&[^<>\"@,;]]+@[\\x21-\\x7E&&[^<>\"@,;]]+)(?![\\x21-\\x7E])");

    private MessageIds() {
    }

    /** The first id in a header value, as {@code <left@right>}. */
    public static Optional<String> parseFirst(String headerValue) {
        List<String> ids = parseAll(headerValue, 1);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * Every id in a header value, in order, deduplicated, capped at {@code cap} by
     * keeping the first and the most recent {@code cap - 1}. Bare ids without angle
     * brackets are accepted only when the header holds no bracketed one: some
     * mailers drop the brackets, but a bracketed list with stray text between ids
     * must not grow extra ids out of that text.
     */
    public static List<String> parseAll(String headerValue, int cap) {
        if (headerValue == null || headerValue.isBlank() || cap <= 0) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher bracketed = BRACKETED.matcher(headerValue);
        while (bracketed.find()) {
            addIfValid(ids, "<" + bracketed.group(1) + ">");
        }
        if (ids.isEmpty()) {
            Matcher bare = BARE.matcher(headerValue);
            while (bare.find()) {
                addIfValid(ids, "<" + bare.group(1) + ">");
            }
        }
        return cap(new ArrayList<>(ids), cap);
    }

    private static void addIfValid(Set<String> ids, String id) {
        if (isValid(id)) {
            ids.add(id);
        }
    }

    /** Keeps the first id and the last {@code cap - 1}. */
    public static List<String> cap(List<String> ids, int cap) {
        if (ids.size() <= cap) {
            return List.copyOf(ids);
        }
        List<String> capped = new ArrayList<>(cap);
        capped.add(ids.get(0));
        capped.addAll(ids.subList(ids.size() - (cap - 1), ids.size()));
        return List.copyOf(capped);
    }

    /**
     * Whether {@code id} is a well-formed {@code <left@right>} id this code would
     * write.
     */
    public static boolean isValid(String id) {
        return id != null && id.length() <= MAX_ID_LENGTH && BRACKETED.matcher(id).matches();
    }

    /**
     * A new random id for a message sent from {@code domain}.
     *
     * @throws IllegalArgumentException
     *             when the domain is not a valid mail domain
     */
    public static String generate(String domain) {
        return "<eddi." + UUID.randomUUID() + "@" + AddressNormalizer.normalizeDomain(domain) + ">";
    }
}
