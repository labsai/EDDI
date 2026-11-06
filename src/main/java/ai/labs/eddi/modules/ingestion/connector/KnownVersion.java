/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

/**
 * What the framework has stored for a document whose vectors are live — the
 * baseline a connector may use to avoid downloading it again.
 *
 * @param versionToken
 *            the change key recorded at the last ingestion: the connector's
 *            {@link SourceDocument#versionToken()} when it supplied one,
 *            otherwise a hash of the extracted text
 * @param etag
 *            the HTTP {@code ETag} that came with it, or null
 * @param lastModified
 *            the HTTP {@code Last-Modified} that came with it, or null
 */
public record KnownVersion(String versionToken, String etag, String lastModified) {
}
