/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects everything a crawl produced, so tests can assert on it.
 */
final class RecordingSink implements CrawlSink {

    private final List<CrawledPage> pages = new ArrayList<>();
    private final List<String> unchanged = new ArrayList<>();
    private final List<CrawlError> errors = new ArrayList<>();
    private final Map<String, ConditionalHeaders> knownValidators = new HashMap<>();

    private int cancelAfterPages = Integer.MAX_VALUE;

    /** Pretends a previous run stored these validators for a document. */
    RecordingSink knows(String documentId, String etag, String lastModified) {
        knownValidators.put(documentId, new ConditionalHeaders(etag, lastModified));
        return this;
    }

    /** Makes the sink ask for cancellation once it has seen this many pages. */
    RecordingSink cancelAfter(int pageCount) {
        this.cancelAfterPages = pageCount;
        return this;
    }

    @Override
    public ConditionalHeaders conditionalFor(String documentId) {
        return knownValidators.getOrDefault(documentId, ConditionalHeaders.none());
    }

    @Override
    public void onPage(CrawledPage page) {
        pages.add(page);
    }

    @Override
    public void onUnchanged(String documentId) {
        unchanged.add(documentId);
    }

    @Override
    public void onError(CrawlError error) {
        errors.add(error);
    }

    @Override
    public boolean isCancelled() {
        return pages.size() >= cancelAfterPages;
    }

    List<CrawledPage> pages() {
        return pages;
    }

    List<String> documentIds() {
        return pages.stream().map(CrawledPage::documentId).toList();
    }

    List<String> unchanged() {
        return unchanged;
    }

    List<CrawlError> errors() {
        return errors;
    }

    CrawledPage page(String documentId) {
        return pages.stream()
                .filter(page -> page.documentId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no page for " + documentId + ", got " + documentIds()));
    }
}
