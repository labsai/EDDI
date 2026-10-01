/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

/**
 * Turns an uploaded file into the Markdown a knowledge base stores.
 *
 * <p>
 * One per format family. Implementations answer for a MIME type rather than a
 * file extension, because the type is sniffed from the bytes — a file named
 * {@code report.pdf} that is really a ZIP is the ordinary case, not the exotic
 * one.
 *
 * <p>
 * An implementation never throws for a file it merely dislikes: it throws
 * {@link UnreadableDocumentException} with a sentence an operator can act on,
 * so one bad file in a batch of forty reports itself and the rest still land.
 */
public interface DocumentTextExtractor {

    /** Whether this extractor handles that MIME type. */
    boolean supports(String mimeType);

    /**
     * @return Markdown, never null; empty when the file genuinely holds no text (a
     *         scanned page image, an empty deck)
     */
    String extract(byte[] content, ExtractionLimits limits);
}
