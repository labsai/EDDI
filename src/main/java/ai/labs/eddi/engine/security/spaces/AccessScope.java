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

    private static final AccessScope UNRESTRICTED = new AccessScope(null, null);

    private final List<String> admittingTokens;
    private final String spaceId;

    private AccessScope(List<String> admittingTokens, String spaceId) {
        this.admittingTokens = admittingTokens;
        this.spaceId = spaceId;
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
        return new AccessScope(DescriptorAccess.admittingTokens(caller, admitLegacy), null);
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
        return new AccessScope(admittingTokens, spaceId.trim());
    }

    /**
     * Whether the caller's own reach is unlimited. A space narrowing does not
     * change this — it is a view preference layered on top, and
     * {@link #toSpaceFilter()} carries it separately.
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
     * The space narrowing, as its own AND-ed group, or {@code null} when this scope
     * names no space.
     * <p>
     * Separate from {@link #toQueryFilters()} because filter groups are ANDed while
     * filters within a group follow the group's connector: the access tokens must
     * stay an OR among themselves, and the space must AND with the result. Folding
     * the space into the same group would OR it, turning a narrowing into a
     * widening — the exact bug this shape exists to prevent.
     */
    public IResourceFilter.QueryFilters toSpaceFilter() {
        if (spaceId == null) {
            return null;
        }
        return new IResourceFilter.QueryFilters(List.of(
                new IResourceFilter.QueryFilter(FIELD_SPACE_ID, Subjects.exactPattern(spaceId))));
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
