/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * How a document is cut into units.
 *
 * <p>
 * The one definition of the strategy names. An agent's configuration holds the
 * value as a string and converts it to this type, so the configuration model
 * depends on no extraction class.
 */
public enum SplitStrategy {
    /**
     * The outline when it is usable (two or more chapters whose pages can be
     * found), otherwise fixed page windows. A document with no text layer at all is
     * cut into page windows whatever its outline says.
     */
    AUTO,
    /** The document's outline (bookmarks); page windows when it has none usable. */
    OUTLINE,
    /**
     * Headings found by font size. Not available yet: until it is, a request for it
     * is handled as {@link #AUTO}, and the manifest says so in a warning.
     */
    HEADINGS,
    /** Fixed windows of {@link SplitConfig#pagesPerUnit()} pages. */
    PAGES
}
