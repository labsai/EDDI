## 🔒 fix(security): close SSRF, unbounded-download and resource-exhaustion gaps on outbound paths (2026-09-26)

**Repo:** EDDI (`fix/security-ssrf-outbound`)

Eight verified findings across the outbound-request and archive-import paths. Grouped by what they let an
end user, the LLM, or a config author reach or exhaust.

### What changed

- **Unbounded response downloads (MEDIUM).** The attachment forwarder, web-scraper tool and PDF reader
  all buffered a whole response before checking its size, and the PDF path staged an unbounded file on
  disk first. A shared
  [`BoundedBodyReader`](../../src/main/java/ai/labs/eddi/engine/httpclient/BoundedBodyReader.java)
  now reads through a byte cap and a wall-clock deadline (the same shape as the crawler's
  `SafeHttpPageFetcher.readBounded`), exposed on
  [`SafeHttpClient`](../../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java) as
  `sendValidatedBounded`/`sendBounded` returning a `BoundedResponse`.
  [`AttachmentForwarder`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarder.java),
  [`WebScraperTool`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/WebScraperTool.java) and
  [`PdfReaderTool`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/PdfReaderTool.java) use
  it, capped to the per-file/tool limits (`eddi.attachments.max-forward-bytes`, new
  `eddi.tools.web-scraper.max-response-bytes`, new `eddi.tools.pdf-reader.max-download-bytes`).
  [`AttachmentTextExtractor`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/AttachmentTextExtractor.java)
  now loads PDFs with a temp-file scratch cache (PDFBox's default is unlimited in-memory) and stops at a
  page cap (`eddi.attachments.extraction.max-pages`, default 500) as well as the existing char cap.
- **httpcalls / A2A buffered the full body before the size check (LOW).**
  [`HttpClientWrapper`](../../src/main/java/ai/labs/eddi/engine/httpclient/impl/HttpClientWrapper.java)
  now streams the Vert.x response into a size-capped sink (`CappedBufferSink`) that fails the transfer
  mid-stream at `maxLength` instead of buffering then rejecting.
  [`A2AToolProviderManager`](../../src/main/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManager.java)
  reads the Agent Card and task responses through `BoundedBodyReader` at its 1 MB cap.
- **OpenAPI spec parsing fetched external `$refs` and read local files (MEDIUM).**
  [`McpApiToolBuilder.parseSpec`](../../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java)
  now sets `parseOptions.setSafelyResolveURL(true)` so every `$ref` the parser follows goes through
  swagger-parser's blocked-URL resolver, and runs `rejectCloudMetadataTarget` on the spec location
  before fetching — `isValidHttpUrl` alone let `http://169.254.169.254/...` through. Stale Javadoc
  corrected.
- **Shared cookie jar across all users (MEDIUM).**
  [`HttpClientModule`](../../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java)
  built one application-scoped `WebClientSession`, so a `Set-Cookie` from user A's httpcall was
  replayed on user B's call to the same host. It now uses a plain `WebClient` with no cookie store;
  `VertxHttpClient` and `HttpClientWrapper` updated to the plain type.
- **URL-validator range/family gaps (LOW).**
  [`UrlValidationUtils`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UrlValidationUtils.java)
  now unpacks and re-checks every IPv6 embedding of an IPv4 address (IPv4-compatible `::/96`, NAT64
  `64:ff9b::/96` and `64:ff9b:1::/48`, 6to4 `2002::/16`, Teredo `2001::/32` server and client), and
  blocks the missing IPv4 ranges `198.18.0.0/15`, `240.0.0.0/4` (incl. `255.255.255.255`) and
  `192.0.0.0/24`, plus the Azure WireServer `168.63.129.16` and OCI `192.0.0.192` metadata endpoints.
- **Metadata guard missing on model/vector-store endpoints (LOW).** The OpenAI and Ollama language-model
  builders, `EmbeddingModelFactory` (Ollama), `EmbeddingStoreFactory` (pgvector, Elasticsearch, Qdrant,
  Chroma) and `AgentSetupService` (llmBaseUrl) now call `rejectCloudMetadataTarget` where the endpoint
  URL is built — a guard, not an SSRF lockout, so legitimate internal hosts stay reachable.
- **Redirect leaked a custom credential header (MEDIUM, ssrf-protection-off path).**
  [`ApiCallExecutor`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java)
  now disables redirect-following for any request whose header carries a connection, vault or caller
  credential — Vert.x strips only Authorization/Cookie/Proxy-Authorization cross-origin, so a custom
  `X-Api-Key` would otherwise survive a redirect to another host.
  [`SafeHttpClient`](../../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java)'s
  cross-origin strip set gained the common custom-credential header names.
- **ZIP import had no bomb limits (MEDIUM).**
  [`ZipArchive.unzip`](../../src/main/java/ai/labs/eddi/backup/impl/ZipArchive.java) now caps entry
  count, per-entry inflated bytes and total inflated bytes, counted as read (never trusting
  `entry.getSize()`), aborting with an `IOException`. Zip-slip protection unchanged.

### Tests

New: `UrlValidationUtilsAddressFormsTest` (thorough per-address-form table), `BoundedBodyReaderTest`,
`HttpClientWrapperTest.CappedBufferSinkTests`, `ZipArchiveTest` bomb cases, `McpApiToolBuilderTest`
spec-location cases, `ApiCallExecutorConnectionHeaderTest.RedirectFollowingWithCredentials`. Updated the
mock plumbing in `AttachmentForwarderTest`, `PdfReaderToolTest`, `WebScraperToolExtendedTest` for the new
bounded methods.

Mutation-checked (revert → the named test fails → restore) for findings 2, 3, 5, 7 and 8: five mutations,
five killed. The `SafeHttpClient`-constructing tests (`PdfReaderToolTest.setUp` etc.) hit the known
sandbox baseline "Unable to establish loopback connection" and are verified on CI; all pure tests pass
locally.

### Follow-up (adversarial review round)

- **Redirect credential leak was wider than the reference-only fix.** Vert.x's default
  redirect handler copies every request header and removes only `Content-Length` (it does
  NOT strip Authorization/Cookie), so with ssrf-protection off a *literal* credential
  written in the httpcall config leaked cross-origin too — the reference-only
  `setFollowRedirects(false)` missed it. `HttpClientModule` now installs
  `strippingCrossOriginCredentials` on the shared client's redirect handler (composed with
  the existing metadata veto): it removes `SafeHttpClient.SENSITIVE_HEADERS` from any hop
  whose origin (scheme/host/effective-port) differs from the originating request,
  regardless of where the credential came from. `SafeHttpClient.SENSITIVE_HEADERS` is now
  public so both layers share one set. The false "Vert.x strips only the RFC three" comments
  in `ApiCallExecutor` and the stale buffering comment in `HttpClientWrapper` are corrected;
  the ApiCallExecutor reference-credential redirect disable stays as the stronger measure for
  resolved secrets. New tests in `HttpClientModuleTest` (cross-origin strip, same-origin keep,
  different-port, null-origin fail-safe, wrapper end-to-end).
- **A2A bounded read no longer passes a null watchdog.** `A2AToolProviderManager` gained a
  daemon `ScheduledExecutorService` and passes it to `BoundedBodyReader.read`, so a peer that
  sends 200+headers then stalls the body cannot hang the worker (the JDK request timeout does
  not bound `ofInputStream` body reads). Covered by a new `BoundedBodyReaderTest` case with a
  real scheduler and a stream that blocks until closed (`@Timeout(10)`).
- **Inline OpenAPI spec filesystem `$ref` closed.** `setSafelyResolveURL(true)` guards only
  URL-format refs; a filesystem-relative ref (`$ref: "/etc/passwd"`, `./x.yaml`, `file:…`) in
  inline content is resolved against the process CWD without the checker.
  `McpApiToolBuilder.parseSpec` now scans inline content and rejects any `$ref` that is neither
  an internal fragment (`#/…`) nor an `http(s)` URL. New `McpApiToolBuilderTest` cases.

Round-2 mutation checks (revert → named test fails → restore): cross-origin strip (3 tests
killed) and the A2A watchdog (stalled-body test times out). Both restored.

### Known residuals (documented, not fixed here)

- `WebSearchTool` and `WeatherTool` still use `SafeHttpClient.send(..., ofString())` unbounded;
  their responses are small API JSON, but they are not yet on the bounded path.
- `UrlValidationUtils` unpacks the common /96 NAT64 embedding; other RFC 6052 prefix lengths
  (/40, /48, /56, /64) place the IPv4 at different offsets and are not unpacked.
- `BoundedBodyReader`'s watchdog has a benign completion race (it may fire just as the read
  finishes); it is fail-safe — the worst case is a completed body reported truncated.

### Note for the merge

`engine/mcp/McpApiToolBuilder.java` is also edited on branch `fix/security-qute-engine` (template
variable-name safety). This branch touched only the OpenAPI-resolution logic in `parseSpec` (and one
comment in `buildApiCall` about the removed cookie session); the two changes are in different methods and
should merge cleanly.

```decision-log
| 2026-09-26 | Bound outbound response reads with a shared BoundedBodyReader (cap + deadline) rather than per-tool ad-hoc checks | One implementation across attachment/web/pdf/A2A paths, mirroring the crawler's tested readBounded; size is enforced while streaming, not after buffering | Keep per-tool post-buffer size checks (the status quo that let unbounded bodies into memory first) |
| 2026-09-26 | Drop the shared WebClientSession cookie jar for httpcalls in favour of a plain WebClient | The single app-scoped session replayed one user's Set-Cookie on another user's call to the same host; no httpcalls feature needs cookies | Scope a WebClientSession per conversation/principal (more machinery for a capability nothing uses) |
| 2026-09-26 | Disable redirect-following when a request header carries any credential, instead of stripping connection-owned headers per cross-origin hop | Simple and fails safe; Vert.x strips only the three RFC headers cross-origin, and the custom-header strip would have to be threaded through the Vert.x redirect handler | Strip all connection-owned headers on each cross-origin hop |
```
