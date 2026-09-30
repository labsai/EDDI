/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.datastore.DescriptorStore;
import ai.labs.eddi.datastore.IResourceFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * What a single descriptor listing is allowed to return, in the form the query
 * layer can actually apply.
 *
 * <h3>An explicit argument, never ambient state</h3> Every listing takes one of
 * these, and an internal caller that genuinely needs to see everything — the
 * export service walking a config graph, the orphan sweep, a startup migration
 * — passes {@link #unrestricted()} in plain sight at the call site. Reading the
 * scope from a thread-local instead would make "unfiltered" the behaviour of
 * any code path that simply forgot to set it, which is the shape most fail-open
 * authorization bugs have.
 *
 * @author ginccc
 */
public final class AccessScope {

    /** The descriptor field naming the space a resource belongs to. */
    public static final String FIELD_SPACE_ID = "spaceId";

    private static final AccessScope UNRESTRICTED = new AccessScope(null, null, Ownership.ANY, null);

    private final List<String> admittingTokens;
    private final String spaceId;
    private final Ownership ownership;
    private final String ownerPrincipal;

    private AccessScope(List<String> admittingTokens, String spaceId, Ownership ownership, String ownerPrincipal) {
        this.admittingTokens = admittingTokens;
        this.spaceId = spaceId;
        this.ownership = ownership == null ? Ownership.ANY : ownership;
        this.ownerPrincipal = ownerPrincipal;
    }

    /**
     * No filtering at all — administrators, deployments with workspaces switched
     * off, and internal callers that operate below the access model.
     */
    public static AccessScope unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * Restricted to what {@code caller} may see.
     *
     * @param caller
     *            the caller's spaces and subjects
     * @param admitLegacy
     *            whether resources with no recorded owner are admitted
     */
    public static AccessScope forCaller(CallerSpaces caller, boolean admitLegacy) {
        return new AccessScope(DescriptorAccess.admittingTokens(caller, admitLegacy), null, Ownership.ANY, null);
    }

    /**
     * Narrows this scope to one space — the server side of a space switcher.
     * <p>
     * A narrowing, never a widening: it ANDs a space predicate onto whatever this
     * scope already admits, so asking for a space you cannot reach returns nothing
     * rather than granting it. Applied even to an unrestricted scope, so an
     * administrator can look at one team's workspace without being handed every
     * other one — that is a view preference, and it must not become a way to see
     * less than you are entitled to <em>or</em> more.
     * <p>
     * This has to happen in the query. Filtering a page client-side breaks paging:
     * page 2 of "everything" is not page 2 of "this space".
     */
    public AccessScope withinSpace(String spaceId) {
        if (spaceId == null || spaceId.isBlank()) {
            return this;
        }
        return new AccessScope(admittingTokens, spaceId.trim(), ownership, ownerPrincipal);
    }

    /**
     * Narrows this scope by who owns the resource — the server side of the "Mine"
     * and "Shared with me" filters.
     * <p>
     * Like {@link #withinSpace}, a narrowing and never a widening: it ANDs onto
     * whatever the scope already admits. "Shared with me" is therefore "everything
     * I can reach that I do not own", which is the question the filter asks — a
     * resource a teammate filed in our team space is shared with me, and one I
     * filed there myself is mine.
     *
     * @param ownership
     *            {@link Ownership#ANY} leaves the scope unchanged
     * @param principal
     *            the caller's principal; without one there is nothing to own, so
     *            {@code MINE} matches nothing and {@code SHARED} matches everything
     *            reachable
     */
    public AccessScope withOwnership(Ownership ownership, String principal) {
        if (ownership == null || ownership == Ownership.ANY) {
            return new AccessScope(admittingTokens, spaceId, Ownership.ANY, null);
        }
        String trimmed = principal == null || principal.isBlank() ? null : principal.trim();
        return new AccessScope(admittingTokens, spaceId, ownership, trimmed);
    }

    /**
     * Whether the caller's own reach is unlimited. A space narrowing does not
     * change this — it is a view preference layered on top, and
     * {@link #toNarrowingFilter()} carries it separately.
     */
    public boolean isUnrestricted() {
        return admittingTokens == null;
    }

    /**
     * The OR-group to AND into a listing query, or {@code null} when unrestricted.
     * <p>
     * Every entry is an anchored, escaped whole-token pattern — see
     * {@link Subjects#tokenPattern}, and the class comment there for why an
     * unescaped identity predicate is a real vulnerability on both backends rather
     * than a style preference.
     */
    public IResourceFilter.QueryFilters toQueryFilters() {
        if (isUnrestricted()) {
            return null;
        }
        List<IResourceFilter.QueryFilter> filters = new ArrayList<>(admittingTokens.size());
        for (String token : admittingTokens) {
            filters.add(new IResourceFilter.QueryFilter(DescriptorStore.FIELD_ACCESS_INDEX, Subjects.tokenPattern(token)));
        }
        return new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, filters);
    }

    /**
     * The view narrowings — space and ownership — as one AND-ed group, or
     * {@code null} when this scope applies neither.
     * <p>
     * Separate from {@link #toQueryFilters()} because filter groups are ANDed while
     * filters within a group follow the group's connector: the access tokens must
     * stay an OR among themselves, and each narrowing must AND with the result.
     * Folding a narrowing into the access group would OR it, turning a narrowing
     * into a widening — the exact bug this shape exists to prevent.
     * <p>
     * Ownership is matched on the access index's owner token rather than on the
     * {@code ownerId} field, because the index is the field every listing already
     * queries and indexes, and its token is escaped the same way everywhere.
     */
    public IResourceFilter.QueryFilters toNarrowingFilter() {
        List<IResourceFilter.QueryFilter> filters = new ArrayList<>(2);
        if (spaceId != null) {
            filters.add(new IResourceFilter.QueryFilter(FIELD_SPACE_ID, Subjects.exactPattern(spaceId)));
        }
        if (ownership != Ownership.ANY) {
            String ownerToken = ownerPrincipal == null
                    ? null
                    : Subjects.tokenPattern(Subjects.OWNER_TOKEN_PREFIX + Subjects.encode(ownerPrincipal));
            if (ownership == Ownership.MINE) {
                // No principal owns nothing. TOKEN_NONE is never admitted to anybody,
                // so this is an explicit "match nothing" rather than a missing filter.
                filters.add(new IResourceFilter.QueryFilter(DescriptorStore.FIELD_ACCESS_INDEX,
                        ownerToken != null ? ownerToken : Subjects.tokenPattern(Subjects.TOKEN_NONE)));
            } else if (ownerToken != null) {
                filters.add(new IResourceFilter.QueryFilter(DescriptorStore.FIELD_ACCESS_INDEX, new IResourceFilter.NotMatching(ownerToken)));
            }
        }
        return filters.isEmpty() ? null : new IResourceFilter.QueryFilters(filters);
    }

    /** The ownership narrowing this scope applies. */
    public Ownership ownership() {
        return ownership;
    }

    /** The space this scope is narrowed to, or {@code null}. */
    public String spaceId() {
        return spaceId;
    }

    /**
     * The tokens this scope admits. Empty when unrestricted. Visible for testing.
     */
    public List<String> admittingTokens() {
        return admittingTokens == null ? List.of() : List.copyOf(admittingTokens);
    }
}
