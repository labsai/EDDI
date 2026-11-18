/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * A file embedded in a document, as the manifest lists it: what it is, never
 * its bytes.
 *
 * <p>
 * Nothing fills this list yet; embedded files are read in a later phase, which
 * may add fields. The manifest's list is empty until then.
 *
 * @param name
 *            the file name the document gives, as data — never a path to open
 * @param mimeType
 *            the type recognised from the file's own bytes, or null when not
 *            recognised
 * @param bytes
 *            the declared size, or -1 when the document does not say
 * @param page
 *            the 1-based page the file is attached to, or null for a file in
 *            the document-level name tree
 */
public record EmbeddedFileInfo(String name, String mimeType, long bytes, Integer page) {
}
