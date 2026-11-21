/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.HiddenContentFilter;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

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

    /**
     * Drops the elements the file itself hides: {@code display:none}, the
     * {@code hidden} attribute, {@code opacity:0}, off-screen and zero-size
     * elements. The page converter keeps those on purpose — on a live page a script
     * reveals them (accordions, tabs, "read more") — but a saved file is read by
     * nothing that runs script, so what it hides stays hidden, and a hidden
     * paragraph is where a prompt injection goes.
     */
    public static String withoutHiddenElements(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        Document document = Jsoup.parse(html);
        HiddenContentFilter.removeHidden(document, HiddenContentFilter.Mode.VISIBLE);
        return document.outerHtml();
    }

    @Override
    public boolean supports(String mimeType) {
        return converter.supports(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        String html = PlainTextExtractor.decode(content);
        if (limits.visibleTextOnly()) {
            html = withoutHiddenElements(html);
        }
        // No base URL: an uploaded file has no address, so relative links stay
        // relative rather than being resolved against something invented here.
        return converter.convert(html, null, limits.maxCharacters());
    }
}
