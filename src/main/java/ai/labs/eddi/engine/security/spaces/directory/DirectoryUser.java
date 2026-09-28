/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * One person who has signed in to this deployment, as their token described
 * them the last time they did.
 * <p>
 * The directory exists because sharing needs to name somebody, and the only
 * identifier EDDI stores — the principal — is not what people know each other
 * by. Without it a share box accepts {@code carol@example.com}, stores a grant
 * for a principal that is actually {@code carol}, answers 200, and reaches
 * nobody.
 *
 * @param principal
 *            the principal name EDDI stamps as {@code ownerId} and grants to —
 *            the key
 * @param subject
 *            the token's {@code sub}: stable for the lifetime of the account
 *            even when a username or email changes, kept so a principal re-used
 *            by a different account can be noticed
 * @param username
 *            {@code preferred_username}, when the token carries one
 * @param email
 *            the email claim, when present
 * @param emailVerified
 *            whether the identity provider vouched for that email. An
 *            unverified email is never used to resolve a share: in an identity
 *            provider that lets users edit their own profile, anybody could
 *            claim any address and receive every grant made to it
 * @param displayName
 *            the {@code name} claim, for showing a person to a person
 * @param teams
 *            the team subjects ({@code team:<group>}) the token listed
 * @param firstSeen
 *            when this principal first signed in
 * @param lastSeen
 *            the most recent sign-in the directory recorded — updated at most
 *            every few minutes per principal, not per request
 */
public record DirectoryUser(String principal, String subject, String username, String email, boolean emailVerified, String displayName,
        List<String> teams, Instant firstSeen, Instant lastSeen) {

    public DirectoryUser {
        teams = teams == null ? List.of() : List.copyOf(teams);
    }

    /**
     * How to show this person: their name, else their username, else the principal.
     * Never an email on its own — that is the secondary line.
     */
    public String label() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return principal;
    }

    /** Lower-cased for case-insensitive lookups; null stays null. */
    static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
