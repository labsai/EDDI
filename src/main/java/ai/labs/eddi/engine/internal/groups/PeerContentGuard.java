/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

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
    static final String BROKEN_FENCE = "`​``";

    private static final String ENTRY_CUT_NOTE = "\n[... %d more characters not shown]";
    private static final String DIGEST_CUT_NOTE = "[%d earlier entries not shown: the quoted discussion is over %d characters]";

    /** Template-data keys holding a single quoted response. */
    private static final List<String> SINGLE_VALUE_KEYS = List.of("targetResponse", "originalResponse");

    /** Template-data map keys naming who wrote an entry. */
    private static final List<String> SPEAKER_KEYS = List.of("speaker", "reviewer");

    private PeerContentGuard() {
    }

    /**
     * Cleans every quoted value in {@code data} in place: the single responses
     * ({@code targetResponse}, {@code originalResponse}) and every list of entries,
     * recognised as a list of maps carrying a {@code content} key.
     */
    static void apply(Map<String, Object> data) {
        for (String key : SINGLE_VALUE_KEYS) {
            if (data.get(key) instanceof String value) {
                data.put(key, content(value));
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
        String safe = text.replace(FENCE, BROKEN_FENCE);
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
     * {@code name} on one line: line breaks and other control characters become
     * spaces, fences are broken, and it is cut at {@link #MAX_SPEAKER_CHARS}.
     */
    static String speaker(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), MAX_SPEAKER_CHARS));
        for (int i = 0; i < name.length() && sb.length() < MAX_SPEAKER_CHARS; i++) {
            char c = name.charAt(i);
            sb.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? ' ' : c);
        }
        if (!sb.isEmpty() && Character.isHighSurrogate(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString().replace(FENCE, BROKEN_FENCE).strip();
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
            int length = contentLength(cleaned.get(firstKept - 1));
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

    private static int contentLength(Map<String, Object> entry) {
        return entry.get("content") instanceof String text ? text.length() : 0;
    }
}
