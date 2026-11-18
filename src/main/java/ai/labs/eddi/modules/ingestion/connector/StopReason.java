/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

/**
 * Why a discovery stopped. Recorded by name in run history, so the names are a
 * stored format: add values, never rename one.
 *
 * <p>
 * The same names as {@code WebCrawler.StopReason}, which the run report still
 * carries; {@code SourceConnectorContractTest} keeps the two in step.
 */
public enum StopReason {
    /** Everything in scope was discovered. */
    COMPLETED,
    /**
     * The source's document limit was reached ({@code maxPages}, {@code maxFiles}).
     */
    PAGE_LIMIT,
    /** The request limit was reached. */
    FETCH_LIMIT,
    /** The byte budget was spent. */
    BYTE_LIMIT,
    /** The run's time budget was spent. */
    TIME_LIMIT,
    /** The framework asked discovery to stop, or the source could not be read. */
    CANCELLED
}
