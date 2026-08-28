/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

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

    /** The field the materialised access index is stored in. */
    public static final String FIELD_ACCESS_INDEX = "accessIndex";

    private static final AccessScope UNRESTRICTED = new AccessScope(null);

    private final List<String> admittingTokens;

    private AccessScope(List<String> admittingTokens) {
        this.admittingTokens = admittingTokens;
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
        return new AccessScope(DescriptorAccess.admittingTokens(caller, admitLegacy));
    }

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
            filters.add(new IResourceFilter.QueryFilter(FIELD_ACCESS_INDEX, Subjects.tokenPattern(token)));
        }
        return new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, filters);
    }

    /**
     * The tokens this scope admits. Empty when unrestricted. Visible for testing.
     */
    public List<String> admittingTokens() {
        return admittingTokens == null ? List.of() : List.copyOf(admittingTokens);
    }
}
