/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * Where a deployment is in the org enablement (plan §5.3). The persisted
 * marker, not {@code eddi.org.mode}, decides predicates, unique-key shape and
 * RLS.
 * <ul>
 * <li>{@link #ABSENT}: today's layout. No predicate is added to any read.</li>
 * <li>{@link #SWITCHING}: the point of no return. Nodes start using predicates
 * and org-including unique keys, then wait at a presence barrier.</li>
 * <li>{@link #ENABLED}: the strict layout, verified by the integrity step.</li>
 * </ul>
 * The marker only moves forward. A fresh multi-mode install goes straight from
 * {@link #ABSENT} to {@link #ENABLED}.
 */
public enum OrgMarkerState {
    ABSENT, SWITCHING, ENABLED;

    /**
     * True for the forward moves the enablement and the fresh-install bootstrap
     * make.
     */
    public boolean canMoveTo(OrgMarkerState next) {
        return switch (this) {
            case ABSENT -> next == SWITCHING || next == ENABLED;
            case SWITCHING -> next == ENABLED;
            case ENABLED -> false;
        };
    }
}
