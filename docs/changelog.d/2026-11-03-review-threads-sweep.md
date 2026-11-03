## Review-thread sweep over the merged PR backlog (2026-11-03)

**Repo:** EDDI · **Branch:** `fix/review-threads-sweep`

Fixes the findings that were still open as review threads on the 38 PRs merged on 2026-11-02, each checked against current `main` first. Findings that were not defects, or are design work, are answered on their PRs and listed at the end.

**Security and data integrity**

- **Authorization** (`RestWorkflowStore.java`): `addReference` no longer skips a host-bearing reference without a scheme (`//ai.labs.llm/…`); the engine resolves it by host and id, so it now gets the same VIEW check as `eddi://`.
- **Redaction** (`SecretRedactionFilter.java`, `SecretScrubber.java`): a quoted cookie value (`sid="…"`, also as `sid=\"…\"` inside a JSON carrier, with an apostrophe inside, or starting with a colon) is masked with its quotes kept; `Set-Cookie` attribute values (`Path`, `Domain`, `Expires`, …) are exempt only in a `Set-Cookie` header and only after its first pair (the first pair is the cookie itself, even when it is named `Path`), not in a `Cookie` request header, and a bare flag no longer ends the walk; both export-scrubber query paths (relative and absolute URL) judge a percent-encoded name (`%6bey`, `%73ig`) by its decoded form.
- **PDF budget** (`PdfDecodeBudget.java`, `PdfTextExtractor.java`): predictor dimensions are checked for overflow and non-positive values before they are multiplied (a zero dimension also hangs PDFBox's TIFF predictor), and a `/Filter` that PDFBox reads as zero filters (`[]`, a number, a string) is charged like an unfiltered stream.
- **Crawler** (`WebCrawler.java`): the link-length bound is applied to the URL that will be fetched, for page links and sitemap entries alike, before canonicalization drops tracking parameters.
- **Erasure race** (`ClusterConversationCoordinator.java`): after publishing a node-local dead letter the coordinator looks again for an erasure marker and retries until it can — an unreadable marker or a failed purge no longer leaves the erased turn in the shared stream, and a failed purge no longer escapes the fixed-delay forwarding task (which cancelled every later run).
- **Alerts** (`AlertMaintenanceService.java`, both alert event stores): the bounded orphan scan resumes after the last rule id it saw, so rules that still digest cannot crowd a disabled or deleted rule out of every pass.
- **HITL journal** (`HitlToolJournalStore.java`): `reconcileRetention` counts only index drops that happened, still handles every other stale index and recreates the partial one when a drop fails, and then rethrows the first failure (anything but "index not found").
- **Helm** (`helm/eddi`, chart `2.13.0` → `2.14.0`): `nats.externalAllowPlaintext` must be a boolean (the string `"false"` is truthy and switched the plaintext guard off); external NATS credentials are escaped for the properties file they are written to (backslash, line breaks, `${…}` expansion and a leading blank).
- **Audit evidence** (`TurnAuditBuffer.java`): a turn that recorded a failed task, or whose entry the ledger refused, no longer hands its compiled prompt and model response over to the ledger, and a step marker (`audit:evidence_retained`) carries that across a pause and its resume. The failure entry carries no LLM detail, so releasing them deleted the only copy.
- **Legacy path rewrite** (`LegacyPathRewriteFilter.java`): the rules are matched below `quarkus.http.root-path`; the cache memo of hashed keys (`CacheFactory.java`) forgets an evicted key on the publishing node.
- **Shutdown** (`SchedulePollerService.java`): a poll that is already running stops claiming once shutdown begins.

**Correctness and smaller fixes**

- Postgres `loadConversationMemorySnapshot` calls `ensureSchema()` again (a read can be the first statement on a fresh database); the simple projection of a drifted document with outputs and no steps no longer throws.
- Dark-only brand colours render the same stylesheet in mock mode as the server (`brand-css.ts`); `SwaggerUiBranding` survives a malformed alpha; the Slack refusal WARN (`ChannelTargetRouter.java`) carries `[SECURITY]` as documented, and only that one; the apicalls `authHeaderRef` error names `${eddivault:…}`.
- `docs-site/scripts/check-live.mjs` validates both URL arguments; the cluster demo treats an empty `MOCK_LLM_BIND` as unset (it bound every interface); the changelog collator refuses a link to a dated pending fragment (judged on the resolved path and the link destination alone, so `.././changelog.d/…`, a root-relative form and a titled link are caught; the README is still linkable) and the CI register-row detector accepts the same whitespace the collator does.
- **Manager:** the synthesis heading and footer keep their contrast for a bright brand; `BrandIcon` falls back once when `/branding/icon` fails; version labels and the About entry are translated, with focus returned to the Help trigger; French and Korean strings no longer attach an elision or particle to the brand name; the European Portuguese login-theme strings; `swatchFor` shows a valid `rgb()` primary; save, reset, upload and remove on the branding page are one write at a time and bounded by a timeout, and out-of-order or failing live brand scripts can no longer leave a stale or missing brand; a failed logo or icon no longer falls back to EDDI's own wordmark on a branded deployment; `AboutDialog`, `BrandWordmark` and `BrandIcon` are in the design-system mirror.
- **Docs:** the vault fallback counter is described as cumulative and attempt-based (rate, not zero total); the manual-fire timeout (`504`, interrupt, 30 s grace); NATS certificates and discovered brokers; a failed Keycloak admin login stops the Compose stack on loopback too; the audit ledger's crash window.

**Answered, not changed**

- `TemplateSyntaxMigrator` ReDoS: already bounded by `conversionWork` before any pattern runs; a regression test pins it.
- `BoundedRegex` stacked counted quantifiers: measured at milliseconds on this JDK, so no refusal is needed.
- `TurnAuditBuffer` durability window: documented; releasing the evidence keys only after the store confirms the entries is a larger change, left open.
- NATS `tls://` without `eddi.nats.tls.enabled` (#1206): a first fix that gave those servers the host-name-verifying context was withdrawn. jnats 2.26.4 resolves each server name to an IP before the TLS upgrade (`ResolveToAll` is its default), so the check would compare the certificate with an IP and reject a certificate that names only the DNS host. It cannot be verified without a TLS-enabled NATS server, so the behaviour is unchanged; the same question applies to the existing `eddi.nats.tls.enabled=true` path (`HostnameVerifyingSslContext`) and is left for a real-handshake test.
- `CacheFactory`: receiving nodes keep the memoised hash of an evicted key until the bounded memo turns it over; a reverse lookup per event on the dispatch thread would cost what the memo exists to save.
- A fragment dated 2026-11-01 for the Node 26 change: the date is when it was written.
