/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves the calling identity into the spaces and subjects the access policy
 * reasons about.
 * <p>
 * Composed the same way {@code ConversationAccessGuard} is — a
 * {@code @ApplicationScoped} bean holding the injected {@link SecurityIdentity}
 * proxy, which Quarkus resolves per request. That keeps a single injection
 * point for "who is calling" rather than each caller re-deriving it.
 *
 * <h3>Groups come from the token, not from roles</h3> Roles say what a caller
 * may <em>do</em>; groups say what they may <em>see</em>. Mapping groups onto
 * realm roles would collapse the two and make {@code @RolesAllowed} sensitive
 * to team membership, so group paths are read from a token claim
 * ({@code eddi.workspaces.groups-claim}, default {@code groups}) instead. The
 * claim is absent unless a protocol mapper adds it, in which case the caller
 * has a personal space and no teams — which is a correct answer, not a failure.
 *
 * @author ginccc
 */
@ApplicationScoped
public class SpaceContext {

    private static final Logger LOGGER = Logger.getLogger(SpaceContext.class);

    /**
     * The request header naming the space a resource created by this request is
     * filed under — the Manager's "Create in" choice.
     * <p>
     * A header rather than a query parameter on every create endpoint, because
     * resources are created through fifteen stores, duplication, import and the
     * group wizard, and every one of them already ends in
     * {@code ResourceAccessGuard.stampNewDescriptor}. One header reaches all of
     * them without a signature change anywhere.
     */
    public static final String SPACE_HEADER = "X-EDDI-Space";

    private final SecurityIdentity identity;
    private final WorkspaceSettings settings;
    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public SpaceContext(SecurityIdentity identity, WorkspaceSettings settings, CurrentVertxRequest currentVertxRequest) {
        this.identity = identity;
        this.settings = settings;
        this.currentVertxRequest = currentVertxRequest;
    }

    /**
     * Without request access: the {@link #SPACE_HEADER} is never read. Test seam.
     */
    public SpaceContext(SecurityIdentity identity, WorkspaceSettings settings) {
        this(identity, settings, null);
    }

    /**
     * The caller's principal name, or {@code null} when there is no authenticated
     * caller (authorization disabled, a background thread, a scheduled fire).
     */
    public String currentPrincipal() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return null;
        }
        String name = identity.getPrincipal().getName();
        return name == null || name.isBlank() ? null : name;
    }

    /** The caller's spaces and subjects, or {@link CallerSpaces#ANONYMOUS}. */
    public CallerSpaces current() {
        String principal = currentPrincipal();
        if (principal == null) {
            return CallerSpaces.ANONYMOUS;
        }
        return CallerSpaces.of(principal, currentGroupPaths());
    }

    /**
     * The space a resource this caller creates is filed under, in order: the space
     * the request names in {@link #SPACE_HEADER}, when the caller belongs to it;
     * the deployment's default team, when one is set; the caller's personal space.
     *
     * @return the space id, or {@code null} when there is no authenticated caller —
     *         in which case nothing should be stamped at all
     */
    public String defaultWriteSpace() {
        String principal = currentPrincipal();
        if (principal == null) {
            return null;
        }
        String requested = requestedSpace();
        if (requested != null && current().spaces().contains(requested)) {
            return requested;
        }
        // A space the caller is not in is refused before anything is created — see
        // SpaceHeaderFilter — so reaching here with one means a path the filter does
        // not cover. Falling back is the safe answer: the resource lands where it
        // would have without the header, never in a space the caller cannot see.
        return settings.getDefaultSpaceTeam().map(Subjects::teamSpace).orElseGet(() -> Subjects.personalSpace(principal));
    }

    /**
     * The space this request asked for in {@link #SPACE_HEADER}, trimmed, or
     * {@code null} when there is none — or no request at all, as on a scheduled
     * fire.
     */
    public String requestedSpace() {
        if (currentVertxRequest == null) {
            return null;
        }
        try {
            var current = currentVertxRequest.getCurrent();
            if (current == null) {
                return null;
            }
            String value = current.request().getHeader(SPACE_HEADER);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException e) {
            // Outside a request (a worker thread, a startup task) there is no header.
            return null;
        }
    }

    /**
     * The raw Keycloak group paths on the caller's token. Empty when the claim is
     * absent, not an error.
     */
    public Set<String> currentGroupPaths() {
        Set<String> groups = new LinkedHashSet<>();
        if (identity == null || identity.isAnonymous()) {
            return groups;
        }

        Object claim = readGroupsClaim();
        collectStrings(claim, groups);
        return groups;
    }

    private Object readGroupsClaim() {
        // The OIDC principal implements JsonWebToken; when OIDC is off (or the caller
        // authenticated some other way) it does not, and there are simply no groups.
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            try {
                return jwt.getClaim(settings.getGroupsClaim());
            } catch (RuntimeException e) {
                LOGGER.debugf(e, "Could not read groups claim '%s' from token", settings.getGroupsClaim());
                return null;
            }
        }
        // Fall back to an identity attribute so a non-OIDC augmentor can supply groups.
        return identity.getAttribute(settings.getGroupsClaim());
    }

    /**
     * Flattens whatever the claim deserialised to. A {@code groups} claim arrives
     * as a JSON array through one code path and as a {@code List<String>} through
     * another depending on how the token was parsed, and a single-valued claim may
     * arrive as a bare string — all three have to work, or team membership silently
     * evaporates for some deployments.
     */
    private static void collectStrings(Object claim, Set<String> out) {
        if (claim == null) {
            return;
        }
        if (claim instanceof CharSequence text) {
            String value = text.toString().trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
            return;
        }
        if (claim instanceof Collection<?> collection) {
            for (Object element : collection) {
                collectStrings(element, out);
            }
            return;
        }
        if (claim instanceof Object[] array) {
            for (Object element : array) {
                collectStrings(element, out);
            }
            return;
        }
        // jakarta.json values (JsonString / JsonArray) implement neither of the above;
        // JsonArray is a List so it is caught by the Collection branch, and JsonString
        // stringifies with surrounding quotes, which toString-and-strip handles.
        String value = claim.toString().trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.isEmpty()) {
            out.add(value);
        }
    }
}
