/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.time.Instant;
import java.util.Optional;

/**
 * What a connector discovers against, and where it puts what it finds.
 *
 * <p>
 * Implemented by the framework. Every method may be called from the thread that
 * called {@link SourceConnector#discover} only.
 */
public interface DiscoveryContext {

    /** The run this discovery is part of. */
    SourceContext source();

    /** The partition being discovered. */
    Partition partition();

    /**
     * The cursor the previous discovery of this partition returned, or empty on the
     * first run and after a reset.
     */
    Optional<String> cursor();

    /**
     * When discovery must have stopped, for connectors that do not enforce a time
     * budget of their own. Report {@link StopReason#TIME_LIMIT} when it passes.
     */
    Instant deadline();

    /**
     * What is stored for a document whose vectors are live, so the connector can
     * ask the provider for changes only (a conditional request, a revision
     * comparison). Empty when the document must be read in full — never stored,
     * tombstoned, or the vector store was rebuilt and every document has to be
     * embedded again.
     */
    Optional<KnownVersion> knownVersion(String documentId);

    /**
     * Hands one discovered document to the framework. In this version the framework
     * processes it — fetch, convert, embed, record — before returning, so a
     * connector sees its effects (and its exceptions) at once.
     */
    void accept(WorkItem item);

    /**
     * Reports a failure that discovery met and moved past.
     *
     * @param documentId
     *            the document it concerns, or null when it concerns the source as a
     *            whole — which never counts as evidence about any one document
     * @param location
     *            where it happened, for the log (a URL, a path); may be null
     */
    void reportFailure(String documentId, String location, SourceError error);

    /**
     * Whether the framework wants discovery to stop: the run was cancelled or
     * superseded, or a budget was spent. Checked between documents; a connector
     * that sees true returns with {@link StopReason#CANCELLED} and
     * {@link Coverage#PARTIAL}.
     */
    boolean isCancelled();
}
