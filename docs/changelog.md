# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Not here.** Write your entry as a new file in
[`changelog.d/`](changelog.d/README.md) — `YYYY-MM-DD-<slug>.md`, with the slug
unique to your branch — and leave this file alone. The same goes for the two
running registers at the bottom: their rows ride along in the fragment, in a
fenced `decision-log` or `regression-note` block.

Entries used to be inserted at the top of this file, and the registers appended
to at the bottom. Both are a fixed point in a shared file, which git cannot
merge: with several PRs open, every one of them conflicted with every other over
a document that had nothing to do with the code under review. A fragment is a new
file under a name no other branch picks, so the same two PRs merge without
touching each other.

`.github/workflows/changelog-collate.yml` runs nightly, merges the fragments in
here **by date** — a PR that stayed open for weeks lands among its
contemporaries rather than on top — trims this file back under its rotation
target, and opens a PR. Until that PR merges, `changelog.d/` holds the newest
history, so read it alongside the top of this file. To do it by hand:

```bash
python scripts/collate-changelog.py   # fragments -> this file
python scripts/rotate-changelog.py    # this file -> docs/changelog/<YYYY-MM>.md
```

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. Rotation runs at a
lower threshold than the cap, trimming back to **200 KB** whenever the file is
over that, so the session whose entry tips it over is not the one made to rotate
it. Rotation moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by date,
adds one `../` to the relative links it moves (an archive sits a directory deeper
than this file) without touching the ones inside code spans, and regenerates both
the Archive table below and the changelog list in [`SUMMARY.md`](SUMMARY.md) from
what is on disk. Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [November 2026](changelog/2026-11.md) | 15 | 50 KB |
| [October 2026](changelog/2026-10.md) | 267 | 1235 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1216 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## ✨ feat(httpcalls): validate LLM tool arguments against an argument schema before the approval gate (2026-11-04)

**Repo:** EDDI (`feat/httpcall-argument-schema`)

### What changed and why

Generated OpenAPI tools filled a whole-body template from string parameters, so a malformed call
(missing required field, wrong type, misspelled field, value outside an enum) reached the human
approval card and then failed at the API — or, when the API ignores unknown fields, silently did
nothing after a human had approved it.

- **`ApiCall.argumentSchema`** (new, optional) — JSON Schema of the tool arguments. `null` keeps
  string parameters and today's rendering byte for byte. **`ApiCall.unknownFields`**
  (`reject` | `drop` | `allow`, default `reject` when a schema is set; save-time validated).
- **`McpApiToolBuilder`** generates the schema per operation (new `OpenApiArgumentSchemas`): scalar
  path/query parameters typed, the body schema with every component `$ref` resolved (`allOf` of
  objects merged, recursion and `anyOf`/`oneOf` left untyped, `readOnly` fields omitted).
- **`HttpCallToolsProvider`** types the tool parameters from the schema (via the new
  `ResponseSchemaConverter.toElement`; a parameter with no tool form, including a property-less object
  Gemini would reject, stays a string). With a schema, arguments are normalised (a JSON string for an
  object/array parameter is decoded; `drop` removes undeclared fields) and every non-string value is
  rendered as compact JSON — never a Java `toString` — and substituted as data, as before.
- **`ToolLoopRunner.refuseInvalidArguments`** runs before `ToolApprovalGate.classify`: every executor
  implementing the new SPI `ToolArgumentValidator` is asked; a violating call gets a `NOT_EXECUTED`
  envelope with one line per violation (`$.requestBody.emails: required`,
  `$.requestBody.role: must be one of manager, curator`, `… unknown field (declared fields: …)`), a
  `tool_error` trace step with `error: "argument_schema"`, and `eddi.tool.arguments.refused`. It is
  never paused or executed. The httpcall executor re-checks before sending, which covers resumed calls.
- **`ToolArgumentSchemaValidator`** (`modules/llm/guardrails`) — pure function, uses the
  networknt validator already on the classpath on an effective schema reduced to a safe subset
  (no `$ref` loading, no `pattern`); built so it can become a tool-call guardrail type later.

Docs: [`httpcalls.md`](httpcalls.md#argument-schema-for-llm-tools),
[`langchain.md`](langchain.md#argument-schema-refusals).

**Files:** [`ApiCall.java`](../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCall.java),
[`ToolArgumentSchemaValidator.java`](../src/main/java/ai/labs/eddi/modules/llm/guardrails/ToolArgumentSchemaValidator.java),
[`ToolArgumentValidator.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/spi/ToolArgumentValidator.java),
[`OpenApiArgumentSchemas.java`](../src/main/java/ai/labs/eddi/engine/mcp/OpenApiArgumentSchemas.java),
`McpApiToolBuilder.java`, `HttpCallToolsProvider.java`, `ToolLoopRunner.java`,
`ResponseSchemaConverter.java`, `ApiCallsConfigurationValidator.java`.

### Design decisions

- Rendering moved to the tool-argument boundary (`HttpCallToolsProvider.templateDataFor`, shared by
  the gate-time resolver and the executor) rather than `ApiCallExecutor`: the executor keeps receiving
  strings, so the fingerprint pinned at gate time and the request built at execution agree.
- `drop` is applied when the request is built, not by rewriting the model's call: the transcript and
  the approval card keep the call as the model wrote it.
- Top-level parameters stay all-required as before (an omitted query value would render `?name=`);
  requiredness inside the body follows the spec.

---

## ✨ feat(llm): rescue near-miss JSON replies without a second model call (2026-11-04)

**Repo:** EDDI (`feat/llm-json-near-miss-rescue`)

### What changed and why

A tool-using agent on Gemini, Anthropic or Bedrock gets no native JSON mode, and in production about 2-6% of the turns of such agents were replaced by the fallback answer although the model had written a good one: plain HTML/text with no JSON wrapper (most often non-Latin scripts), double-escaped JSON, a misspelled field name, or `{}`. `onInvalidJson: "retry"` helps but costs a second model call. Three opt-in local repairs now cover the first three shapes, in the shared parse path (`ModelOutputParser`), so the live turn, the HITL resume, the format-retry runner's detection, the circuit breaker's view and every cascade step see the same reply:

- **`responseValidation.onInvalidJson: "wrap"`** with required `wrapField`, optional `wrapDefaults` and `wrapMaxChars` (default 20,000): a non-JSON, non-blank reply becomes `{ "<wrapField>": "<text>" }` (plus the defaults) and is validated like any parsed reply. Text starting with `{` or `[` is broken JSON and is never wrapped (`fallbackAction` applies). New parse outcome `wrapped` (`llm:output:outcome:*`, `eddi.llm.output{outcome=wrapped}`), `eddi.llm.recovery{action=wrap}` and a `LlmRecoveryLog` line.
- **Double-escape repair** (`{\"a\": \"b\"}`): one-level unescape, on whenever `onInvalidJson` is `wrap` or `retry`; accepted only as a JSON object and never tried on a reply that already parsed.
- **Task `fieldAliases`** (`{"htmlResponseStr": "htmlResponseText"}`): renames a top-level key when the target is absent (the target wins when both exist).
- `{}` stays covered by `nonBlankFields` plus `onSchemaMismatch: "retry"`; documented as the recommended pair.

Save-time validation in `LlmStore`: `wrap` without `wrapField` is a 400 naming what to set; `wrapField`, alias names and `wrapDefaults` keys must be plain top-level names. A config without the new fields behaves exactly as before.

### Design decisions

- The options are resolved once into `RescueOptions.of(task)` and passed through the existing `parse` / `Policy.from` / `classifyJsonReply` / `convertResponseToObject` overloads (the old signatures delegate with `RescueOptions.NONE`), so a later caller that routes a plain-text answer through the invalid-JSON handling (the `answerMode: "tool"` work) gets the rescue for free.
- Wrapping and the double-escape repair follow `responseValidation.enabled` like every other policy; `fieldAliases` is independent of it, like `nonBlankFields`.
- Plan deviation: `wrap` and `retry` are alternatives for the single `onInvalidJson` action. A reply `wrap` cannot rescue (broken JSON, over the cap) is handled by `fallbackAction` (as after spent retries), not by a second policy.
- Platform Operator: no change needed (it does not teach per-task response-validation fields).

**Files:** [`ModelOutputParser.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java), [`RescueOptions.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/RescueOptions.java), [`FormatRetryRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LlmConfiguration.java`](../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`LlmStore.java`](../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java), [`langchain.md`](langchain.md), [`llm-resilience.md`](llm-resilience.md)

---

## ✨ feat(setup-api): per-turn auth from secret context, generate-only, and re-sync (2026-11-04)

**Repo:** EDDI (`feat/setup-api-context-auth-sync`)

### What changed and why

An integrator could not build a per-user-token operator agent with EDDI's own OpenAPI converter, nor update one from a regenerated spec. Three additions, usable independently:

1. **`${context:<key>}` in httpcall headers and in `apiAuth`.** Resolved per request from the turn's context entry of that key — only when the client marked it `"secret": true`, only in a header, only as a lone reference after an optional scheme, only when written in the configured header itself, and only towards the origin of the httpcalls configuration's `targetServerUrl`. A missing value, an empty one, or the `<secret context>` placeholder a resumed turn holds when the client did not re-supply it fails the call — never `Bearer ` with nothing. The plaintext joins the executor's redaction set (request record, approval preview, error bodies, logs) and disables redirects. Read from the step datum (`context:<key>`), which holds the live value during the turn and after a HITL resume re-injects it (`Conversation.injectResumeSecretContext`). A Qute pass-through resolver for the `context` namespace keeps the reference intact through templating. Metric `eddi.httpcalls.context.reference{outcome}`.
2. **`"dryRun": true` on `POST /administration/agents/setup-api`.** Runs the whole setup code path with every write skipped (`ResourceWriter`): no store, vault, descriptor, grant or deploy. Returns `200` with the apicalls configurations, tool names, LLM configuration, workflow and agent (gate included). A plaintext `apiKey`/`apiAuth` is shown as a placeholder. Hidden from the OpenAPI schema so the Platform Operator's generated `setup-api` tool cannot carry it.
3. **`POST /administration/agents/{agentId}/setup-api/sync`.** Regenerates only the apicalls configurations stamped with the new `ApiCallsConfiguration.openApiOrigin` marker (now set by every setup), rewrites the LLM task's `tools` list and the generated API summary in its prompt, keeps everything else, and saves the agent as a compatible version (`updateAgent(..., compatible=true)`), then deploys unless `deploy: false`. Reports added/changed/removed tools; refuses with 400 and nothing written when a removed tool is still named by `toolQuotas`, a `toolApprovals` exemption (agent or task) or `toolResultGuardrails.exemptTools`, listing each. The request's `requireApproval` patterns are unioned into the stored gate; nothing is removed and request exemptions are ignored. An agent without marked configurations (created before this change) is refused with how to add the marker. Supports `dryRun`.

### Findings

- The existing `Bearer {context.userToken}` Qute form does **not** leak into persisted memory: an `Authorization` header is name-redacted in the request record, and the turn-end scrub (`Conversation.scrubSecretContextValues`) removes the value (8+ characters) from every step datum, property, pending tool-call batch and audit entry. It is weaker in-turn: it renders anywhere (path, query, body), towards any host, follows redirects, sends `Bearer ` when missing, and an API error body echoing the token reaches the model's tool result only pattern-redacted. Unchanged; the reference is documented as the recommended form.

- Review follow-up: a dry run that names an existing vault key (`vaultKeyName`) together with a plaintext `apiKey` can't tell reuse from creation without vault metadata, so it reports the destination-host rule a new key would fail in `resources.warnings` instead of refusing the request; the live setup checks hosts only when it creates the key.

### Not done

- `toolDisplay` reference checks: the field is not on `main` yet (`feat/resume-stream-tool-events`); sync preserves it (the LLM task is copied whole) but cannot check it for dangling references.

**Files:** [`SecretContextReferences.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/SecretContextReferences.java), [`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`AgentSetupService.java`](../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java), [`ApiAgentSyncService.java`](../src/main/java/ai/labs/eddi/engine/setup/ApiAgentSyncService.java), [`ApiCallsConfiguration.java`](../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCallsConfiguration.java); docs [`httpcalls.md`](httpcalls.md), [`architecture.md`](architecture.md), [`rest-api-reference.md`](rest-api-reference.md), [`secrets-vault.md`](secrets-vault.md).

---

## 🛡️ fix(groups, llm): fenced peer text in group prompts, head-and-tail tool truncation, identifiers kept in summaries, retry jitter (2026-11-04)

**Repo:** EDDI (`fix/tier1-prompt-hygiene-runtime`)

Four small changes that came out of comparing EDDI with OpenClaw's group-chat and agent runtime.
Each one is local to one or two files.

### 1. Peer text in group prompts is fenced, cleaned and bounded

The preset discussion templates quoted other participants as `— {entry.speaker}: "{entry.content}"`.
A peer is not necessarily trustworthy (a dynamic or recruited agent, a human member, or a model
talked into something), and its text could close the quotation and carry on as if it were the
template giving instructions. The text could also be any size.

- **New `PeerContentGuard`** ([`PeerContentGuard.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/PeerContentGuard.java)),
  applied once in `GroupContextBuilder.buildPhaseInput` to the template data of every phase type,
  custom `inputTemplate`s included. It breaks any ` ``` ` inside quoted text with a zero-width space,
  reduces a speaker name to one line of at most 120 characters, cuts one entry at 12,000 characters
  with a note, and drops the oldest entries of a list once it passes 96,000 characters, leaving one
  `System` note in their place. The newest entry is always kept. The stored transcript is unchanged.
- **The 14 presets that quote peers** ([`DiscussionStylePresets.java`](../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java))
  now put that text between ` ``` ` fences, including `targetResponse` and `originalResponse`, and
  open with `QUOTED_CONTENT_NOTICE`, which tells the member that fenced text is quoted material to
  weigh and not instructions to follow.
- The participant names spliced into the instructions themselves (`targetName` in "You are reviewing
  {targetName}'s…", the speaker's own `displayName`) get the same one-line treatment, and the list
  budget counts every rendered string of an entry (names and phase name too), not only its content.
- The Manager's verbatim copy of the arbitration prompt (`NEGOTIATION_ARBITRATION_TEMPLATE` in
  [`hitl-config.ts`](../ui/manager/src/lib/hitl-config.ts)) follows the new preset; a Vitest
  compares the two.
- The user's own `question` is not treated as peer text.
- **Not covered yet:** the task-force templates (`TEMPLATE_EXECUTE`, `TEMPLATE_VERIFY`), which
  `TaskForceEngine` renders from its own data. They quote task results the same unfenced way.

### 2. Tool results over the limit keep their start and their end

`ToolResponseTruncator` cut an over-limit result after its first `maxChars` characters, which drops
exactly what a model most needs from a failed command or API call: the exit code, the error and the
totals at the end.

- **New strategy `headTail`**, the default when `toolResponseLimits.truncationStrategy` is unset. It
  keeps 70% of the limit from the start and 30% from the end, with a `[TRUNCATED: …]` note in place
  of the middle. The kept content is still exactly the limit, and neither cut splits a surrogate pair.
- `truncate` (start only) is unchanged when set explicitly, and is still what an unknown strategy
  name and the `paginate`/`summarize` fallbacks use.
- `toolResponseLimits` had no documentation, so it now has its own section in
  [`langchain.md`](langchain.md#tool-response-limits).

### 3. Summaries keep identifiers exactly as written

The rolling conversation summary's default prompt ([`ConversationSummarizer.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationSummarizer.java))
gained a seventh rule: copy every opaque identifier (IDs, UUIDs, hashes, order and ticket numbers,
URLs, hostnames, IPs, ports, file names and paths) exactly as written. The group context-window
summary (`GroupContextBuilder.WINDOW_SUMMARY_INSTRUCTIONS`) got the same line. A custom
`summarizationPrompt` replaces the whole default, so it gets none of this; the docs now say so.

### 4. Retry backoff has jitter

`RetryConfiguration` slept exactly `backoffDelayMs × multiplier^n`, so every conversation that hit
the same provider outage retried in lockstep and arrived in one burst again.

- **New field `jitterRatio`**, default `0.2`: each configured backoff is shortened by a random share
  of up to that fraction (a 1000 ms backoff sleeps 800–1000 ms). Applied in both `executeWithRetry`
  and the public `backoff(...)` used by the streaming path.
- **Downwards only**, so a jittered sleep never exceeds `maxBackoffDelayMs`, the 60-second total
  backoff budget or the turn deadline. A provider-requested wait (`honorRetryAfter`) is never
  shortened. `0` turns it off; values outside `[0, 1]` are clamped and `NaN` counts as `0`.
- The Manager's `retry` type gained the optional field ([`types.ts`](../ui/manager/src/components/editors/llm/types.ts)).

### Behaviour changes operators may notice

- A tool result over its limit now shows its last 30% instead of more of its start. Set
  `"truncationStrategy": "truncate"` to keep the old output.
- Retry sleeps are up to 20% shorter and no longer identical across conversations.
- Group member prompts show peer text in fences, with a one-line notice at the top.

### Tests

New `PeerContentGuardTest`, including a real Qute render in which a peer's attempt to close the
fence stays inside it, and a check that every peer-quoting preset carries the notice. Also new
head-and-tail cases in `ToolResponseTruncatorExtendedTest`, jitter cases in
`RetryConfigurationTest`, and an identifier-rule case in `ConversationSummarizerTest`. Existing
retry tests that assert exact sleep values or exact budget arithmetic
(`RetryConfigurationTest`, `RetryConfigurationDeadlineTest`, `StreamingLegacyChatExecutorRetryTest`,
`ToolExchangeIdsAndProvenanceTest`) now set `jitterRatio` to `0`: jitter only shortens a sleep, so
with it on, one more retry fits inside the same budget.

---

## 🔒 fix(backup): agent-findings review — repeated references, parser references, export vetting (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-backup`)

### What changed and why

Verified the 43 CodeRabbit "agent findings" on backup / import / export / sync against the
current code. 18 had been scanned before the review fixes of 2026-10-31 and most of those were
already closed there; the rest were checked on today's code.

**One document, one copy, one request**

- The ZIP and remote source readers read and parse a workflow once per id and version, however
  often the source agent names it; every further position shares that record
  (`IResourceSource.atPosition`). `UpgradeExecutor` now repoints a copy of an adopted source
  config (`copyOf`) instead of the shared one.
- The ZIP reader reads an extension file once per archive path; both readers scan a parser
  document for its dictionaries once, not once per step naming it.
- The remote reader remembers failed reads, so a repeated failing reference is not requested
  again, and stops after `eddi.backup.sync.max-requests` requests (default `10000`) with `502`.
- Export reads each referenced resource once (`IResourceId` has no value equality, so the map kept
  one copy per reference); the legacy merge preview scans each archived workflow once.
- Import: two spellings of one workflow URI are one workflow; versions of one id, which read the
  same archive file, are decoded and written once and every reference names the one resource;
  a workflow directory holding more than one workflow document is a `400`.
- `StructuralMatcher` indexes source extensions by id instead of scanning them per diff row.
- Export finds `${connection:…}` references with the linear `ConnectionReference.findAll`
  instead of the backtracking regex.

**Authorization**

- Export no longer counts an agent or group reference (checked for `USE` only when the workflow
  was written) as vetting the id it carries, so a configuration named elsewhere under that id
  needs its own `VIEW`.
- A parser document created by import or upgrade runs `RestParserStore`'s reference check: every
  dictionary it names needs `VIEW` (`WorkflowReferenceAccess.requireAccessToParserReferences`).
- A structural preview's REMOVE row names a target workflow only for a caller with `VIEW` on it.
- A failed merge's rollback puts back the name, description, origin and sync baseline it rewrote
  onto the *current* descriptor, so a sharing change the owner made meanwhile is not undone.

**Secrets**

- `ScrubbedSecrets` compares the destination fields of both sides: a source that drops the
  target's `baseUrl` (provider default) or carries a non-string `host` counts as a changed
  destination, and the target's literal credential is not restored into it.
- `SecretScrubber` judges a header or query map serialized into a string (an LLM task's
  `customHeaders` / `customQueryParams`) entry by entry under the map's name, as the editor read
  does, so an opaque credential header no longer leaves in an export.

**Verdicts** (43 findings):

| Verdict | Count | Notes |
| --- | --- | --- |
| FIXED | 24 | this branch |
| ALREADY_FIXED | 16 | the 2026-10-31 backup review fixes (#1189) |
| ACCEPTED_RISK | 2 | DNS rebinding between check and connect (documented in `security.md`); `VIEW` on an agent covering its step references (documented in `import-export-an-agent.md`) |
| NEEDS_DECISION | 1 | per-name connection lookups on export |

### Design decisions

- Repeated references are collapsed, not refused: an agent naming a workflow twice and a stale
  parser document naming an older dictionary version are legitimate and keep importing.
- The request budget is a count, configurable, far above any real agent; an elapsed-time budget
  for the whole sync was not added (each request already has its own 30 s timeout).
- Not done: export resolves each distinct `${connection:…}` name through
  `IConnectionStore.idOfName` + `readByName`, each of which reads every connection; a batched
  lookup needs a store API change (left as a decision).

**Files:** [`IResourceSource.java`](../src/main/java/ai/labs/eddi/backup/IResourceSource.java),
[`RemoteApiResourceSource.java`](../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java),
[`ZipResourceSource.java`](../src/main/java/ai/labs/eddi/backup/impl/ZipResourceSource.java),
[`RestExportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestExportService.java),
[`RestImportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`UpgradeExecutor.java`](../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`StructuralMatcher.java`](../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`ScrubbedSecrets.java`](../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java),
[`WorkflowReferenceAccess.java`](../src/main/java/ai/labs/eddi/backup/impl/WorkflowReferenceAccess.java),
[`SecretScrubber.java`](../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java),
[`import-export-an-agent.md`](import-export-an-agent.md), [`agent-sync-guide.md`](agent-sync-guide.md),
[`configuration-reference.md`](configuration-reference.md)

---

## 🐛 fix(configs,datastore): agent findings for the configuration stores and the datastore (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-configs-datastore`)

Verified the 56 CodeRabbit "agent findings" for `configs/**`, `datastore/mongo`, `datastore/postgres` and
`ui/BrandedOpenApiRoute` against `main`. Many had been closed since the scans, mostly by #1191
(`87f98b7b3`), #1190, #1175, #1173 and #1161. Verdicts: 14 fixed here, 29 already fixed, 5 accepted risk,
8 needing a decision, 0 false positives (two of the already-fixed ones left a gap that is fixed here too).

### What changed and why

**Reference checks.**
- `RestWorkflowStore.addReference` now needs only a host, not a scheme. A scheme-relative
  `//ai.labs.output/outputstore/outputsets/<id>` was skipped, but the engine resolves a step by host and
  id and loaded it anyway.
- A parser document's own `config.uri` is now one of the references `RestParserStore` checks. The
  standalone parse endpoint follows it (`InputParserTask.loadParserDocument`) and uses that parser's
  dictionaries.
- `RestAction` and `RestExpression` (the Manager's action/expression helpers) check VIEW on every rule
  set, API-call set, output set and dictionary they read, not just on the workflow. They pick a store by
  step type and read whatever id `config.uri` spells, relative ones included. A resource the caller
  cannot read contributes nothing.

**Artifact validators (group conversations).**
- A JSON-schema validator is refused at save, and not run at write, when its evaluation is unbounded
  (`ArtifactSchemaComplexity`). The library evaluates every `$ref` separately and reads no clock.
  Measured: 20 `$defs` that each `allOf` the previous one twice took 2 s to save and 3.6 s per artifact
  write, doubling with each further level. A self-`allOf` threw a `StackOverflowError` out of the tool
  call, and two routes to the same property doubled the work per nesting level. The check refuses:
  - an expansion beyond 2000 subschemas;
  - recursion that comes back at the same content location;
  - two recursive routes that do not start with differently named properties.

  Trees and binary trees still pass. A nested `$id` is refused rather than resolved by guesswork.
- `REGEX` validators and schema `pattern`s are run through `BoundedRegex.excessiveRepetition`, the scan
  agent tests already use. A counted repetition of an empty match (`(?:(?:){99999}){99999}`) spins
  without reading input, so the match deadline never saw it.

**JSON Patch.** `ConfigJsonPatcher` masks credentials and judges their relocation by the store's own
read rules. Before, it used the generic redaction rules, while `RestLlmStore`'s read leaves
`tasks[].toolCacheScopes` as it is. A key copied to `toolCacheScopes.apiKey` was accepted as "still
hidden" and then returned in plain text. The LLM and API-call stores pass their `NOT_CONFIGURATION` skip
paths through a new `RestVersionInfo.patch` overload.

**Knowledge bases.**
- A 6.5.0 name-layout knowledge base that changes its `storeType`, or the server it connects to, moves
  to the id layout. The server parameters are `host`, `port`, `database`, `databaseName`,
  `connectionString`, `serverUrl`, `baseUrl` and `tenantName`. On the new backend or server the same
  name maps to that one's default store, which can belong to another legacy knowledge base with the
  same name.
- The standalone endpoints (search, ingest, documents, chunks) apply the deployment rule: the caller
  must belong to every space tenant the configuration's `${vars:<tenant>/…}` / `${vault:<tenant>/…}`
  references name, with variables expanded and connections followed. They build the model and store on
  the spot with the server's authority, and no deployment ever checked them. This adds
  `SpaceReferenceGuard.requireMayUse` and `VaultGrantChecker.referencedTenantsOf`. Docs:
  [RAG](rag.md).

**Connections.** `ConnectionStore.readByName` reads the live version of each connection. Before, it
read the version the descriptor names. The response filter advances that descriptor only after
`updateConnection` has invalidated the registry, so a lookup in between cached the previous
`allowedHosts` for the registry's one-minute TTL.

**Datastore.** `GridFsAttachmentStore` counts quota usage, and enumerates a conversation's blobs for
erasure, on the primary, like `MongoScheduleStore`'s erasure reads. The client reads `nearest`, so a
lagging secondary undercounted admitted uploads and left committed blobs out of an erasure.

**API description.** `quarkus.http.auth.permission.api-docs.paths` now lists `/openapi.json`, `.yaml`,
`.yml` and their `/q/` forms. Neither `/openapi` nor `/openapi/*` matches them, so the role-gated
document was served to any authenticated token through a suffix URL. Docs:
[security](security.md), [configuration reference](configuration-reference.md).

### Design decisions

- **Schema complexity is bounded statically.** networknt offers no hook that runs during evaluation, so
  a budget cannot be enforced while the schema runs. The analysis follows only references inside the
  document. References to other documents are left to the loader, which loads nothing but the bundled
  meta-schemas.
- **Standalone RAG reuses the deployment rule** (`SpaceReferenceGuard`) instead of a second
  tenant-membership check.

### Needs a decision (not changed)

- Descriptor `PATCH` against sharing: the full descriptor is written back, guarded only by the resource
  version, so a concurrent revocation can be undone. Needs a descriptor revision or field-level writes
  on both backends.
- Mongo authorization reads use `nearest`. Moving descriptor reads to the primary affects every
  descriptor read.
- Slack approval records are keyed by the integration's name, not its id.
- A non-admin `duplicateLlm` (or a deep workflow copy) carries plaintext credentials the copier cannot
  read.
- The knowledge-base collision check does not see locations used by older, still-pinned versions.
- Uploads are not fenced against a concurrent conversation erasure (MongoDB and PostgreSQL).

### Follow-up: review findings on the parse path and the connection lookup

- **Standalone parse checks a parser document's `config.uri` per request.** Parser documents saved
  before the save-time check can still name another owner's parser, and `RestSemanticParser` loaded
  them unchecked. The cache entry now records the parser documents `config.uri` named when it was
  built, and every request requires `VIEW` on each of them on the request thread, hit or miss, with the
  same rule `RestParserStore` applies on save. The cache stays shared and the document is still read
  once per cached parser. The parser is now resolved on the request thread instead of the pool thread,
  so that the check has the caller's identity. Not done: a one-off scan or migration of stored legacy
  parsers, and the same per-request check for dictionary references in `extensions`.
- **The connection lookup reads from the primary.** `IResourceStorage.primaryReads()` returns a view
  served by the authoritative copy: on MongoDB the same collections with `ReadPreference.primary()`,
  on PostgreSQL the storage itself. `ConnectionStore` resolves the current version and fetches the
  connection through it, so a lagging secondary cannot hand the registry a connection whose
  `allowedHosts` was just narrowed. Every other store keeps `nearest`. Authorization descriptor reads
  are unchanged and remain the open decision above.

---

## 🔒 fix(engine): agent-findings review of engine/internal — resume USE check, undeploy overflow, synthesis approval under the cost ceiling (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-engine-internal`)

### What changed and why

A batch of 41 scanner findings against `engine/internal` (REST endpoints, conversation
and group authorization, data exposure) was traced through the current code. 26 had
already been fixed by #1161, #1188, #1193 and #1207; the real remainder is fixed here.

- **Resume re-checks USE for an owner.** `HitlAccessGuard.requireConversationResumeAccess`
  is `requireConversationHitlAccess` plus, for a caller who is neither admin nor approver,
  the turn check `say` applies (`ConversationAccessGuard.requireConversationTurnAccess`):
  ownership outlives a revoked share, the right to run the agent does not. Used by
  `POST /agents/{conversationId}/resume` and MCP `resume_conversation`. Approvers and
  admins decide by role, unchanged — the same split the group approve already makes.
- **MCP `get_approval_status` withholds the compiled prompt** from a caller without VIEW
  on the agent, as the REST `approval-status?detail=full` has since #1193.
- **Single-version undeploy refuses a version below 1** (400). The decrementing loop
  wrapped at `Integer.MIN_VALUE` and never ended; the bulk path was bounded in #1193.
- **A SYNTHESIS phase that runs under the `SYNTHESIZE_NOW` skip-ahead keeps its
  `requiresApproval` gate.** Only the phase that trips the ceiling skips its own gate
  (abandoned work); the synthesis answer is executed work and is published.
- **Member tool-pause recovery** resumes with the detailed projection (the summary one
  dropped the dynamic-agent tracking keys, so agents created while resuming escaped
  cleanup) and harvests the resumed run's cost into the ledger before any return,
  including a re-pause — the next member's ceiling check saw a stale total.
- **`GET /administration/logs/history` with `limit` ≤ 0** reads the default page of 100.
  Both log stores treat a non-positive limit as "no limit" and collected the whole
  history onto the heap.

Verdicts: 6 fixed, 26 already fixed, 1 false positive (the vote-option regex: a
400,000-character worst-case synthesis parses in well under the test timeout on the
current JVM, so no quadratic cost could be shown), 3 accepted risk (the auto-approval counter is the
documented consecutive no-progress guard, total pauses are capped by `maxPausesPerTurn`;
a resume deploys its pinned version on demand, documented in `hitl.md`; the agent quota's
bounded concurrent overshoot, documented on `enforceAgentQuota`), 5 need a decision
(contextScope for the phase-specific prompt variables, a durable record of follow-up
creations after a timeout, explicit unretain propagation, an atomic discussion-wide
creation reservation, a configured-task cap).

**Files:** [`HitlAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java),
[`RestAgentEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`McpHitlTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`RestAgentAdministration.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java),
[`GroupConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`MemberTurnExecutor.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java),
[`RestLogAdmin.java`](../src/main/java/ai/labs/eddi/engine/internal/RestLogAdmin.java),
[`log-administration.md`](log-administration.md), [`hitl.md`](hitl.md),
[`workspaces.md`](workspaces.md), [`group-conversations.md`](group-conversations.md)

---

## Agent findings, engine misc: audit erasure barrier, scrubbed audit payloads, MCP schedule floor (2026-11-03)

**Repo:** EDDI. **Branch:** `fix/agent-findings-engine-misc`.

40 CodeRabbit agent findings in `engine/{mcp,memory,a2a,alerts,audit,httpclient,mail,ratings,agenttests}` were traced against the current code. Most were already closed by #1173, #1187, #1188, #1193 and #1198; four real gaps are fixed here, plus one extra item carried over from the llm-impl sibling branch.

**Fixed**

- `AuditLedgerService.stopInFlightWorkByHash` (the peer's reply to the cluster `gdpr-stop`) now waits for a flush that is already committing before it answers. The erasing node sweeps the stored audit rows as soon as every node replied, so a batch drained before the peer marked the user could previously commit its raw entries after the sweep.
- `TurnAuditBuffer` scrubs the buffered audit payloads with the shared deep, JSON-aware `SecretValueScrubber.scrubDeep` instead of its own string/number/map/list walk. A secret inside a payload object, or inside a JSON string in its escaped form (a quote, backslash or newline in the secret), no longer survives into the persisted audit entry.
- MCP `create_schedule` refuses heartbeat and cron intervals below `eddi.schedule.min-interval-seconds` (default 60), as the REST schedule store already did; one MCP call could otherwise create a schedule firing agent turns faster than the deployment's floor.
- OpenAPI discovery from a remote `specUrl` refuses a downloaded document whose `$ref` carries an explicit non-http(s) scheme (`file:`, `classpath:`, a drive path or UNC path). Relative refs stay: they resolve against the document's URL.
- `CompatibilityRules.containsTemplate` strips configuration-reference mentions with a linear forward scan, not a regex that went quadratic on a value of unclosed `{vault:` openers (same approach as `LlmTask.escapeConfigReferenceMentions` on the llm-impl branch).

**Verified, no change needed** — `SelfUngatingGuard.refusalFromArguments` already checks the JSON-decoded keys and string values (`argumentHaystack`, #1192), so a `\uXXXX`-escaped id or key in tool arguments is caught; `SelfUngatingGuardProtectionTest` pins it.

**Open decisions** (not implemented; they change a contract or need a redesign): a cap on active alert rules per agent and a per-event delivery budget; routing OpenAPI external `$ref` fetches through the guarded fetcher (or refusing external refs); a durable deletion barrier between conversation purge and in-flight dependent writes.

Verdicts for the 40 findings: 4 FIXED, 28 ALREADY_FIXED (two of them on the sibling `fix/agent-findings-engine-internal` branch), 1 FALSE_POSITIVE, 3 ACCEPTED_RISK, 4 NEEDS_DECISION.

**Review follow-up (PR #1257)**

- `CronParser.computeMinIntervalSeconds` skips calendar dates a zone does not contain (Pacific/Apia lost 2011-12-30), which used to yield a gap of 0 and reject a valid daily schedule on REST, MCP and RAG ingestion alike.
- Embedded JSON in scrubbed strings is read with decimals as `BigDecimal`: unrelated decimals keep their text (`1.00` stays `1.00`) and a secret number is matched by value.
- A downloaded OpenAPI spec is checked on its decoded tree only (prose and YAML comments that mention `$ref: file:` no longer refuse it) and a `file:`/`jar:`/`classpath:` schema `$id` is refused, since relative refs resolve against it.
- The MCP schedule-floor error tells operators to lower, not raise, `eddi.schedule.min-interval-seconds`.

---

## 🔒 fix(infra): agent-findings triage of the installer, GCP provisioner, CI and load-test scripts (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-infra`)

### What changed and why

A batch of 35 machine-generated security findings against the infrastructure files was
checked against the current code. Most were already closed by the earlier infra review
pass (#1185); the rest are fixed here or recorded as accepted risk or open decisions.

- **`install.sh`** sends Keycloak and Grafana credentials to `127.0.0.1` instead of
  `localhost`. The Docker ownership check matches the published IPv4 port, but `localhost`
  can resolve to `::1` first, where another local user's listener could hold the same port.
- **`gcp/provision-vm.sh`**: the generated startup script reads Keycloak's real port from
  `.env` (the installer moves off an occupied 8180), sends the admin password only to a port
  Docker reports as a running `keycloak` container's, uses the same port in nginx, and passes
  the admin bearer token through a curl config on stdin rather than `-H` (argv).
- **`src/main/docker/seed-demo-agent.sh`**: the `/v1/models` bearer key goes through
  `curl -K -` instead of `-H`.
- **`.github/workflows/ci.yml`**: the PR title shown in the Slack notification is escaped
  (`&`, `<`, `>`), so it cannot form a link in the mrkdwn field.
- **`scripts/loadtest`**: result and report files are written through an exclusive temp file
  and `os.replace`, so a symlink planted in the world-writable `results/` directory is
  replaced rather than followed. The stream driver reads bodies and SSE frames in linear time
  and caps the error body it keeps.
- **`k8s/overlays/monitoring`**: a `NetworkPolicy` admits only the Grafana pod to Prometheus.
- **`application.properties`**: a comment says the metrics permission path is literal and must
  be repeated if the endpoint is relocated.

- **`install.sh` / `install.ps1`** (PR #1099 review): a fresh install (no `.env` yet) now
  generates its own `MONGO_APP_PASSWORD`, so EDDI no longer connects to MongoDB with the
  root password under another name. An existing `.env` is never given one (its app user
  already exists with the old password); a stored value is carried forward as before.

- **PR #1239 review follow-ups:** the GCP startup script now sets `EDDI_DIR` before it reads
  Keycloak's port (it read `/.env` and always fell back to 8180); the credential-bearing
  `curl` calls in `install.sh` pass `--noproxy 127.0.0.1`; `install.ps1` takes an exported
  `MONGO_APP_PASSWORD` as the value it persists; the stream driver reads a chunked body in
  64 KiB pieces; `write_text_atomic` sets the mode with `fchmod` on the open descriptor and
  keeps an existing file's mode or the umask default; the NetworkPolicy and metrics-path
  notes are corrected.

- **PR #1239 outside-diff review:** the Grafana health probe and the GCP script's Keycloak
  grant and admin calls also pass `--noproxy 127.0.0.1`; the GCP script re-checks that a
  running Keycloak container owns the port on every attempt instead of once; `install.ps1`
  removes only one matching outer quote pair when reading `.env`.

- **Stream driver, SSE across piece boundaries:** `sse_events` holds a trailing `
` until the next piece
  and decodes incrementally, so a CRLF or a multi-byte character split by the 64 KiB reads no longer
  delays or merges events.

- **PR #1239 third review:** `sse_events` resumes its `"\n\n"` search one character before each
  new piece instead of rescanning an incomplete frame from its start, and fails the turn as a
  protocol error once a frame passes 1 MiB (`MAX_SSE_FRAME`, the reader's existing line limit);
  `report --out` / `cluster-report --out` naming a special file such as `/dev/stdout` is written
  in place rather than replaced; the GCP script takes the Keycloak container id from
  `docker compose ps -q keycloak` with the stack's own compose arguments, so a keycloak of
  another Compose project on the same port is never sent the admin password.

- **PR #1239 fourth review:** a complete SSE frame is held to the same 1 MiB limit before it is
  yielded, so the verdict no longer depends on where the 64 KiB boundaries fall. `--out
  /dev/stdout` (or `-`) now writes to the process's stdout descriptor. It works even when stdout
  is redirected to a file, where a stat reports a regular file and the temp file would have
  landed in `/dev`. No other `--out` path is opened in place, so a FIFO planted in `results/`
  is replaced rather than written to. This drops the stat-based special-file branch.

### Verdicts (35 findings)

FIXED 13, ALREADY_FIXED 14, ACCEPTED_RISK 4, NEEDS_DECISION 4 (the Copilot auto-approve
workflow's trust boundary, the shared GCP firewall rules, the updater's compose checksums,
the Manager client's `localhost:*` redirect URIs).

**Files:** [`install.sh`](../install.sh), [`gcp/provision-vm.sh`](../gcp/provision-vm.sh),
[`seed-demo-agent.sh`](../src/main/docker/seed-demo-agent.sh),
[`ci.yml`](../.github/workflows/ci.yml), [`loadtest.py`](../scripts/loadtest/loadtest.py),
[`stream_driver.py`](../scripts/loadtest/drivers/stream_driver.py),
[`monitoring-stack.yaml`](../k8s/overlays/monitoring/monitoring-stack.yaml),
[`InfraReviewHardeningTest.java`](../src/test/java/ai/labs/eddi/deploy/InfraReviewHardeningTest.java)

---

## 🔒 fix(llm): agent-findings batch llm-impl — streaming suppression, linear reference escaping, budget accounting, rebinding re-checks (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-llm-impl`)

### What changed and why

Verified the 39 CodeRabbit agent findings against `modules/llm/impl/**` (scans of 2026-10-26 … 11-02) on current `main`. Verdicts: 10 fixed here, 17 already fixed (#1160, #1163, #1190, #1192, #1194), 8 accepted risk (documented residuals), 4 need a decision.

- **`addToOutput: false` no longer streams the raw answer.** The plain legacy branch built a streaming model and handed it the caller's event sink even when the task suppresses its raw output, so intermediate tokens reached an SSE caller before `postResponse` chose what to show. Such a task now takes the plain, buffered path (`LlmTask`).
- **Configuration-reference escaping is linear.** `escapeConfigReferenceMentions` ran `CONFIG_REF_MENTION.replaceAll`, which re-scans to the end of the value from every unclosed `{vault:` opener: an editor-written prompt full of them cost quadratic CPU on every turn, before any template limit. It is now a forward scan with the same matches; a mention is copied literally (the regex replacement also read `$`/`\` in a mention as group references).
- **An enforced tool budget is charged even with `enableCostTracking: false`.** The ceiling is checked against the cost tracker, but turning tracking off also stopped charging it, so the ceiling never moved. A task with an enforced `maxBudgetPerConversation` now always records the charge (`ToolLoopRunner`), a `summarize` truncation's model call included.
- **An endpoint key the builder does not read no longer skips its default's `allowedHosts` check.** `ChatModelRegistry` returned as soon as any endpoint-like key was set, so an `endpoint` value on an OpenAI-compatible preset (which reads `baseUrl` only) let the `region`-selected URL through unchecked. The builder's default is now checked whenever it reports one.
- **Self-conversation check decodes JSON.** The raw-argument fallback compared the serialized arguments with the conversation id; an id written with a JSON unicode escape passed the check and was decoded by the executor. The arguments' string values are now checked after JSON decoding as well (`ToolLoopResumer`, live and resume path).
- **Mistral and Gemini embeddings and the Chroma store re-check their destination per request.** They now use `BoundedJdkHttpClient` with the outbound policy, like OpenAI/Ollama embeddings (DNS rebinding on cached clients).
- **A MongoDB connection string's `proxyHost` is checked** like its seeds — for SSRF and as an `allowedHosts` destination; the driver dials the proxy and sends it `proxyUsername`/`proxyPassword`.
- **An invalid Chroma `apiVersion` no longer echoes its value** (resolved vault references reach this message in RAG search responses and ingestion status).
- Docs: [`security.md`](security.md) (DNS-rebinding table, cost tracking) and [`secrets-vault.md`](secrets-vault.md) (`allowedHosts` scope).

### Design decisions

- Fixed-vendor defaults (OpenAI, Anthropic, Mistral … with no endpoint key) stay unchecked against `allowedHosts`, as `secrets-vault.md` documents: the destination is a vendor host, not one the editor controls. Changing that is a contract change (every builder would report its fixed host, and existing secrets whose list omits the vendor host would stop working).
- The per-request re-check is a pre-send check with the documented cache-expiry residual, not address pinning: the JDK client has no resolver hook.

### Needs a decision (not implemented)

- **Jlama loader** (`jlama-core` 0.8.4): header allocation before a bounds check and unchecked shard paths in a downloaded index. Options: an operator allow-list of Hugging Face repositories for `jlama`, waiting for an upstream fix, or disabling `jlama` by default.
- **Elasticsearch (and other) store transports are not closed** when the store cache is invalidated; each secret or variable change followed by a rebuild leaves a live REST client behind. Options: share transports per endpoint/credential like the pooled `MongoClient`s, or close retired stores after a grace period.
- **HITL resume fallback** rebuilds messages from memory (which holds the input as sent) when the paused transcript was omitted, without re-running the task's input guardrails. Options: re-run the input guardrails on the rebuilt messages, or refuse model continuation when the screened transcript cannot be restored.

**Files:** [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`ChatModelRegistry.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java), [`ToolLoopRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`ToolLoopResumer.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java), [`EmbeddingModelFactory.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java), [`EmbeddingStoreFactory.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java)

---

## 🔒 fix(llm): agent-findings batch llm-tools — tool rate limit, memory ownership and erasure, selector and PDF work bounds, guardrail number parsing (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-llm-tools`)

### What changed and why

The 29 CodeRabbit "agent findings" filed against `modules/llm/tools/**` and `modules/llm/guardrails/**` (scans of 2026-10-26 … 11-02), each re-traced through the current code. Many were already closed by #1160, #1163, #1192 and #1194; the ones still live after those are fixed here:

- **`enableRateLimiting: false` no longer skips the operator's deployment-wide limit.** The flag turned off the whole admission call, so an agent designer's task setting opted out of `eddi.tools.ratelimit.global.*`. It now skips only the task's own per-conversation limit (`ToolRateLimiter.refusedByGlobalLimit`, same bucket, cluster-aware). Documented in [`langchain.md`](langchain.md) and [`configuration-reference.md`](configuration-reference.md).
- **`forgetFact` no longer deletes another agent's `global` memory.** Read visibility was treated as permission to delete, so an agent could delete a foreign global key and then write it again with its own value — the replacement `rememberFact` refuses without `allowGlobalKeyOverwrite`. The same rule now applies to deletion ([`user-memory.md`](user-memory.md)).
- **An update in `rememberFact` cannot recreate a memory a GDPR erasure just deleted.** Updates go through the update-only compare-and-set `replaceIfUnchanged` first; when that no longer applies, the inserting write runs and counts as an insert, so the existing post-write cancellation check takes it back.
- **`extractWithSelector` refuses positional pseudos on a page too wide for them.** jsoup's `:eq`/`:lt`/`:gt`/`:nth-*`/`:*-of-type`/`:only-child` walk every sibling of every candidate — quadratic in one parent's width, CPU the parse timeout cannot interrupt. With such a pseudo in the selector, a page whose sum of squared child counts exceeds 50 million is refused with a message; ordinary tables are unaffected.
- **PDF tool extraction has the ingestion extractor's two missing bounds**: unfiltered page-content references are charged to the decode budget before PDFBox parses them (`PdfTextExtractor.chargePageContents`, now public), and glyph layout stops at the character cap plus slack instead of holding millions of glyphs before the cap is applied — the text laid out so far is kept.
- **The expression guardrail compares against the real downstream number reading.** Both of its readers parsed numbers exactly, so a value only exact parsing refuses (`1e2147483648`) made both skip the same earlier object and agree on a later one while the task's double-based conversion took the earlier. The tolerant reader is now the default mapper, compared number-aware.

### Verdicts

FIXED 9 · ALREADY_FIXED 14 · ACCEPTED_RISK 1 · NEEDS_DECISION 5 · FALSE_POSITIVE 0.

Open decisions (not implemented): process-local tool budget lost on cost-tracker eviction (two findings); quota refunds read failure from result text; `converse_with_agent`'s per-task count resets on a HITL resume; dynamic-agent USE checks treat a `/v1` shared-key conversation's self-asserted user id as a verified principal. The `OutboundHttp` DNS-rebinding residual (a blind request before the post-connect re-check) stays the documented accepted risk in [`security.md`](security.md#dns-rebinding).

---

## 🔒 fix(secrets): agent-findings pass over the vault, grant checks, redaction and connections (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-secrets-connections`)

### What changed and why

The 45 CodeRabbit "agent findings" filed against the secrets vault, the grant and host checks, the redaction layer and connections were each traced through the current code. Most had already been closed by the October hardening rounds; nine were still real and are fixed here.

- **Space-membership check could miss a tenant** ([`VaultGrantChecker`](../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java)). Tenant discovery used one scanner for `${vault:` and `${vars:`, so a malformed opener of one namespace (`${vars:a${vault:t.finance…/key}`) swallowed a real reference of the other into a single bogus tenant, while the runtime resolved the inner reference with its own rules. Vault tenants now come from a vault-only scanner and variable tenants from `GlobalVariableResolver.VARS_PATTERN`, the grammar the resolver uses (made public for this).
- **Rotation racing a delete** ([`VaultSecretProvider.store`](../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java)). A rotation whose row vanished was turned into a create, and the create checks its endpoint runs had been skipped. The Manager's rotate call, which restates the agent grant but no host list, re-created the key sendable to any host. The rotation now fails and writes nothing.
- **Stale conditional grant edit** ([`RestSecretStore.updateGrant`](../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java)). With `expectedAllowedAgents` and `allowedHosts` together, the host list was committed before the precondition was checked, so a stale request answered 409 "nothing was written" after it had changed where the key may go. The precondition is now checked before the host write. An edit landing in the moment between that check and the grant write is still refused by the conditional grant write, after the hosts. One atomic operation for both lists would need a new persistence operation on both backends.
- **Linear unresolved-reference check** ([`CredentialReferenceResolver`](../src/main/java/ai/labs/eddi/connections/CredentialReferenceResolver.java)). The last `find()` over a `[^}]*` reference regex in the area, run on decrypted credential text, was quadratic on unterminated openers. It is replaced by an exact linear equivalent.
- **Read redaction** ([`ConfigSecretRedactor`](../src/main/java/ai/labs/eddi/secrets/sanitize/ConfigSecretRedactor.java)). A numeric value in an embedded `customHeaders` / `customQueryParams` map is judged like a string. A JSON request body has every literal under a credential-named field masked whatever its length, because the shape scan's eight-character floor left `{"password":"hunter2"}` legible to editors. Only one whole JSON document is re-serialized, never a prefix.
- **Long non-JSON text** ([`SecretRedactionFilter`](../src/main/java/ai/labs/eddi/secrets/sanitize/SecretRedactionFilter.java)). The 1 MiB bound that assumes a JSON carrier now applies only to text that could be JSON. Text that cannot be JSON keeps apostrophe-quoted passwords whole across an embedded double quote.
- **Export scrubbing** ([`SecretScrubber`](../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java)). The additional query-name rule (`key`, `sig`, …) now also judges the percent-decoded name on both export branches, so `%6Bey=` no longer survives export.

Docs: [`secrets-vault.md`](secrets-vault.md) (rotation racing a delete, the grant precondition and host lists) and [`security.md`](security.md) (JSON body masking).

### Verdicts (45 findings)

FIXED 9 · ALREADY_FIXED 27 · ACCEPTED_RISK 6 · NEEDS_DECISION 2 · FALSE_POSITIVE 1.

- **Accepted (documented):** the connections master switch does not stop `${connection:…}` resolution ([`connections.md`](connections.md)). Unreadable or absent grant metadata is allowed at deploy time, with the grant checked at creation instead, and a deployment racing a secret's creation is a named gap ([`secrets-vault.md`](secrets-vault.md#what-counts-as-granted)).
- **Open:** whether an OAuth service grant should carry the connection's immutable id, so that a stale configuration minting during a delete and re-create cannot store its token under the successor. And whether GDPR erasure should report the auto-vaulted-secret step incomplete when the vault is disabled (no master key) but secret rows remain.

### Design decisions

- No runtime per-agent grant check was added. `VaultGrantChecker`'s class Javadoc records why resolution time is the wrong place, and the race findings that ask for one are accepted as documented.
- The rotation now prefers "the delete wins" over re-creating the key. A re-create would have to repeat every endpoint check (host list, deployed-agent impact) inside the provider, which knows neither the mode nor the deployments.

---

## 🔒 fix(manager): agent-findings batch for the Manager and Chat UI (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-ui`)

Verified 19 findings from the CodeRabbit agent-findings export against the current code. Most had already been closed by the review-fix PR #1200; the rest are fixed here.

### What changed and why

- **Per-call approval decisions survive a `__proto__` call id.** `ApprovalBanner` built its `toolDecisions` on a plain `{}`; a provider-chosen call id of `__proto__` re-prototyped the object, the reviewer's explicit rejection vanished from the JSON, and the backend read the omitted call as approved. The dictionary now has no prototype.
- **Secret rotation no longer restates the grant.** `rotateSecret` sent the grant and description read just before the PUT, so a concurrent revocation could be put back (last-writer-wins). Both backends keep an omitted grant and description on an update (`VaultSecretProvider.store`, `MongoSecretPersistence.secretWrite`, the PostgreSQL upsert), and the Manager is bundled with the backend that does, so the PUT now carries the value only.
- **Operator gate verification refuses exemptions it cannot judge.** `ToolApprovalGate.addressesOf` matches a call by `http.<method>:<path>`, `<source>:<name>` and the bare name, so an `exempt` entry or `AUTO_APPROVE` rule such as `deployAgent` or `http:*` could un-gate a write while the Manager certified the gate. `gateLooksInstalled` now accepts such an entry only when it fixes a read method (`http.get:`, `http.head:`, `http.options:`) or a non-`http` source literally; anything else is refused (the safe direction: write scope withheld, or a warning on a created agent). `operator-revision.json` is not bumped: neither the system prompt nor the allow-list changed.
- **Hostile-input bounds in the group transcript and diff viewer.** The markdown autolink matcher stops at the next `<` and is bounded (it ran to the end of the text from every opener); the JSON repair of malformed task arrays is a single forward pass that tracks string and escape state (a regex retried from every escaped quote); `reindentJsonText` caps printed indentation at 64 levels (N openers printed N lines of up to N levels) and trims a trailing-comma line by hand instead of with a regex scanning the whole output on every closer.

### Verdicts (19)

FIXED 6; ALREADY_FIXED 11 (by #1200: the diff-viewer budget, JSON pretty-print bounds, markdown destination, indented-run and verification-line bounds, selective export, conversation/agent binding, erasure cache sweep, latest-version matching, operator pause binding, and the every-write-method gate check); ACCEPTED_RISK 2 (`fc72ab76`, `96a089e2`); FALSE_POSITIVE 0; NEEDS_DECISION 0. The URL-token adoption in the chat widget is off unless the operator sets `eddi.chat.url-token.enabled` and is documented as a residual login-CSRF risk; the release check's `Origin` header cannot be suppressed by a cross-origin fetch and the card says so (a same-origin proxy would be a new backend endpoint).

**Files:** [`approval-banner.tsx`](../ui/manager/src/components/hitl/approval-banner.tsx), [`secrets.ts`](../ui/manager/src/lib/api/secrets.ts), [`operator.ts`](../ui/manager/src/lib/api/operator.ts), [`group-utils.ts`](../ui/manager/src/components/groups/group-utils.ts), [`reindent-json.ts`](../ui/manager/src/lib/reindent-json.ts)

---

## ✨ feat(hitl): config-driven context on approval cards (2026-11-03)

**Repo:** EDDI (`feat/approval-card-context`), stacked on `feat/hitl-edit-on-approve`

### What changed and why

An approval card said *that* something was waiting and *why*. For a decision that is more than "run it" — publish this
post, send this reply — the approver also needs the thing itself. `approvalContext` lets the agent or group designer
say what to show: short templates over the conversation's own data, rendered **once, when the pause is committed**, and
served unchanged on every surface.

- **Config** — `hitlConfig.approvalContext` (agent, at most 8 blocks), `toolApprovals.rules[].context` (at most 4,
  with `call.*` = the one gated call the rule matched) and the group `hitlConfig.approvalContext` (at most 8, with
  `group.*`: the phase, members, transcript, `lastByRole`/`lastByType`/`lastBySpeaker`, tasks awaiting approval and
  shared artifacts). A block is `{label, template, maxChars}`
  ([`ApprovalContextBlock`](../src/main/java/ai/labs/eddi/configs/hitl/model/ApprovalContextBlock.java)). All
  optional; absent config renders nothing, stores nothing and leaves the Slack card byte-identical.
- **Save-time validation** — [`ApprovalContextValidation`](../src/main/java/ai/labs/eddi/configs/hitl/ApprovalContextValidation.java)
  from `HitlConfigValidation`, so every store and the ZIP import refuse a bad block with a 400 that names it: limits, a
  template that does not parse on the restricted runtime engine
  ([`RuntimeTemplateParseCheck`](../src/main/java/ai/labs/eddi/modules/templating/impl/RuntimeTemplateParseCheck.java),
  extracted from `TemplateSyntaxMigrator`, which now delegates to it), the `vault:`/`eddivault:`/`connection:`/`caller:`
  namespaces (they would print a secret's name on a card), and `call.*` outside a rule.
- **Rendering** — [`ApprovalContextRenderer`](../src/main/java/ai/labs/eddi/engine/hitl/context/ApprovalContextRenderer.java)
  (regular conversations and rule blocks per gated call) and
  [`GroupApprovalContextRenderer`](../src/main/java/ai/labs/eddi/engine/hitl/context/GroupApprovalContextRenderer.java),
  on a dedicated restricted `TemplatingEngine` with a 500 ms / 16,000-character / 2,000-iteration budget per block and
  8,000 characters in total. Reference tokens become `[secret]`, `SecretRedactionFilter` runs, and tool arguments come
  only from `argumentsRedacted` — `argumentsRaw` is never read. A block that throws, times out or exceeds a limit
  becomes an item with an `error` and **never fails or delays the pause**.
- **Persistence** — one field, `hitlApprovalContext` ([`RenderedApprovalContext`](../src/main/java/ai/labs/eddi/engine/hitl/context/RenderedApprovalContext.java):
  items, `truncated`, a SHA-256 `digest`), on the conversation snapshot and the group conversation. Written at the
  single pause-commit hooks (`ConversationHitlService.populateHitlTimeoutBookmark`, `GroupHitlCoordinator.commitPause`
  and the restore after a failed group resume), overwritten by a re-pause, cleared with the bookmark in memory, in the
  MongoDB and PostgreSQL stores and on every group resume/reject/cancel/fail. The raw conversation read strips it.
- **Surfaces** — `approvalContext` on REST `approval-status` and MCP `get_approval_status` (one shared
  [`ApprovalContextViews`](../src/main/java/ai/labs/eddi/engine/hitl/context/ApprovalContextViews.java), re-redacted
  at serve time; the MCP summary map is now `Map<String,Object>`, its existing values unchanged), on the group
  approval-status (REST and MCP) and on the group conversation read.
- **Slack** — [`SlackApprovalContextBlocks`](../src/main/java/ai/labs/eddi/integrations/slack/SlackApprovalContextBlocks.java)
  inserts the blocks before the `actions` block (which stays last), as `plain_text` (so no mention, link or bold
  spoofing), within Slack's 50-block budget ("+N more context items — open in Manager"). Because everyone in the
  approval channel sees a card, the channel needs `platformConfig.hitlApprovalContext = "true"`; without it the card
  gets one line, "More context available in the Manager". A card that carries context is posted with
  `unfurl_links`/`unfurl_media` off (`SlackWebApiClient.postBlocksMessage(…, unfurl)`); cards without context are posted
  by the unchanged call. `HitlPauseEvent` gains a `@JsonIgnore approvalContext` component and keeps its old constructors.
- **Manager** — `ApprovalContext` shows the items as plain text (`whitespace-pre-wrap`, never markdown or HTML) above the
  decision on the Approvals page, the conversation page and the group discussion; a block editor
  (`ApprovalContextEditor`) in the agent's Human-in-the-Loop section and in the group approval editor, with the same
  limits shown as the author types. New keys under `approvalContext.*` in all 11 locales.
- **Docs and metrics** — new page [HITL Approval Card Context](hitl-approval-context.md) (config, the data model, the
  audience table, validation, Slack), linked from `hitl.md`, `group-conversations.md`, `security.md` and `SUMMARY.md`;
  `eddi_hitl_approval_context_render_total{outcome,surface}` in `metrics.md` and on the Full Metrics Reference dashboard
  (panel 420); two properties in the configuration reference. The operator and MCP follow-up is in
  [`planning/operator-followups/approval-card-context.md`](../planning/operator-followups/approval-card-context.md).

### Not in this change

- No Platform Operator edits and no new MCP tools (both go to the combined follow-up).
- The Manager's tool-approval editor has no rule editor, so `rules[].context` is written in the agent's JSON.
- No `hitl.draft` template root: this branch does not resolve a rule pause's draft for templates.
- Stacked on `feat/hitl-edit-on-approve`: merge after it. Expect a trivial conflict with the approval-analytics branch in
  the Full Metrics Reference dashboard's HITL row and in `GroupConversation`/`HitlConfig` field lists.

---

## 🐛 fix(cluster): leases stop trusting themselves, survive lost replies, and cost fewer round trips (2026-11-03)

**Repo:** EDDI (`fix/cluster-lease-validity`) — workstream A of the cluster production-readiness review (`planning/cluster-production-readiness-review.md`, findings A1, A2, A11, D1–D5, D7, and the lease part of C12).

### What changed and why

**A1 — a holder cut off from NATS stops itself.** A renewal that could not reach NATS used to be ignored forever (`KvLeaseManagerTest.heartbeatIoFailureKeepsLease` asserted it), so a partitioned holder kept running — LLM calls, tools, the unfenced user-memory upsert, a 200 to its client — after another node had taken the conversation over at `lease.ttl`. A lease is now trusted for `ttl − 2 × heartbeat` (10 s by default) after the last renewal NATS *acknowledged*, counted from when that renewal was *sent*, so the server-side TTL always ends later. Past that `LeaseHandle.isLost()` is true at once and the lost callbacks fire (from the tick every quarter heartbeat, or from `isLost()` itself, on the I/O executor). The test is rewritten to assert the new contract. Contract for the turn side (WS-B): check `isLost()` before the user-memory upsert and before delivering the reply.

**A2 — a write applied on the server whose reply was lost.** Every lease value now carries a lease id (`lid`) unique within the process. A renewal that conflicts re-reads the key from the stream leader: if it still holds this lease (same node, boot and `lid`), an earlier renewal landed although its reply timed out — the lease is kept at the revision found (`eddi_cluster_lease_adopted_total{op="renew"}`) instead of cancelling a healthy turn. An acquire that finds its own `lid` adopts the lease its timed-out create wrote; a lease of this process that nobody here holds (an acquire that gave up) is deleted instead of blocking the conversation for the TTL — this node included. `release()` of a lost handle now still deletes the key when it is provably still this lease.

**A11 — waiter markers and peek.** A granted waiter deletes its `w.<key>` marker by compare-and-set at the revision it wrote, so another node's marker survives; a waiter that times out, is drained or shut down removes its own. New `IConversationLeaseManager.peekStrict` / `peekKeyStrict` throw `ClusterUnavailableException` instead of reading an outage as "no lease" (for `ClusterHitlRecovery.eligible`, owned by WS-B).

**D1 — fewer round trips.** Acquire reads the key's last record (`ISharedKv.latest`, which also returns a delete marker's revision) and writes at that revision: 2 round trips instead of jnats' 3 for a `create` on a deleted key. Release is one guarded delete that returns the marker's revision (`deleteReturningRevision`); the unconditional waiter-marker read and the broadcast are gone. An uncontended turn's lease went from about 6 to 3 round trips; a waiter woken by a release acquires with 1.

**D2 — contention.** The coordinator hands a healthy lease straight to the next turn of the same conversation queued on this node (`IConversationLeaseManager.handOver`: no I/O, refused when less than a heartbeat of validity is left, when another node is known to wait, or after 16 handoffs in a row). The waiter poll is capped at 500 ms (250–750 ms jittered; it was 2.5–7.5 s at the default TTL).

**D3 — reopen storm.** `NatsSharedKv` reopens a bucket handle once, under a lock, for all the callers that failed on it; it never drops a handle younger than 1 s or on a full outgoing queue / closed connection, and a failed open is not retried for 250 ms.

**D4 — heartbeats at scale.** At most 64 renewals in flight, least recently confirmed first; each lease is renewed when 3/4 of a heartbeat has passed since its last renewal, from a tick every quarter heartbeat, so thousands of leases no longer go out in one burst.

**D5 — stale-lease sweep.** Runs once per process (leases of an earlier boot cannot appear after it started), with one watch instead of a read per key — no more whole-bucket scans on every node at every reconnect.

**D7 — release notifications.** A waiter tells the holder's node that it waits (`<root>.lease.to.<nodeId>`, core NATS, repeated with each poll); the holder addresses the release only to those nodes, with the delete marker's revision. The cluster-wide broadcast is kept only for an administrative force-release.

**C12 (lease part).** `eddi_cluster_lease_acquire_seconds` (every outcome × contended), `eddi_cluster_lease_lost_total` (every reason), `eddi_cluster_lease_adopted_total` and `eddi_cluster_degraded_decisions_total{area="turns"}` are registered at construction, so `increase()` sees the first occurrence on each pod.

### Rolling upgrade: why old and new nodes keep mutual exclusion

- **Acquisition stays create-if-absent.** The new acquire is a compare-and-set at the revision of the key's last record, which succeeds only while the key holds no live value — the same server-side check jnats' `create` makes. An old node's `create` and a new node's write race on the one stream leader; exactly one wins. `LeaseProtocolIT.earlierVersionInteroperates` runs both against real NATS.
- **The delete marker is jnats' own.** `deleteReturningRevision` publishes the `KV-Operation: DEL` marker with `Nats-Expected-Last-Subject-Sequence`, exactly as jnats' `delete(key, revision)` does, only keeping the acknowledgement's sequence; old nodes read it as a deleted key.
- **The value only gains a field.** `lid` is ignored by old nodes, which read `node`, `boot` and `since` as before. A new node treats a value without `lid` as somebody else's lease, so it never adopts or deletes an old node's lease as its own.
- **Takeover rules are unchanged** (presence, boot id, three unchanged heartbeats); renewals and releases stay compare-and-set on the revision.
- **Notifications are hints.** Old nodes still broadcast on `lease.released.<key>`, and new nodes still listen there. New nodes no longer broadcast a plain release, so an old node waiting for a lease a new node releases falls back to its poll (`ttl/4`, 5 s by default) until it is upgraded, and a new holder does not see an old waiter's marker when it decides to yield or hand off — fairness toward old-version waiters is weaker for the length of the upgrade. Neither affects mutual exclusion or the fence.

### Tests

`KvLeaseManagerValidityTest` (A1, A2, A11), `KvLeaseManagerScaleTest` (D1, D2, D4, D5, D7, C12), `NatsSharedKvReopenTest` (D3), two handoff tests in `ClusterConversationCoordinatorTest`, and `LeaseProtocolIT` against a real NATS: the delete-marker round trip, interoperation with an earlier version's jnats `create`/`delete`, a renewal and a create applied on the server whose replies are reported as timeouts, and addressed release notifications with no broadcast. Rewritten: `KvLeaseManagerTest.heartbeatIoFailureKeepsLease` (now `…KeepsLeaseOnlyUntilItsValidityRunsOut`).

**Files:** [`KvLeaseManager.java`](../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java), [`NatsLeaseManager.java`](../src/main/java/ai/labs/eddi/engine/cluster/lease/NatsLeaseManager.java), [`IConversationLeaseManager.java`](../src/main/java/ai/labs/eddi/engine/cluster/lease/IConversationLeaseManager.java), [`ISharedKv.java`](../src/main/java/ai/labs/eddi/engine/cluster/ISharedKv.java), [`NatsSharedKv.java`](../src/main/java/ai/labs/eddi/engine/cluster/NatsSharedKv.java), [`InMemorySharedKv.java`](../src/main/java/ai/labs/eddi/engine/cluster/InMemorySharedKv.java), [`ClusterConversationCoordinator.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/ClusterConversationCoordinator.java); docs: [`clustering.md`](clustering.md), [`configuration-reference.md`](configuration-reference.md), [`metrics.md`](metrics.md).

### Not done here

- The turn side of A1 (checking `isLost()` before the user-memory upsert and the reply) and the switch of `ClusterHitlRecovery` to `peekStrict` belong to WS-B.
- D2's "load memory after the lease is granted" and the reference ingress configs' consistent hashing are outside this workstream.
- Sharding `LEASES` by hash prefix (D3–D5 plan item) is not attempted.

---

## Clear the Guava advisory flagged by Scorecard; OpenTelemetry blocked on Quarkus (2026-11-03)

**Repo:** EDDI, branch `fix/dep-advisories-guava-otel`

**What changed** (`pom.xml`, `dependencyManagement` only): `com.google.guava:guava` 33.6.0-jre -> 33.7.2-jre, for GHSA-xxph-c9ww-hj94 (uncontrolled memory allocation when `CompactHashMap`, `CompactHashSet` or `MapMakerInternalMap` is deserialized). Pulled in by `langchain4j-qdrant`. Not reachable from a known code path; fixed because OpenSSF Scorecard counts advisories regardless of reachability.

**Not fixed - GHSA-ccrj-gw8r-854m** (quadratic CPU in W3C `tracestate` parsing, `opentelemetry-api` <= 1.66.0, fixed in 1.67.0). Quarkus 3.40.1.1, the latest platform, manages OpenTelemetry at 1.62.0, and 1.67.0 cannot be forced in either shape; both were tried and both fail at startup (Backend E2E caught the first, a local boot the second):
- Whole family at 1.67.0 (BOM imports): `NoSuchMethodError: io.opentelemetry.exporter.internal.ExporterBuilderUtil.validateEndpoint(String)`, because `quarkus-opentelemetry` calls an internal exporter method that 1.67 changed.
- API line only (`opentelemetry-api`, `-context`, `-common`) at 1.67.0 with SDK and exporter at 1.62.0: `NoClassDefFoundError: io.opentelemetry.api.internal.ConfigUtil`, which 1.67 removed and the 1.62 SDK still uses.

Neither showed up in the unit tests run locally (the tracing, style and repo-guard tests; the full suite was not run), so only a real boot shows it. Revisit when a Quarkus platform manages OpenTelemetry 1.67.0 or later. Until then the exposure is a long inbound `tracestate` header; capping header size at the proxy (or `quarkus.http.limits.max-header-size`) bounds it.

The `minimatch` advisory (GHSA-g3hv-75wg-m249, dev tooling only) is handled in a separate PR.

---

## 🐛 fix(groups): a caller without access to a group gets 403 from the workspace endpoints, not 500 (2026-11-03)

**Repo:** EDDI (`fix/group-workspace-forbidden`)

### What changed and why

`RestGroupWorkspace` (`readWorkspace`, `readBacklog`, `addBacklogTask`, `addCadence`, `deleteCadence`) calls `ResourceAccessGuard.requireAccess`
inside a `try` whose last `catch (Exception e)` logs and answers 500. The guard denies with `io.quarkus.security.ForbiddenException`, so a
caller without access to the group received a 500 and an error log line instead of 403. Each of the five methods now catches
`ForbiddenException` ahead of the generic catch and rethrows it, so Quarkus maps it to 403.

`RestGroupWorkspaceTest` gains `everyEndpoint_accessDenied_propagatesForbidden`, which makes the guard throw the real Quarkus type for each of the
five endpoints. Mutation-checked: with the new catch removed the test fails because the endpoints answer 500 instead of throwing.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-11-04 | Tool-argument schema check runs before the approval gate via a `ToolArgumentValidator` executor marker | Malformed generated-tool calls reached human approval, then failed or silently no-op'd at the API | A new map threaded through `ToolContribution`/`ToolSetup` (more plumbing for the same result); a hand-written validator (networknt already ships) |
| 2026-11-04 | setup-api sync identifies generated apicalls by an `openApiOrigin` marker set at setup; unmarked agents are refused with how to add it | Sync must not regenerate hand-written httpcalls | Heuristic adoption by targetServerUrl match (could overwrite hand-written configs) |
| 2026-11-04 | `dryRun` on setup-api is hidden from the OpenAPI schema | No addition may become callable by the Platform Operator, whose setup-api tool is generated from that schema | A sibling endpoint (the brief asked for the body field) |
| 2026-11-04 | Retry jitter only shortens the backoff (`[base × (1 − r), base]`), never lengthens it | Adding ±20% jitter as OpenClaw does | Symmetric jitter, which could push a sleep past `maxBackoffDelayMs`, the total backoff budget or the turn deadline |
| 2026-11-04 | Peer-content caps (12,000 per entry, 96,000 per list) are engine constants, not group config | Bounding peer text in group prompts | A per-group setting: it would have to be threaded through every `buildPhaseInput` caller for a safety bound nobody has needed to tune; the I9 context window is the tuning knob |
| 2026-11-04 | `headTail` became the default only for an *unset* strategy; explicit `truncate` and the fallbacks keep start-only | Making head-and-tail truncation the default | Changing `truncate` itself, which would have silently changed configs that asked for it by name |
| 2026-11-03 | Vault references in a SERVICE judge's parameters need a secret granted to all agents ("*") and are re-checked at run time | A group has no agent identity, so the deploy-time VaultGrantChecker cannot apply; an editor could otherwise aim any vault key at an endpoint of their choosing | A group-level grant model; restricting parameters to ${vars:...} |
| 2026-11-03 | `approvalContext` is rendered once at pause commit and persisted as `hitlApprovalContext`; every surface serves that text, re-redacted | The Slack card is posted once, so the Manager, REST, MCP and a later audit must show the same text the approver saw; a read-time render would drift (snippets, artifacts, republished config) and would need the whole multi-MB conversation memory on every poll | Rendering at read time on each surface; rendering only for Slack |
| 2026-11-03 | Showing the context in a Slack channel needs the integration's own opt-in `hitlApprovalContext=true`, on top of the designer's `approvalContext` | The channel audience (everyone who can read it) is wider than the approver role the Manager, REST and MCP surfaces are gated to | Designer opt-in only; always on |
| 2026-11-03 | Slack values are `plain_text` sections; a card that carries context is posted with link and media unfurling off | The text is conversation data a model may have written: plain text cannot form a mention, a disguised link or bold spoofing, and a model-chosen URL must not make Slack fetch it | Escaping mrkdwn (`SlackMrkdwn.escape` leaves `@here` and bold untouched) |
| 2026-11-03 | A render failure becomes an item with an `error`; it never throws into the pause commit | A broken template must not strand a pause or hide the approval from the person who has to decide | Failing the pause; dropping the block silently |
| 2026-11-03 | A lease is trusted for ttl − 2×heartbeat after the last acknowledged renewal, counted from its send time; past that isLost() is true and callbacks fire | A holder cut off from NATS ran on past the TTL (A1) | Keeping the lease until a conflict is seen (the old behaviour); counting from the reply's arrival (the server's TTL starts earlier) |
| 2026-11-03 | Lease values carry a per-process lease id; conflicts are re-read and adopted when the id matches | Applied-but-timed-out writes read as takeovers (A2) | Matching on node+boot only — a second lease of the same process under the same key could be mistaken for this one |
| 2026-11-03 | Release notifications are addressed to the nodes that said they wait; no broadcast for plain releases | Every node received every release (D7) | Keeping the broadcast during the upgrade — old waiters poll instead, a hint is never needed for exclusion |
| 2026-11-02 | A deployment price catalog completes config prices; EDDI ships reference prices that are never active until an admin adopts them | Cache, thinking and unpriced spend were invisible to budgets; `TokenPricing` said "deliberately no built-in provider price table" | Active shipped defaults (stale silently, upgrades change charges); per-tenant catalogs (EDDI meters one tenant) |
| 2026-11-02 | Unknown cache prices are charged at the input price | Anthropic/Bedrock cache tokens were $0 | Charging them $0 until priced (under-counts a hard budget) |
| 2026-11-02 | The SERVICE judge calls SummarizationService.summarizeWithUsage, not GuardrailModelClient | GuardrailModelClient is package-private and bound to an LLM task's GuardrailContext/PaidCallGate, which a group does not have; the group summarizers already price and book through SummarizationService + TokenPricing + GroupCostLedger | Reusing the guardrail client; a new model client |
| 2026-11-02 | The judge's parameters accept vault/vars references only for credential keys, and refuse ${connection:...} | A group config must not become a plaintext key store, and ChatModelRegistry rejects connection references for model parameters at call time | Accepting literals with a warning; resolving connections for the judge |
| 2026-11-02 | One `hitl.approval` entry per decision event with a `units[]` list (answer, tool call, phase, task) and `schema: 2`; legacy fields kept | Analytics needs per-unit outcomes, categories and edit sizes; existing readers need the old fields | One entry per unit (breaks the one-entry-per-decision shape, multiplies ledger sequence numbers); a separate store written alongside the ledger |
| 2026-11-02 | An approved edit of a rule-pause answer replaces only the draft's own output item and data keys; nothing re-runs; a guardrail-refused edit re-pauses on the unchanged draft | Re-running later tasks would re-trigger the pause rule; a refused edit must not consume the human's pause | Truncating and re-running the rest of the turn (as REVISE does); screening before the CAS (model calls on the request thread and inside Slack's 3-second window) |
| 2026-11-02 | A group SYNTHESIS edit is appended as an unsigned reviewer entry (`hitl:<decidedBy>`, `errorReason: reviewer-edit`), reusing the SYNTHESIS type | Signed transcript entries must never be rewritten; the existing "last synthesis" extraction then needs no change and older pods deserialize it | A new transcript entry type; rewriting the signed entry in place |
| 2026-11-02 | Slack edits of tool arguments are merged server-side against the raw arguments; redacted values cannot be edited from Slack | Slack only ever sees redacted arguments; a value left as shown must keep its raw secret, and a marker must never become an argument | Sending raw arguments to Slack; refusing every edit of a call that has any redacted value |
| 2026-11-02 | `HitlDecisionRecorded` is fired with `fireAsync` through `Arc.container()` from the plain audit writer | Nothing a consumer does may run on the decision's critical path; the HITL collaborators are plain classes built in their facades' constructors | A synchronous event; new CDI constructor parameters on the facades |
| 2026-11-02 | Cross-team handoff requires two-sided consent (source `targets` and target `accept.from`), enforced in `TeamHandoffService`, not by prompt | A one-sided design lets an author with EDIT on any agent write into any team's backlog | Source-only allow-list; wildcard accept rules (explicit ids keep consent auditable) |
| 2026-11-02 | Handoff provenance is a side map on the workspace (`handoffs.origins`), not a `TaskItem` component | The cadence writeback rebuilds tasks with the shorter constructors and already drops `createdByAgentId`; a component would also touch ~30 call sites | Adding `origin` to the `TaskItem` record |
| 2026-11-02 | The default accept mode is REVIEW (an intake inbox no cadence can pull) | The handed-over text crosses a trust boundary and will be read by the receiving team's models | AUTO by default |
| 2026-11-02 | The approval source for the tool is a new `team`, not `builtin` | `exempt: ["builtin:*"]` would silently un-gate cross-team writes | Reusing `builtin` |
| 2026-11-02 | `toolQuotas` reserve inside `ToolExecutionService.executeToolWrapped` (after cache, before execution), release on failure, keep on timeout | A quota that counts cache hits or refused calls, or that cannot give a failed call back, charges users for nothing; a timed-out call may still have completed its side effect | Reserving in `ToolLoopRunner` around the supplier (cannot see the cache; the refusal would be cached and charged); counting at result time (overshoots under concurrency) |
| 2026-11-01 | Late agent-test charges adjust the stored terminal record (compare-and-swap on `heartbeatAt`) | A timed-out run would otherwise wait up to the turn timeout plus the write-back wait before it could report | Holding finalisation for abandoned turns |
| 2026-11-01 | Dead-letter forwarding is fenced by a shared erasure tombstone, checked before and after publishing | The snapshot outlives the shared purge, so "does the conversation exist" cannot see an erasure under way, and one check still races the purge | Relying on the existence check alone |
| 2026-10-27 | docs.labs.ai redirects to docs.eddi.technology with an explicit 301 (not Vercel's default 308), on every path, kept forever | Domain decision for the GitBook replacement; released images print docs.labs.ai links indefinitely | `permanent: true` (308) — equally permanent, but not the decided code |
| 2026-10-27 | The prompt and response are removed from the stored step once the ledger accepted the turn's entries; tool calls, usage, cost, model name and guardrail records stay | The ledger is the system of record and holds the full text; the copy was a fifth of the document. Agent tests, cost metering and history replay read the small keys back from the stored step | A config switch (no consumer needs the text in memory); a hash/reference in place of the text (the ledger is addressable by conversation id and step index already); stripping `audit:tool_calls` too (agent tests read it from the stored step) |
| 2026-10-27 | Current-step and approval-status reads load a server-side projection (last step/output + computed totals) instead of the whole conversation document | Whole-document reads ran at ~100 KB/s on a bandwidth-limited database link; 0.5–3 MB documents made these reads take 11–29 s | Caching the snapshot (stale across nodes, does not help the first read); a `$slice` find projection without totals (undoAvailable, output/step pairing and conversationCost need the full lengths and every step's spend) |
| 2026-10-26 | Due resubmission rows of a deleted integration are left to ledger retention, not refused by the sweeper | Once the query is scoped to one integration they block nobody; a sweeper pass over all RECEIVED rows would again read one global batch | Refusing them in the inbound sweep; deleting an integration's ledger rows with it (the rows are GDPR-reachable history) |
| 2026-10-25 | A conversation-mode reply's recipient is decided in one function, from the ledger row cross-checked with the mail's step in conversation memory and the conversation's identity, on every attempt | Authentication is a fact of the inbound mail; the processor forces it on, and the reply path checks it again from two independent records, so a reply to an unauthenticated or anonymous sender cannot be produced even for a turn that should not have run | An authenticated flag on the ledger row (a new column on both stores for a fact the step already holds); trusting the processor's check alone |
| 2026-10-25 | Replies are rebuilt from the conversation on retry; group replies, whose discussion keeps no copy of the mail, are retried only by the node that holds them | The ledger stores no body or subject (plan §12); a group conversation has no field for the answered mail's subject | Storing the subject in the ledger; an extra copy of the mail in the group conversation |
| 2026-10-25 | A mail whose conversation was started but whose turn never ran is found again in the mailbox by Message-ID and run once | The raw mail is not kept, and the mailbox still has it (flagged or moved); the hand-back is a conditional transition and the turn carries the mail's ledger key | Keeping the rendered input (a body in storage); marking it lost (a mail silently not processed) |
| 2026-10-25 | The email suppression list stores a SHA-256 of the address per integration, and GDPR erasure deletes the entry | Enough to answer "suppressed?" and to clear it for someone who knows the address; conversation mode only answers mail the person sends, so an erased person is written to again only if they write first | Plain addresses (a list of correspondents); keeping a hash after erasure (plan §23 Q4 — keeps the identifier the request asked to remove) |
| 2026-10-25 | Suppressions are cleared through two channel REST endpoints, not an MCP tool or an operator grant | An administrator needs a way to undo a bounce without database access; the operator holds no channel endpoints by design | Store-only clearing (database access); an MCP tool (one more admin surface to secure) |
| 2026-10-25 | Email trigger mode never runs a mail twice once a side effect may have happened; a claim that did nothing is given back and the mail, still unmarked in the mailbox, runs again | The CLAIMED state separates "nothing left EDDI" from the point of no return; only a crash while starting the conversation is ambiguous, and that one is REFUSED with an owner notice | Refusing every stranded claim (lost mail that had run nothing); re-running submitted turns (double side effects); a sweep that opens its own IMAP session (two nodes on one mailbox) |
| 2026-10-25 | The thread mapping key of a correlated email conversation includes the sender's address | Message-IDs travel in every forward; under identityMode integration all senders share a user id, so a thread-only key lets anyone join any thread | An explicit sender field checked after lookup (one more stored fact and a check that can be forgotten); correlating by user id only (unsafe in integration mode) |
| 2026-10-25 | Unauthenticated email senders get a one-off email-anon id with no user-memory store and no memory tool, enforced in ConversationService and ContextualToolsProvider | Their From is a claim anyone can make; memory written under it would be read by the next forger | A shared anonymous id (one sender's injection persists for all); relying on the random id never recurring (still writes orphaned memories) |
| 2026-10-25 | Mail-server outages back off inside the poller and are COMPLETED fires with the error in the fire log | The schedule retry ladder dead-letters after a few minutes, which would silently stop a mailbox after a short outage | FAILED fires (dead-letter); SKIPPED fires (no error recorded) |
| 2026-10-25 | Polling state lives in a new IEmailMailboxStateStore (Mongo + Postgres), not the ledger or the cluster KV | The cursor is one value per mailbox that must outlive every ledger row's 30-day retention; the KV buckets are node-local and volatile in single-node mode | A cursor row in the ledger (expires, mixes per-message and per-mailbox facts); NATS KV (lost on restart) |
| 2026-10-25 | Save-time warnings travel as stable codes in an X-Eddi-Warnings response header | The channel save responses carry no body to extend, and codes let the Manager translate them in 11 locales | A response body (changes the create/update contract); log-only warnings (invisible to the author) |
| 2026-10-25 | Mail in the folder before an integration's first poll is never processed | Connecting an integration to a full mailbox must not make an agent work through years of mail | Processing everything unread (cost and side effects nobody expected); a startFrom key (more surface, deferred until asked for) |
| 2026-10-25 | A validly signed message with a second From, or a duplicated Subject/Date/Message-ID/In-Reply-To/References, is FAIL, not ALIGNED_PASS | Bottom-up selection hashes only one instance; the other is unsigned attacker text that clients may display | Trusting the signature alone; downgrading to PASS_UNALIGNED (would hide that the message is malformed) |
| 2026-10-25 | DKIM l= is refused as FAIL rather than honoured | It lets anyone append unsigned text that EDDI would read as the sender's | Honouring l= and ignoring the extra body (EDDI's parser would still read it) |
| 2026-10-25 | Signatures under a t=y key make a message NONE, not FAIL | RFC 6376 §3.6.1: testing-mode mail must be treated as unsigned | FAIL (would misreport a sender who is merely testing) |
| 2026-10-25 | A temporary DMARC lookup failure assumes strict alignment and flags the result temporary | Strict still authenticates an exact-domain signature, and never over-authenticates a subdomain one | Relaxed (could authenticate what the domain's policy forbids); FAIL (punishes the common exact-domain case for a DNS blip) |
| 2026-10-25 | dnsjava, not JNDI's DNS provider | JNDI gives no TTLs, no rcode, no control of the server per lookup and no TCP fallback control; dnsjava is BSD-3-Clause with one runtime dependency | JNDI (com.sun.jndi.dns); a hand-written DNS client |
| 2026-10-24 | Self-continuation is delivered as a one-shot schedule on the cluster-aware schedule machinery, not through the conversation coordinator's queue | E8 needs a continuation that survives a node crash, runs once under the conversation lease, and lands in dead letters when lost | Coordinator queue (in memory, lost on a crash); a custom scheduler (AGENTS.md forbids it) |
| 2026-10-24 | `maxContinuations` counts per conversation (steps carrying the engine-written `continuation:number`), not per chain | A counter the author or a property setter can reset is no bound; a job that needs more starts a new conversation | A conversation property as counter (writable by config); per-chain counts reset by user input |
| 2026-10-26 | Docs move off GitBook to an Astro + Starlight build of `docs/` on Vercel at docs.eddi.technology; docs.labs.ai redirects permanently; flat URLs from file names | GitBook URLs carry the SUMMARY section and break when a page moves; the backend's flat docs links 404 there; the marketing site already moved to eddi.technology on Vercel | Keeping docs.labs.ai canonical (docs stay on the old brand domain); folding the docs into the website repo (plain Astro with 11 locales, and docs PRs could not be validated before merge); GitHub Pages (no real redirects) |
| 2026-10-26 | DNS rebinding is closed per client: Vert.x checks the connected peer before sending, OutboundHttp re-checks after the exchange, MCP re-validates per request | Found by a repository security scan; previously an accepted risk | A JVM-wide InetAddressResolverProvider (affects every lookup in the process, loaded before app classes in Quarkus); IP-literal URLs with a Host header (breaks SNI and certificate checks) |
| 2026-10-25 | HITL notification recipients are bounded by an operator domain allowlist, defaulting to the sender's own domain | Agent authors choose recipients but the mail goes out under the operator's sender and relay, with author- and user-influenced text; an unbounded list makes the relay an open mailer | Unbounded recipients (open mailer); a per-agent allowlist (set by the same author it is meant to bound) |
| 2026-10-24 | Proposed (not approved): make → judge → repair extends the shipped output guardrails with a `judge` type (closed-vocabulary issue codes) and `onExhausted: deliver-best`, rather than a new loop (planning/evaluated-generation-plan.md) | Analysis of hosting a multi-stage video-generation pipeline found seven existing loops; #1064/#1112 made output guardrails the natural home | Checks in responseValidation, a classifier issues mode, free-text judge feedback, loops in LifecycleManager, a refine ILifecycleTask, groups for the per-item loop, a workflow engine |
| 2026-10-24 | HITL email notifications go to configured recipients only, not to "every approver" | EDDI's approvers are role holders in the identity provider, which EDDI cannot enumerate; a list built from past sign-ins would keep mailing a revoked approver indefinitely | Recording roles in the user directory at sign-in (stale and unrevokable); mailing the conversation owner (usually the end user, not an approver) |
| 2026-10-24 | The audit entry of a notification records the recipient as SHA-256 + domain, not the address | The audit ledger is write-once and erasure only pseudonymises its userId, so a plain address would outlive an erasure; the hash still answers "was this address mailed?" for someone who has it | Plain address (unerasable personal data); no recipient at all (unauditable) |
| 2026-10-24 | Operator-configured relays get their own socket policy instead of a bypass | An on-premises relay on a private address or port 25 is a normal deployment, but the pinning, metadata refusal and TLS verification are still worth having; a bypass would lose them | Allowing private addresses through eddi.email.allowed-hosts (would open them to channel authors too); opening a raw socket outside SafeSocketConnector |
| 2026-10-24 | Hidden-content pass in two modes: conservative for pages (comments, 1px, transparent, explicitly same-coloured text), strict for mail | Pages hide accordions, tabs and "read more" content with display:none/hidden/opacity until script reveals it — content RAG wants; mail runs no script, so anything hidden there is only for a machine | One strict rule set for every caller (dropped FAQ answers and tab panels from crawled pages); opt-in only for mail (leaves RAG without the white-on-white and zero-size defences) |
| 2026-10-24 | Style-block hiding rules over-approximate instead of being capped | A rule cap is a bypass: a display:none rule placed after the cap would leave the hidden text in | Truncating the style text or the rule count; exact selector matching per rule (one document search per rule, quadratic) |
| 2026-10-24 | A forward (Fwd:/FW:/WG:/TR: subject, or a forward separator) is never quote-stripped | Outlook writes the same From/Sent block for forwards as for replies, and a forwarded invoice is the content of a trigger-mode mail | Stripping by header block alone (deleted the forwarded mail) |
| 2026-10-24 | httpcall `forEach` is a new field, not `batchRequests` made to collect | Old configs with `batchRequests` on a waiting call send one request; reusing the field would silently send N | Extending `batchRequests`; dropping the fire-and-forget restriction |
| 2026-10-24 | `forEach.maxConcurrency` defaults to 1 | Sequential sends the same load as the calls written one after another; parallel is opt-in for APIs that allow it | A small default such as 4 |
| 2026-10-24 | A `forEach` item that threw (timeout) is never retried | The request may have been processed and billed | Retrying timeouts with the response retry policy |
| 2026-10-24 | Single-agent revise is `hitlConfig.onRejection: REVISE`, RULE pauses only, the frozen request kept as step data | Plan decision 9.5; a rejected tool call already reaches the model with the note | A new verdict value on the approval API; rebuilding the request from memory at resume (loses retrieved context and tool results) |
| 2026-10-24 | `maxReworks` runs the rework inside the VERIFY phase | The phase list stays linear and resume bookmarks untouched | Jumping the phase loop back to EXECUTE |
| 2026-10-24 | A guardrail-refused message is withheld from every later model-facing history by a step flag written at block time (`llm:input_withheld:<taskId>`), not by re-screening | The classifier does not re-screen history, so a message it blocked reached the model on the next turn | Making the classifier screen history (cost multiplies by the window); storing the redacted text in memory (size, and a classifier redaction is the block message anyway) |
| 2026-10-24 | `postResponse` runs after an input block, like every other fallback | An input-blocked `convertToObject` + `addToOutput: false` task answered nothing | Correcting the docs instead — that left the silent empty answer |
| 2026-10-24 | Webhook dedup refuses (503) per integration (own budget, fair share from half capacity, hard stop at 90%) rather than evicting; shared via the DEDUP bucket in a cluster | The dedup cache is the only replay protection of the github / generic-hmac schemes, and it sat on the 1,000-entry default shared by all integrations | A configurable size property (could be set below the traffic); fail-closed on any eviction (Caffeine cannot report one) |
| 2026-10-26 | A rejection note from a person asks the model to revise; automated `system:` decisions keep the "declined" text | The note is the user's way to say "not like this"; the model should come back with a new proposal to approve. Automated notes are explanations, and the group auto-reject would otherwise be retried in a loop. | Matching on the note text; a new verdict type that means "revise" |
| 2026-10-19 | The REST turn timeout counts from the request's arrival (first route of the router), and a turn still queued when its caller gave up is dropped unrun | Under a burst the worker-pool wait before the timeout was armed reached 79 s, and turns nobody waited for spent the model and the database | A default `turnDeadlineMs` for every agent; arming the timeout on a Vert.x timer at arrival, which races the resource method's own response |
| 2026-10-19 | `say`/`rerun` run on virtual threads, conversation starts stay on the worker pool | Starts on virtual threads collapsed a 2-CPU node (event loops starved); the pool is their only concurrency bound | All agent endpoints on virtual threads; a new start-concurrency limit (follow-up) |
| 2026-10-19 | `/q/metrics` is served by an EDDI route on two dedicated threads, ahead of Quarkus' blocking handler | The blocking route shares the worker pool and went blind for two minutes in every overloaded run | The Quarkus management interface, whose blocking routes use the same pool |
| 2026-10-15 | Webhook channel starts the conversation and admits the turn on the request thread | 202 must carry the conversation id and quota refusals must be a 429 | A separate executor like Slack's (429 unreportable), a non-consuming quota pre-check (none exists; racy) |
| 2026-10-15 | Self-hosted OpenAI-compatible servers get their own type, `openai-compatible`, with author-declared capability flags (jsonMode, jsonModeWithTools, vision; all off by default) instead of catalog rows | `type: openai` + `baseUrl` inherited OpenAI's JSON-with-tools and vision rules; the catalog validator requires a fixed https URL and model | Adding self-hosted rows to `openai-compatible-providers.json`; a new `extraBody` key next to `customParameters` |
| 2026-10-17 | GDPR database-log pseudonym is keyed (ledger key, else a persisted per-deployment salt); old unsalted pseudonyms are not migrated, and ledger step 7b keeps the unsalted token | L9: unsalted SHA-256 of an e-mail id is dictionary-reversible | Re-keying old rows (raw id is gone); keying step 7b too (breaks v3/v4 signatures) |
| 2026-10-17 | A secret create under grant-enforcement=enforce requires allowedHosts; ["*"] is the explicit opt-out, [] is refused | allowedAgents limits which agent references a secret, not where it is sent; every editor can choose the destination (security review 2026-10-17, H1) | A [SECURITY] warning only, which keeps scripts working but leaves the default open; requiring hosts in every mode, which overrides the warn/off opt-out |
| 2026-10-15 | LLM input/output guardrails use an EDDI-native `ContentGuardrail` SPI, not `dev.langchain4j.guardrail` | WS4/R0 implementation against langchain4j 1.22.0 | lc4j `InputGuardrail`/`OutputGuardrail` + `EddiChatExecutor` (mandatory executor = unbudgeted model-call path; sealed results; mandatory builder fields) |
| 2026-10-15 | Tenant cost is metered per settled step from the completion callback, with an `audit:metered_cost` marker; LLM tasks get a soft pre-call gate (fallback answer) | `recordCost` had no caller; the tool-call gate alone never stopped a tool-less agent | Metering per LLM task (double counts — `audit:cost` is already a per-step total); metering in `GroupCostLedger` (member turns already settle through `say`); failing the turn on refusal (the tool gate is soft; a deterministic reply is friendlier) |
| 2026-10-15 | A listing above the page ceiling is refused with 400, not clamped | A clamp returns a short page a client mistakes for the whole; a refusal tells it to page. The ceiling (1000) is the Manager's largest page — it resolves agent names and dashboard counts from one page of 1000, and 200 broke both | Silent clamping, as the conversation listing did at 100; a 200 ceiling with the Manager paging |
| 2026-10-15 | An agent with any `scope: secret` instruction never has its turn input dead-lettered | The capture runs before the pipeline, so the scrub cannot help, and a client cannot be relied on to flag every secret turn; losing replayability for those agents' failed turns is the lesser cost | Per-turn detection of secret-shaped input (the redaction filter still runs on everything captured) |
| 2026-10-15 | An approver-only caller may cancel only an `AWAITING_HUMAN` conversation | The role exists to decide pauses; a cancel of an executing turn is the owner's or an admin's to make | A separate cancel permission |
| 2026-10-15 | The Chat UI's `?token=` is an operator opt-in, `eddi.chat.url-token.enabled`, default off; served to the widget as a script global before the bundle | Security review M6: a token in the URL is login CSRF and is logged by the first request | Keeping it on and documenting the risk; a fetched JSON config, which would race the `?token=` read |
| 2026-10-15 | All three CSP filters use negative Vert.x orders so they run before the `/q` sub-router mount | Security review L11: at order 20 the Swagger filter never ran, and Quarkus passes the order to Vert.x unchanged, ascending | Moving Swagger UI off the sub-router, or a Java `Filters` observer — both heavier than three numbers |
| 2026-10-15 | `quarkus.oidc.resource-metadata.force-https-scheme` defaults to `false`; the advertised MCP resource scheme follows the request | Security review L3: a plain-http instance with auth on advertised an https identifier nothing served | Keeping `true` (wrong on every plain-http instance); deriving it from `quarkus.http.proxy.*` (no config signal separates plain http from a TLS proxy without forwarded headers) |
| 2026-10-15 | `k8s/quickstart.yaml` keeps all three `*_ALLOW_UNAUTHENTICATED` flags `true` behind a loud banner | Review L4 asked for the MCP/secret-store opt-outs to be `false` like Helm | Setting them `false` with OIDC off: `HighValueSurfaceGuard` refuses to boot, so the quickstart would crash-loop; Helm's `false` is paired with a render-time refusal, which a static manifest cannot express |
| 2026-10-15 | `docker-compose.yml` enforces "no non-loopback bind while unauthenticated" with a one-shot `bind-guard` service on the `mongo` image | Review L4; the contract was only a comment | A busybox/alpine init image (extra pull, breaks offline); an entrypoint wrapper on `eddi` (intrusive); installer-only check (does not cover `docker compose up` by hand) |
| 2026-10-15 | `mongodb.existingSecret` requires `eddi.existingSecret` | The chart builds `mongodb.connectionString` from `mongodb.rootPassword` | Reading the password back out of the operator's Secret (`lookup` is empty under `helm template` and dry runs, so the render would differ from the install) |
| 2026-10-15 | Tool cost ceilings enforced by default (`eddi.tools.budget.enforce-by-default=true`); `enforceBudget: false` is the named opt-out | Security review M8: every stored ceiling was advisory because the opt-in key was undeclared, and the $0.00 built-in pricing that justified the opt-in was fixed releases ago | Keep the opt-in and only document it |
| 2026-10-15 | Remote MCP/A2A tool parameter schemas are governed (text redaction, per-tool and per-server byte caps; descriptions stripped before a tool is dropped) | Security review M7: schemas are prompt content authored by the remote party, and a partial schema would make the model call the tool wrongly | Govern descriptions only; truncate the serialised schema mid-document |
| 2026-10-15 | `eddi.self.base-url` is the one exemption from `eddi.security.ssrf-protection.enabled` for configured endpoints (LLM `baseUrl`, embedding endpoints, spec locations) | It is deployment configuration set by the operator, not agent configuration, and an agent that talks to EDDI's own `/v1` API is a supported arrangement; without it, turning the switch on would break that on every deployment, since the self address is loopback or private by construction. Bare database hosts get no exemption: EDDI's listener is never a database. |
| 2026-10-15 | Templated httpCall hosts are refused while eddi.security.ssrf-protection.enabled=false; allowed-hosts list added | A conversation-controlled host reached loopback and private APIs | Defaulting protection to true (breaks the localhost reference agent and every configured internal API) |
| 2026-10-15 | Only eddi.caller-identity.author-roles (default eddi-admin) may store a config containing ${caller:token} | An editor-authored call spent the chatting admin's token on EDDI's admin API | Disabling self-release by default (breaks the Platform Operator); HITL-gating every caller-token call (a prompt per tool call) |
| 2026-10-13 | Model-version rules live in a data catalog consulted when a model is built, rejected parameters are dropped with a WARN (switchable) | Exposing 30 more parameters per provider without knowing which model accepts them would make agents easier to break; dropping a certain-4xx parameter keeps the agent answering | Fail the build of the model; only document the rules |
| 2026-10-13 | No catalog rule without an official source; unknown models fail open | A wrong rule silently removes a working setting, a missing rule only costs a visible vendor error | Infer rules from model-name prefixes |
| 2026-10-12 | A2A streaming uses the agent-qualified `sayStreaming`, like A2A's non-streaming `say()` | #1008 fixed the #949/#953 compile break by routing A2A streaming through the conversation-id overload and its ownership check | Keeping #1008's routing (A2A streaming and non-streaming would apply different access rules) |
| 2026-10-12 | In-memory vector stores live in their own never-evicting registry keyed by knowledge base; an ingestion run re-embeds everything when the store's epoch differs from the one recorded with the source's state | In-memory KBs emptied after 30 idle minutes or any secret rotation, and unchanged content hashes stopped scheduled runs from refilling them | Keeping them in the client cache with a longer TTL; a per-document epoch column (wider schema change for the same effect); evicting the largest KB when a memory cap is hit |
| 2026-10-07 | Bind `POST /agentstore/agents/{id}/cascade` for the operator while `PUT /agentstore/agents/{id}` stays excluded | Multi-step edits cost one approval per step (five for a knowledge-base binding); dependent steps cannot be pre-approved because approval binds to the resolved request | Plan-level / template approval (explicitly out of scope); a looser same-id-only rule on mappings (would forbid swapping to a different config of the same type); admin-only on the REST endpoint (stricter than every write it composes, and would 403 an editor-operator that holds every other write) |
| 2026-10-07 | Operator edits LLM, apicall and mcpcall configs with RFC 6902 PATCH; gate-guard reads patch operations and refuses copy/move | The operator's llmstore PUT could never work for a config holding a ${vault:…} reference: ConfigReferenceGuard refuses the echo, and an allowed echo would have stored the plaintext key | Relaxing ConfigReferenceGuard with an "unchanged from the stored document" exemption (the guard does not know the target, and the read-back is conversation data); a server-side toolApprovals restriction (cannot distinguish the operator from an admin) |
| 2026-10-07 | Operations whose required input the generator cannot send are skipped and reported, not generated half-working | An approved `updateResourceUri` call could only ever fail, and the model could not say why | Generating them and warning (the tool still exists for the model to call); failing the whole setup (one unusable endpoint would block every other tool) |
| 2026-10-07 | A gated call whose request the credential-reference guard refuses is refused before the pause, not approved unpinned | An admin approved a setupAgent call that was refused the instant they approved it | Adding an "allowed if unchanged from the stored document" exemption to the guard; weakening the guard; an approval-card warning flag (dead on every call the operator can make) |
| 2026-10-06 | A deploy refused by the vault-grant gate returns 200 + `failure`, not 409 | Handoff preferred 409 so callers notice a grant refusal | 409: every other failed waited deploy is a 200 with the outcome in the body; one outcome would get two shapes across 4 in-process callers, the Manager and quarkus-eddi |
| 2026-10-06 | One-agent grant append is a compare-and-set loop over the existing guarded write, not a new persistence method | Two admins granting two new agents at once lost one grant with the replacing PUT | A new atomic `$addToSet` per store (Mongo + Postgres twins) for the same guarantee |
| 2026-10-04 | Cluster console: read-only insights for eddi-viewer, content and actions for eddi-admin | Ids, counts and timings help a read-only on-call role; a dead letter's input is what a user typed, and every action changes the live cluster |
| 2026-10-04 | No purge-all, no stream/bucket delete, no lease hand-over in the console | Each could lose data or bypass fencing; per-item discard and the scripted DELETE endpoint remain |
| 2026-10-04 | Activity timeline in its own JetStream stream rather than node-local rings | Behind a round-robin load balancer a node-local timeline changes on every refresh |
| 2026-10-04 | Operator gets cluster reads but no recovery action | Actions act on the live cluster, not on a reviewable document; not in operator-write-scope-plan's write set |
| 2026-10-13 | The Kubernetes copy of the Full Metrics Reference ships as two linked dashboards | Keeps plain client-side `kubectl apply -k` working; the source dashboard is unchanged | Requiring `kubectl apply --server-side` (changes every operator's apply command); raising the test's budget (removes the headroom that caught this) |
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save, with Save & leave on the app's one unsaved-changes prompt | Every inline edit created an agent version and could not be undone | Keeping auto-save with an undo stack; a second navigation guard beside the router's single blocker |
| 2026-10-06 | One `LlmRecoveryLog` INFO line per recovery action, emitted by the call site that performs it | Six ad-hoc log lines had no shared shape and no conversation id; "one recovery, one line" needs a single place that formats it | Keeping each component's own log message and documenting them (not greppable as one family, no ids) |
| 2026-10-06 | A HITL resume applies onError, responseValidation and the final-answer re-ask, but not the breaker, cascade, truncation/context re-asks or a deadline | The resume pins the paused model and must not replay tools; the breaker and cascade decisions were made before the pause | Re-running the cascade on resume (would re-enter the tool loop and replay approved tools) |
| 2026-10-03 | HTTP latency panels chart mean and peak; http.server.requests publishes no histogram | The P50/P95/P99 panels queried _bucket series nobody publishes | A percentile histogram on every uri-tagged series (cardinality x ~70), and SLO buckets (still x ~10) |
| 2026-10-03 | eddi_vault_errors_count_total no longer counts "no such key" | Agent signing probes a missing key every turn, so the counter could not be alerted on | Tagging the counter by kind (a second tag shape on an existing meter is dropped by the registry) |
| 2026-10-06 | R8 circuit breaker is a new per-model class with half-open single-probe, not a generalisation of the MCP/A2A breakers | Those are private consecutive-failure counters with no half-open state or per-class window; an LLM model needs N-of-M by class and one concurrent probe | Rewriting both existing breakers onto a shared utility in the same change (behaviour-visible for MCP/A2A) |
| 2026-10-06 | R5 recovery order: repair, same-model re-ask, then escalate | A format slip is usually fixed by the model that made it; escalating first pays a pricier model per hiccup | Escalating to the next cascade step first (pays a different, pricier model for every one-off format slip) |
| 2026-10-04 | Manager imports send `X-EDDI-Space` | 6.6 scopes merge-import matching to the importing space; the raw-fetch import calls never sent the selected space | Leaving placement to the default space (merge would silently target a different copy than the one on screen) |
| 2026-10-04 | Operator allow-list not extended for `/auditstore/admin-actions` in the UI PR | `findMissingEndpoints` refuses activation when the spec lacks an allow-listed endpoint | Duplicating #952's allow-list line (would break activation on main until #952 merged) |
| 2026-10-04 | Manager uses a data router with one catch-all route wrapping `<App />` | `useBlocker` needs a data router; converting every route would churn the table and every `MemoryRouter` test | Route objects per page |
| 2026-10-03 | A2A pinned to 1.0 (spec v1.0.1), with 0.3 and pre-0.2 tasks/send still served on the same endpoint; dialect chosen by method name | The pre-0.2 dialect was unreadable to every current SDK, and 1.0 renamed every method and enum | Serving only 1.0 (breaks 0.3 peers and every older EDDI client); a separate endpoint per version (a card can name one URL per interface, and the method names never collide) |
| 2026-10-03 | Server-issued A2A task ids embed the conversation id; turns carry the task id in the input context | tasks/get had to survive a restart without redesigning the task store, which the NATS cluster work owns | Storing tasks in a new collection (a parallel store P11 would have to replace); trusting the node-local cache (loses every task on restart) |
| 2026-10-03 | /chat img-src appends eddi.chat.img-sources, default https: | Designer image output items on other hosts were blocked by the widget's own CSP | Default empty (feature broken out of the box); Manager-wide img-src widening |
| 2026-10-03 | Chat token hand-off: ready/token-request/token protocol with a 401 retry hook; allow-list stays ?tokenOrigin= (exact origins) under the operator's frame-ancestors | The widget's first request beat the host's token, was refused and never retried | A backend-served origin list (new endpoint for a value frame-ancestors already enforces); delaying start on a fixed timer |
| 2026-10-03 | GDPR erasure redacts audit content and re-signs only rows that verified; rows and sequences are kept | Erasure left verbatim PII in audit input/output/llmDetail/toolCalls and reported complete:true | A v6 canonical form over content digests (redactable without the key); deleting rows (breaks the chain); pseudonymising only (the old behaviour, kept as eddi.audit.erasure-mode=pseudonymize) |
| 2026-10-03 | Administrative REST actions are audited by excluding the data plane, not by listing admin paths | "Every API call is audited" was false; no actor was on record for config, deploy, vault, backup or GDPR actions | An allow-list of admin paths, which silently misses the next endpoint; auditing reads (volume, and the proxy access log already has them) |
| 2026-10-03 | Dream deletes/overwrites are compare-and-set on updatedAt; on a miss the user's newer write wins | Dream deleted or overwrote facts written during its LLM call | A version counter field (schema change on both backends for the same guarantee) |
| 2026-10-03 | A group member may hold one seat; repeats are refused at save time and dropped on read for stored configs | Duplicate members shared one member conversation (review top issue #12) | One conversation per seat (needs a seat id through every agentId-keyed group structure) |
| 2026-10-03 | Generic sealed data is AAD-bound to a caller context with an unbound-read fallback; a DEK rotation re-seals bound | `seal()` bound nothing, so a grant token copied between rows opened | Forced re-encryption migration; binding without fallback (would strand every existing grant) |
| 2026-10-03 | /v1 requests without a chat id are keyed by a hash of their first system + first user message (`chat-key-fallback=history`), with `shared` as the opt-back | One `:default` conversation per user/agent shared context across unrelated chats and grew without bound | Always-new conversation per request (breaks history-less clients and memory); inferring restarts from message counts; honouring a caller-supplied EDDI conversation id (needs an ownership check, deferred) |
| 2026-10-02 | EDDI's Deployment upgrades with `Recreate`; Helm `eddi.updateStrategy` can opt back into `RollingUpdate` | `maxSurge: 1` ran two EDDI JVMs against one database during every rollout, and the engine's turn lock, nonces and caches are node-local | Keep `RollingUpdate` and only document the overlap (rejected: the default would stay unsafe) |
| 2026-10-02 | Installers fetch the compose files for `latest` from the newest release tag (GitHub releases API), falling back to `main` with a warning | The `latest` image is the newest release while `main` can be ahead of it | Pin the image to the resolved tag as well (rejected: changes what `eddi update` pulls) |
| 2026-10-02 | The metrics job keeps aggregate counts only; the stargazer roster is no longer collected | A per-person record kept forever, for a question the star count answers | Keep the roster and document a retention rule (rejected: data minimisation) |
| 2026-10-02 | The two "first agent" tutorials are folded into one page (creating-your-first-agent/README.md) and the sub-pages are deleted | Both repeated the same steps with different mistakes; one tested page is cheaper to keep correct than two. The anchors other pages link to are directory links and still resolve | Correcting both pages in place (two copies of the same steps to keep in sync) |
| 2026-10-02 | Doc examples keep `labsai/eddi:6.4.0` / `EDDI_VERSION=6.4.0` although 6.5.0 is tagged | ReleaseVersionSourceTest ties every pointer to helm/eddi/Chart.yaml's appVersion, still 6.4.0 until the post-release PR lands; bump-version.py moves them all together. Superseded on 2026-10-12: the post-release PR moved the chart to 6.5.0, so the examples were bumped with it when main was merged | Writing 6.5.0 now (fails the build until the chart moves) |
| 2026-10-02 | Conversations with no owner anywhere are admin-only; the snapshot owner is the fallback | F1: a stripped descriptor opened a conversation to every token | Keeping "unowned = open" for legacy data — the pre-v5.1.6 legacy case records its owner in the snapshot, so the open rule protected nothing real |
| 2026-10-02 | The generic descriptor/sharing API answers for configuration types derived from the IRestVersionInfo stores | F1 | A deny-list of non-config types (breaks silently on the next new type); moving conversation descriptors to their own collection (a data migration on both backends for the same protection) |
| 2026-10-02 | Triggers need EDIT on their targets for create, update and delete | F2 | USE (any chat recipient could re-point); OWN (excludes team editors who legitimately maintain the routing) |
| 2026-10-02 | Metrics default to a role (eddi-admin, eddi-metrics) rather than any token | F7 | Keeping `authenticated` (a role-less token read the exposition); shipping the eddi-metrics role in the realm files (needs three realm copies and a chart bump for an opt-in scrape account) |
| 2026-10-02 | Parameter-conversion 404s become 400 with a JSON body; other 404s unchanged | JAX-RS §3.2 maps a bad query/path parameter to 404, indistinguishable from a missing resource | A global ParamConverter (does not cover built-in types) |
| 2026-10-02 | A timed-out turn stays in inFlightConversations until its body returns | the registry was the only handle /cancel and GDPR stop had on it | Interrupt-only abandonment (swallowable) |
| 2026-10-02 | HITL timeouts whose decision cannot be applied yet are re-armed at a fixed interval and never dead-lettered | A COMPLETED-and-disabled one-shot silently turned a finite policy into wait-forever | Retrying on the normal backoff ladder (dead-letters after ~21 min) |
| 2026-10-02 | Schedule fires re-check USE against a creator access snapshot (teams + admin flag) taken at creation | principalMayUse(id, principal) refuses team shares; server has no team membership without a token | Re-checking the bare principal; skipping the check |
| 2026-10-02 | Scheduler lease is raised at startup to outlast fire-timeout + grace instead of failing startup | A misconfigured scheduler should not take the platform down | Failing startup; heartbeat-renewed leases (more store surface) |
| 2026-10-02 | Conversation-bound tools are excluded from the tool cache entirely, not narrowed to the conversation scope | Their state also changes within a conversation (new uploads, peer artifacts) and several have side effects; the classification is derived from the tool class (non-CDI-bean = per-conversation) | Forcing the conversation scope (still stale within a conversation); extending the hand-kept name list |
| 2026-10-02 | EDDI's RetryConfiguration is the single LLM retry layer; provider SDK retries are set to 0 | 3 × 3 = 9 upstream calls per failure; EDDI's layer is configurable, budgeted and skips non-retryable 4xx | Disabling EDDI's layer and keeping the SDK retries (not configurable per task, no shared backoff budget) |
| 2026-10-02 | Manager deletes default to soft; hard delete is an explicit unticked option in every dialog | Review #7: "Delete Group Only" sent permanent=true and also deleted the workspace | Keeping permanent defaults with stronger wording; removing hard delete from the UI entirely |
| 2026-10-02 | Data router with one catch-all route around the existing <Routes> to get useBlocker | No in-app unsaved-changes guard was possible under <BrowserRouter> | Rewriting the route table as route objects; intercepting link clicks by hand |
| 2026-10-02 | Role-aware UI fails open when the token carries no EDDI role | A deployment may map roles from another claim; hiding admin screens from a real admin is worse than noise | Hiding everything not positively allowed |
| 2026-10-02 | MCP role tiers mirror REST `@RolesAllowed` per tool; `eddi-viewer` is kept as an MCP-only read-and-converse role; audit trail and conversation logs stay admin + viewer (REST: admin); schedules and memory writes stay admin-only over MCP | Review §2 #17: MCP setup open to editors, deploy closed to them, config reads open to viewers, and no hierarchy for reads | A real role hierarchy in `requireRole` (would also widen every admin-only check implicitly); dropping `eddi-viewer` from MCP entirely (breaks every viewer-based MCP client) |
| 2026-10-02 | MCP tool failures become `isError: true` through an interceptor that rethrows `errorJson` results as `ToolCallException` | Every tool returns a `String`, and the unit tests compare those strings | Changing every tool's return type to `ToolResponse` |
| 2026-10-02 | Store Long as valueLong and Double/BigDecimal as valueDouble (new Property fields); a Long that fits an int is stored as valueInt | PropertySetterTask/PrePostUtils dropped or blanked them | Widening valueInt/valueFloat (breaks stored documents); storing numbers as strings (loses type in templates and conditions) |
| 2026-10-02 | A postResponse fromObjectPath that resolves to nothing writes nothing | The two property engines disagreed (postResponse stored "", property.json skipped) | Keeping "" (overwrote a good value with an empty one whenever an API omitted a field) |
| 2026-10-02 | Failed preRequest/postResponse property instructions are recorded under step key propertyInstructions:errors and do not fail the turn (scope secret excepted) | The failure was a log line only | Failing the turn (the response has already been received; one bad instruction would discard the others) |
| 2026-10-02 | The parser builds next-turn quick-reply dictionaries from the previous step's quickReplies:* data, which client-context quick replies never get; postResponse-built ones are marked server-generated on their Context | N3: client context quick replies minted expressions/actions | Stripping only the expressions (a value still generates an expression); a new persisted provenance field on IData |
| 2026-10-02 | A parser step's config.uri document is merged with the inline step: step config keys win, step extensions are appended after the document's | N1: the uri was ignored | Document-only or inline-only (either breaks existing workflows that carry both) |
| 2026-10-02 | Setup-created agents no longer get expressionsAsActions=true | Not needed by their inputmatcher-* rule; amplified N3 | Keeping it (documented risk) |
| 2026-10-02 | Unknown, foreign and malformed ids are "not found" (404) on both datastores, checked before any statement is sent | PostgreSQL answered 500, MongoDB 400 on a GET and 500 on a POST, for the same request; archives and URLs from one backend carry the other's id format | 400 for a malformed id on both (MongoDB had it on some paths) — rejected: a UUID is not malformed, only foreign, and most callers cannot tell the backends apart |
| 2026-10-02 | PostgreSQL sorts `DESC NULLS LAST`, and its field indexes are rebuilt in that order (`idx_resources_fdesc_*`, replacing `idx_resources_field_*`) | MongoDB sorts a missing field last, PostgreSQL first | `NULLS LAST` on the old ascending index — rejected: the planner can no longer read a sorted page off it and sorts the whole collection per listing page |
| 2026-10-02 | On an upgraded database the `idx_resources_fdesc_*` replacements are built `CONCURRENTLY` in the background, under a per-index advisory lock, and the old index is dropped only once the new one is valid | A blocking `CREATE INDEX` at boot locks the shared `resources` table (a descriptor per conversation) against writes cluster-wide for the whole build | `CREATE INDEX CONCURRENTLY` at boot — rejected: it waits for every open transaction, so boot could stall, and two booting instances race on one name; keeping the ascending index and adding the new one beside it — rejected: two indexes per field cost every write |
| 2026-10-02 | The EDDI 5 migrations are inert when `eddi.datastore.type=postgres` | `holdsRetention()` dereferenced the lazy `MongoDatabase` proxy on PostgreSQL and held retention for ever | Injecting `Instance<MongoDatabase>` — rejected: the proxy is already lazy; the defect was calling it, and EDDI 5 never ran on PostgreSQL |
| 2026-10-02 | RAG stores are addressed by the config id; a `storeNamespace` field keeps 6.5.0 KBs on their name-derived store until renamed or switched | Cross-KB poisoning via `?kbId=` and same-name KBs sharing a store | A startup migration moving vectors (EDDI does not own external stores); filtering legacy KBs (would hide all 6.5.0 chunks) |
| 2026-09-30 | An idle limit below 1 disables idle-ending; old versions with no active conversation are still undeployed | `-1` ended every conversation, while the retention settings read `-1` as "never" | Treating `-1` literally; also stopping undeploys (a separate decision needing its own setting) |
| 2026-09-30 | A NEGOTIATION group's prompt-less Arbitration phase is repaired in the backend at run time and on save, not rejected | Only the Manager repaired it, so behaviour depended on which client saved the group | A 400 at save (the Manager saved this shape itself, so its groups would become unsaveable) |
| 2026-09-30 | Schedule `enabled` changes only through `/enable`/`/disable`; a configuration PUT keeps the stored value inside the same atomic write on both stores | CodeRabbit on labsai/EDDI#854: the Manager editor's save re-enabled a schedule another operator had disabled after the dialog read it | Optimistic concurrency on `updatedAt` with 409 (needs a client protocol change, and the other lifecycle fields already follow the keep-stored rule); simply omitting `enabled` from the PUT (the model defaults it to `true`) |
| 2026-09-29 | Merge with main: a captured conversation property is kept at turn end; a secret input shorter than 4 characters is removed as a whole token, in values only | The wizard pattern hands a captured key to a later turn; a short PIN echoed into a reply must not persist, but digits inside other numbers and field names must | Main's property scrub; no search below 4 characters; renaming map keys in token mode |
| 2026-09-29 | One wall-clock deadline, max(3 × connect timeout, request timeout), covers every SafeHttpClient send path including buffered body reads; redirect bodies go to a 64 KiB discarding subscriber | H16/R8 after #862: the per-request timeout stops at the headers, and 3xx bodies reached the caller's handler (an unclosed stream per hop on the bounded paths) | Leaving buffered paths to per-caller bounds; discarding redirect bodies without a cap (downloads a huge courtesy page in full) |
| 2026-09-29 | Reconciled fix/outbound-http-hardening with #862 by keeping main's implementation wherever both fixed the same finding | Two parallel fixes; main's shipped first and is what other code builds on | Keeping the PR's handler-level BoundedBodyHandlers alongside main's sendBounded (two mechanisms for one job) |
| 2026-09-29 | Block the NAT64 local-use prefix 64:ff9b:1::/48 whole instead of unwrapping its trailing 32 bits | RFC 8215 allows any RFC 6052 prefix length inside it, so the IPv4 address need not be at the end | Unwrapping per prefix length (the length is a per-network choice EDDI cannot see) |
| 2026-09-29 | PR #831 keeps #859's `ClientContextGuard` and #860's converse ownership check, drops its own `ReservedContextKeys` and started-conversation set, and applies the delegation USE check to continuations | Two implementations of one boundary drift apart; the USE check on continuations closes the one case the started set covered that ownership does not | Keeping the started set alongside the ownership check (two continuation rules for one tool); stripping in `ConversationService` as well (a second boundary for the same keys) |
| 2026-09-29 | Reconciled with main: main's per-write auto-vault slots (AutoVaultedSecrets) replace this branch's per-conversation key; only the deltas main lacked are kept | Both sides fixed C2; one implementation, main's | Keeping both; keeping the per-conversation key and pre-allocated conversation id |
| 2026-09-29 | Where this branch and #861 fixed the same Slack finding, keep #861's implementation (approval records + card ids, owner-bound signatures, mandatory namespaced ids) | Two parallel bindings of one decision are harder to reason about than one | Keeping both bindings; keeping this branch's opt-in user-id namespacing |
| 2026-09-29 | Every Slack route the event handler takes must belong to an app holding the secret that verified the event | Thread locks and group follow-ups are found by a sender-chosen timestamp, outside the webhook's channel-owner check | Checking the channel only (a DM is owned by nobody) |
| 2026-09-29 | KEK rotation keeps main's rollback and adds re-runnability only for what a rollback cannot undo | Both branches fixed "a half-way rotation strands DEKs"; one mechanism, main's, with the pending salt and either-KEK verify for the unrecoverable cases | Forward-only re-run (this branch's original); rollback without the pending salt (loses DEKs when the salt write fails) |
| 2026-09-29 | Libraries that come from Quarkus or integrate with it take the version the Quarkus platform ships, never a newer one | Quarkus validates its platform as a set; running ahead of it invites classpath and behaviour mismatches nobody tested | Taking the newest stable release of every library independently |
| 2026-09-29 | langchain4j stays at 1.20.0 (1.20.2 reverted) | The Quarkus 3.39.5 platform ships langchain4j 1.19.3 via quarkus-langchain4j-bom | 1.20.2 with langchain4j-community-oci-genai pinned at 1.20.0-beta30 |
| 2026-09-29 | Global-key ownership for the rememberFact tool is decided by an owner-conditional write in the store (ON CONFLICT … WHERE on PostgreSQL; an owner-filtered upsert against a new unique partial index on MongoDB) | Read-then-upsert let two agents both see a key as free and the later value win (CodeRabbit on #860, CWE-367) | Serialising writes per user in the tool (single-node only); a transaction around read and write (not available on standalone Mongo) |
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API; the ban-jackson3 enforcer rule forbids it and the Quarkus BOM ships Jackson 2 | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
| 2026-09-28 | UI majors: take every stable major whose peers allow it; keep TypeScript 5.9 in the Manager, hold jsdom at 30.0.x, and pin react-hooks to its two classic rules | typescript-eslint 8.71 caps TypeScript below 6.1; Vitest 5.0.2 cannot unwrap jsdom 30.1 Blobs; react-hooks 7 `recommended` adds 119 React Compiler findings | Forcing TS 7 with overrides / --legacy-peer-deps; jsdom 30.1 with a Blob shim in test setup; fixing 119 compiler findings inside a dependency bump |
| 2026-09-28 | A vault reference in synced content yields to the target's own value at the same place; it travels only where the target has none | Which vault entry an environment uses belongs to that environment; a promotion that repointed production at staging's entry broke every call it made | Transferring references as-is (the defect); stripping them entirely (a first promotion would lose the only hint of which entry to create) |
| 2026-09-28 | Local edits are detected by a `syncedVersion` recorded on the descriptor at every sync or import write, and a resource changed on both sides is written only when named | The descriptor already travels forward with every version through `DocumentDescriptorFilter`, so a version past the recorded one is a local edit without any new store; overwriting a hotfix by default is the one outcome a promotion must not produce | Content hashes in a separate collection (a second store to keep in step); warning but writing anyway (the hotfix is still lost) |
| 2026-09-28 | A resource for a step the source added is created only when the workflow adoption that places it is decided to succeed, before anything is written | The previous refusal made adding a step impossible to promote; creating unconditionally left an orphan whenever the adoption was refused afterwards | Refusing (unusable); creating then deleting on refusal (a second failure path mid-sync) |
| 2026-09-28 | Without `targetAgentId`, a sync targets the agent promoted from that source (by `originId`), with `createNew` to force a copy and 409 when ambiguous | The guide already said the originId "is what lets the next sync match it", but nothing matched on it, so every call without a target made another full copy | Keeping "no target means create" (not idempotent for scripts and CI); picking the most recent of several (a guess) |
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed (64-bit) per-space tenants, authorized by membership and checked again at deploy; a reference assembled from variable pieces is refused at resolution | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
| 2026-09-27 | A client-flagged secret input is searched for from 4 characters, not 8 and not 1 | A PIN or short password is what a password field carries; below 4 the replace-everywhere search shreds the turn's reply and map keys for a guessable value. Same floor in the turn-end scrub, read-time masking and audit buffer | The 8-character context-value floor; every nonempty form |
| 2026-09-26 | Chat UI: KaTeX and highlight.js are loaded on first use, not bundled | Wiring the advertised features statically tripled the widget bundle (645 → 1086 kB) | Static imports; dropping the features from the README |
| 2026-09-26 | A client-flagged secret input is scrubbed when its turn ends, and the turn output's `input` is the masked display copy on the wire | The parser overwrote the placeholder with the normalized plaintext, so exposing `input` alone would have leaked a copy | Exposing `input` without the engine fix; scrubbing properties the designer captured the input into (breaks the wizard pattern) |
| 2026-09-26 | Older stored secret turns are masked on read, not migrated | Every secret turn carries `context.secretInput`; masking on read closes the exposure for history at no migration cost | A data migration; leaving history exposed |
| 2026-09-26 | Chat UI math is `$$…$$` only | Single-dollar math turned dollar amounts into formulas | remark-math defaults |
| 2026-09-26 | A queued turn whose snapshot was superseded is rebuilt over a reloaded memory (revision probe first) | H13a/E1: queued turns ran on request-time memory | Always reloading (doubles every turn's load); refreshing the memory in place (the Conversation's longTerm baseline would stay stale) |
| 2026-09-26 | Persistent schedules may roll over (opt-in, idle only, conversation properties carried) at a step limit instead of trimming | 16 MB document limit | Trimming steps (destroys history, violates "full data is never deleted by optimization"); on by default (silently resets a stateful agent's history) |
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
| 2026-09-26 | A failed merge import writes each updated resource's pre-import content back as a new version (and restores its descriptor) | M-P4: rollback only deleted created resources, leaving the target half-promoted | Deleting the imported version / restoring history in place (history is append-only everywhere else) |
| 2026-09-26 | `IResourceStorage.storeHistoryAndRemove` takes the expected version and has no default implementation | M-P5: a delete racing an update erased the committed version and lost its tombstone | Keeping an unconditional default for non-transactional backends |
| 2026-09-26 | Tool-loop retry wraps one model request, not the loop | H11a: whole-loop retry re-executed tools | Keeping whole-loop retry with a dedupe journal on the live path (more state, same result) |
| 2026-09-26 | Cascade escalation carries the executed tool exchange to the next step (default on, `carryToolResultsOnEscalation`) | H11b: each escalation re-ran side-effecting tools | Refusing to escalate after any tool ran (defeats agent-mode cascades) |
| 2026-09-26 | The carried-exchange fallback retries only a rejected FIRST request of a step, and only for a 400/422-class rejection | A failure after tools ran would re-run them; auth/model errors cannot be fixed by dropping the exchange | Retrying on any non-retryable failure (replays side effects, masks auth errors) |
| 2026-09-26 | HTTP/MCP/A2A tool results are cached only when named in `toolCacheScopes` | A cache hit skips a write | A new `cacheableToolSources` list (extra field, same effect) |
| 2026-09-26 | Manager approval decisions carry the shown pause (pauseId when reported, else a fresh re-read compared on kind/start/call ids) | A queue row up to 10 s old approved whatever the conversation was paused on, including an unreviewed tool-call batch | Sending toolDecisions only (does not stop a rule-row approval landing on a tool pause); waiting for the backend pauseId alone (leaves current main unprotected) |
| 2026-09-26 | Audit screen verdict comes from GET /auditstore/verify, green only when verified | hmac presence was shown as a verified shield | Verifying client-side (no key in the browser); keeping the count banner with softer wording |
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence. Accepted Vixie consequences: `0 2,3` collapses into one fire on spring-forward, fixed hour ranges do not repeat the doubled hour | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 (both operator-configurable) | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `null` or `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
| 2026-09-26 | A schedule PUT is judged on the STORED row for managed schedules: RAG ingestion refused outright (409), team cadence requires EDIT on the group | Review MAJOR 1: carrying metadata over let USE+VIEW re-cron a knowledge base's crawl | Requiring KB EDIT for ingestion edits (as fireNow does): the knowledge-base save re-creates the row from its source cron, so an edit here would be silently undone anyway |
| 2026-09-26 | Batch cap default and ceiling are operator properties (`eddi.httpcalls.batch.*`); a `maxBatchSize` above the ceiling is refused at save time | Review MINOR 2: hard-coded cap was a regression with no escape hatch | Silent runtime clamping only |
| 2026-09-26 | `RestAgentStore.updateAgent` keeps the stored `dynamicOrigin`; duplicate and new-agent ZIP import drop it; create still accepts it | The marker authorizes permanent deletion, so only the engine may write it; setup writes it through the loopback REST create | Stripping on create (breaks `AgentSetupService`); a separate engine-only store field (larger change, same outcome) |
| 2026-09-26 | Reserved context keys are read with exact-key lookups (`getData`, new `getExactDataPerStep`) **and** `ClientContextGuard` also drops keys that start with a reserved name | `getLatestData`/`getAllLatestData` match by prefix, so `groupIdSuffix` and friends passed the exact-name guard and were read as the reserved key (PR #831, CWE-863) | Only the exact lookups (the next prefix read reopens it); only the prefix drop (leaves every reader relying on a distant boundary); changing `getLatestData` to exact matching (many callers rely on the prefix, e.g. `httpCalls:*`, `output:*`) |
| 2026-09-26 | `teardown_agent` and end-of-discussion cleanup require a `dynamicOrigin` marker on the target agent in addition to the created-ids list | The list alone decided and could be forged; deletion is permanent | Trusting the list once the context channel is closed (one leak away from deleting a person's agent) |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
| 2026-09-26 | HITL decisions may carry a pauseId (epoch-ms of the pause start), checked before and after the resume CAS | A stale REST/MCP decision could approve a later, different pause | A stored random pause UUID (needs a schema field and migration for RULE pauses) |
| 2026-09-26 | A non-owner approver's identity is bound only around the tool calls they approved; the rest of the resumed turn has no caller | ${caller:token} calls after an approval ran with the approver's token, including un-previewed calls after a RULE pause | Keeping the whole-turn binding (privilege escalation); dropping it entirely (breaks approved caller-bound calls) |
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Lost master key is resolved by an explicit admin call (adopt-master-key), not automatically | A stale replica and a lost key look identical from a node; guessing either way loses data | Auto-adopting on mismatch (lets a stale replica strand tenants); manual DB edit (undocumented) |
| 2026-09-26 | UNKNOWN_KEY only for key ids recorded as sealed system values | The key id in a row is attacker-writable text | Rewording docs only |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
| 2026-09-26 | Schedules that run as a real user are that user's for every operation, workspaces on or off | H2a: editors could list/disable/delete other users' dream schedules | Workspace-only scoping (left the per-user leak open with workspaces off) |
| 2026-09-21 | Default the per-tool timeout to 120000ms and leave it ON, rather than shipping it disabled | A bound nobody enables does not fix a hang; and the field is null on every agent already stored, so the engine-side fallback is what actually decides the default | Shipping `-1` by default (inert for everyone), and a 30s default (would cut into legitimate nested-agent and delegation calls, and duplicate the MCP/A2A transport timeouts) |
| 2026-09-21 | Return an error string on expiry instead of throwing | `RetryConfiguration.isRetryableError` treats `TimeoutException` as retryable, so a thrown timeout becomes a retry loop over a hang; and the rate-limit branch already established "tell the model, keep the turn" | Throwing a `LifecycleException` (fails the turn), throwing `TimeoutException` (retried), a custom checked exception (every caller would have to translate it back into a string anyway) |
| 2026-09-21 | Run the bounded call on a shared virtual-thread executor, inline when unbounded | A timeout needs a second thread by construction; virtual threads make the abandoned worker of a hung tool nearly free, and running inline when disabled keeps the untouched path byte-identical | A fixed platform-thread pool (an abandoned worker costs a whole thread and stack, and a pool of N hung tools deadlocks the N+1st), a pool per call (leak by construction) |
| 2026-09-21 | Add `int timeoutMs` as a tenth parameter rather than a field on `ToolInvocation` | The brief asked for the `rateLimit` shape end to end, and `rateLimit` is a parameter; it also breaks stale Mockito stubs at COMPILE time instead of silently mismatching at runtime | Putting it on the `ToolInvocation` record (would have kept every `any(ToolInvocation.class)` stub compiling while no longer describing the call), a separate 10-arg overload (same silent-mismatch problem) |
| 2026-09-21 | Count abandoned workers on a gauge, but put no admission bound on the timeout executor | A leak an operator cannot see is the dangerous half of the trade-off, and counting is unambiguously right; refusing healthy tool calls because unrelated ones are stuck trades a per-tool failure for a conversation-wide one, which is a product call rather than a review fix | A bounded-queue or semaphore executor with a model-visible "too many tools running" error (Copilot's suggestion, left open for a human), a per-tool `Semaphore` (same blast radius, more bookkeeping), doing nothing and leaving the accumulation undetectable |
| 2026-09-21 | Leave the `String` overload unbounded | Its only caller is `McpCallsTask`, which has no `LlmConfiguration.Task` and already bounds its tools with `McpCallsConfiguration.timeoutMs` | Giving it the same default (would apply an LLM-task setting to a workflow step that cannot configure it) |
| 2026-09-21 | Walk `docs/` rather than list it in `ComposeStackTest`, and assert the sweep's shape | A guard that reads only the top level would have failed over a nested page that *does* document a stack; a false failure is how a guard gets deleted rather than fixed | Listing the top level only (the reviewed version), naming the nested directories explicitly (a third place to forget when one is added) |
| 2026-09-29 | Each Slack approval card carries a random card id in its buttons, recorded with the card; a click must present the id recorded for the current pause | Subject + pause matching let an old card of the same subject approve a newer pause | Storing the posted message `ts` on the record after `chat.postMessage` (a post-then-update window, and a failed update leaves a live card unusable) |
| 2026-09-28 | The Slack workspace comes only from a declared `platformConfig.teamId`, for owned channels too; without one the identity is team-less | A signing secret authenticates the integration, not the workspace in the payload | Trusting an owned channel's payload team_id; scoping team-less ids by integration name (names are mutable) |
| 2026-09-28 | Adoption of raw-id `/v1` chat mappings is opt-in (`adopt-legacy-header-mappings`, default false) | A raw mapping may have been written for an OIDC principal under `http-policy=authenticated`; its origin is unrecorded | Adopting by default (reopens the cross-namespace reach); heuristics on the id's shape |
| 2026-09-28 | `/v1` refuses an OIDC principal carrying the reserved `openwebui:` prefix | Such a principal equals a header-derived identity | Namespacing OIDC principals too |
| 2026-09-27 | Removed the standalone bare-id memory MOVE for both OpenAI-compat and Slack; kept adopt/rekey only | The move read the shared bare-id namespace on an attacker-chosen id and could relocate+erase an OIDC user's memories (review Finding A/B) | A mapping-gated move (still leaks on a bare-id string collision with an OIDC principal) |
| 2026-09-27 | HITL owner binding refuses an ambiguous integration name and enforces global per-type name uniqueness | Integration display names were not unique, so a copied name could bind a decision to the attacker's config (review Finding C) | Keying everything on a unique resource id (larger blast radius on a security branch) |
| 2026-09-27 | Slack webhook rejects an event whose claimed workspace disagrees with the signing integration's declared teamId; a teamId-less unowned (DM) event is bound team-less, not to the payload team | Payload team_id was attacker-controllable in a validly-signed DM event → cross-tenant memory read (review residual #1) | Trusting the payload team when the integration declares none |
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
| 2026-09-26 | GDPR erasure stops in-flight work through a CDI SPI (`UserErasureParticipant`) called before the cascade, not a direct call | The services that own turns and discussions depend on GdprComplianceService, so the dependency cannot point the other way; same pattern as SealedDataRotationParticipant | Injecting ConversationService/GroupConversationService directly (dependency cycle); a CDI event (no failure reporting into failedSteps) |
| 2026-09-26 | `_gdpr_` memory keys are refused at the store (`upsert`/`mergeProperties`) and written only via `upsertReserved` | A caller-side check misses the next write path; a category check is forgeable because REST/MCP callers choose the category | Validating in each caller only; honouring the key when category == gdpr |
| 2026-09-26 | Manager treats a deployment at an older version as "live" (flagged `deployedVersion`) instead of "Not deployed" | Every save bumps the version and the per-version status endpoint answers only for the exact version | A backend "any version" query parameter (needs a contract change); asking the listing for every card (N requests) |
| 2026-09-26 | Workforce Stop cancels on the server before closing the stream; a 409 closes it without claiming CANCELLED | Stop only aborted the SSE connection, leaving the run spending | Aborting first and cancelling after (a failed cancel would then leave a running discussion with nothing following it) |
| 2026-09-26 | A stream that ends without a terminal event is "interrupted", and the UI follows the stored conversation | A dropped connection froze the board or showed FAILED for a still-running discussion | Closing placeholders as SKIPPED on interruption (those members may still be answering) |
| 2026-09-26 | returnDetailed hardened by redaction + denylist rather than an owner gate | The detailed conversion is a central chokepoint reached by several endpoints and the owner is not cheaply available at that layer; raw full-fidelity data stays on the owner/admin-gated raw endpoint | Restricting returnDetailed to admin/editor/owner at every call site |
| 2026-09-26 | UserMemoryTool default allowedVisibilities = [self] | Secure-by-default: a prompt-injected model must not broadcast group/global memories unless an operator opts in; the configured defaultVisibility is always unioned in so it is never self-blocking | Leaving visibility fully model-chosen |
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id mappings are re-keyed only | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
| 2026-09-26 | OpenAI-compat header users are `openwebui:<id>` in EDDI; raw-id mappings are re-keyed only (opt-in since 2026-09-28) | `X-OpenWebUI-User-Id` shared the OIDC principal namespace, so a shared-key holder reached OIDC users | Namespacing OIDC principals too (they are already canonical); a one-shot bulk migration |
| 2026-09-26 | Mask secret input by scrubbing input:initial post-pipeline in Conversation's finally, not at store time | The plaintext must reach parser/property tasks (vaulting) during the turn but never persist; the finally runs on error/pause paths too and precedes the audit redaction | Storing a placeholder up front (would break scope=secret vaulting and normalization) |
| 2026-09-26 | Chat markdown images render as links (keep img in sanitize schema + component override) rather than dropping img from the schema | The "renders as a link" behaviour needs the node to reach the renderer; the override prevents any fetch, preserving the URL as a click-through | Dropping img from the sanitize schema (loses the URL entirely and cannot render a link) |
| 2026-09-26 | Deep links preselect the agent only; starting a conversation stays an explicit user action | Auto-starting as the admin from a URL param is a CSRF-style side effect | Keeping the auto-start behind a confirmation step |
| 2026-09-26 | Bind EDDI to 127.0.0.1 by default in compose, opt-in `EDDI_BIND` to expose | An unauthenticated default must not be network-reachable out of the box; MongoDB was already loopback-bound, EDDI was not |
| 2026-09-26 | Helm: require explicit opt-in for /mcp and /secretstore when OIDC is off | Deriving the opt-outs from oidc.enabled silently opened the two highest-value surfaces, defeating HighValueSurfaceGuard |
| 2026-09-26 | CI: sign the image digest and require the release tag to be an ancestor of main | A tag on any commit could publish and sign :latest, bypassing review; a digest signature cannot be moved by a later tag push |
| 2026-09-26 | Bound outbound response reads with a shared BoundedBodyReader (cap + deadline) rather than per-tool ad-hoc checks | One implementation across attachment/web/pdf/A2A paths, mirroring the crawler's tested readBounded; size is enforced while streaming, not after buffering | Keep per-tool post-buffer size checks (the status quo that let unbounded bodies into memory first) |
| 2026-09-26 | Drop the shared WebClientSession cookie jar for httpcalls in favour of a plain WebClient | The single app-scoped session replayed one user's Set-Cookie on another user's call to the same host; no httpcalls feature needs cookies | Scope a WebClientSession per conversation/principal (more machinery for a capability nothing uses) |
| 2026-09-26 | Strip `SafeHttpClient.SENSITIVE_HEADERS` on every cross-origin redirect hop in the shared httpcalls client (`HttpClientModule`), and additionally disable redirect-following for a request whose header carries a resolved (reference) credential | Vert.x's redirect handler strips none of these headers cross-origin, so a literal credential written in the config leaked too; the hop-level strip covers every credential source, and the per-request disable is the stronger measure for resolved secrets | Disable redirects only for reference credentials (the first cut: missed literal credentials); strip only the three RFC headers (Vert.x does not even do that) |
| 2026-09-26 | Under enforced workspaces a render gets only snippets from the agent's space, its owner's own snippets (personal-space agents only) and legacy snippets; granted/published snippets from other spaces are never auto-injected | C4c + pre-push review: snippets are injected by name, so a grant or publish is a push into other tenants' prompts (incl. the counterweight preset names) | Ranking grants/published below closer tiers (they remain the only candidates for unclaimed names such as the presets); scoping by the chatting user |
| 2026-09-26 | Caller-supplied and already-rendered output is marked verbatim per data entry, persisted in ResultSnapshot, and never templated again | C4a + review: context output was rendered as Qute; a HITL resume or a second templating pass re-rendered data | Key-prefix convention (collides with an action named "context", suffix is client-chosen); a transient flag (lost on tool-call resume) |
| 2026-09-26 | Strip engine-reserved context keys at the client entry points (REST, streaming, MCP trigger context) with an opt-in config list, rather than verifying them downstream | The engine trusted client-supplied values for group memory scope, dynamic-agent policy, created-agent tracking and delegation depth | Marking trusted entries with a flag on `Context` (does not survive the store round-trip, so reloaded steps would lose the policy); stripping inside `ConversationService` (internal group/delegation callers use the same methods) |
| 2026-09-26 | Runtime templates render with an EDDI-built Qute engine assembled from the Quarkus engine's resolvers through allow-lists | The injected engine exposes config:, inject:/cdi:, str:eval and unrestricted reflection to agent-authored templates | Keeping the Quarkus engine and scrubbing template text (unbounded syntax surface); hand-writing all extension resolvers (duplicates Quarkus's generated ones) |
| 2026-09-26 | Values read via fromObjectPath are stored verbatim, with no opt-out | They are user/upstream data; no legitimate configuration needs them evaluated, and valueString covers composing text | A config flag to restore rendering (would re-open the path per agent) |
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
| 2026-09-22 | Round boundaries are recovered from QUESTION entries but validated against the stored index | Only the current round's start is persisted. Trusting the QUESTION invariant blindly split a discussion on a stray marker; validating and falling back narrows the switcher instead of slicing the view wrongly. | EDDI Manager |
| 2026-09-22 | The interaction band is a list, not a graph | A force-directed diagram of five nodes is decoration and of twenty is unreadable; a list answers "who did this member take on" and "who went unchallenged" at any size with no layout engine. | EDDI Manager |
| 2026-09-22 | DELPHI gets no interaction band | Naming who answered whom would undo the anonymity the method rests on — the reason its later rounds run ANONYMOUS. | EDDI Manager |
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call, and also widen `sanitizeSubject` to strip CR, LF and tab | The log call and the subject token answer different questions, so both are fixed. Widening was first rejected as moving the subject namespace; that was wrong — NATS refuses a subject containing those characters outright, so nothing was published under one, and the rejection is an unchecked `IllegalArgumentException` that escapes the publish's `IOException \| JetStreamApiException` handler | Leave CR/LF in `sanitizeSubject` and rely on the log call alone — keeps a latent unchecked-exception path for no gain |
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with a repeatable startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
| 2026-09-21 | Arm a legacy row in the zone the poller will use, rather than in UTC or by widening the store's re-arm to carry one | `armIfUnarmed` writes only `nextFire`, so a legacy row's `timeZone` stays null and the poller re-arms it in the deployment default; arming the first fire in UTC regardless would make exactly one interval the wrong length | Adding a zone to the store's re-arm on both backends (CodeRabbit's suggestion — it would also normalise legacy rows to UTC, at the cost of a third field in a predicate that exists to do one thing), leaving the fixed-UTC arm and the drift with it |
| 2026-09-21 | Close the two-node repair race with a conditional `armIfUnarmed` on both stores rather than a re-read | A re-read narrows the window to one store round-trip and still reads a snapshot; the predicate is the only place two nodes meet, and ~20 lines per backend is a small price for a write that cannot skip a fire | A re-read before writing (narrows, does not close), a distributed lock for a startup sweep, leaving the inaccurate idempotency claim in place |
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-17 | Keep `deny-licenses` in dependency-review, broadened to GPL-2.0, LGPL-2.0/2.1/3.0, SSPL-1.0, BUSL-1.1 and Elastic-2.0 | An allow-list would fail today on the `LicenseRef-bad-non-standard` values GitHub reports for jsoup and classgraph, and would gate nothing extra — unknown licences are informational in both modes | Migrate to `allow-licenses` (needs two permanent per-package exclusions to work around GitHub's normalisation); leave the list at GPL-3.0/AGPL-3.0 (misses the source-available relicensing hazard that actually threatens a project depending on MongoDB and Elasticsearch clients) |
| 2026-09-17 | Mark secret context on the value (`"secret": true`), scrub every copy when the turn ends | A per-user credential sent as context was stored, echoed and copied into properties; `scope: secret` holds one vault slot per agent | A list of secret keys in the agent configuration — couples every agent to one client's field names |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
| 2026-09-20 | Escape record boundaries in the throwable's MESSAGE before the trace is rendered, not in the rendered `%s%e` output | `%e` prints `toString()` as the trace's first line, so a CR/LF in an exception message forged a record past every call-site `sanitize(...)` | Scan the rendered trace and keep the breaks that begin `\tat ` / `Caused by:` / `\t... N more` — an attacker can write all three into a message, so the scan has to guess; or drop the throwable at the ~415 call sites — the stack trace is often the only diagnostic left |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
| 2026-11-02 | Anthropic/Bedrock cached prompt tokens and Gemini/Vertex thinking tokens were not priced; `audit:token_usage.inputTokens` now includes cache tokens, so costs and counts rise for cached or thinking workloads | Provider usage conventions differ and only input/output were read | Normalization in the chat-model decorator; escape hatch `eddi.llm.pricing.provider-normalization=false` | feat/cost-accounting |
| 2026-10-20 | Every HITL resume that re-supplied secret context answered 400, so an approved call acting as the user could never run | Both conversation stores rebuilt context entries without the `secret` flag when loading a conversation; the resume check and the injection read it from that memory | Both loaders keep the flag; store round-trip tests for MongoDB and PostgreSQL | (this branch) |
| 2026-10-15 | An embedding that relied on `?token=` stops authenticating | The parameter is now ignored unless `eddi.chat.url-token.enabled=true` (M6); the widget says so in the browser console | Set the property, or move to the `postMessage` hand-off | a136e09 |
| 2026-10-15 | A deployment behind a TLS-terminating proxy with neither `quarkus.http.proxy.proxy-address-forwarding` nor an absolute `quarkus.oidc.resource-metadata.resource` advertises an `http` MCP resource identifier | `force-https-scheme` now defaults to `false` (L3) | Set one of those two, or `force-https-scheme=true` | a136e09 |
| 2026-10-15 | `ExpressionProvider` recursed without bound (StackOverflowError at depth 20,000, ~10 s CPU for 3 MB); the Error escaped task-failure accounting; `saveResponse` without `responseObjectName` failed every turn | No length/depth cap and per-level `substring`; `LifecycleManager` caught only `RuntimeException`; no save-time validation | `ExpressionLimits` + index-based parser, `StackOverflowError` handled as a task failure, `ApiCallsConfigurationValidator` | fix/parser-bounds |
| 2026-10-03 | Dashboards queried series that do not exist (`eddi_nats_dead_letter_count`, `http_server_requests_seconds_bucket`, a `skill` label, `jvm_info`) and read "No data" forever | The coverage test only substring-matched the Full dashboard against registered meters; nothing checked queries against what a scrape contains | Every dashboard parsed; every query's series checked against registered meters plus a verified built-in list | (this branch) |
| 2026-10-03 | GDPR erasure kept SSNs in 6/6 audit rows and reported complete:true; fast erasures reported auditEntriesPseudonymized: 0 | Erasure only rewrote userId, store-side, while entries were still in the write queue | AuditLedgerService.eraseUser flushes, redacts and re-seals; IAuditStore.redactEntry | fix/gdpr-audit-memory |
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `fix/llm-tool-execution` |
| 2026-09-30 | `maximumLifeTimeOfIdleConversationsInDays=-1` ended every conversation five minutes after boot | `DAYS.between(date, today) >= -1` is always true | A limit below 1 disables idle-ending | fix/650-idle-negotiation |
| 2026-09-29 | EDDI failed to start on this branch (caught by CI E2E before merge) | `LlmModule.configure()` runs twice under CDI and the provider collision guard checked the map it had just filled | Guard against a fixed built-in type set; a test calls `configure()` twice | feat/openai-compatible-providers |
| 2026-09-28 | Manager RAG upload tests failed under Node 24 | undici 7 brand-checks File/Blob; jsdom's global File loses its bytes in FormData and breaks request.formData() | Assert on the multipart part's name and content type, parsed from the raw body | d09d1b120a |
| 2026-09-28 | A live sync after the first one showed the source's plaintext credentials in the preview and wrote them, and the source's vault references, over the target's | `RemoteApiResourceSource` read store documents unscrubbed, and `ScrubbedSecrets` restored only placeholders | Scrub every document the remote source reads; treat vault references as target-bound in the restore | `fix/agent-sync-hardening` |
| 2026-09-28 | Syncing an agent after a workflow was added on the source answered 201 and left an agent version that could not be deployed | `createNewWorkflow` stored the source's workflow document unchanged — its steps named source-only ids — and created none of its resources | Create the workflow's resources first and repoint its steps at them | `fix/agent-sync-hardening` |
| 2026-09-26 | A delete racing an update erased the new version; `set()` on a history version silently did nothing and on the current version could roll back a newer write | Delete removed by id alone; `set()` used insert-if-absent for history and an id-only replace for the current row | Version predicate on delete (tombstone upsert + revert on conflict), CAS for current `set()`, `replaceHistory` for history rows | fix/import-persistence |
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Digests computed row-to-row from the `metrics` branch, sent by any run once due, deduplicated by a claimed marker | fix/weekly-digest-durable-baseline |
| 2026-09-27 | Updating either of two pre-existing duplicate-named channel integrations of the same type now fails validation until one is renamed | New global per-type name uniqueness check on create/update (Finding C) — benign migration: rename one integration | Rename one of the clashing integrations, then update | fix/security-channel-identity |
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
| 2026-09-26 | Saving a second Studio stage 409'd; group/workforce edits "reverted"; a quick second agent-section edit vanished | Pages kept the version they were opened with instead of the one in the save's `Location` | Pages move onto the saved version; cascade checks references before writing and reports partial progress for retry | (this branch) |
| 2026-09-26 | Secret 🔒 input persisted as public input:initial and echoed on reload | Only conversationOutput["input"] was masked; input:initial kept the raw text and is always included in the simple snapshot | Scrub input:initial/normalized to the placeholder before persist in Conversation.executeConversationStep's finally | fix/security-frontend |
| 2026-09-26 | Secret input still persisted verbatim inside parser expressions (unknown(<token>)) | scrubSecretUserInput scrubbed only input:initial/normalized; the parser's expressions:parsed/matches/intents keep the normalized secret and a free-text secret matches no dictionary | Also drop the derived parsed forms in scrubSecretUserInput (dropParsedSecretForms), mirroring PropertySetterTask.dropParsedForms | fix/security-frontend |
| 2026-09-26 | Chat ?apiServer= deny-list bypassed by a leading control char, leaking the bearer token off-origin | %09/space prefix escaped the scheme/// /\ tests but the URL parser strips it, resolving ${base}${path} to the attacker origin | Replace with an allow-list requiring a clean single-leading-/ path | fix/security-frontend |
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus a repeatable startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |
