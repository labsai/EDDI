/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

/**
 * A group approval refused by the review validation
 * ({@code GroupReviewEditor}): the one {@link IllegalArgumentException} whose
 * message is written to be shown to the caller (which field, and why), already
 * stripped of control characters and capped. REST returns exactly this message
 * in the 400 body; any other {@code IllegalArgumentException} gets a fixed
 * text, because its message was not written for a caller and may carry internal
 * or request data.
 */
public final class ReviewRefusalException extends IllegalArgumentException {

    /** Longest message a caller is shown. */
    static final int MAX_MESSAGE_CHARS = 300;

    public ReviewRefusalException(String message) {
        super(clean(message));
    }

    private static String clean(String message) {
        if (message == null) {
            return "the review fields were refused";
        }
        StringBuilder out = new StringBuilder(Math.min(message.length(), MAX_MESSAGE_CHARS));
        for (int i = 0; i < message.length() && out.length() < MAX_MESSAGE_CHARS; i++) {
            char c = message.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }
}
