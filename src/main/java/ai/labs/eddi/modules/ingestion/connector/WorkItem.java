/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.Map;
import java.util.Objects;

/**
 * One document that discovery found, and what is to be done with it.
 *
 * @param documentId
 *            stable across renames and moves — what the document's state and
 *            chunks are keyed by
 * @param versionToken
 *            the provider's version of the document (a revision id, a content
 *            hash) when it knows one without downloading. When present and
 *            equal to the stored one, the document is unchanged and is not
 *            fetched. Null when only the content can tell
 * @param action
 *            what happened to the document
 * @param partitionId
 *            the {@link Partition} it was found in
 * @param hints
 *            connector-private values discovery already learned and fetch will
 *            need (a file name, a MIME type, a download link). The framework
 *            never reads them
 * @param prefetched
 *            the document itself, when discovering it meant reading it — a
 *            crawl. Null otherwise, and the framework calls
 *            {@link SourceConnector#fetch}
 */
public record WorkItem(String documentId, String versionToken, Action action, String partitionId,
        Map<String, String> hints, SourceDocument prefetched) {

    /** What discovery learned about a document. */
    public enum Action {
        /** New or possibly changed: ingest it unless its version says otherwise. */
        UPSERT,
        /**
         * The provider confirmed, without sending it, that the stored version is
         * current (an HTTP 304 against {@link DiscoveryContext#knownVersion}).
         */
        UNCHANGED,
        /** Explicitly removed at the source (a change feed's deletion record). */
        DELETE
    }

    public WorkItem {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(partitionId, "partitionId");
        hints = hints == null ? Map.of() : Map.copyOf(hints);
        if (prefetched != null && action != Action.UPSERT) {
            throw new IllegalArgumentException("Only an UPSERT item can carry a document");
        }
        if (prefetched != null && !documentId.equals(prefetched.documentId())) {
            throw new IllegalArgumentException("A prefetched document must have the item's document id");
        }
    }

    /** A document to be fetched by {@link SourceConnector#fetch}. */
    public static WorkItem upsert(String documentId, String versionToken, Partition partition,
                                  Map<String, String> hints) {
        return new WorkItem(documentId, versionToken, Action.UPSERT, partition.id(), hints, null);
    }

    /** A document discovery has already read. */
    public static WorkItem prefetched(SourceDocument document, Partition partition) {
        return new WorkItem(document.documentId(), document.versionToken(), Action.UPSERT, partition.id(), Map.of(),
                document);
    }

    /** A document the provider confirmed unchanged. */
    public static WorkItem unchanged(String documentId, Partition partition) {
        return new WorkItem(documentId, null, Action.UNCHANGED, partition.id(), Map.of(), null);
    }

    /** A document removed at the source. */
    public static WorkItem delete(String documentId, Partition partition) {
        return new WorkItem(documentId, null, Action.DELETE, partition.id(), Map.of(), null);
    }
}
