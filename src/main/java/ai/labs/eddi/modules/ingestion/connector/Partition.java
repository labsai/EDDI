/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.Objects;

/**
 * One independently listed part of a source — a shared drive, a SharePoint
 * site, a mailbox — with a cursor of its own.
 *
 * @param id
 *            stable, provider-assigned where there is one; stored with every
 *            document found in it
 * @param displayName
 *            for run history and the Manager; may be null
 */
public record Partition(String id, String displayName) {

    /** The single partition of a source that has no partitions of its own. */
    public static final Partition IMPLICIT = new Partition("default", null);

    public Partition {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A partition id must not be blank");
        }
    }
}
