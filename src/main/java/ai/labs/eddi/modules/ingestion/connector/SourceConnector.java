/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.List;

/**
 * Where a knowledge base's documents come from: the only place that knows a
 * provider.
 *
 * <p>
 * The ingestion framework ({@code IngestionPipeline}) owns everything that is
 * the same for every source — run claims, change detection, embedding,
 * replacement, deletion reconciliation, budgets and run history. A connector
 * owns what is not: how to list a source, how to read one document, and what
 * the provider's failures mean. The framework never names a provider; adding a
 * source type is one {@code @ApplicationScoped} bean implementing this
 * interface, picked up by {@link SourceConnectors}.
 *
 * <h2>Two halves</h2>
 * <ul>
 * <li>{@link #discover} lists what the source holds — or what changed since the
 * cursor — and hands each document to the framework as a {@link WorkItem}. It
 * downloads nothing it does not have to.</li>
 * <li>{@link #fetch} reads one document's content.</li>
 * </ul>
 * A connector whose discovery is itself the download — a crawler only learns
 * the next link by reading the page — hands the document over already fetched
 * ({@link WorkItem#prefetched}), and {@link #fetch} is then never called for
 * it.
 *
 * <h2>Discovery styles</h2>
 * <p>
 * How a connector discovers is its own business. Three shapes are expected:
 * <ul>
 * <li><b>A — listing plus change feed.</b> A full listing on the first run
 * (taking the change-feed baseline <em>before</em> listing, so nothing changed
 * during the backfill is lost), then the provider's change feed from the stored
 * cursor. Google Drive's {@code changes} API, Gmail history, IMAP
 * {@code MODSEQ}.</li>
 * <li><b>B — delta.</b> One call that is both the backfill and the change feed;
 * the cursor is the provider's continuation link. Microsoft Graph
 * {@code delta}.</li>
 * <li><b>C — listing only.</b> Every run lists everything and lets the
 * framework's version comparison skip what has not changed. A crawl, a sitemap,
 * the upload store.</li>
 * </ul>
 * <p>
 * The one part of the style the framework depends on is the {@link Coverage} a
 * run reports, because it decides whether absence means deletion.
 *
 * <h2>Cursors</h2>
 * <p>
 * A cursor is opaque to the framework: one serialised string (JSON by
 * convention) per {@link Partition}, written by the connector into
 * {@link DiscoveryResult#nextCursor()} and handed back unchanged as
 * {@link DiscoveryContext#cursor()} next time. The connector versions and
 * interprets it; the framework only stores it. A connector that finds its
 * cursor stale reports {@link SourceError.CursorInvalid}.
 *
 * <h2>Failures</h2>
 * <p>
 * A failure about one document is reported through
 * {@link DiscoveryContext#reportFailure} (during discovery) or thrown as a
 * {@link SourceException} from {@link #fetch}, and never stops the run. A
 * failure of the whole source — credentials refused, the provider unreachable —
 * is thrown from {@link #discover}. Either way it is classified by
 * {@link SourceError}, because the framework treats "could not look" and "it is
 * gone" very differently.
 *
 * <p>
 * Implementations are singletons shared by every run, and keep no run state in
 * fields.
 */
public interface SourceConnector {

    /**
     * The {@code IngestionSource.type} this connector serves — {@code "web"},
     * {@code "upload"}, … Unique across connectors.
     */
    String type();

    /**
     * The partitions of a source — drives, sites, mailboxes — each with its own
     * cursor. Most sources have one, which is the default.
     */
    default List<Partition> listPartitions(SourceContext context) throws SourceException {
        return List.of(Partition.IMPLICIT);
    }

    /**
     * Checks that the source's credential can still read a partition, before any
     * discovery against it. The default accepts every partition.
     *
     * @throws SourceException
     *             classified — {@link SourceError.Auth} when access was revoked,
     *             {@link SourceError.NotFound} when the partition is gone
     */
    default void verifyAccess(SourceContext context, Partition partition) throws SourceException {
        // Sources without credentials have nothing to verify.
    }

    /**
     * Lists one partition from its cursor and hands every document found to
     * {@link DiscoveryContext#accept}, in order, as it is found.
     *
     * <p>
     * Streaming rather than returning a list, so a source of any size is never held
     * in memory, and so the framework can stop discovery early —
     * {@link DiscoveryContext#isCancelled()} is the signal to return.
     *
     * @return how the partition was covered, why discovery stopped, and the cursor
     *         to resume from
     * @throws SourceException
     *             when the source as a whole cannot be read; per-document failures
     *             go to {@link DiscoveryContext#reportFailure} instead
     */
    DiscoveryResult discover(DiscoveryContext context) throws SourceException;

    /**
     * Reads one document that discovery listed without content.
     *
     * @throws SourceException
     *             classified. {@link SourceError.NotFound} means the document
     *             vanished between discovery and fetch; the framework skips it and
     *             the next listing reconciles it
     */
    SourceDocument fetch(WorkItem item, SourceContext context) throws SourceException;
}
