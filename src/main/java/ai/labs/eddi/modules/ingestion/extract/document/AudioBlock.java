/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * An audio file the document carries, at the place it is anchored.
 *
 * @param audioId
 *            identifies the audio among the document's derived files
 * @param mimeType
 *            recognised from the file's own bytes
 * @param bytes
 *            its size
 * @param seconds
 *            its duration, or null when it could not be read
 * @param label
 *            the description the document gives it, as data, or null
 */
public record AudioBlock(int page, float y, int order, String audioId, String mimeType, long bytes, Double seconds,
        String label) implements Block {
}
