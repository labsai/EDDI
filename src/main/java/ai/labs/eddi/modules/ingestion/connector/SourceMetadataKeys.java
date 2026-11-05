/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import java.util.List;

/**
 * The standard metadata vocabulary of an ingested document, shared by every
 * connector so retrieval, citations and filters can read one set of names.
 *
 * <p>
 * A connector sets the descriptive keys on {@link SourceDocument#metadata()};
 * the identity keys ({@link #DOCUMENT_ID}, {@link #SOURCE_KEY},
 * {@link #PARTITION_ID}, {@link #VERSION_TOKEN}) are typed fields or assigned
 * by the framework. Where a key already exists on stored chunks its value is
 * the stored name — {@link #CITATION_URL} is {@code "url"} — so the vocabulary
 * describes the data that is there rather than renaming it.
 *
 * <p>
 * Chunks are written today with {@code documentId}, {@code url}, {@code title},
 * {@code sourceName}, {@code sourceKey}, {@code runId}, {@code ingestedAt} and
 * {@code kbId}. The other keys are reserved: copying them onto chunks comes
 * with the connectors that produce them.
 */
public final class SourceMetadataKeys {

    /** Stable document id; also on every chunk. */
    public static final String DOCUMENT_ID = "documentId";

    /** The owning source's state key; also on every chunk. */
    public static final String SOURCE_KEY = "sourceKey";

    /** The {@link Partition} the document was found in. */
    public static final String PARTITION_ID = "partitionId";

    /** The document's title, capped before it is copied onto chunks. */
    public static final String TITLE = "title";

    /**
     * What a human opens from a cited answer — a URL, or {@code file:<name>} for an
     * uploaded file. Stored on chunks as {@code url}.
     */
    public static final String CITATION_URL = "url";

    /** Path within the source: folder path and file name, a page path. */
    public static final String PATH = "path";

    /** The MIME type the content was read as. */
    public static final String MIME_TYPE = "mimeType";

    /**
     * The format class — {@code document}, {@code spreadsheet},
     * {@code presentation}, {@code pdf}, {@code text}, {@code html}, … — as named
     * by the {@code filters.formats} config.
     */
    public static final String FORMAT = "format";

    /** Author or owner as the provider names them. */
    public static final String AUTHOR = "author";

    /** When the provider last modified the document, ISO-8601. */
    public static final String MODIFIED_AT = "modifiedAt";

    /** See {@link SourceDocument#versionToken()}. */
    public static final String VERSION_TOKEN = "versionToken";

    /** The connector's {@link SourceConnector#type()}. */
    public static final String PROVIDER = "provider";

    /**
     * Who may read the document at the source: a list of namespaced principal
     * strings. <b>Schema only</b> — nothing enforces it yet.
     *
     * <p>
     * Form: {@code <namespace>:<kind>:<id>}, or one of the two special values.
     * <ul>
     * <li>{@code google:user:a@example.com}, {@code google:group:team@example.com}
     * — Google identities by verified email</li>
     * <li>{@code entra:user:<object-id>}, {@code entra:group:<object-id>} —
     * Microsoft identities by Entra object id, never by email or UPN</li>
     * <li>{@value #ACL_LINK_ORGANIZATION} — anyone in the owning organisation with
     * the link</li>
     * <li>{@value #ACL_ANYONE} — public</li>
     * </ul>
     */
    public static final String ACL_PRINCIPALS = "acl.principals";

    /** Principal meaning anyone in the owning organisation who has the link. */
    public static final String ACL_LINK_ORGANIZATION = "link:organization";

    /** Principal meaning anyone at all. */
    public static final String ACL_ANYONE = "anyone";

    /** Every standard key, for validation and documentation. */
    public static final List<String> ALL = List.of(DOCUMENT_ID, SOURCE_KEY, PARTITION_ID, TITLE, CITATION_URL, PATH,
            MIME_TYPE, FORMAT, AUTHOR, MODIFIED_AT, VERSION_TOKEN, PROVIDER, ACL_PRINCIPALS);

    private SourceMetadataKeys() {
    }
}
