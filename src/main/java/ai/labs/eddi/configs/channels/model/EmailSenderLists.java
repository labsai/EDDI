/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.engine.mail.AddressNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The targeted edit of an email integration's sender lists — {@code GET} and
 * {@code POST /channelstore/channels/{id}/email-senders} and the MCP tools
 * {@code list_email_senders} / {@code update_email_senders} (email plan v3
 * §5.1).
 * <p>
 * The lists stay where they always were, the {@code senderAllowlist} and
 * {@code senderBlocklist} keys of {@code platformConfig}. {@link #apply} is the
 * pure part of an edit: it compares entries in their normalised form, reports
 * every entry that changes nothing instead of failing on it, and refuses what
 * would leave the lists invalid. Storing the result is the channel store's
 * ordinary update, with its validation, warnings and poll re-registration.
 */
public final class EmailSenderLists {

    /**
     * Most characters of a change's {@code reason}; a longer one is cut, not
     * refused.
     */
    public static final int MAX_REASON_LENGTH = 500;

    /**
     * The allowlist was emptied with {@code confirmOpen}: every sender may now make
     * the agent run (subject only to {@code requireAuthentication}).
     */
    public static final String WARNING_ALLOWLIST_EMPTIED = "email.allowlist-emptied";

    private EmailSenderLists() {
    }

    /** The sender lists of one version of an integration. */
    public record View(Integer version, List<String> allow, List<String> block) {
    }

    /** Entries for one direction of a change. */
    public record Entries(List<String> allow, List<String> block) {
    }

    /**
     * The body of {@code POST .../email-senders}.
     *
     * @param expectedVersion
     *            the version the caller read; a different current version is a
     *            {@code 409}
     * @param add
     *            entries to add to each list
     * @param remove
     *            entries to remove from each list
     * @param reason
     *            why, recorded in the audit ledger
     * @param confirmOpen
     *            required to remove the last allowlist entry, which opens the
     *            integration to every sender
     */
    public record UpdateRequest(Integer expectedVersion, Entries add, Entries remove, String reason, Boolean confirmOpen) {
    }

    /**
     * The answer to an edit.
     *
     * @param previousVersion
     *            the version the edit was made on
     * @param version
     *            the version now current — the same as {@code previousVersion} when
     *            nothing changed
     * @param changed
     *            whether a new version was written
     * @param added
     *            entries added, as {@code allow:<entry>} / {@code block:<entry>}
     * @param removed
     *            entries removed, in the same form
     * @param unchanged
     *            entries that changed nothing (already listed, or not listed), in
     *            the same form
     * @param warnings
     *            save-time warning codes
     */
    public record UpdateResult(Integer previousVersion, Integer version, boolean changed, List<String> allow, List<String> block,
            List<String> added, List<String> removed, List<String> unchanged, List<String> warnings) {
    }

    /** What {@link #apply} computed: the new lists and the diff. */
    public record Change(List<String> allow, List<String> block, List<String> added, List<String> removed, List<String> unchanged,
            boolean allowlistEmptied) {

        /** Whether the lists differ from the ones the edit started from. */
        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    /** An edit that cannot be made; each message fits a {@code 400}. */
    public static final class InvalidChange extends Exception {
        private final List<String> errors;

        public InvalidChange(List<String> errors) {
            super(String.join(" ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    /** The lists as {@code platformConfig} stores them. */
    public static View read(Integer version, Map<String, String> platformConfig) {
        Map<String, String> pc = platformConfig == null ? Map.of() : platformConfig;
        return new View(version, EmailChannelSettings.list(pc.get(EmailChannelSettings.KEY_SENDER_ALLOWLIST)),
                EmailChannelSettings.list(pc.get(EmailChannelSettings.KEY_SENDER_BLOCKLIST)));
    }

    /**
     * Applies {@code request} to the current lists.
     *
     * @throws InvalidChange
     *             an entry that is not a sender pattern, an entry both added and
     *             removed, an entry left in both lists, a list over
     *             {@value EmailChannelSettings#MAX_LIST_ENTRIES} entries, or the
     *             allowlist emptied without {@code confirmOpen}
     */
    public static Change apply(List<String> currentAllow, List<String> currentBlock, UpdateRequest request) throws InvalidChange {
        List<String> errors = new ArrayList<>();
        Entries add = request.add() == null ? new Entries(List.of(), List.of()) : request.add();
        Entries remove = request.remove() == null ? new Entries(List.of(), List.of()) : request.remove();
        List<String> addAllow = normaliseAll(add.allow(), "add.allow", errors);
        List<String> addBlock = normaliseAll(add.block(), "add.block", errors);
        List<String> removeAllow = normaliseAll(remove.allow(), "remove.allow", errors);
        List<String> removeBlock = normaliseAll(remove.block(), "remove.block", errors);
        bothWays(addAllow, removeAllow, "allow", errors);
        bothWays(addBlock, removeBlock, "block", errors);
        if (!errors.isEmpty()) {
            throw new InvalidChange(errors);
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Map<String, String> allow = keyed(currentAllow);
        Map<String, String> block = keyed(currentBlock);
        boolean wasAllowlisted = !allow.isEmpty();
        edit(allow, addAllow, removeAllow, "allow", added, removed, unchanged);
        edit(block, addBlock, removeBlock, "block", added, removed, unchanged);

        for (String key : allow.keySet()) {
            if (block.containsKey(key)) {
                errors.add("'" + allow.get(key) + "' would be on both the allowlist and the blocklist; remove it from one of them.");
            }
        }
        if (allow.size() > EmailChannelSettings.MAX_LIST_ENTRIES) {
            errors.add("The allowlist would have " + allow.size() + " entries; at most " + EmailChannelSettings.MAX_LIST_ENTRIES + ".");
        }
        if (block.size() > EmailChannelSettings.MAX_LIST_ENTRIES) {
            errors.add("The blocklist would have " + block.size() + " entries; at most " + EmailChannelSettings.MAX_LIST_ENTRIES + ".");
        }
        boolean emptied = wasAllowlisted && allow.isEmpty();
        if (emptied && !Boolean.TRUE.equals(request.confirmOpen())) {
            errors.add("This removes the last allowlist entry, and an empty allowlist lets every sender make the agent run. Send "
                    + "confirmOpen: true if that is what you want, or add the senders who should stay allowed.");
        }
        if (!errors.isEmpty()) {
            throw new InvalidChange(errors);
        }
        return new Change(List.copyOf(allow.values()), List.copyOf(block.values()), added, removed, unchanged, emptied);
    }

    /**
     * {@code platformConfig} with the lists replaced, as a new map; an empty list
     * removes its key.
     */
    public static Map<String, String> withLists(Map<String, String> platformConfig, List<String> allow, List<String> block) {
        Map<String, String> pc = platformConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(platformConfig);
        put(pc, EmailChannelSettings.KEY_SENDER_ALLOWLIST, allow);
        put(pc, EmailChannelSettings.KEY_SENDER_BLOCKLIST, block);
        return pc;
    }

    /** The reason as recorded: trimmed, control characters dropped, capped. */
    public static String cleanReason(String reason) {
        if (reason == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < reason.length() && out.length() < MAX_REASON_LENGTH; i++) {
            char c = reason.charAt(i);
            out.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        String cleaned = out.toString().strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * The normalised form of a sender pattern: a lower-case address, or
     * {@code *@domain} / {@code *@*.domain} with the domain normalised.
     *
     * @throws IllegalArgumentException
     *             for anything that is not a sender pattern
     */
    public static String normalise(String entry) {
        if (entry == null || !EmailChannelSettings.SenderPatternSyntax.isValid(entry)) {
            throw new IllegalArgumentException("not a sender pattern");
        }
        String trimmed = entry.trim();
        if (trimmed.startsWith("*@*.")) {
            return "*@*." + AddressNormalizer.normalizeDomain(trimmed.substring(4));
        }
        if (trimmed.startsWith("*@")) {
            return "*@" + AddressNormalizer.normalizeDomain(trimmed.substring(2));
        }
        return AddressNormalizer.normalize(trimmed);
    }

    private static List<String> normaliseAll(List<String> entries, String field, List<String> errors) {
        Set<String> out = new LinkedHashSet<>();
        if (entries == null) {
            return List.of();
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                out.add(normalise(entry));
            } catch (IllegalArgumentException e) {
                errors.add(field + ": '" + clip(entry) + "' must be 'user@domain', '*@domain' or '*@*.domain'.");
            }
        }
        return List.copyOf(out);
    }

    private static void bothWays(List<String> adds, List<String> removes, String list, List<String> errors) {
        for (String entry : adds) {
            if (removes.contains(entry)) {
                errors.add("'" + entry + "' is both added to and removed from the " + list + "list.");
            }
        }
    }

    /**
     * The current entries by their normalised form, in order. An entry stored
     * around the validation keeps its raw text as key, so it can still be removed
     * verbatim.
     */
    private static Map<String, String> keyed(List<String> entries) {
        Map<String, String> keyed = new LinkedHashMap<>();
        if (entries == null) {
            return keyed;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String key;
            try {
                key = normalise(entry);
            } catch (IllegalArgumentException e) {
                key = entry.trim().toLowerCase(Locale.ROOT);
            }
            keyed.putIfAbsent(key, key);
        }
        return keyed;
    }

    private static void edit(Map<String, String> list, List<String> adds, List<String> removes, String name, List<String> added,
                             List<String> removed, List<String> unchanged) {
        for (String entry : removes) {
            if (list.remove(entry) != null) {
                removed.add(name + ":" + entry);
            } else {
                unchanged.add(name + ":" + entry);
            }
        }
        for (String entry : adds) {
            if (list.putIfAbsent(entry, entry) == null) {
                added.add(name + ":" + entry);
            } else {
                unchanged.add(name + ":" + entry);
            }
        }
    }

    private static void put(Map<String, String> pc, String key, List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            pc.remove(key);
        } else {
            pc.put(key, String.join(",", entries));
        }
    }

    private static String clip(String value) {
        String stripped = value.strip();
        return stripped.length() > 80 ? stripped.substring(0, 80) + "…" : stripped;
    }
}
