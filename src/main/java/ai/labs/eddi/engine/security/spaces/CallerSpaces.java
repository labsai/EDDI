/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Everything about a caller that the access policy is allowed to look at: the
 * principal names that count as "them", the spaces they can reach, and the
 * subjects a grant could name them by.
 * <p>
 * A value object rather than a live {@code SecurityIdentity} lookup, so
 * {@link DescriptorAccess} is a pure function and can be tested without a
 * security context — and so a caller resolved once per request is not
 * re-resolved per descriptor in a listing.
 *
 * @param selfPrincipals
 *            the raw principal names this caller answers to. Normally exactly
 *            one; a set because an owner recorded under an older principal
 *            spelling should still resolve to its owner.
 * @param spaces
 *            every space id the caller can reach — their personal space plus
 *            one per team.
 * @param subjects
 *            every subject a {@code ResourceGrant} could name this caller by.
 *
 * @author ginccc
 */
public record CallerSpaces(Set<String> selfPrincipals, Set<String> spaces, Set<String> subjects) {

    /** A caller with no identity at all: matches nothing but the public tokens. */
    public static final CallerSpaces ANONYMOUS = new CallerSpaces(Set.of(), Set.of(), Set.of());

    public CallerSpaces {
        selfPrincipals = immutable(selfPrincipals);
        spaces = immutable(spaces);
        subjects = immutable(subjects);
    }

    /**
     * Builds a caller from a principal name and the group paths they belong to.
     *
     * @param principal
     *            the authenticated principal name; blank or null yields
     *            {@link #ANONYMOUS}
     * @param groupPaths
     *            Keycloak group paths, in any of the spellings
     *            {@link Subjects#normalizeGroup} accepts
     */
    public static CallerSpaces of(String principal, Set<String> groupPaths) {
        if (principal == null || principal.isBlank()) {
            return ANONYMOUS;
        }
        String trimmed = principal.trim();

        Set<String> spaces = new LinkedHashSet<>();
        Set<String> subjects = new LinkedHashSet<>();

        String personal = Subjects.personalSpace(trimmed);
        spaces.add(personal);
        subjects.add(personal);

        if (groupPaths != null) {
            for (String groupPath : groupPaths) {
                String team = Subjects.team(groupPath);
                if (team != null) {
                    spaces.add(team);
                    subjects.add(team);
                }
            }
        }

        return new CallerSpaces(Set.of(trimmed), spaces, subjects);
    }

    /**
     * Whether {@code ownerId} names this caller.
     * <p>
     * A plain equality check on the principal name, matching what
     * {@code OwnershipValidator.isOwner} does — the two must agree, or a resource
     * could be readable through one guard and not the other.
     */
    public boolean isSelf(String ownerId) {
        return ownerId != null && !ownerId.isBlank() && selfPrincipals.contains(ownerId);
    }

    /** Whether this caller has any identity at all. */
    public boolean isAnonymous() {
        return selfPrincipals.isEmpty();
    }

    private static Set<String> immutable(Set<String> input) {
        return input == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(input));
    }
}
