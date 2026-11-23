/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * Persistence of the deployment's {@link OrgMarker}: one platform-plane record
 * (plan Appendix B).
 * <p>
 * {@link #read()} is what {@link DataLayoutGuard} calls before anything else at
 * startup, so it must not write: no collection, table or index is created by a
 * read. The writers create what they need. Both writers only move forward, so a
 * stale or concurrent caller can never lower the layout or rewind the marker.
 */
public interface IOrgMarkerStore {

    /**
     * The current record, read from the authoritative copy (the primary, with
     * majority read concern, on MongoDB). {@link OrgMarker#NONE} when there is
     * none.
     *
     * @throws RuntimeException
     *             when the datastore cannot be read
     */
    OrgMarker read();

    /**
     * Moves the marker from {@code expected} to {@code next}, atomically.
     *
     * @return {@code false} when the stored state is not {@code expected}
     * @throws IllegalArgumentException
     *             when {@code expected} cannot move to {@code next}
     */
    boolean moveMarker(OrgMarkerState expected, OrgMarkerState next);

    /**
     * Raises {@code layout.required} to {@code level}; a lower or equal level
     * leaves it unchanged.
     *
     * @return the stored level afterwards
     */
    int raiseRequiredLayout(int level);
}
