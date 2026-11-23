/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.Objects;

/**
 * What one discovery of one partition concluded. The documents themselves have
 * already gone to {@link DiscoveryContext#accept}.
 *
 * @param stopReason
 *            why discovery ended
 * @param coverage
 *            how much of the partition was seen — whether absence means
 *            deletion
 * @param incompleteReason
 *            for the log when coverage is not full and the stop reason alone
 *            does not say why ("incomplete sitemap discovery"); null otherwise
 * @param nextCursor
 *            the opaque cursor to resume from next run, or null when the
 *            connector keeps none
 * @param summary
 *            one line describing what the listing found — how many files, how
 *            large, of which types, and what was skipped — or null. Returned as
 *            the message of a preview, so an operator can judge a source before
 *            the first run pays for it; logged for a run
 * @param scopeSignature
 *            an opaque signature of the scope this discovery listed — for a
 *            Google Drive source, the resolved folder ids it read and excluded
 *            — or null for a connector without a configurable scope. When it
 *            differs from the signature the last completed reconciliation
 *            applied, documents a complete listing did not see are removed at
 *            once instead of after {@code tombstoneAfterMissedRuns} runs
 */
public record DiscoveryResult(StopReason stopReason, Coverage coverage, String incompleteReason, String nextCursor,
        String summary, String scopeSignature) {

    public DiscoveryResult {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(coverage, "coverage");
    }

    /** A discovery of a source without a configurable scope. */
    public DiscoveryResult(StopReason stopReason, Coverage coverage, String incompleteReason, String nextCursor,
            String summary) {
        this(stopReason, coverage, incompleteReason, nextCursor, summary, null);
    }

    /** A discovery with no summary to report. */
    public DiscoveryResult(StopReason stopReason, Coverage coverage, String incompleteReason, String nextCursor) {
        this(stopReason, coverage, incompleteReason, nextCursor, null, null);
    }

    /** A discovery that ended at {@code stopReason} with the given coverage. */
    public static DiscoveryResult of(StopReason stopReason, Coverage coverage) {
        return new DiscoveryResult(stopReason, coverage, null, null, null, null);
    }
}
