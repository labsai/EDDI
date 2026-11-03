## Review-thread sweep over the merged PR backlog (2026-11-03)

**Repo:** EDDI · **Branch:** `fix/review-threads-sweep`

Fixes the findings that were still open as review threads on PRs merged on 2026-11-02, each checked against current `main` first.

- **Authorization** (`RestWorkflowStore.java`): `addReference` no longer skips a host-bearing reference without a scheme (`//ai.labs.llm/…`); the engine resolves it by host and id, so it now gets the same VIEW check as `eddi://`.
- **Redaction** (`SecretRedactionFilter.java`, `SecretScrubber.java`): a quoted cookie value (`sid="…"`, also as `sid=\"…\"` inside a JSON carrier) is masked with its quotes kept; `Set-Cookie` attribute values (`Path`, `Domain`, `Expires`, …) are exempt only in a `Set-Cookie` header, not in a `Cookie` request header, and a bare flag no longer ends the walk; both export-scrubber query paths (relative and absolute URL) judge a percent-encoded name (`%6bey`, `%73ig`) by its decoded form.
- **PDF budget** (`PdfDecodeBudget.java`, `PdfTextExtractor.java`): predictor dimensions are checked for overflow and non-positive values before they are multiplied (a zero dimension also hangs PDFBox's TIFF predictor), and a `/Filter` that PDFBox reads as zero filters (`[]`, a number, a string) is charged like an unfiltered stream.
- **Smaller:** Postgres `loadConversationMemorySnapshot` calls `ensureSchema()` again; dark-only brand colours render the same stylesheet in mock mode as the server; `SwaggerUiBranding` survives a malformed alpha; the Slack refusal WARN (`ChannelTargetRouter.java`) carries `[SECURITY]` as documented, and only that one — an unresolved secret is a missing value, not a refusal; `BrandDelivery`'s Javadoc now says when the `.dark` block is written; the apicalls `authHeaderRef` error names `${eddivault:…}`; `docs/langchain.md` counts three defences.

Review threads that describe design work rather than a defect (`TurnAuditBuffer` durability window, the `ClusterConversationCoordinator` erasure race) are answered on their PRs and not changed here.
