/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An uploaded .html file, through the same converter the crawler uses.
 *
 * <p>
 * Sharing it is the point: a page ingested by crawling it and the same page
 * saved to disk and uploaded should produce the same text, or the two paths
 * disagree about what the site says.
 */
@ApplicationScoped
public class HtmlDocumentExtractor implements DocumentTextExtractor {

    private final HtmlToMarkdownConverter converter;

    @Inject
    public HtmlDocumentExtractor(HtmlToMarkdownConverter converter) {
        this.converter = converter;
    }

    @Override
    public boolean supports(String mimeType) {
        return converter.supports(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        // No base URL: an uploaded file has no address, so relative links stay
        // relative rather than being resolved against something invented here.
        return converter.convert(PlainTextExtractor.decode(content), null, limits.maxCharacters());
    }
}
