/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.Context;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.Map;

/**
 * The rules for a conversation's title: which user message a default title may
 * come from, how it is shortened, and what a title set by its owner may be.
 *
 * <h3>Never from a secret turn</h3> A title is shown in lists, exported and
 * kept as long as the conversation, so it must not carry anything the turn it
 * came from was told to keep secret. A turn is not used when:
 * <ul>
 * <li>the client flagged its input {@code secretInput} (a secret
 * {@code inputField} does that), which leaves the placeholder in place of the
 * input;</li>
 * <li>a {@code scope: "secret"} property vaulted the input, which replaces it
 * (or the part of it the property captured) with the placeholder;</li>
 * <li>its input carries a secret context value's placeholder;</li>
 * <li>an input guardrail withheld it.</li>
 * </ul>
 * All of these are read from the turn as stored, that is <em>after</em>
 * {@code Conversation} has scrubbed it, so the plaintext of a secret is never
 * seen here. When the first message is unusable, the next usable one gives the
 * title; when none is, the conversation stays untitled.
 */
public final class ConversationTitles {

    /** The longest title an owner may set, in characters. */
    public static final int MAX_TITLE_LENGTH = 200;

    /** The default length of a title cut from the first message. */
    public static final int DEFAULT_DERIVED_TITLE_LENGTH = 80;

    private static final String ELLIPSIS = "…";

    private ConversationTitles() {
    }

    /**
     * The index of the first step whose user input may give a title, or -1 when no
     * step's may (yet).
     */
    public static int firstTitleStep(IConversationMemory memory) {
        if (memory == null) {
            return -1;
        }
        List<ConversationOutput> outputs = memory.getConversationOutputs();
        var steps = memory.getAllSteps();
        int count = steps == null ? 0 : steps.size();
        for (int index = 0; index < count; index++) {
            ConversationOutput output = outputs != null && index < outputs.size() ? outputs.get(index) : null;
            if (titleInput(steps, index, output) != null) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The user input of step {@code index} as a title may use it — the raw message,
     * not the parser's normalized copy — or null when the step has no input or its
     * input may not be used (see the class comment).
     */
    public static String titleInput(IConversationMemory memory, int index) {
        if (memory == null || memory.getAllSteps() == null || index < 0 || index >= memory.getAllSteps().size()) {
            return null;
        }
        List<ConversationOutput> outputs = memory.getConversationOutputs();
        return titleInput(memory.getAllSteps(), index, outputs != null && index < outputs.size() ? outputs.get(index) : null);
    }

    private static String titleInput(IConversationMemory.IConversationStepStack steps, int index, ConversationOutput output) {
        // The stack is newest first; step i of the conversation sits at size - 1 - i.
        IConversationMemory.IConversationStep step = steps.get(steps.size() - 1 - index);
        if (step == null) {
            return null;
        }
        if (isSecretTurn(step, output) || ConversationLogGenerator.isInputWithheld(step)) {
            return null;
        }
        String raw = stringResult(step.getData(MemoryKeys.INPUT_INITIAL.key()));
        String display = output != null && output.get(MemoryKeys.INPUT.key()) instanceof String text ? text : null;
        // Both copies are checked: a scrub reaches each of them, and either may be
        // the one carrying the placeholder.
        if (carriesPlaceholder(raw) || carriesPlaceholder(display)) {
            return null;
        }
        String input = raw != null ? raw : display;
        String normalized = normalize(input);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Whether the step was a client-flagged secret turn: its output's context, or
     * its step's {@code context:secretInput} datum, says {@code secretInput:
     * true}.
     */
    static boolean isSecretTurn(IConversationMemory.IConversationStep step, ConversationOutput output) {
        if (output != null && output.get("context") instanceof Map<?, ?> context && isTrue(context.get("secretInput"))) {
            return true;
        }
        var flag = step.getData("context:secretInput");
        if (flag == null) {
            return false;
        }
        Object value = flag.getResult();
        if (value instanceof Context context) {
            value = context.getValue();
        } else if (value instanceof Map<?, ?> map) {
            value = map.get("value");
        }
        return isTrue(value);
    }

    private static boolean isTrue(Object value) {
        return value != null && "true".equals(String.valueOf(value));
    }

    private static boolean carriesPlaceholder(String text) {
        return text != null && (text.contains(MemoryKeys.SECRET_INPUT_PLACEHOLDER) || text.contains(MemoryKeys.SECRET_CONTEXT_PLACEHOLDER));
    }

    private static String stringResult(IData<?> data) {
        return data != null && data.getResult() instanceof String text ? text : null;
    }

    /**
     * Collapses every run of whitespace (line breaks included) into one space,
     * drops control and format characters (bidi overrides among them, which could
     * make a list entry read as something else) except ZWNJ and ZWJ, and trims.
     * Never null.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), 4 * MAX_TITLE_LENGTH));
        boolean space = false;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = sb.length() > 0;
                continue;
            }
            int type = Character.getType(cp);
            // ZWNJ and ZWJ stay: Persian, the Indic scripts and emoji sequences need them.
            if (type == Character.CONTROL || (type == Character.FORMAT && cp != 0x200C && cp != 0x200D) || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            if (space) {
                sb.append(' ');
                space = false;
            }
            sb.appendCodePoint(cp);
            if (sb.length() > 4 * MAX_TITLE_LENGTH) {
                // Enough for any title: the rest of a long message is never needed.
                break;
            }
        }
        return sb.toString().trim();
    }

    /**
     * {@code text} cut to at most {@code maxLength} characters, at a word boundary
     * when one is near the end, with an ellipsis when it was cut. Never splits a
     * surrogate pair.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        int limit = Math.max(1, maxLength);
        if (text.length() <= limit) {
            return text;
        }
        int end = limit - ELLIPSIS.length();
        if (end < 1) {
            return text.substring(0, safeEnd(text, limit));
        }
        int space = text.lastIndexOf(' ', end);
        // Break at a word when that keeps at least two thirds of the room.
        if (space >= (end * 2) / 3) {
            end = space;
        }
        end = safeEnd(text, end);
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    private static int safeEnd(String text, int end) {
        return end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
    }

    /** The default title from {@code input}: normalized, then truncated. */
    public static String deriveTitle(String input, int maxLength) {
        String normalized = normalize(input);
        return normalized.isEmpty() ? null : truncate(normalized, Math.min(maxLength, MAX_TITLE_LENGTH));
    }

    /**
     * A title its owner sent, normalized.
     *
     * @throws BadRequestException
     *             when it is blank, or longer than {@link #MAX_TITLE_LENGTH}
     */
    public static String requireValidOwnerTitle(String title) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            throw new BadRequestException("title must not be blank");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new BadRequestException("title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        return normalized;
    }
}
