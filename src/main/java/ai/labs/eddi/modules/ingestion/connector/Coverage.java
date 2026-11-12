/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

/**
 * How much of a partition a discovery saw — which decides whether a document
 * that was not seen has been deleted.
 *
 * <p>
 * Getting this wrong in the generous direction is how a knowledge base empties
 * itself because a provider was slow, so a connector that is unsure reports
 * {@link #PARTIAL}.
 */
public enum Coverage {

    /**
     * An authoritative listing of everything the partition holds: absence means
     * deletion. A document missing from enough consecutive complete listings
     * ({@code tombstoneAfterMissedRuns}) is tombstoned and its chunks removed. Even
     * an empty listing is believed — an operator who deleted the last file means
     * it.
     */
    FULL_LISTING,

    /**
     * A traversal that exhausted everything it could reach — a crawl. Absence means
     * deletion as for {@link #FULL_LISTING}, with one extra guard: the run must
     * have learned something definitive (a usable document, or a "gone" answer). A
     * site behind a bot challenge answers 200 with nothing usable on every page,
     * "covers the source", and would otherwise delete the corpus.
     */
    FULL_TRAVERSAL,

    /**
     * A change feed applied from a cursor. Absence means nothing; deletions arrive
     * as explicit {@link WorkItem.Action#DELETE} items.
     */
    INCREMENTAL,

    /**
     * Discovery stopped short — a budget, a cancellation, an unreadable listing.
     * Nothing is concluded to be deleted.
     */
    PARTIAL
}
