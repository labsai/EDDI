/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes other participants' text safe to quote in a group member's prompt.
 * <p>
 * A member's input quotes what its peers said, and a peer is not necessarily
 * trustworthy: a dynamic agent, a recruited agent or a human member writes
 * whatever it likes, and a model can be talked into writing anything. The
 * preset templates put that text between {@code ```} fences, so the member can
 * tell quoted material from the instructions around it. This class keeps the
 * fences meaningful and the prompt bounded:
 * <ul>
 * <li>a {@code ```} inside quoted text is broken with a zero-width space, so it
 * cannot close the fence early and continue as if it were the template
 * talking;</li>
 * <li>a speaker name is reduced to one line, so it cannot open a new
 * paragraph;</li>
 * <li>one entry is cut at {@link #MAX_ENTRY_CHARS} and one list of entries at
 * {@link #MAX_DIGEST_CHARS}, dropping the oldest entries first, so one verbose
 * or hostile participant cannot crowd everyone else out of the context.</li>
 * </ul>
 * The stored transcript is never changed: this shapes only what is rendered
 * into a prompt. Custom {@code inputTemplate}s receive the same cleaned values;
 * whether they fence them is the template author's choice.
 */
final class PeerContentGuard {

    /** Characters of one quoted entry before it is cut (~3k tokens). */
    static final int MAX_ENTRY_CHARS = 12_000;

    /**
     * Characters of one list of quoted entries before the oldest are dropped (~24k
     * tokens). Large on purpose: a SYNTHESIS reads the whole discussion, and a
     * normal panel stays well below it. It is a bound against runaway input, not a
     * context-management strategy — that is the I9 context window.
     */
    static final int MAX_DIGEST_CHARS = 96_000;

    /** Characters of a speaker name. */
    static final int MAX_SPEAKER_CHARS = 120;

    static final String FENCE = "```";
    /** {@link #FENCE} with a zero-width space (U+200B) after its first backtick. */
    static final String BROKEN_FENCE = "`​``";
    /** The character {@link #BROKEN_FENCE} carries after its first backtick. */
    private static final char ZERO_WIDTH_SPACE = BROKEN_FENCE.charAt(1);

    private static final String ENTRY_CUT_NOTE = "\n[... %d more characters not shown]";
    private static final String DIGEST_CUT_NOTE = "[%d earlier entries not shown: the quoted discussion is over %d characters]";

    /** Template-data keys holding a single quoted response. */
    private static final List<String> SINGLE_VALUE_KEYS = List.of("targetResponse", "originalResponse");

    /** Template-data map keys naming who wrote an entry. */
    private static final List<String> SPEAKER_KEYS = List.of("speaker", "reviewer");

    /**
     * Top-level template-data keys holding a participant's name. Both are spliced
     * into the instructions themselves ("You are reviewing {targetName}'s…", "As
     * {displayName}, …"), and either can be a dynamic agent's or a human member's
     * chosen name.
     */
    private static final List<String> NAME_KEYS = List.of("targetName", "displayName");

    private PeerContentGuard() {
    }

    /**
     * Cleans every quoted value in {@code data} in place: the single responses
     * ({@code targetResponse}, {@code originalResponse}), the participant names
     * ({@code targetName}, {@code displayName}) and every list of entries,
     * recognised as a list of maps carrying a {@code content} key.
     */
    static void apply(Map<String, Object> data) {
        for (String key : SINGLE_VALUE_KEYS) {
            if (data.get(key) instanceof String value) {
                data.put(key, content(value));
            }
        }
        for (String key : NAME_KEYS) {
            if (data.get(key) instanceof String value) {
                data.put(key, speaker(value));
            }
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getValue() instanceof List<?> list && isEntryList(list)) {
                e.setValue(digest(list));
            }
        }
    }

    /** {@code text} with fences broken and cut at {@link #MAX_ENTRY_CHARS}. */
    static String content(String text) {
        if (text == null) {
            return null;
        }
        String safe = breakFences(text);
        if (safe.length() <= MAX_ENTRY_CHARS) {
            return safe;
        }
        int cut = MAX_ENTRY_CHARS;
        if (Character.isHighSurrogate(safe.charAt(cut - 1))) {
            cut--;
        }
        return safe.substring(0, cut) + ENTRY_CUT_NOTE.formatted(safe.length() - cut);
    }

    /**
     * {@code text} as a {@code ```} block with every fence inside it broken, for
     * the engine's own prompts (verifier, executor, bidder, judge, facilitator,
     * dissent, summarizer) that quote member-authored text. Not cut: those prompts
     * quote a task result or a synthesis that the reader must see whole, and they
     * have their own bounds where they need one.
     */
    static String fenced(String text) {
        return FENCE + "\n" + (text == null ? "" : breakFences(text)) + "\n" + FENCE;
    }

    /**
     * {@code text} with no three backticks in a row: every run of three or more is
     * broken with zero-width spaces ({@code ```} becomes {@link #BROKEN_FENCE}; a
     * longer run gets a zero-width space after its first backtick and after every
     * second one from there). {@code String.replace(FENCE, …)} was not enough — it
     * left a fence behind in a run of four or more, which then closed the quoted
     * block early. One linear pass, and idempotent: applied twice, the text does
     * not change, so a value broken once can be shown and matched again.
     */
    static String breakFences(String text) {
        if (text == null || !text.contains(FENCE)) {
            return text;
        }
        var sb = new StringBuilder(text.length() + 8);
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (text.charAt(i) != '`') {
                sb.append(text.charAt(i++));
                continue;
            }
            int end = i;
            while (end < length && text.charAt(end) == '`') {
                end++;
            }
            int run = end - i;
            sb.append('`');
            for (int k = 1; k < run; k++) {
                if (run >= 3 && k % 2 == 1) {
                    sb.append(ZERO_WIDTH_SPACE);
                }
                sb.append('`');
            }
            i = end;
        }
        return sb.toString();
    }

    /**
     * {@code prompt} opened by {@link DiscussionStylePresets#QUOTED_CONTENT_NOTICE}
     * — what tells the reader that {@link #fenced} text is quoted, not
     * instructions.
     */
    static String withQuotedNotice(String prompt) {
        return DiscussionStylePresets.QUOTED_CONTENT_NOTICE + "\n\n" + prompt;
    }

    /**
     * {@code name} on one line: line breaks and other control characters become
     * spaces, fences are broken, and it is cut at {@link #MAX_SPEAKER_CHARS}.
     * <p>
     * A name ending in {@link AgentGroupStore#NOT_AN_AGENT_SUFFIX} — how
     * {@code GroupSigningGuard} marks an entry no agent wrote for a member that
     * requires peer verification — keeps that suffix: the cut and the strip apply
     * to the part before it. Otherwise a long or blank-padded name would lose the
     * mark and read like a verified agent's.
     */
    static String speaker(String name) {
        if (name == null) {
            return null;
        }
        String suffix = AgentGroupStore.NOT_AN_AGENT_SUFFIX;
        if (name.endsWith(suffix)) {
            return (oneLine(name.substring(0, name.length() - suffix.length()), MAX_SPEAKER_CHARS - suffix.length()) + suffix).strip();
        }
        return oneLine(name, MAX_SPEAKER_CHARS);
    }

    private static String oneLine(String name, int maxChars) {
        StringBuilder sb = new StringBuilder(Math.min(name.length(), maxChars));
        for (int i = 0; i < name.length() && sb.length() < maxChars; i++) {
            char c = name.charAt(i);
            sb.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? ' ' : c);
        }
        if (!sb.isEmpty() && Character.isHighSurrogate(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
        return breakFences(sb.toString()).strip();
    }

    private static boolean isEntryList(List<?> list) {
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || !map.containsKey("content")) {
                return false;
            }
        }
        return !list.isEmpty();
    }

    /**
     * Each entry cleaned, then the oldest dropped until the rest fit in
     * {@link #MAX_DIGEST_CHARS}, with a note in their place. The newest entry is
     * always kept.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> digest(List<?> entries) {
        List<Map<String, Object>> cleaned = new ArrayList<>(entries.size());
        for (Object item : entries) {
            Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) item);
            if (entry.get("content") instanceof String text) {
                entry.put("content", content(text));
            }
            for (String key : SPEAKER_KEYS) {
                if (entry.get(key) instanceof String name) {
                    entry.put(key, speaker(name));
                }
            }
            cleaned.add(entry);
        }

        int total = 0;
        int firstKept = cleaned.size();
        while (firstKept > 0) {
            int length = renderedLength(cleaned.get(firstKept - 1));
            if (firstKept < cleaned.size() && total + length > MAX_DIGEST_CHARS) {
                break;
            }
            total += length;
            firstKept--;
        }
        if (firstKept == 0) {
            return cleaned;
        }

        Map<String, Object> note = new LinkedHashMap<>();
        for (String key : cleaned.get(firstKept).keySet()) {
            note.put(key, "");
        }
        for (String key : SPEAKER_KEYS) {
            if (note.containsKey(key)) {
                note.put(key, "System");
            }
        }
        note.put("content", DIGEST_CUT_NOTE.formatted(firstKept, MAX_DIGEST_CHARS));
        if (note.containsKey("phaseName")) {
            note.put("phaseName", "(earlier discussion)");
        }
        List<Map<String, Object>> result = new ArrayList<>(cleaned.size() - firstKept + 1);
        result.add(note);
        result.addAll(cleaned.subList(firstKept, cleaned.size()));
        return result;
    }

    /**
     * Every string an entry renders (content, speaker, phase name), so a run of
     * short entries under long names cannot outgrow the budget. Separator text
     * belongs to the template and is not counted.
     */
    private static int renderedLength(Map<String, Object> entry) {
        int length = 0;
        for (Object value : entry.values()) {
            if (value instanceof String text) {
                length += text.length();
            }
        }
        return length;
    }
}
