/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.engine.security.spaces.CallerSpaces;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Who has signed in to this deployment, and what people mean when they type a
 * name into a share box.
 *
 * <h3>Why sharing needs this</h3> EDDI stores one identifier per person — the
 * principal, which with Keycloak is the username. People know each other by
 * name and email. Before this existed, a grant was accepted for whatever string
 * arrived, so sharing with {@code carol@example.com} when carol's principal is
 * {@code carol} stored a grant that matched nobody and answered 200. A typo did
 * the same. Every share now resolves to a principal the directory has actually
 * seen, or is refused with a message that says why.
 *
 * <h3>Emails resolve only when verified</h3> An identity provider that lets
 * people edit their own profile lets them claim any address. Resolving a share
 * through an unverified email would hand every grant made to
 * {@code ceo@example.com} to whoever typed that into their profile first. The
 * grant itself is always stored against the principal, never the email.
 *
 * <h3>A person must have signed in once</h3> EDDI has no other source of
 * accounts — it does not query the identity provider — so somebody who has
 * never opened EDDI cannot be found. The refusal says so, because "no such
 * user" for a colleague who plainly exists reads as a bug.
 *
 * <h3>Recording is cheap by construction</h3> The directory is written at most
 * once per principal per {@link #RECORD_INTERVAL} per instance, and only when
 * something about the person changed, on a worker thread — never on the request
 * path.
 */
@ApplicationScoped
public class UserDirectory {

    private static final Logger LOGGER = Logger.getLogger(UserDirectory.class);

    /** How often one instance re-records the same unchanged principal. */
    static final Duration RECORD_INTERVAL = Duration.ofMinutes(10);

    /** Upper bound on any directory listing, whatever a client asks for. */
    public static final int MAX_RESULTS = 25;

    /** Shortest query that searches people at all. */
    public static final int MIN_USER_QUERY = 1;

    private final IUserDirectoryStore store;
    private final SecurityIdentity identity;
    private final SpaceContext spaceContext;
    private final WorkspaceSettings settings;
    private final ManagedExecutor executor;
    private final boolean enabled;
    private final boolean exposeEmail;

    /** principal → fingerprint of what was last recorded. */
    private final Cache<String, Integer> recorded = Caffeine.newBuilder().expireAfterWrite(RECORD_INTERVAL).maximumSize(50_000).build();

    /** principal → entry, for labelling rows without a query per row. */
    private final Cache<String, Optional<DirectoryUser>> labels = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(20_000).build();

    @Inject
    public UserDirectory(IUserDirectoryStore store, SecurityIdentity identity, SpaceContext spaceContext, WorkspaceSettings settings,
            ManagedExecutor executor, @ConfigProperty(name = "eddi.workspaces.directory.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.workspaces.directory.expose-email", defaultValue = "true") boolean exposeEmail) {
        this.store = store;
        this.identity = identity;
        this.spaceContext = spaceContext;
        this.settings = settings;
        this.executor = executor;
        this.enabled = enabled;
        this.exposeEmail = exposeEmail;
    }

    /**
     * Whether the directory is in use: switched on, and authentication is on so
     * there is somebody to record.
     */
    public boolean isActive() {
        return enabled && settings.isStampingOwnership();
    }

    // --- Recording ----------------------------------------------------------

    /**
     * Records the current caller if anything about them changed since this instance
     * last did. Never blocks the caller and never throws.
     */
    public void recordCurrentCaller() {
        if (!isActive()) {
            return;
        }
        DirectoryUser observed;
        try {
            observed = observeCurrentCaller();
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Could not read the caller's profile for the user directory");
            return;
        }
        if (observed == null) {
            return;
        }
        int fingerprint = Objects.hash(observed.subject(), observed.username(), observed.email(), observed.emailVerified(),
                observed.displayName(), observed.teams());
        Integer previous = recorded.getIfPresent(observed.principal());
        if (previous != null && previous == fingerprint) {
            return;
        }
        recorded.put(observed.principal(), fingerprint);
        try {
            executor.execute(() -> write(observed));
        } catch (RuntimeException e) {
            // A rejected task is not worth failing a request over; forget the
            // fingerprint so the next request tries again.
            recorded.invalidate(observed.principal());
            LOGGER.debugf(e, "Could not schedule a user directory update");
        }
    }

    private void write(DirectoryUser observed) {
        try {
            Optional<DirectoryUser> existing = store.find(observed.principal());
            existing.ifPresent(before -> warnOnReusedPrincipal(before, observed));
            store.upsert(observed);
            labels.invalidate(observed.principal());
        } catch (RuntimeException e) {
            recorded.invalidate(observed.principal());
            LOGGER.warnf("Could not record '%s' in the user directory: %s", sanitize(observed.principal()), e.getMessage());
        }
    }

    /**
     * A principal whose token {@code sub} changed belongs to a different account
     * than it used to — a deleted and re-created user, or a renamed one whose old
     * name was handed to somebody else. Every grant and every resource owned by
     * that principal now reaches the new account. EDDI cannot tell a rename from a
     * takeover, so it says so loudly and leaves the decision to an administrator.
     */
    private static void warnOnReusedPrincipal(DirectoryUser before, DirectoryUser now) {
        if (before.subject() != null && now.subject() != null && !before.subject().equals(now.subject())) {
            LOGGER.warnf("[WORKSPACES] Principal '%s' now signs in as a different account than before (token subject changed). "
                    + "Everything owned by or shared with '%s' is reachable by the new account. If that is not intended, "
                    + "transfer ownership and revoke its grants.", sanitize(now.principal()), sanitize(now.principal()));
        }
    }

    /** The caller as their token describes them, or {@code null} when anonymous. */
    DirectoryUser observeCurrentCaller() {
        String principal = spaceContext.currentPrincipal();
        if (principal == null) {
            return null;
        }
        String subject = null;
        String username = null;
        String email = null;
        boolean emailVerified = false;
        String name = null;
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            subject = jwt.getSubject();
            username = claimString(jwt, "preferred_username");
            email = claimString(jwt, "email");
            emailVerified = "true".equalsIgnoreCase(claimString(jwt, "email_verified"));
            name = claimString(jwt, "name");
        }
        List<String> teams = new ArrayList<>();
        for (String group : spaceContext.currentGroupPaths()) {
            String team = Subjects.team(group);
            if (team != null) {
                teams.add(team);
            }
        }
        Instant now = Instant.now();
        return new DirectoryUser(principal.trim(), subject, username, email, emailVerified, name, teams, now, now);
    }

    private static String claimString(JsonWebToken jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        // jakarta.json string values stringify with their quotes.
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.isEmpty() ? null : text;
    }

    // --- Resolving what people type ------------------------------------------

    /**
     * Turns what a person typed into the subject a grant is stored under.
     * <p>
     * Accepts {@code team:<group>}, {@code user:<name>}, or a bare name or email. A
     * user is found by exact principal, then by username, then by verified email —
     * in that order, so a principal that happens to look like somebody else's email
     * still means that principal.
     *
     * @param input
     *            what was typed
     * @param caller
     *            who is sharing — their own teams are always valid targets, even
     *            before anybody else in them has signed in
     * @param callerIsAdmin
     *            an administrator may name any team the directory knows or not
     * @return the normalised subject
     * @throws BadRequestException
     *             naming what was wrong, when the input matches nobody or more than
     *             one person
     */
    public String resolveSubject(String input, CallerSpaces caller, boolean callerIsAdmin) {
        if (input == null || input.isBlank()) {
            throw new BadRequestException("Say who to share with: a name, an email address, or team:<group>.");
        }
        String trimmed = input.trim();
        if (trimmed.startsWith(Subjects.TEAM_PREFIX)) {
            return resolveTeam(trimmed.substring(Subjects.TEAM_PREFIX.length()), caller, callerIsAdmin);
        }
        String name = trimmed.startsWith(Subjects.USER_PREFIX) ? trimmed.substring(Subjects.USER_PREFIX.length()).trim() : trimmed;
        if (!trimmed.startsWith(Subjects.USER_PREFIX) && trimmed.contains(":")) {
            throw new BadRequestException("A subject must start with 'user:' or 'team:' — was '" + trimmed + "'.");
        }
        if (name.isEmpty()) {
            throw new BadRequestException("'user:' needs a name or email address after it.");
        }
        return Subjects.user(resolvePrincipal(name));
    }

    /**
     * The principal a name or email belongs to.
     *
     * @throws BadRequestException
     *             when nobody or more than one person matches
     */
    public String resolvePrincipal(String name) {
        // "user:bob" and "bob" name the same person; a caller that passes the
        // subject form must not end up with an owner literally called "user:bob".
        String trimmed = name.trim();
        if (trimmed.startsWith(Subjects.USER_PREFIX)) {
            trimmed = Subjects.decode(trimmed.substring(Subjects.USER_PREFIX.length()).trim());
        }
        if (!isActive()) {
            // Nothing is recorded, so nothing can be checked. The caller gets exactly
            // what it asked for, which is the behaviour before the directory existed.
            return trimmed;
        }
        Optional<DirectoryUser> exact = store.find(trimmed);
        if (exact.isPresent()) {
            return exact.get().principal();
        }
        List<DirectoryUser> byUsername = store.findByUsername(trimmed);
        if (byUsername.size() == 1) {
            return byUsername.get(0).principal();
        }
        if (byUsername.size() > 1) {
            throw ambiguous(trimmed, byUsername);
        }
        if (trimmed.contains("@")) {
            List<DirectoryUser> byEmail = store.findByVerifiedEmail(trimmed);
            if (byEmail.size() == 1) {
                return byEmail.get(0).principal();
            }
            if (byEmail.size() > 1) {
                throw ambiguous(trimmed, byEmail);
            }
            throw new BadRequestException("Nobody with the verified email address '" + trimmed + "' has signed in to EDDI yet. A person "
                    + "has to sign in once before anything can be shared with them — and only an address their identity provider has "
                    + "verified can be used to find them. Ask them to open EDDI, or share using their username.");
        }
        throw new BadRequestException("Nobody called '" + trimmed + "' has signed in to EDDI yet. A person has to sign in once before "
                + "anything can be shared with them — ask them to open EDDI, then try again. Check the spelling, too.");
    }

    private String resolveTeam(String group, CallerSpaces caller, boolean callerIsAdmin) {
        String team = Subjects.team(group);
        if (team == null) {
            throw new BadRequestException("'team:' needs a group name after it.");
        }
        if (!isActive() || callerIsAdmin || (caller != null && caller.spaces().contains(team)) || store.teamExists(team)) {
            return team;
        }
        throw new BadRequestException("No team called '" + Subjects.normalizeGroup(group) + "' is known here. Teams come from the groups "
                + "on people's sign-in, so a team appears once one of its members has signed in to EDDI. Check the spelling.");
    }

    private static BadRequestException ambiguous(String input, List<DirectoryUser> matches) {
        List<String> principals = matches.stream().map(DirectoryUser::principal).limit(5).toList();
        return new BadRequestException("'" + input + "' matches more than one person (" + String.join(", ", principals)
                + "). Share with the exact one, e.g. user:" + principals.get(0) + ".");
    }

    // --- Labels -------------------------------------------------------------

    /**
     * Directory entries for the given principals, for showing people by name.
     * Unknown principals are absent. Served from a short cache, so a listing costs
     * at most one query.
     */
    public Map<String, DirectoryUser> lookup(Collection<String> principals) {
        Map<String, DirectoryUser> result = new HashMap<>();
        if (!isActive() || principals == null || principals.isEmpty()) {
            return result;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String principal : principals) {
            if (principal == null || principal.isBlank()) {
                continue;
            }
            Optional<DirectoryUser> cached = labels.getIfPresent(principal);
            if (cached == null) {
                missing.add(principal);
            } else {
                cached.ifPresent(user -> result.put(principal, user));
            }
        }
        if (!missing.isEmpty()) {
            try {
                Map<String, DirectoryUser> loaded = store.findAll(missing);
                for (String principal : missing) {
                    DirectoryUser user = loaded.get(principal);
                    labels.put(principal, Optional.ofNullable(user));
                    if (user != null) {
                        result.put(principal, user);
                    }
                }
            } catch (RuntimeException e) {
                // Labels are cosmetic; a directory outage must not fail a listing.
                LOGGER.debugf(e, "Could not look up directory labels");
            }
        }
        return result;
    }

    /**
     * The display label for one principal, or the principal itself when unknown.
     */
    public String labelFor(String principal) {
        if (principal == null) {
            return null;
        }
        DirectoryUser user = lookup(List.of(principal)).get(principal);
        return user == null ? principal : user.label();
    }

    /** The secondary line shown under a person's name: their email, if exposed. */
    public String detailFor(DirectoryUser user) {
        if (user == null) {
            return null;
        }
        if (exposeEmail && user.email() != null) {
            return user.email();
        }
        return user.username() != null && !user.username().equals(user.label()) ? user.username() : null;
    }

    // --- Search -------------------------------------------------------------

    /**
     * One suggestion for a share box.
     *
     * @param subject
     *            what to send back as the share's {@code subject}
     * @param kind
     *            {@code user} or {@code team}
     * @param label
     *            the name to show
     * @param detail
     *            a secondary line — email for a person, when the deployment exposes
     *            it
     */
    public record Match(String subject, String kind, String label, String detail) {
    }

    /**
     * Suggestions for a share box: the caller's own teams and known teams whose
     * name starts with {@code query}, then people whose name, username or email
     * does.
     * <p>
     * The caller themselves is left out — sharing with yourself is never what
     * anybody means.
     */
    public List<Match> search(String query, int limit, CallerSpaces caller) {
        int bounded = Math.max(1, Math.min(limit, MAX_RESULTS));
        String q = query == null ? "" : query.trim();
        String teamQuery = q.startsWith(Subjects.TEAM_PREFIX) ? q.substring(Subjects.TEAM_PREFIX.length()) : q;
        String userQuery = q.startsWith(Subjects.USER_PREFIX) ? q.substring(Subjects.USER_PREFIX.length()) : q;
        Map<String, Match> matches = new LinkedHashMap<>();

        if (!q.startsWith(Subjects.USER_PREFIX)) {
            String lowered = DirectoryUser.lower(teamQuery);
            if (caller != null) {
                for (String space : caller.spaces()) {
                    if (space.startsWith(Subjects.TEAM_PREFIX) && teamName(space).toLowerCase(Locale.ROOT).startsWith(lowered)) {
                        matches.put(space, teamMatch(space));
                    }
                }
            }
            if (isActive()) {
                try {
                    for (String team : store.searchTeams(teamQuery, bounded)) {
                        matches.putIfAbsent(team, teamMatch(team));
                    }
                } catch (RuntimeException e) {
                    LOGGER.debugf(e, "Could not search directory teams");
                }
            }
        }

        if (isActive() && !q.startsWith(Subjects.TEAM_PREFIX) && userQuery.length() >= MIN_USER_QUERY) {
            Set<String> self = caller == null ? Set.of() : caller.selfPrincipals();
            try {
                for (DirectoryUser user : store.search(userQuery, bounded + 1)) {
                    if (self.contains(user.principal())) {
                        continue;
                    }
                    String subject = Subjects.user(user.principal());
                    matches.putIfAbsent(subject, new Match(subject, "user", user.label(), detailFor(user)));
                }
            } catch (RuntimeException e) {
                LOGGER.debugf(e, "Could not search the user directory");
            }
        }
        return matches.values().stream().limit(bounded).toList();
    }

    /** Recorded members of a team, for notifying them. */
    public List<String> teamMembers(String teamSubject, int limit) {
        if (!isActive()) {
            return List.of();
        }
        try {
            return store.teamMembers(teamSubject, limit);
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Could not list team members");
            return List.of();
        }
    }

    // --- GDPR ---------------------------------------------------------------

    /** The stored entry for a principal, for a data export. */
    public Optional<DirectoryUser> export(String principal) {
        return store.find(principal);
    }

    /**
     * Removes a principal from the directory — GDPR erasure. They reappear, with
     * whatever their token then says, the next time they sign in.
     */
    public boolean erase(String principal) {
        recorded.invalidate(principal);
        labels.invalidate(principal);
        return store.delete(principal);
    }

    private static Match teamMatch(String teamSubject) {
        return new Match(teamSubject, "team", Subjects.decode(teamName(teamSubject)), null);
    }

    private static String teamName(String teamSubject) {
        return teamSubject.substring(Subjects.TEAM_PREFIX.length());
    }
}
