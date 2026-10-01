/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

/**
 * A storage that can index fields for substring search
 * ({@link IResourceFilter.Contains}). Optional: a caller asks a storage it got
 * from {@link IResourceStorageFactory} whether it implements this, and a
 * storage that does not simply searches unindexed. Only the PostgreSQL storage
 * implements it today; MongoDB has no index that serves a substring search.
 */
public interface ISubstringSearchIndexing {

    /**
     * Index these top-level fields for substring search. Returns at once: the
     * indexes may be built in the background, and searching works, unindexed, until
     * they are ready.
     */
    void indexForSubstringSearch(String... fields);
}
