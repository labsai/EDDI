## 🛡️ fix(modules): CodeRabbit agent findings for ingestion, nlp, templating and apicalls (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-modules-misc`)

The 43 findings of the CodeRabbit agent-findings export that touch `modules/{ingestion,apicalls,nlp,properties,rag,rules,templating}`
were each traced through the current code. Most were already closed by #1173, #1175, #1186 and #1199 since the scans ran; six
needed a change here, and seven are left for a decision.

### What changed and why

- **Quadratic regex in the `str:fmt` size estimate** (`BoundedFormat`). `%` followed by a long run of `0` could be read as a flag
  or as the start of the width, and the regex tried every split of the run, so the estimate that exists to protect the render
  took time quadratic in the format's length before any limit applied. The width now cannot start with `0`, which is how
  `String.format` reads it too.
- **HTML list items were not charged to the conversion budget** (`HtmlToMarkdownConverter`). A list item is written by
  `appendList` without passing through `convertElement`, so a page of empty `<li>` elements escaped the element allowance.
  Each item is now counted and the list stops when the allowance is spent.
- **Run ownership is checked fail-closed before vectors are published** (`IngestionPipeline`). A state store that could not
  answer used to count as "still owned", so a worker that had been revoked while its embedding call was in flight could publish
  text of a source that had just been removed. The check made before a write now refuses when it cannot be verified; the
  document is not recorded and the next run takes it. The periodic progress check keeps working through a store outage.
- **The crawl frontier is bounded in characters** (`WebCrawler`). The count cap (100,000) did not bound size: a short relative
  link inherits its whole base URL, and both the canonical id and the fetch URL are retained, so one page could pin about 1.6 GB.
  Link discovery now stops admitting URLs after 64 million characters of URL text.
- **PDF pre-filter copied one dictionary per `stream` keyword** (`PdfStreamBudget`). A file that is mostly the word `stream`
  copied up to 64 KB for every occurrence. A later keyword in an object whose dictionary was already read is skipped.
- **Merged-terms correction shares one time budget across its retries** (`MergedTermsCorrection`). Every prefix and suffix lookup
  had its own regex deadline and counted expiry as a miss, so a word cost up to 200 deadlines. The word is now abandoned when
  one `RegEx.MATCH_BUDGET_NANOS` has passed.

### Findings left for a decision

Not changed, because a fix changes a contract or needs a design: a credential-use check for standalone RAG ingestion, a
durable retry for removed-source vector cleanup, the `tenantId` conversation property choosing the auto-vault tenant,
filtering `capabilityMatch` by the caller's USE access, a per-render allocation budget for Qute loops, structural escaping of
values substituted into quick-reply expressions, and provenance tracking so a normalized secret input scrubs its original form.

### Verdicts

43 findings: 6 fixed, 26 already fixed, 1 false positive, 3 accepted risk, 7 left for a decision.
