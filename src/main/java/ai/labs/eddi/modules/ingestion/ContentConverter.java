/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

/**
 * Pluggable content converter for ingestion sources.
 * <p>
 * Implementations convert raw content from various formats (HTML, PDF, etc.)
 * into Markdown for chunking and embedding. Multiple converters can coexist,
 * and the appropriate one is selected based on the document's content type.
 * <p>
 * All implementations are discovered via CDI.
 *
 * @since 6.0.3
 * @see HtmlToMarkdownConverter
 */
public interface ContentConverter {

    /**
     * Checks if this converter supports the given content type.
     *
     * @param contentType
     *            the MIME type (e.g., "text/html", "application/pdf")
     * @return true if this converter can handle the content type
     */
    boolean supports(String contentType);

    /**
     * Converts content to Markdown format.
     * <p>
     * The implementation should:
     * <ol>
     * <li>Parse the raw content according to its format</li>
     * <li>Extract meaningful text (filtering navigation, ads, etc.)</li>
     * <li>Convert structural elements (headings, lists, tables, links)</li>
     * <li>Truncate to {@code maxLength} if content exceeds limits</li>
     * </ol>
     *
     * @param content
     *            the raw content to convert
     * @param sourceRef
     *            reference to the source (URL, path, etc.) for logging/context
     * @param maxLength
     *            maximum characters to return
     * @return Markdown-formatted content
     */
    String convert(String content, String sourceRef, int maxLength);
}
