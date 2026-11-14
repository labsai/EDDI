## 🔒 fix(security): outbound and SSRF residuals — OpenAPI $refs, agent-call marker, crawler scope (2026-11-11)

**Repo:** EDDI (`fix/outbound-ssrf-residuals`)

### What changed and why

Seven confirmed findings from a security review of the outbound paths.

- **OpenAPI `$ref`s no longer reach swagger-parser's own HTTP client.** Only the top-level spec went through
  `OpenApiSpecFetcher`; every `$ref` was fetched by swagger-parser with a deny-list that misses CGNAT (Alibaba's
  `100.100.100.200`), Azure `168.63.129.16` and OCI `192.0.0.192`, ignores `eddi.security.ssrf-protection.*`,
  re-resolves DNS after its check, follows redirects unchecked and reads without a limit. The new
  `OpenApiRefBundler` fetches every referenced document through `OpenApiSpecFetcher` (same policy per hop, same
  16 MB cap; at most 32 documents, 16 MB together, chains 16 deep, 500,000 nodes written) and inlines the referenced
  nodes, so the parser only ever sees `#/…` refs — checked before parsing. A ref that cannot be fetched refuses the
  spec. Recursion across remote documents is cut with an empty schema; textual `$id`s are dropped. The parser
  also resolves discriminator `mapping` values (not `$ref`s): an external one is refused. As a backstop that does
  not depend on that enumeration, the parser runs with `setRemoteRefBlockList(List.of("*"))`: its
  `PermittedUrlsChecker` refuses every host from the deny list before any DNS lookup.
- **Agent-originated requests are marked where the caller's token is released.** `${caller:token}` is released to the
  origin the caller addressed — usually the public hostname — but `AgentCallMarker` only marked loopback,
  `eddi.self.base-url` and `eddi.hitl.oversight.self-hosts`. Now a request carrying a released caller token is
  always marked (httpcalls and MCP servers), and a target on the caller's origin counts as self.
- **HITL decisions refuse the marker.** `AgentOriginatedRequestFilter` (and `OversightWriteGuard` for approved tool
  calls) now also guard `POST /agents/{id}/resume[/stream]` and `POST /groups/{id}/conversations/{id}/approve[/stream]`
  and `/human-input`: a prompt-injected agent could resume another pause as the approver it was talking to, and the
  verdict counted as human evidence toward earned autonomy. Added as separate patterns so the spend-control PR
  that extends the existing ones merges cleanly. Paths are canonicalized (case, matrix parameters, repeated
  slashes) before matching. The MCP tools `resume_conversation`, `approve_group_phase` and
  `submit_group_human_input` refuse a marked `/mcp` request through the shared `AgentOriginatedRequests` helper,
  which reads the header from the `CurrentVertxRequest` quarkus-mcp-server sets for each message. Decision paths
  are matched segment by segment (`AgentOriginatedRequests.isDecisionPath` / `containsDecisionPath`), not by a
  regex: the earlier pattern's two open-ended id groups backtracked polynomially on a caller-chosen path of
  repeated `a/conversations/` (CodeQL `java/polynomial-redos`).
- **Startup WARN with a forward proxy and SSRF protection both on**: the proxy resolves targets itself, so it must
  enforce the egress deny list. Routing unchanged.
- **Crawler `maxBytesPerPage` capped at 50 MB** (validated; stored values and `CrawlRequest` clamped).
- **Crawler scope reads one canonical path.** Encoded dot segments (`%2e%2e`, `..;`) are resolved before the prefix,
  exclude and robots checks; a path with an encoded `/` or `\` is out of scope. Same-site scope now also requires
  the seed's scheme and port (an http seed may follow its host's https on the default port).
- **`AttachmentForwarder`'s image-URL fast path** runs the same `validateUrl` as the download path.
- **`HtmlToMarkdownConverter`** keeps only `http`, `https` and `mailto` destinations (at most 2048 chars), drops
  link targets it cannot resolve, and routes image `src` through `asDestination`.

### Review follow-ups

- **Decision paths are judged raw as well as decoded**: a matrix value with an encoded slash (`approve;x=%2Fzzz`)
  made the decoded path end in `zzz` while the router served `approve`. The filter now also reads the raw path,
  strips matrix parameters per raw segment before decoding, and refuses when either reading is a decision path.
  The raw path comes from the request URI, not `UriInfo.getPath(false)`: RESTEasy Reactive throws from that on
  every call, which answered 400 to every request (`/manage` and all store writes included) until the filter read
  the path only for marked writes, and from the request URI.
- **Every MCP request to this deployment is marked**, whatever its credential (static key, connection, none), not
  only one carrying `${caller:token}`.
- **OpenAPI bundling**: the output is capped at 32 M characters while it is written (a shared large value
  referenced many times no longer builds an unbounded string); a relative or `#` `$ref` under an `$id` that
  changes the base is refused instead of resolved against the wrong document; `$ref` siblings are merged keeping
  the stricter bound, the union of `required` and the intersection of `enum` (holder wins otherwise), because
  `OpenApiArgumentSchemas` turns a non-object `allOf` into an untyped schema; redirect aliases no longer count
  twice toward the 32-document cap.
- **Ingestion**: `maxBytesPerPage` above 50 MB is refused on save and import only; a source stored with one runs,
  clamped (`validateStored` on the run paths). Crawl redirect hops are held to the crawl scope before they are
  requested. Adjacent slashes no longer absorb a `..` in the robots/scope path.
- **Uploaded HTML** keeps relative links and images relative again (a real relative reference only: no scheme, no
  authority, no whitespace), as `HtmlDocumentExtractor` documents.
- **Attachments**: an image URL refused by validation now leaves the model a generic note, like a failed download.
### Second review round

- **Crawl redirect hops keep the policy's checks**: the crawler's scope check is now an `additionalHopCheck` on
  `OutboundRequest`, run after the SSRF policy's hop check. A `hopValidator` replaced that check and dropped the
  connected-peer check for the hop (the DNS-rebinding guard on the Vert.x engine); `hopValidator` keeps its
  replacing meaning for the callers that need it.
- **One served path for scope**: a redirect hop is checked as it will be requested, and page identities use the
  served path too (`/docs/%2e/../private` is `/private`, not `URI.normalize()`'s `/docs/private`). A path with `;`
  parameters is judged as written and with them dropped, for the prefix, `excludePatterns` and robots.txt.
- **OpenAPI literal data**: a `$ref` inside `example`, `default`, `const`, `enum`, an Example `value`, a JSON Schema
  `examples` array or an `x-` extension is copied as a value and never fetched; keys of name maps (`properties`,
  `schemas`, `responses`, ...) are names, so a property called `example` is still a schema.
- **Discriminator mappings in referenced documents** are rewritten: each mapped schema is moved into the root's
  `components.schemas` (renamed on a clash) and the mapping value points at it.
- **Enum intersection** is indexed (linear), keeping the holder's order.
- **Markdown destinations** with an embedded line ending or control character are dropped.
### Design decisions

- Inline-and-bundle rather than refusing remote refs: multi-file specs keep working, and no new property is needed.
- The http→https upgrade exception in crawl scope: nearly every http site redirects to https, and refusing it would
  make an http seed crawl nothing after the redirect.
- Group `/cancel` and agent `/cancel` are not guarded: they stop work, they do not record an approval.

**Files:** [`OpenApiRefBundler.java`](../../src/main/java/ai/labs/eddi/engine/mcp/OpenApiRefBundler.java),
[`McpApiToolBuilder.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java),
[`AgentCallMarker.java`](../../src/main/java/ai/labs/eddi/engine/hitl/oversight/AgentCallMarker.java),
[`AgentOriginatedRequestFilter.java`](../../src/main/java/ai/labs/eddi/engine/hitl/oversight/rest/AgentOriginatedRequestFilter.java),
[`OversightWriteGuard.java`](../../src/main/java/ai/labs/eddi/engine/hitl/oversight/OversightWriteGuard.java),
[`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`McpToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/McpToolProviderManager.java),
[`OutboundHttp.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/outbound/OutboundHttp.java),
[`CrawlUrls.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/CrawlUrls.java),
[`WebCrawler.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/WebCrawler.java),
[`IngestionSource.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/IngestionSource.java),
[`AttachmentForwarder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarder.java),
[`HtmlToMarkdownConverter.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/HtmlToMarkdownConverter.java);
docs: [`security.md`](../security.md), [`hitl-oversight.md`](../hitl-oversight.md), [`rag.md`](../rag.md).
