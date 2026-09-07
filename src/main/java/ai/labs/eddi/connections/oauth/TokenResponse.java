/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import java.time.Duration;
import java.util.List;

/**
 * What a token endpoint returned, normalised.
 *
 * @param accessToken
 *            never null on a success
 * @param refreshToken
 *            null for {@code client_credentials}, and null on a refresh where
 *            the provider chose not to rotate it — in which case the caller
 *            keeps the one it already has
 * @param expiresIn
 *            never null; see {@link #DEFAULT_LIFETIME} for the missing case
 * @param scopes
 *            what the provider actually granted, which is not always what was
 *            asked for
 */
public record TokenResponse(String accessToken, String refreshToken, Duration expiresIn, List<String> scopes) {

    /**
     * Assumed lifetime when a provider omits {@code expires_in}.
     * <p>
     * Deliberately short rather than "never expires". A token treated as eternal is
     * used until the provider rejects it, and that rejection arrives as an opaque
     * 401 in the middle of a user's turn; a short assumed lifetime costs one extra
     * refresh and turns the failure into a non-event.
     */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    @Override
    public String toString() {
        return "TokenResponse[accessToken=<REDACTED>, refreshToken=" + (refreshToken == null ? "none" : "<REDACTED>") + ", expiresIn=" + expiresIn
                + ", scopes=" + scopes + "]";
    }
}
