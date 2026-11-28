/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * What a caller is told when a space id they sent matches none of their spaces.
 *
 * <h3>Why the wording does not say "not a member"</h3> Both refusals used to
 * claim the caller was not a member of the space. Most of the time the id
 * simply was not one of theirs as sent: space ids are opaque and may contain
 * {@code %} (a group {@code a|b} is {@code team:a%7Cb}), so a client that put
 * one into a query parameter without encoding it had it decoded once on the way
 * in, and was then told it did not belong to its own team (security review
 * 2026-11, B9). A header compared exactly, case-sensitively, refused
 * {@code TEAM:engineering} the same way (B10). The check stays exactly as
 * strict — fail-closed, nothing normalised — and only the explanation changes:
 * it says what was received, that it matches none of the caller's spaces, and
 * how to send one that does.
 *
 * <h3>Why the caller's spaces are not listed</h3> {@code GET /workspaces}
 * already lists them to the same caller; repeating them in an error would put
 * them into whatever logs the client keeps of its failures.
 */
public final class SpaceRefusals {

    /** Enough for any real space id; a longer one is cut, not echoed whole. */
    static final int MAX_ECHOED_LENGTH = 200;

    private SpaceRefusals() {
    }

    /**
     * The refusal for a {@code ?space=} query parameter, quoting the id as the
     * server received it — after the one URL decoding the container applies.
     */
    public static String unknownSpaceParameter(String received) {
        return "None of your spaces has the id '" + echo(received) + "' (as received, after URL decoding). Space ids are opaque: send the id "
                + "exactly as GET /workspaces lists it, URL-encoded (encodeURIComponent) — an id containing '%' or '|' sent raw is decoded "
                + "on the way in and then matches nothing.";
    }

    /** The refusal for an {@link SpaceContext#SPACE_HEADER} header. */
    public static String unknownSpaceHeader(String received) {
        return "None of your spaces has the id '" + echo(received) + "'. " + SpaceContext.SPACE_HEADER + " must be one of the ids "
                + "GET /workspaces lists, exactly: it is compared case-sensitively and only surrounding whitespace is ignored. Leave "
                + SpaceContext.SPACE_HEADER + " out to use your default space.";
    }

    /**
     * A received id made safe to put in a response: control characters gone and the
     * length capped. It is the caller's own input, so it is never echoed raw.
     */
    static String echo(String received) {
        String safe = sanitize(received);
        return safe.length() <= MAX_ECHOED_LENGTH ? safe : safe.substring(0, MAX_ECHOED_LENGTH) + "…";
    }
}
