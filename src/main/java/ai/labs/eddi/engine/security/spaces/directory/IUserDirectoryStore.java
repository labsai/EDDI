/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for the {@link DirectoryUser} directory — one row per principal.
 * <p>
 * Every lookup is case-insensitive on the human-facing fields (username, email,
 * display name) and exact on the principal, because the principal is what EDDI
 * compares ownership against and a case-folded match there would resolve to a
 * different account.
 */
public interface IUserDirectoryStore {

    /**
     * Creates or replaces the entry for {@code user.principal()}. The stored
     * {@code firstSeen} of an existing entry is kept; the one passed in is used
     * only for a new entry.
     */
    void upsert(DirectoryUser user);

    /** The entry for exactly this principal. */
    Optional<DirectoryUser> find(String principal);

    /**
     * Entries for these principals, keyed by principal; unknown ones are absent.
     */
    Map<String, DirectoryUser> findAll(Collection<String> principals);

    /** Entries whose username equals {@code username}, ignoring case. */
    List<DirectoryUser> findByUsername(String username);

    /**
     * Entries whose email equals {@code email}, ignoring case, and was verified by
     * the identity provider.
     */
    List<DirectoryUser> findByVerifiedEmail(String email);

    /**
     * Entries whose principal, username, email or display name starts with
     * {@code prefix}, ignoring case, most recently seen first.
     *
     * @param prefix
     *            a literal — never interpreted as a pattern
     */
    List<DirectoryUser> search(String prefix, int limit);

    /**
     * Team subjects ({@code team:<group>}) any recorded user belongs to, whose
     * group name starts with {@code prefix}, ignoring case. A blank prefix lists
     * teams in no particular order.
     */
    List<String> searchTeams(String prefix, int limit);

    /** Whether any recorded user belongs to this team subject. */
    boolean teamExists(String teamSubject);

    /**
     * Principals of recorded members of this team subject, most recently seen
     * first.
     */
    List<String> teamMembers(String teamSubject, int limit);

    /**
     * Removes the entry — for GDPR erasure.
     *
     * @return whether an entry existed
     */
    boolean delete(String principal);
}
