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
| [October 2026](changelog/2026-10.md) | 38 | 185 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1217 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 🐛 fix(monitoring): split the Kubernetes copy of the Full Metrics Reference (2026-10-13)

**Repo:** EDDI (`fix/observability`)

Merging `main` grew the Full Metrics Reference past what one ConfigMap can carry
through a client-side `kubectl apply`: minified and escaped it reached 247,445 bytes
against this PR's own 245,000-byte budget (the last-applied annotation is capped at
262,144). [`sync-monitoring-assets.py`](../scripts/sync-monitoring-assets.py) now
writes it to the monitoring component as two dashboards, split at the top-level row
boundary that best balances their size (131 KB and 119 KB escaped). Part 1 keeps the
uid `eddi-metrics-all`, so existing links and bookmarks still land; part 2 is
`eddi-metrics-all-2`; each links to the other. `docs/monitoring/` keeps the single
dashboard, which Compose and a manual import use.

`DeploymentManifestsTest` checks that the parts' panels, concatenated, are the
source's panels and that nothing else differs beyond title, uid and links (verified
by dropping a panel from part 2: the test fails). Panel ids 174-177 were also each
held by two panels, from parallel PRs taking the next free id; the later holders now
have ids above the maximum.

---

## A2A streaming uses the conversation-scoped `sayStreaming` (2026-10-13)

**Repo:** EDDI · **Branch:** `fix/a2a-streaming-after-990`

**What changed:**
- **The break:** main stopped compiling after two PRs that were green on their own were merged in parallel.
  - #953 (A2A 1.0) called `IConversationService.sayStreaming(Environment, agentId, conversationId, …)`.
  - #990 removed that environment-qualified overload, which had no other callers.
- **The fix:** `A2ATaskHandler` now calls `sayStreaming(conversationId, …)`, which reads the agent and environment from the stored conversation record. `A2ATaskHandlerTest` stubs that overload.

**Behaviour:** unchanged. An A2A conversation is always started as `startConversation(Environment.production, agentId, …)`, so the record holds exactly the agent and environment the old call passed explicitly. The non-streaming path still uses the environment-qualified `say`, which #990 kept.

---

## Navigation guard: one app-level blocker wins over per-page blockers (2026-10-12)

**Repo/branch:** EDDI, `fix/manager-navigation-guard` (merge of `main`)

**What changed**
- #939 (on `main`) and this branch both added in-app unsaved-changes protection. `main` called `useBlocker` from every page through `useUnsavedChangesGuard` and rendered an `UnsavedChangesPrompt` per page; this branch registers dirty state in a store and holds navigation with ONE blocker in `NavigationGuardDialog`.
- The merge keeps the single blocker. `connection-detail`, `workflow-detail`, `config-editor-layout` and `agent-editor-sheet` now just call `useUnsavedChangesGuard(isDirty)`, use the module-level `allowNextNavigation()` for deliberate navigations, and no longer render a prompt. `UnsavedChangesPrompt` and `main`'s per-page navigation test were removed.
- `main`'s role-aware navigation (`useMayOpenScreen`) is kept in the rebuilt sidebar and command palette; both PRs' stable-id section collapse state collapsed into the route-registry ids.

**Design decision:** a router consults only the most recently registered blocker, so a per-page blocker lets any clean guard mounted later silently unprotect a dirty page. Regression tests in `use-unsaved-changes-guard.router.test.tsx` cover a clean sibling mounting after the dirty page and the browser Back button; they fail if each hook registers its own `useBlocker`.

---

## 🐛 fix(manager): agent detail saves on Save, not on every edit — with review, discard and Save & leave (2026-10-06)

**Repo:** EDDI (`fix/manager-agent-detail-explicit-save`)

### What changed and why

**Agent detail no longer auto-saves.** Every toggle, select and (after a 600 ms
debounce) every keystroke in the agent detail page's ten config sections —
workflows, A2A, security, capabilities, user memory, memory policy, session
management, conversation review, HITL, channels — PUT the whole agent document
on its own. Each PUT creates a new agent version, so one sitting of edits made a
dozen versions, there was no way to back out of a change, and the page behaved
unlike every other editor in the Manager. It now works like the workflow and
resource editors:

- Sections edit one page-level **draft**; nothing reaches the backend until
  **Save**, which writes it as one version and follows it ("Saved as version N").
  **Discard** (confirmed) restores the stored version. Ctrl/Cmd+S saves.
- **Seeing what changed:** an *Unsaved changes* badge in the header, a
  **Modified** pill on every section whose fields differ from the stored
  version, a floating bar at the bottom of the (long) page with the count,
  Discard and Save, and **Review changes** — a diff of the draft against the
  stored version (`ResourceDiffViewer`).
- While there are edits, the version picker and Deploy/Undeploy are disabled
  (deploying acts on the *saved* version; the tooltip says so).
- A **409** keeps the edits, moves the page onto the newer version and re-applies
  only the blocks the user changed (`applyChangedFields`), so another client's
  change survives the next Save.
- The debounce timers and the per-agent save queue that existed only to make
  auto-save survivable are gone (`use-agent-section-save.ts` removed).
- Merged with #967: Save shows its "Saved as vN — not live yet" toast with the
  Deploy action (once per Save instead of once per edit); removing a workflow
  no longer opens a confirmation, since it only changes the draft and Discard
  restores it; #967's rename-in-place, live-version header, workflow names and
  accessibility changes are kept.

**Leaving with unsaved edits.** The app holds in-app navigation with one router
blocker and one prompt (`NavigationGuardDialog`, #968); a page only calls
`useUnsavedChangesGuard`. This adds an optional `onSave` to that hook: when every
dirty page can save on demand, the prompt also offers **Save & leave** and lets the
navigation through only once the save landed. Agent detail and the workflow
editor pass it. (This branch first carried its own app-wide guard, written while
the app was still on `<BrowserRouter>`, then a per-page prompt after #939; both
were dropped as #939 and #968 landed.) While a placeholder document from the
previous version is on screen (#939's `configPending`), the draft takes no edits.

**Also:** the "compat. gen 1" badge left the monospace id line, where it
inherited the code font and duplicated the version chip; it now sits beside the
version picker at the same height as "Generation 1", with the explanation in
its tooltip. The secret access ("Who may use …") dialog's body had no padding
against the dialog border; it now has the `p-5` every other dialog body has.

**Dependency:** `source-map-js` 1.2.1 → 1.2.2 in `ui/manager/.ds-sync/package-lock.json`
(CVE-2026-93749, HIGH — `Trivy Filesystem Scan`). Edited by hand rather than via
`npm install`, which on Windows prunes the Tailwind wasm dependencies from the lock.

### Design decisions

- One draft of the whole document rather than per-section drafts: one Save, one
  version, one diff, and the same shape as the workflow editor.
- Save & leave lives in the shared guard and prompt, not on the agent page, so
  any editor whose save resolves to success can opt in with one argument.

**Files:** [`agent-detail.tsx`](../ui/manager/src/pages/agent-detail.tsx),
[`agent-config-sections.tsx`](../ui/manager/src/components/editors/agent-config-sections.tsx),
[`agent-draft.ts`](../ui/manager/src/lib/agent-draft.ts),
[`unsaved-changes-dialog.tsx`](../ui/manager/src/components/ui/unsaved-changes-dialog.tsx),
[`use-unsaved-changes-guard.ts`](../ui/manager/src/hooks/use-unsaved-changes-guard.ts),
[`navigation-guard-dialog.tsx`](../ui/manager/src/components/shared/navigation-guard-dialog.tsx),
[`edit-grant-dialog.tsx`](../ui/manager/src/components/secrets/edit-grant-dialog.tsx),
[`compatibility-generation-badge.tsx`](../ui/manager/src/components/agents/compatibility-generation-badge.tsx)

---

## feat(llm): recovery observability, resume-path parity and the resilience guide (2026-10-06)

**Repo:** EDDI (`feat/llm-resilience-observability-docs`, the capstone of the LLM turn-resilience series, on top of `feat/llm-circuit-breaker` and `feat/turn-idempotency-structured-errors`)

### What changed and why

R11, R12 and R13 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md)), and the plan's status block. The seven items before it each added a recovery; this one makes them readable and keeps the HITL resume path from being the one place they do not apply.

**R11 - observability**

- **Resolved model names.** `audit:model_name`, the cascade trace's `model` and the circuit-breaker key recorded the literal `${vars:gemini-model}` (the registry resolves global variables late, after the task has recorded its name). `LlmTask.resolveModelName(params, type, resolver)` and the cascade's `resolveVariables` now resolve `${vars:...}` through `GlobalVariableResolver` for everything that records or keys on the model, including the new `eddi.llm.failure` tag. An unresolvable reference (unknown variable, resolver error) keeps the configured text instead of failing the turn over a label. The raw template is not kept in a separate field (not trivial: the audit has one name slot).
- **One structured line per recovery.** New `LlmRecoveryLog` (`modules/llm/impl`) writes `LLM recovery conversationId=... agentId=... class=... action=... outcome=... attempt=... durationMs=...` at INFO, once per repair, same-model re-ask, cascade escalation, skipped (open-circuit) model and served fallback. The call site that performs the action emits it; the failures that triggered it keep their own WARN/ERROR lines. The ad-hoc INFO lines it replaces: the `FormatRetryRunner` "Re-asking the same model" line (now written from `ReaskListener.onReaskDone`, where outcome and duration are known), the cascade's WARN for a skipped circuit and its INFO "escalating: confidence" line (kept at DEBUG, it carries the scores). Values are stripped of whitespace and control characters.
- **Metrics gap-fill** (only what was missing): `eddi.llm.recovery` gains `action=repair` (a reply that parsed only after fence stripping or extraction), `action=circuit_skip`, and `action=escalate` for every escalation reason (`timeout`, `error`, `retryable_error`, `low_confidence`, besides the existing `invalid_output`); `eddi.llm.failure{class,model}` is now also counted for a failed single-model task (a cascade still counts per step, and its overall failure is not counted twice; the circuit's own refusal and control flow such as a HITL pause are not model failures). `eddi.llm.output` and `eddi.llm.turn.deadline.exceeded` already existed. The tag keys of `eddi.llm.recovery` stay `action, outcome, trigger` so Prometheus sees one consistent meter.

**R12 - resume parity.** `LlmTask.executeResume` now applies, to the resumed final answer: the `onError: fallback` guard (the approved tools already ran exactly once through the journal, so only the answer is replaced), `responseValidation` actions (it applied none before), and the R5 same-model re-ask of an empty, invalid-JSON or schema-mismatch reply. The re-ask is one model call over the resumed transcript through `reaskFinalAnswer`; `ToolLoopResumer` records that transcript on a new `ExecutionResult.resumeTranscript` (the loop's final messages without the answer), so no tool can run twice. A task that may re-ask is buffered on resume, as on a live turn. **Not applied on resume, and why:** the circuit breaker (the resume pins the model whose loop paused and settles no ticket), the cascade (the escalation decision was made before the pause), truncation and context-too-long re-asks (the loop reports no finish reason; shrinking after tools ran would replay them), and a turn deadline (a resume starts a turn without one). A resume that recorded no transcript cannot re-ask and goes to `fallbackAction`. Parsing and shape validation (R3, R4) were already shared.

**R13 - docs.** New [`docs/llm-resilience.md`](llm-resilience.md): failure taxonomy, recovery order, the full configuration surface with defaults (verified against the config classes), a complete `langchain.json` recipe for a JSON agent behind a 60-second caller, how to read step keys, trace, log line and metrics, the resume table and the known limits. [`langchain.md`](langchain.md), [`model-cascade.md`](model-cascade.md) (new "Cascade as failover") and [`metrics.md`](metrics.md) link to it and no longer say the resume path has no recovery. `docs/SUMMARY.md` lists it.

**Platform Operator.** An admin will ask "why did my agent answer 'Sorry...'" and "why is model X skipped". The operator prompt's docs map now names `llm-resilience`, and its how-to-work section says which step keys to read (`llm:fallback`, `llm:error`, `llm:output:outcome|reason`, a trace status `circuit_open`) and that the log carries one `LLM recovery` line per recovery. No allow-list change (it can already read conversations, logs and the audit trail). `operator-revision.json` 2 to 3.

**Plan status.** The status block of the plan now marks R1 to R16 implemented with the branch and PR of each, where the implementation differs from the plan text, and the known gaps below.

### Review fixes on the stacked PR

- **Fallback turns in the model's history.** The step-wide `llm:fallback` flag dropped the whole output of a step, including another LLM task's good answer in the same step. Now only output items marked `fallback: true` are dropped (`ConversationOutputExtractor.withoutFallbackItems`, both history paths); a flagged step with no marked item (a `postResponse`-rendered fallback) is still dropped whole, since nothing says which text it wrote.
- **Configured `prompt` after a fallback turn** replaced the whole merged user message and lost the fallback turn's unanswered question; it now replaces only the current input's contents.
- **Re-ask budget** now spends from the turn deadline (plain, cascade, tool-mode and resume re-asks): a re-ask no longer starts with less than `minAttemptMs` left. A cascade whose time left is already inside the reserve gives step 0 the remaining turn time instead of a 1 ms timeout.
- **Interrupt on the deadline-bounded retry path** is restored on the thread and surfaces as `LifecycleInterruptedException` instead of being classified `UNKNOWN` (which let a cancelled step pay a second provider call).
- Keyed streaming requests now forward `llm_retry`; dashboard panels in the cascade row no longer overlap and the circuit-transition series is a `rate`.
- Not changed: the Manager editor's selectors still omit `retry` (types only, as documented): adding it is UI work with three i18n gates and is a follow-up.

### Design decisions

- One helper rather than six `LOGGER.infof` calls, so the line has one shape and tests can assert "exactly one line per recovery" through an observer hook (`LlmRecoveryLog.observer`, null in production).
- The retry line is written by a `ReaskListener` default method (`onReaskDone`), not by `FormatRetryRunner`, because only the caller knows the conversation and agent ids.
- `ExecutionResult` grew a fifth component rather than reusing `toolExchange`: the exchange is "tools only" by contract, the resume transcript is "everything the model saw".
- `circuit_skip` is a fifth `action` value next to the plan's four: it is a recovery (the engine avoided a call) and an operator counting escalations should not have to subtract it.

### Known gaps and follow-ups

- The streaming path is still not bounded by the turn deadline and uses the plain backoff.
- R15's `onInvalidJson: "retry"` with `maxRetries: 0` warning is not implemented, and its `onError` check still reads the field reflectively.
- The R14 WireMock cascade IT was not written.
- No dashboard panel dedicated to `action=repair` / `circuit_skip`; the full-metrics dashboard has pre-existing duplicate panel ids (158/159, 168 to 172) on `main` that this change leaves alone. Ids 174 to 181 (this series) are unique.
- Manager UI has types only for the new task fields.

---

## fix+feat(migration): carry 5.x LLM defaults over, unencoded URL hosts, deploy-time compatibility check (2026-10-06)

**Repo:** EDDI (`feat/v5-compat-deploy-checks`)

### What changed and why

Findings from a production 5.x to 6.x migration: valid 5.x configuration that 6.x accepts, deploys
READY, and then handles differently on the first real turn.

- **JSON mode (#7).** A migrated LLM task with `parameters.responseFormat: "json"` and no
  `convertToObject` gets `convertToObject: "true"`; `responseFormat` stays. Without it the model
  wrapped its JSON in a Markdown code fence.
- **Tools default (#8).** A migrated LLM task without `enableHttpCallTools` / `enableMcpCallTools`
  gets both written as `false`, which is what 5.x did (6.x defaults them to `true`, turning a chat task
  into a tool-calling one, which on Gemini also silently drops JSON mode). One transform,
  `LegacyDocumentMigrations.llm()`, runs in the V6 collection sweep (`llms` and `llms.history`, a
  database 5.x wrote) and in the import of a ZIP whose workflow still references the 5.x LLM
  authority. Tasks authored in 6.x are never touched. Idempotent.
- **Templated URL host (#9).** `https://{properties.apiHost}` was rendered as `api%2Eexample%2Ecom`
  because every substituted value was percent-encoded as a path segment. The authority is now
  rendered separately and keeps letters, digits, `.`, `-`, `_`, `:`; `/ @ ? #`, spaces and `%` are
  still encoded, so a value cannot add a path, userinfo or query to the host. The resolved URL is
  unchanged for the SSRF checks (private/loopback refused with
  `eddi.security.ssrf-protection.enabled`, cloud metadata always).
- **Deploy-time compatibility check (#6, #11).** `AgentCompatibilityLint` runs when an agent is
  deployed, reads its LLM, API-call, property-setter and output configs, and reports: a template in an
  LLM `apiKey` (task, cascade steps, judge model; vault/vars/connection references are fine),
  `responseFormat: json` without `convertToObject`, `convertToObject` with tools on a provider that
  cannot combine them (from `JsonResponseFormatPolicy`), strings that do not parse with the restricted
  runtime Qute engine, and left-over `[[${...}]]` Thymeleaf. Warnings only: one WARN line per
  finding, and a new additive `warnings` list on each entry of the deployment-status listing
  (`AgentDeploymentStatus`, `IAgent.getDeploymentWarnings()`). It never changes the status or blocks a
  deployment, and a failing lint is swallowed.
- `docs/upgrading-from-5x.md` section 6.1: every behaviour change with before/after config.

### Design decisions

- The 5.x origin of an LLM document is known, not guessed: the V6 sweep runs once over a database 5.x
  wrote, and an import counts as 5.x only when the workflow names its LLM step by the 5.x authority.
  A v6 document with an absent flag keeps meaning "on".
- The lint does not report a templated URL host (#9, now supported) or `fromObjectPath` naming
  `responseObjectName` (#5, handled in the engine).
- `LlmTask.TEMPLATE_SKIP_PARAMS` and `CONFIG_REF_MENTION` became public so the lint shares the
  engine's own definition of "credential" and "reference".
- A templated host is a trust decision: whoever controls the substituted value chooses where the
  call goes. Documented; the metadata address stays refused regardless.

### Follow-ups

- `GET /administration/preflight` for an unmigrated 5.x database (out of scope here).
- Showing `warnings` in the Manager's deployment view.
- The Postgres backend has no 5.x data, so the sweep has no Postgres twin; only ZIP import applies.

**Files:** [`LegacyDocumentMigrations.java`](../src/main/java/ai/labs/eddi/configs/migration/LegacyDocumentMigrations.java),
[`V6RenameMigration.java`](../src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java),
[`RestImportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`AgentCompatibilityLint.java`](../src/main/java/ai/labs/eddi/engine/compat/AgentCompatibilityLint.java),
[`CompatibilityRules.java`](../src/main/java/ai/labs/eddi/engine/compat/CompatibilityRules.java),
[`upgrading-from-5x.md`](upgrading-from-5x.md)

---

## fromObjectPath now keeps an object or list, so an LLM response object can be stored as a property (2026-10-06)

- Repo: EDDI, branch `fix/fromobjectpath-llm-response-object`.
- **Symptom (reported from a production 5.x deployment moved to 6.x).** An LLM task with `responseObjectName: "aiOutput"`, `convertToObject: "true"` and a `postResponse` property instruction `{ name: "aiOutputObject", fromObjectPath: "aiOutput", convertToObject: true }` left `{properties.aiOutputObject.htmlResponseText}` empty.
- **Root cause.** The path itself resolved fine: `PathNavigator` reads the template data, which the LLM task fills with the converted response (a Map) under `responseObjectName`. The loss was in `PrePostUtils.executePropertyInstructions`: only a `String` was carried forward, and every other value reached through `fromObjectPath` fell into an `else` branch that replaced it with `""`. A Map or List therefore became an empty-string property. The same applied to httpcalls and MCP calls, which share the helper, but their responses are usually strings that `convertToObject` parses, so it went unnoticed. Workaround that worked: `valueString: "{json:serialize(aiOutput)}"` with `convertToObject: true`.
- **Fix.** An object or list found through `fromObjectPath` is now stored as it is (`PrePostUtils`). Strings (including `convertToObject` parsing), numbers and booleans behave as before, a path that resolves to nothing still stores an empty string, and `scope: "secret"` still refuses a non-string value. Tests: `PrePostUtilsFromObjectPathObjectTest`. Docs: `docs/langchain.md`, `docs/httpcalls.md`.
- **Follow-up (not done).** A number or boolean read through `fromObjectPath` is still stored as `""`; changing it is a separate compatibility decision.

---

## Idle sweep no longer loads every open conversation; bulk "end inactive conversations" (2026-10-06)

**Repo:** EDDI | **Branch:** `fix/idle-conversation-sweep-oom`

**Problem.** `AgentDeploymentManagement.endOldConversationsWithOldAgents` (the daily idle sweep, first run five minutes after boot) called `loadActiveConversationMemorySnapshot`, which deserializes every open conversation of an agent version in full. A production 5.x deployment with tens of thousands of open conversations of several hundred KB each ran out of heap shortly after boot. `GET /conversationstore/conversations/active/{agentId}` had the same shape, and so did `POST /administration/{env}/undeploy/{agentId}?endAllActiveConversations=true`, which lists through it.

**What changed.**
- `IConversationMemoryStore` gets `loadOpenConversationActivity` (keyset-paged projection: id, state, agent, version, last-interaction time), `loadConversationActivity` (the same for one id) and `endIdleConversations` (the bulk end), implemented for MongoDB and PostgreSQL. The last-interaction time is computed by the database as the newest `conversationSteps[].workflows[].lifecycleTasks[].timestamp`, the same quantity the sweep always aged conversations by. MongoDB converts each timestamp with `$convert`, because the persistence mapper stores dates as ISO-8601 strings while older documents can hold BSON dates or epoch milliseconds; PostgreSQL reads numeric epoch millis and ISO strings.
- The idle sweep reads batches of 200 projections, re-checks one conversation's projection (not a full load) right before ending it, and keeps every existing rule: `AWAITING_HUMAN` is spared, the agent descriptor is the fallback age, "cannot prove it is idle" never ends a conversation, state compare-and-set, the `LocalDate` arithmetic. It now also records the end reason `idle` (new `IConversationService.END_REASON_IDLE`), best-effort.
- `RestConversationStore.getActiveConversations` is built from the projection, so `/active/{agentId}` no longer loads a conversation in full (contract unchanged). Undeploy-with-end lists straight from the store projection (`RestAgentAdministration.openConversationsOf`), skipping the `/active` listing's per-conversation descriptor read for a last-interaction time it does not use.
- New `POST /conversationstore/conversations/end-inactive?inactiveForDays=N[&agentId=...][&dryRun=true]`, `eddi-admin` only (like the bulk delete; there is no REST role hierarchy): ends every `READY` conversation idle for longer than N days with end reason `idle`, returns `{"count": n, "dryRun": bool}`, validates `inactiveForDays >= 1`, and writes an audit-ledger entry. MongoDB: one `updateMany` with a server-side `$expr`; PostgreSQL: batched `UPDATE` (500 rows per statement and transaction) so a 22 GB backlog does not become one transaction.
- `undeployThisAndAllPreviousAgentVersions` no longer aborts on the first failing or refused version: every version is attempted, then a `500` naming the failed versions (or a `409` for refusals) is returned.

**Design decisions.**
- *Descriptors are not touched by the bulk end.* Retention deletes ended conversations by the descriptor's `lastModifiedOn`; rewriting descriptors would be one write per conversation (defeating the single operation) and would restart the retention clock at "now". The existing sweep never touched descriptors either, so retention keeps counting from the last interaction for both. The listing re-derives state from the conversation, so it shows `ENDED` immediately. Consequence, documented: a conversation idle for longer than the retention is deleted by the next retention run.
- *The bulk end only touches `READY`.* `AWAITING_HUMAN` needs the HITL-aware end (timeout schedule, bookmark, oversight audit), `IN_PROGRESS` is running, and a conversation with no timestamp cannot be proven idle. Conversation-state cache entries (30 s TTL) are not invalidated, as for the sweep.
- *The sweep stays per-conversation* (projection + compare-and-set) rather than calling the bulk method, because it must keep the descriptor age fallback and the `AWAITING_HUMAN` accounting; both use the same server-side last-interaction computation.
- *Undeploy schedules:* `disableSchedulesForAgent` is already gated on the call leaving no deployed version of the agent in the environment (main already had this, with tests); nothing to change.
- *Platform Operator:* the new endpoint is a destructive deployment-wide bulk mutation, so it is deliberately not added to the operator allow-list; no operator revision bump.

**Operators.** Anyone who disabled the sweep with `maximumLifeTimeOfIdleConversationsInDays=-1` because of the out-of-memory failure can re-enable it. See [Upgrading from EDDI 5](upgrading-from-5x.md#ending-a-backlog-of-idle-conversations-in-one-call).

**Follow-ups.** The listing behind `/active/{agentId}` still reads one descriptor per conversation (slow on very large backlogs, memory-safe). The idle sweep's check-then-act window between its re-check and the compare-and-set remains (a revision-conditional narrow update would close it).

---

## ✨ feat(llm): per-model circuit breaker for systemic failures (2026-10-06)

**Repo:** EDDI (`feat/llm-circuit-breaker`, stacked on `feat/llm-recovery-policies`)

### What changed and why

Item R8 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md)). Retries and re-asks fix an unlucky turn; they do nothing for a model that cannot answer at all (a revoked key, a retired model id, a prompt it always answers in prose). Every turn then spent a full attempt, and the re-asks, to arrive at the same failure, and a cascade kept paying its first step's deadline before reaching a step that works.

New opt-in task field `circuitBreaker: { enabled, window, threshold, coolDownMs }` (defaults `false`, 10, 8, 60000), mirrored in the Manager's `editors/llm/types.ts`. Without it nothing changes.

- **New `LlmCircuitBreakers`** (`modules/llm/impl`, `@ApplicationScoped`): one in-memory breaker per (agent, agent version, provider, model), in a bounded (10,000) Caffeine cache that forgets idle keys after an hour. The model is the name the model was built with, after `${vars:…}`/templating (the cascade's own `resolveModelName`).
- **What counts:** `INVALID_OUTPUT` (a `convertToObject` reply still invalid JSON or off-shape after the re-asks), `BAD_REQUEST`, `MODEL_NOT_FOUND` when `threshold` of the last `window` counted turns share the class; `AUTH` and `QUOTA_EXHAUSTED` at once. Transient, rate-limited, timeout, context-too-long, unknown and uncounted outcomes are not recorded at all, so they neither fill nor drain the window.
- **Open:** the model is not called. `CascadingModelExecutor` skips the step (trace `status: circuit_open`, `eddi.llm.cascade.escalations{reason=circuit_open}`, `cascade_escalation` SSE event) and the next step runs; with no step left it returns the best response so far or throws `LlmCircuitOpenException` (a `LifecycleException` carrying the class that opened the circuit). On the non-cascade path `LlmTask` throws the same exception inside the `onError` guard, so `onError: fallback` serves the fallback with no model call.
- **Half-open:** after the cool-down exactly one probe turn is let through (a concurrent turn is denied); success closes, a counted failure re-opens, an uncounted outcome (a transient error, a HITL pause) releases the probe, and a probe whose result never arrives is abandoned after one more cool-down. A late result from a turn that started before the circuit opened cannot move it.
- **R5 hook:** the `ReaskGate` is now live. A circuit that is open or half-open for invalid output refuses the same-model re-ask (`eddi.llm.recovery{outcome=skipped_breaker}`), so a probe asks once and the cascade moves on. Each cascade step carries its own gate (`StepRecovery.gate`), because the old gate did not know which model it was asked about.
- **Invalid output is looked at directly** (`FormatRetryRunner.classifyJsonReply`) for tasks with no re-ask policy, so a model that never returns valid JSON is seen whatever the `responseValidation` settings are.
- **Alerting:** one ERROR log line per circuit **open** (agent, version, provider, model, class, EDDI's reason; never model output); half-open and closed are INFO. Metrics: `eddi.llm.circuit{state,class}` (transitions), `eddi.llm.circuit.skipped{class}`, gauge `eddi.llm.circuit.open`; two panels on the Full Metrics dashboard and a section in `docs/metrics.md`.

### Design decisions

- **A new class rather than reusing the MCP/A2A breakers.** Both are private fields of their managers: a consecutive-failure counter per server URL, no half-open state, no probe, no per-class accounting. "N of the last M failed the same way" plus a single concurrent probe could not be added without rewriting both, so `LlmCircuitBreakers` is the reusable one; migrating the other two onto it is a separate, behaviour-visible change.
- **A ticket per turn** (`acquire` then `success` / `failure` / `release`, settle-once) instead of a bare "record" call, so a probe is tied to the turn that holds it and late results from other turns are ignored. A disabled breaker hands out a no-op ticket, so callers have no `if (enabled)`.
- **Transition-based, not state-gauge-only, metric,** plus a gauge of circuits not closed: the counter drives alerting on "just opened", the gauge answers "is anything open now".
- **ERROR only for open.** The plan says one ERROR line per state change; a recovery logged at ERROR would page an operator for good news, so half-open and closed are INFO.
- **Registry is injected by an initializer method** on `LlmTask` (the same reason `attachmentForwarder` is a field): the many direct-construction tests are untouched, and a null registry means no guard.
- **"A fallback is configured"** is read as `onError: fallback`. A `responseValidation` `fallbackAction` still applies to a reply that was produced; an open circuit produces none, so it ends in the `onError` path (default `error`: fail fast with the class).

### Not done / follow-ups

- **Webhook / Slack notification.** There is no generic operator-notification hook: the only notifier infrastructure is the HITL approval channel, which is bound to a conversation's pending approval. Alerting is the ERROR log line and the metric; a generic hook would be a separate change.
- The breakers are per node. A shared (NATS/DB) state would make a cluster open at once; not needed for the failure modes here, each node sees the same failures within a window.
- The Platform Operator prompt is unchanged: it points the operator at the `langchain` and `model-cascade` docs for LLM troubleshooting, and both now describe the breaker.

---

## feat(llm): classify provider failures, honour retry delays, one retry owner (2026-10-06)

**Repo:** EDDI (`feat/llm-error-classification`)

### What changed and why

LLM retries decided on the HTTP status alone, so a Gemini or OpenAI **daily quota** 429 was retried like a
per-minute rate limit (wasted attempts that cannot succeed), the provider's own retry delay was ignored, an
HTTP 500 was never retried, and langchain4j's built-in client retries stacked underneath EDDI's
(`maxAttempts: 3` could mean nine provider calls). This is R2 of the LLM turn-resilience plan.

- **Failure classes.** New `FailureClass` (`TRANSIENT`, `RATE_LIMITED`, `QUOTA_EXHAUSTED`, `AUTH`, `BAD_REQUEST`,
  `CONTEXT_TOO_LONG`, `MODEL_NOT_FOUND`, `TIMEOUT`, `UNKNOWN`), `LlmFailure(cls, retryAfterMs, reason)` and
  `LlmFailureClassifier` ([`LlmFailureClassifier.java`](../src/main/java/ai/labs/eddi/configs/shared/LlmFailureClassifier.java)).
  It walks the whole cause chain and reads the provider error **body** that langchain4j keeps as the
  `HttpException` message: Gemini `error.status` and `details[]` (`QuotaFailure` per-day vs per-minute,
  `RetryInfo.retryDelay`), OpenAI `insufficient_quota` / `rate_limit_exceeded` / `context_length_exceeded`,
  Anthropic `overloaded_error` / `rate_limit_error` / "prompt is too long". `RetryConfiguration.isRetryableError`
  is now a wrapper: retryable = `TRANSIENT`, `RATE_LIMITED`, `TIMEOUT`. The existing "typed verdict beats an outer
  message" ordering is preserved.
- **Bug fix: HTTP 500 is now retried** (and Gemini `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, Anthropic 529).
  Side effect: on the native-JSON-mode path a gateway that answers an unsupported `response_format` with a
  **500** used to fall back to a plain request; it now fails the turn like any other 5xx. Set
  `jsonResponseFormat: "off"` on such a gateway.
- **Quota 429s are not retried** and escalate the cascade immediately (`QUOTA_EXHAUSTED`).
- **Retry delay honoured.** New optional `retry` fields `honorRetryAfter` (default `true`) and `maxRetryAfterMs`
  (default `10000`, engine ceiling 30000). The provider's delay replaces the configured backoff when it is larger;
  a delay above `maxRetryAfterMs` stops retrying **without sleeping** so the cascade escalates at once. An HTTP
  `Retry-After` header is not reachable: langchain4j's `HttpException` keeps only the status and the body.
- **Single retry owner.** `maxRetries(0)` on the synchronous OpenAI, Anthropic, Gemini, Vertex Gemini, Mistral,
  Ollama, Azure OpenAI and Bedrock clients; `executeWithRetry` is the only loop. Hugging Face and OCI GenAI expose
  no setting, streaming clients have none (EDDI's own streaming loop retries). The judge model, tool-response
  summariser and `SummarizationService` call models with no task retry policy, so they now go through
  `RetryConfiguration.executeWithDefaultRetry` (default policy, original exception rethrown unwrapped): every
  model call has exactly one retry owner. The Azure OpenAI and Vertex Gemini builders use the same constant but are
  not asserted in tests (Azure exposes no retry field; Vertex needs GCP credentials to build).
- **Cascade.** Failed steps carry `failureClass` (and `retryAfterMs`) in the trace entry, additively, and count
  `eddi.llm.failure{class,model}`. `status` / `error` / escalation reason strings are unchanged.
- Docs: [`langchain.md`](langchain.md#retry-configuration) (the default is 3 attempts even without a `retry`
  block, classification table), [`model-cascade.md`](model-cascade.md), [`metrics.md`](metrics.md).

### Design decisions

- `honorRetryAfter` defaults to `true`: it only lengthens a sleep that would have happened anyway, and retrying a
  rate limit earlier than the provider asked just burns an attempt.
- The classifier lives in `configs.shared` because `RetryConfiguration` (shared with MCP calls) is its main caller.
- No new model parameter for `maxRetries`: a configurable library retry count would recreate the double loop.

### Follow-ups

- Streaming retry loop (`StreamingLegacyChatExecutor`) still uses the plain backoff, not the provider delay.
- Manager's retry editor does not expose `honorRetryAfter` / `maxRetryAfterMs` (settable in JSON).
- R8 (circuit breaker) and R5 (`onContextTooLong`) can now key on `FailureClass`.

---

## ✨ feat(llm): configurable, JSON-aware fallback answer and task-level `onError` (2026-10-06)

**Repo:** EDDI (`feat/llm-fallback-and-onerror`, based on `feat/llm-output-parsing-never-throws`)

### What changed and why

Items R6 and R7 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md)). A production 5.x deployment showed that when a model call fails after its retries, the user gets an HTTP 500 and a conversation in `ERROR`, and that the one fallback the engine had (the `responseValidation` `fallback` action) was a fixed English sentence, not JSON-shaped under `convertToObject`, and was fed back to the model on the next turn.

**R6 — the fallback is configurable.** `responseValidation` gains:

- `fallbackMessage`: a Qute template (author-written config, so it is rendered) with the task's template data — `{#if properties.language == 'de'}…`, `{snippets.x}`. Default is the old sentence; a template that fails to render or renders blank degrades to it.
- `fallbackField`: under `convertToObject` the fallback is stored under `responseObjectName` as `{"<fallbackField>": "<message>"}`, so `{properties.aiOutputObject.htmlResponseText}` in a `postResponse` output template renders it unchanged. The raw `langchain:<type>:<taskId>` slot holds the same JSON.
- `fallbackQuickReplies`: stored the way `OutputGenerationTask` stores quick replies (`quickReplies:<…>` entry plus the `quickReplies` conversation-output list); `value`/`expressions` are templates.

The existing `fallback` action of `responseValidation` goes through the same code (new `LlmFallbackHandler`), so it is wrapped and flagged too.

**The model never sees the fallback.** The step gets `llm:fallback:<taskId>` = `true`, and `ConversationHistoryBuilder` (via `ConversationLogGenerator.generate(…, skipFallbackOutputs)` and the summarized-window loop) leaves out the assistant message of such a turn. The user's input stays; the following user message is merged into it so the history keeps strictly alternating roles (Anthropic rejects consecutive user messages). The transcript returned by the REST log and the UI is unaffected. `OutputItem` gains an optional `fallback` field (omitted from JSON unless set) which the task sets on the output item it adds under `addToOutput`; the step flag is the authoritative signal because a fallback rendered by a `postResponse` never passes through an `OutputItem` the task controls.

**R7 — `onError`.** Task-level `onError: {"action": "fallback" | "error"}`, default `error` (today). With `fallback`, any exception from the model phase (model construction, `preRequest`, cascade, tool loop, plain chat, and a `responseValidation` action of `error`) produces the fallback; the task returns normally, so the turn completes with 200 and the conversation is not `ERROR`. Never absorbed: `ToolApprovalRequiredException` (HITL tool pause), `ConversationPauseException`, `LifecycleInterruptedException`, cancelled group member turns, `CancellationException`/`InterruptedException` (looked for through the whole cause chain), a cancelled conversation, an interrupted thread.

- `llm:error:<taskId>` = `{class, message}`: root-cause simple class name; message redacted with `SecretRedactionFilter`, URLs replaced by `[url]`, cut at 200 characters. Kept in one helper (`LlmFallbackHandler.describe`) so R2's failure classes can replace it.
- Commit Flags: data the failed phase wrote to the step is marked uncommitted (same rule as strict write discipline). The model phase writes little before it succeeds, so in practice this guards partial data from `preRequest` and tool traces.
- Metric `eddi.llm.recovery{action=fallback,outcome=served,trigger=onError|validation}`; charted on the Full Metrics dashboard, listed in `docs/metrics.md`.

### Design decisions

- The guard wraps from model construction to the end of the model-phase branches; a few declarations moved above it (no behaviour change). With `onError` absent the guard rethrows unchanged.
- A served fallback is not run through response validation (an author's apology could match a refusal prefix) and not parsed (it would count as `invalid`/`valid` in `eddi.llm.output`). The audit ledger's `audit:model_response` is not written for it: it records what the model said.
- `fallbackField` only applies under `convertToObject`; otherwise the fallback stays a plain string.
- The audit ledger has no error field, and `LifecycleManager` still writes the task's normal audit entry; the failure is on the step as `llm:error:<taskId>`. Extending `AuditEntry` was out of scope.

### Not done / follow-ups

- The HITL resume continuation (`executeResume`) does not apply `onError` (R12).
- The rolling summary and the recall tool still include fallback turns.
- Manager: only the TypeScript types were extended; no form fields.
- Platform Operator prompt unchanged: it does not cover LLM response-validation troubleshooting today.

### Docs

[`docs/langchain.md`](langchain.md) — "Fallback answers and `onError`"; [`docs/metrics.md`](metrics.md).

**Files:** [`LlmFallbackHandler.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmFallbackHandler.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LlmConfiguration.java`](../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`ConversationLogGenerator.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationLogGenerator.java), [`ConversationHistoryBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java), [`OutputItem.java`](../src/main/java/ai/labs/eddi/modules/output/model/OutputItem.java)

---

## 🐛 fix(llm): malformed model JSON no longer fails the turn; fenced/prefixed JSON is recovered (2026-10-06)

**Repo:** EDDI (`feat/llm-output-parsing-never-throws`)

### What changed and why

First step of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md), items R3 and R14). A production 5.x deployment with `convertToObject: "true"` showed turns failing or rendering empty bubbles whenever the model wrapped its JSON in a markdown fence, added a sentence around it, or ran out of tokens mid-object.

**Bug fix (behaviour change):** under `convertToObject`, output that is not valid JSON **no longer fails the turn**. The raw string is stored under `responseObjectName` with the existing WARN, exactly as plain-text replies always were. Before this, a reply that merely started with `{` and was malformed or truncated threw out of `LlmTask` on both the live and the HITL-resume path.

- New `ModelOutputParser` (`modules/llm/impl`) used by both paths in `LlmTask`: trim, strip one surrounding fence (`` ``` `` / `` ```json ``, tag case-insensitive), then extract the outermost balanced `{...}` / `[...]` (string- and escape-aware). No other repair. An extracted fragment is trusted only if it is a JSON object with at least one key, and a top-level array only as the whole reply, so prose such as `Pick option [1] or [2].` or `The empty set {} is...` stays the raw string. A fenced or prose-wrapped reply that used to be stored as a string now becomes an object.
- Result is `VALID` (flag `repaired`), `INVALID(reason)` or `EMPTY`. Reasons are EDDI-generated constants (`not JSON`, `truncated JSON`, `unbalanced braces`, `invalid JSON syntax`) and never contain model output, so a later corrective re-ask (R16) can quote them safely.
- Outcome recorded on the step under `llm:output:outcome:<taskId>` (`valid|repaired|invalid|empty`; not a public snapshot key because of its `llm:` prefix) and counted in `eddi.llm.output{outcome}` (charted on the Full Metrics dashboard, listed in `docs/metrics.md`).

**R14 (test scope only):** `FaultInjectingChatModel` (`src/test/.../modules/llm/testing`), a scripted langchain4j `ChatModel` for later resilience work: per call it returns text, an empty reply, a finish reason (`LENGTH`, `CONTENT_FILTER`), throws a simulated HTTP status (mapped through langchain4j's own `ExceptionMapper`, so `RetryConfiguration.isRetryableError` classifies it like a real provider error), throws any exception, or delays. It is not registered as a model type and nothing in `src/main` knows about it.

### Design decisions

- One parser class rather than two inline copies, so the live and resume paths cannot drift.
- Successive top-level balanced candidates are tried in order, so `Use {name} here: {"a":1}` still yields the object.
- Not done: the optional WireMock cascade IT from the plan (not trivial); retry/corrective re-ask (R5), schema validation (R4) and the fallback answer (R6/R7) build on this and follow.

### Docs

[`docs/langchain.md`](langchain.md) Structured Output: parsing steps, "never throws", outcome key and metric.

**Files:** [`ModelOutputParser.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`FaultInjectingChatModel.java`](../src/test/java/ai/labs/eddi/modules/llm/testing/FaultInjectingChatModel.java)

---

## ✨ feat(llm): recovery policies — re-ask the same model before escalating (2026-10-06)

**Repo:** EDDI (`feat/llm-recovery-policies`, stacked on `feat/llm-fallback-and-onerror` and `feat/llm-error-classification`)

### What changed and why

Item R5 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md)), plus the cost and injection guards of R16 that belong to it. A one-off format slip (prose around the JSON, an empty reply, a cut-off object) is usually fixed by the model that made it; escalating first pays for a different, possibly slower or pricier model on every hiccup. `responseValidation` gains a `retry` action and the engine now recovers in cost order: **answer → local repair (the R3 parser) → up to `maxRetries` same-model corrective re-asks → escalate to the next cascade step (which repeats this with its own re-asks) → `fallbackAction`.**

New `responseValidation` fields: `onInvalidJson` (default `ignore`), `onSchemaMismatch` (a reply that parses but breaks `responseSchema` / `nonBlankFields`, detected by R4; handled exactly like `onInvalidJson`, the R4 violation being the corrective message's reason), `onContextTooLong` (default `error`), `maxRetries` (default 1, clamped 0..3), `truncationRetryFactor` (default 2, clamped 1..4, never above 32768 tokens), `correctiveMessage`, `maxRetryCostUsd`, `minAttemptMs` (default 3000), `fallbackAction` (default `fallback`). `retry` is also accepted by `onEmpty`, `onTruncation`, `onContentFilter`. A cascade step gets `maxFormatRetries` (null inherits). Every default keeps today's behaviour: a task without a `retry` action is untouched.

- **New `FormatRetryRunner`** owns the same-model recovery for all three call sites: plain chat (`LlmTask`), each cascade step (`CascadingModelExecutor`, escalation reason `invalid_output` in the trace, in `eddi.llm.cascade.escalations` and in the `cascade_escalation` SSE event), and the final answer of a tool loop.
- **Truncation:** one re-ask with `maxOutputTokens` × factor, set on the `ChatRequest` (the cached model is not touched). The base cap comes from `maxTokens` / `maxOutputTokens` (Gemini) or Anthropic's built-in default; unknown base means no re-ask.
- **Context too long (B3):** one re-send with the history window halved (`maxContextTokens` / 2, else `conversationHistoryLimit` / 2, else half the conversation), rebuilt through `ConversationHistoryBuilder`, anchored first steps kept. Not available in tool mode.
- **Tool mode:** only the final model call is re-asked (`IAgentOrchestrator.reaskFinalAnswer`, a single request over system + history + the loop's tool exchange + corrective). `ToolLoopRunner` is never re-entered; a tool request in the re-asked answer is ignored. Proven by tests that count tool executions.
- **Time:** a re-ask starts only if the step's remaining time (its `timeoutMs`) is at least `minAttemptMs`; otherwise the cascade escalates at once. The budget is a `RemainingBudget` (`UNBOUNDED` by default, combined with the step timeout via `min`) — the hook the turn deadline (R1) feeds through the new `CascadingModelExecutor.execute(..., contextShrinker, turnBudget)` overload.
- **Breaker hook:** `FormatRetryRunner.ReaskGate` (`CascadingModelExecutor.setReaskGate`) lets R8 skip the re-ask for a model that has been failing the same way for most recent turns. Nothing sets it yet.
- **Streaming:** a task that may re-ask is buffered, not streamed; a new SSE event `llm_retry` `{reason, attempt}` (via `ConversationEventSink.onLlmRetry`) precedes each re-ask.
- **Observability:** `eddi.llm.recovery{action=retry|escalate, outcome, trigger}` (R6's meter) with outcomes `recovered`, `still_invalid`, `failed`, `skipped_budget|no_time|cost|breaker` and `invalid_output`; a panel on the Full Metrics dashboard; `llm:retry:<taskId>` = `{reasks, unresolved}` on the step. Token usage of every attempt is summed into the turn's usage, so re-asks appear in the audit ledger and in `maxCostPerRun`.

### Design decisions

- **What a re-ask sends.** Invalid JSON: original messages + the model's own bad reply as an **assistant** turn (cut at 16,000 chars) + one corrective **user** message. Echoing the reply is standard corrective prompting and keeps model text in the model's role. The user-role text is the configured template with `{reason}` replaced literally by `ModelOutputParser`'s own constant reason — no Qute, no model output, no user input (R16). Empty / truncated / filtered replies are re-asked with the original request unchanged (a corrective sentence adds nothing there).
- **Escalation vs the confidence ladder.** An unusable step is a candidate of last resort (confidence 0 in the best-so-far ranking); if the last step is unusable and an earlier step produced a usable answer, that answer wins.
- **`retry` after exhaustion.** The final `responseValidation` pass resolves a `retry` that is still failing to `fallbackAction`. `retry` on `onRefusal` / `onStreamingTimeout` is not supported and is treated as `warn`.
- **Bug fix on the way:** `LegacyChatExecutor`'s "provider may not support JSON mode" fallback re-sent the identical request after *any* non-retryable failure; a context-too-long, auth, quota or unknown-model refusal now propagates instead (the retry could not help, and the context recovery needs to see the failure).
- **`maxRetryCostUsd`** is priced from the task's / step's configured per-1M prices (zero when none). It does not consult the per-conversation `ToolCostTracker`.

### Not done / follow-ups

- The breaker (R8); the R1 deadline plumbing (only the `RemainingBudget` hook exists).
- Tool mode: no truncation or context-too-long re-ask (no finish reason from the loop; shrinking after tools ran would replay them).
- The HITL resume path (`executeResume`) keeps R3's local repair but has no re-asks.
- Manager: TypeScript types only (`responseValidation`, `CascadeStep.maxFormatRetries`); no form fields yet.
- Platform Operator prompt unchanged: it does not cover LLM response-validation troubleshooting today.

### Docs

[`docs/langchain.md`](langchain.md) — "Recovery policies: retry where it helps"; [`docs/model-cascade.md`](model-cascade.md) — "Format recovery and escalation"; [`docs/metrics.md`](metrics.md).

**Files:** [`FormatRetryRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java), [`CascadingModelExecutor.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LegacyChatExecutor.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LegacyChatExecutor.java), [`AgentOrchestrator.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java), [`LlmConfiguration.java`](../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`ConversationEventSink.java`](../src/main/java/ai/labs/eddi/engine/lifecycle/ConversationEventSink.java)

---

## ✨ feat(llm): response shape validation and native JSON schema (2026-10-06)

**Repo:** EDDI (`feat/llm-response-schema-validation`, stacked on `feat/llm-output-parsing-never-throws`)

### What changed and why

R4 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md)). Valid JSON of the wrong shape (the field the template reads is missing, has the wrong type, or is `""`) used to render an empty bubble with no signal. This change produces the verdict; the recovery policy that acts on it (`onSchemaMismatch`, retry) is R5.

- **`ResponseShapeValidator`** (`modules/llm/impl`): in-house validator for a JSON-Schema subset (`type` incl. type arrays, `required`, `properties`, `items`, `enum`, `minLength`, optional `additionalProperties: false`). `networknt` is on the classpath but deliberately not used: it evaluates `pattern` (ReDoS from model output) and resolves `$ref` (remote fetch). Unknown keywords are ignored. An unparseable or malformed schema logs one `WARN` per distinct schema and skips validation; it never fails the turn.
- **`nonBlankFields`** (new `LlmConfiguration.Task` field, list of top-level or dotted names that must be non-blank strings). Placed on the task next to `jsonResponseFormat`, not in `responseValidation`, so it is independent of `responseValidation.enabled` and does not touch the class R5 extends.
- **New outcome `schema_mismatch`** (`ModelOutputParser.Kind.SCHEMA_MISMATCH`, parse overload `parse(raw, convert, schema, nonBlankFields, taskId)`). **The parsed object is kept** (not the raw string): templates that worked on a partially valid object keep working, which a plain `INVALID` would break. The reason is recorded on the step under `llm:output:reason:<taskId>` (also now written for `invalid`), counted in `eddi.llm.output{outcome=schema_mismatch}`. Reasons are built from the failed keyword and the schema's own path (`schema: $.htmlResponseText required`, `nonBlank: $.a.b`), never a model value and never a key taken from the reply (`additionalProperties` names the object, not the key). R5 consumes `Kind.SCHEMA_MISMATCH` / `outcome.reason()`.
- **Native schema enforcement** via `JsonResponseFormatPolicy` (new `responseSchema` record component, `of(..., schema)`, `supportsNativeSchema`) and `ResponseSchemaConverter`: for `openai`, `azure-openai`, `mistral` and `gemini` the request carries the `responseSchema` as a `JSON_SCHEMA` format, per request, never on the cached model. The Gemini no-JSON-with-tools rule is unchanged. `gemini-vertex` and all other providers keep schemaless JSON plus the prompt block (the Vertex binding only takes a schema on the model builder). Conversion is all-or-nothing: an inexpressible schema falls back to schemaless JSON.

### Design decisions

- **Strict mode is not sent.** In langchain4j 1.20.2 `strict` is a model-builder flag (`strictJsonSchema`), not part of `ChatRequest`; setting it would bake it into a cached model, which this codebase avoids (the historical Gemini 400). The schema goes out non-strict and EDDI's validation is the actual gate. Documented in `docs/langchain.md`.
- A `responseSchema` written as an *example object* (`{"answer": "string - ..."}`, as in older docs) is not a JSON Schema: it stays prompt-only and is neither validated nor sent natively, so existing agents are unaffected. With neither a JSON-Schema `responseSchema` nor `nonBlankFields` set, behaviour is identical to before apart from the key `llm:output:reason:<taskId>` on `invalid` replies.
- Shape checks run whenever a schema or `nonBlankFields` is configured; there is no master switch because the only effect is an outcome label, a log line and a metric until R5 adds an action.

### Docs

[`docs/langchain.md`](langchain.md) Structured Output (native schema matrix, shape validation, reason table, outcome table), [`docs/metrics.md`](metrics.md), Full Metrics dashboard panel description. Manager type `nonBlankFields` in `ui/manager/src/components/editors/llm/types.ts`.

### Follow-ups

- R5: `onSchemaMismatch` action and corrective retry consume `SCHEMA_MISMATCH`.
- Not verified against live provider APIs: the native-schema matrix comes from the langchain4j 1.20.2 bindings, and the native request is checked at the `ChatRequest` level only.
- Manager UI has the type only; no editor control for `nonBlankFields` yet.

---

## feat(llm): turn deadline end to end, cancellation hygiene, deploy-time warnings (2026-10-06)

**Repo:** EDDI (`feat/llm-turn-deadline`, based on `feat/llm-error-classification`)

### What changed and why

Retries, cascade steps and HTTP calls each carried their own timeouts, and they multiply: a caller with a
60-second timeout could give up while the engine was still working, and the late answer was stored but never
seen. This is R1, R10 and R15 of the LLM turn-resilience plan.

- **One deadline per turn (R1).** New agent-level `turnDeadlineMs` (default unset = today's behaviour) and
  `turnDeadlineReserveMs` (default 1500), and a per-request header `X-EDDI-Turn-Deadline-Ms` on `say`, the
  managed-conversation endpoint, the streaming `say` and the OpenAI-compatible `/v1` adapter. The deadline
  is `arrival + min(config, header)`. **Decision:** a header alone enables a deadline when the agent configures
  none (the caller knows how long it will wait), capped at 10 minutes; when the agent configures one the header
  can only shorten it. A malformed, negative or zero header is ignored with a debug line, never an error.
  The value is carried on `IConversationMemory#getTurnDeadline()` as a **transient** field (never persisted),
  set by `ConversationService` before every `say`/`rerun` turn and always overwritten, so a reused memory never
  carries a previous turn's deadline. `TurnDeadline` ([`TurnDeadline.java`](../src/main/java/ai/labs/eddi/configs/shared/TurnDeadline.java))
  takes an injectable `Clock`.
- **Consumers.**
  - `RetryConfiguration.executeWithRetry(…, TurnDeadline)`: the first attempt always runs while time remains; a
    retry starts only if at least 3000 ms plus the reserve remain; each attempt runs on a virtual thread and is
    abandoned and cancelled when it overruns what is left after the reserve (a cached model carries a fixed provider
    timeout, so this is the only per-attempt bound available); a backoff or a provider `Retry-After` that would
    leave no room for the next attempt is not slept - the loop stops and rethrows the classified failure.
  - `CascadingModelExecutor`: `maxTotalDurationMs = min(config, remaining - reserve)`, step timeouts clamped to the
    remaining budget, no new step under 3 s plus the reserve (best answer so far is returned).
  - `ApiCallExecutor`: the per-call timeout is clamped to the remaining budget; `retryApiCallInstruction` retries
    that cannot fit are skipped; the first call is never skipped unless the deadline has already passed, when it
    fails fast with a clear message.
  - `LlmTask` only passes `memory.getTurnDeadline()` into the three non-cascade `LegacyChatExecutor` calls; the
    tool loop does the same from the memory it already holds.
- **Cancellation hygiene (R10).** The model built for a cascade step has its request `timeout` clamped to the
  step's `timeoutMs` (rounded up into coarse buckets - whole seconds to 10 s, 5 s above - so a shrinking deadline
  does not mint a cached model per millisecond; the registry cache key already includes `timeout` and stays
  bounded). A streamed step keeps its own bound. New counter `eddi.llm.cancelled{scope=attempt|cascade_step}`.
- **Deploy-time warnings (R15), never blocking.** Logged once per agent deployment: a model `timeout` longer than
  its cascade step's `timeoutMs`; `convertToObject` with no fallback path; for agents with `turnDeadlineMs`, a static
  worst case (LLM retries x timeout, cascade budget, slowest httpcall of each httpcalls step with its retries) above
  the deadline minus the reserve. The `onError: fallback` half of the fallback check is read reflectively because
  that field ships in a parallel change.
- **Metrics.** `eddi.llm.turn.deadline.exceeded{stage}` and `eddi.llm.cancelled{scope}`, with two dashboard panels
  and entries in `docs/metrics.md`.
- **Docs.** `docs/langchain.md` (Turn Deadline), `docs/model-cascade.md`, `docs/httpcalls.md`,
  `docs/conversations.md`, `docs/metrics.md`.

### Follow-ups

- The SSE streaming model path (`StreamingLegacyChatExecutor`) keeps its own timeout and is not yet bounded by the
  deadline; a HITL resume starts a turn without one.
- Warning "onInvalidJson retry with maxRetries 0" lands with the format-retry change (R5).
- The Platform Operator prompt does not document agent-level resilience settings today, so it is unchanged.

---

## Missing quick-reply array no longer fails the turn; managed calls answer 503 while no agent is ready (2026-10-06)

Repo: EDDI, branch `fix/quick-reply-orempty-and-not-ready-503`. Both found migrating a production 5.x deployment to 6.x.

- `PrePostUtils.renderPerIteration` now iterates `<pathToTargetArray>.orEmpty`, so a response without the array (e.g. an LLM reply with no `quickReplies`) yields zero quick replies / output items instead of `Iteration error - {...} not found` (5.x behaviour). Not appended when the path already ends in `.orEmpty` or uses `?:`, `??`, `or` or a range. This also covers `batchRequests` iteration, which shares the renderer. Tested against the real Qute engine with strict rendering off.
- `RestAgentManagement`: when `RestAgentEngine` refuses to start a conversation because no agent version is ready (404; or 503 while draining), the managed endpoints (`loadConversationMemory`, `sayWithinContext`) now answer 503 with `Retry-After: 5` and a clear message instead of an NPE on the missing user conversation and a 500. The concurrent-creation fallback also no longer dereferences a null conversation.
- Design: `RestAgentEngine` keeps its 404 for the start endpoint (public contract); the translation to 503 happens in the managed layer. Undo/redo/end endpoints only act on existing conversations and cannot hit this.
- Docs: `docs/httpcalls.md`, `docs/managed-agents.md`.

---

## Qute migration parses what it converts, and converts nested conditionals (2026-10-06)

- **Repo / branch:** EDDI, `fix/qute-migration-validate-output`.
- **Problem:** on a production 5.x deployment the Thymeleaf-to-Qute migration turned a nested
  conditional (`[[${a > 0 ? 'x ' + a + (b > 0 ? 'y' : 'z') : 'w'}]]`) into text Qute cannot parse,
  counted the document as migrated, and logged "migration complete". Every render of it then
  failed with `Parser error: invalid identifier found`.
- **What changed:**
  - `TemplateSyntaxMigrator.quteParseError` parses a converted template with a plain Qute engine
    (parse only, nothing rendered; namespaces such as `vars:`, `json:`, `vault:` are resolved at
    render time and do not fail a parse). `V6QuteMigration` runs it on every converted string. A
    template that does not parse keeps its original, makes the document unconvertible and takes
    the existing path: one ERROR, the boot summary, the migration not marked complete, the
    document not counted as migrated. The archive import runs the same check and imports the
    original with a WARN instead.
  - New `OgnlTernaryConverter`, a small recursive-descent parser: `cond ? a : b`, nested to any
    depth, with `'lit' + path + 'lit'` concatenation in the branches, becomes
    `{#if cond}a{#else}b{/if}`. Conditions are paths, string and number comparisons, `&&`/`||`/`!`
    (and `and`/`or`/`not`) and parentheses. A comparison against a number is guarded
    (`x && x > 0`), because Qute throws when it compares a missing value.
  - `migrateCloseTags` now pops its stack on a Qute `{/if}` / `{/for}`, so a converted conditional
    inside a Thymeleaf `[# th:if]…[/]` element does not steal the `[/]`.
- **Design decisions:** the converter answers "not sure" rather than guessing, and the caller then
  falls back to the previous conversion, whose result is judged by the parse check. Comparisons
  such as `x >= 0` or `x < 5` are not converted by it, because the zero-is-false guard would change
  the answer for `x == 0`; they fall back and, if Qute rejects the result, are reported. Plain
  Elvis (`a ?: b`) and safe navigation (`a?.b`) are not conditionals and are unchanged.
- **Follow-ups:** none required. A stray string in an imported archive that is not valid Qute now
  also blocks the conversion of the resource it sits in (the original is imported, with a WARN).

---

## feat(conversations): structured turn errors and idempotent turns (2026-10-06)

**Repo:** EDDI (`feat/turn-idempotency-structured-errors`, based on `feat/llm-turn-deadline`)

### What changed and why

R9 of the LLM turn-resilience plan: the caller contract. A production 5.x deployment's backend timed out at
60 s, retried, met `409 IN_PROGRESS`, and never saw the answer the engine finished a few seconds later; and a
failed turn gave the caller no machine-readable reason.

- **Structured errors.** A failed task now records `code` / `retryable` / `retryAfterMs` / `message` in its
  `taskErrors` entry (classified by `LlmFailureClassifier`; unrecognised failures are `TURN_FAILED`), and the
  `say` response gains a top-level `error` object for a turn in `ERROR`, plus a `Retry-After` header when the
  provider gave a hint. Streaming `done` carries it too. An unexpected server fault is a `500` with
  `{"error":{"code":"INTERNAL_ERROR",...}}` and no exception text. (`TurnError`, `LifecycleManager`,
  `ConversationService`, `RestAgentEngine`.)
- **Idempotent turns.** `Idempotency-Key` / `X-EDDI-Request-Id` on `say`, managed and streaming `say`
  (`TurnIdempotencyService`, `IdempotencyKeyHeaderReader`): a duplicate during the turn waits for it
  (bounded by the R1 header, capped at the agent timeout) and returns its result; a duplicate after completion
  within `eddi.turns.idempotency.ttl-seconds` (default 600, `0` = off) returns the stored answer. The key is
  stored on the step (`idempotency:key`, persisted); an in-memory map (Caffeine, bounded and expiring) holds
  in-flight turns plus a 30 s copy that bridges the moment before the persist. Keys are per conversation,
  <= 128 printable ASCII characters, else `400`.

### Design decisions

- **One `error` object.** Main's #946 already put a `{taskId, taskType, errorType, message}` object under
  `error` on every snapshot in the `ERROR` state; R9 adds `{code, retryable, retryAfterMs, message}` on the say
  response. They are one top-level `ai.labs.eddi.engine.model.TurnError` carrying the union (`taskId`,
  `taskType`, `errorType` are omitted from the JSON when unknown; `retryAfterMs: null` is always emitted),
  built from the same `taskErrors` entry by `TurnError.fromTaskError` on both paths. `message` is #946's digest
  text (`Task 'x' failed: ...`). No field was removed or renamed.
- **The status is unchanged.** A failed turn was already `200` with `conversationState: "ERROR"` (not a bare
  500), so `error` is added to that body rather than replacing it; clients that parse the snapshot keep
  working. The `/v1` adapter and the existing `409/429/503` bodies are untouched. The endpoints produce JSON
  only, so there is no text/plain variant to negotiate.
- **A failed turn is never replayed**, or the retry the error invites would return the same failure.
- **Multi-node:** waiting for a running turn only works on the node running it; the persisted lookup works
  everywhere.

### Follow-ups

- A duplicate of a running turn on another node (NATS) still meets `409`; a cross-node wait would need the
  event bus.

---

## 5.x migration: background properties migration, progress logging, operations guidance (2026-10-06)

Repo: EDDI, branch `fix/v5-migration-startup-ops`.

**Why.** A production 5.x to 6.x upgrade blocked HTTP for minutes at startup (liveness failed), logged
nothing during long migration steps, and printed the database connection string into the container
log.

**What changed.**
- `PropertiesMigrationService` no longer runs from a `StartupEvent` observer on the main thread. Its
  public `runIfNeeded()` is called by `AgentDeploymentManagement` on the scheduler thread, right after
  the V6 rename migration and before the readiness callback and the first deployment. Readiness
  therefore stays DOWN until it finishes, liveness stays UP, and no agent (so no conversation, so no
  property load) exists before it completes. A failure is logged and retried on next start, as before.
- New `ai.labs.eddi.utils.ProgressLogger`: at most one INFO line per interval (10 s), driven by
  `advance()` in per-document loops or by a daemon `startHeartbeat()` around a single long
  `updateMany`. Wired into `V6RenameMigration` (agents, workflow/descriptor/other document loops,
  triggers, and the four server-side `updateMany` steps including the conversation step reshape) and
  `PropertiesMigrationService`. Tested with a fake clock.
- Docs: new section 9 in `upgrading-from-5x.md` (health checks and start period, rollback, memory,
  vault for former property keys, never-ended conversation clean-up, rehearsal, connection string), a
  "Secrets in the Container Environment" section in `security.md`, and the AWS setup page now uses
  `MONGODB_CONNECTIONSTRING` from a secret and the liveness endpoint.

**Decisions.**
- The UBI `run-java.sh` logs the exec line unconditionally and its logging helpers have no off switch,
  so no Dockerfile environment variable can silence it; the image is unchanged and the guidance is
  documentation. A custom logging module via `JBOSS_CONTAINER_UTIL_LOGGING_MODULE` would work but
  replaces base-image code, so it was not done.
- The migration is injected into `AgentDeploymentManagement` as a field `Instance`, not a constructor
  argument, to keep the constructor stable for its many tests.

**Follow-up.** The end-inactive conversation endpoint is described generically in the upgrade guide
and should get a literal path once its PR is merged.

---

## Regression test: outbound httpcalls client keeps no cookie jar (2026-10-05)

**Repo/branch:** EDDI, `fix/httpclient-no-cookie-jar`

**What changed**
- The shared Vert.x client behind every agent's httpcalls was already switched from an application-scoped `WebClientSession` (cookie store, replayed `Set-Cookie` across agents, users and tenants) to a plain `WebClient` in #862. No production code changed here.
- Added `HttpClientModuleCookieTest`: the real producer against a loopback server proves a `Set-Cookie` from one call is not sent on the next call to the same host. Mutation-checked by restoring `WebClientSession.create(...)`, which fails the test.
- Documented the behaviour in `docs/httpcalls.md` ("Cookies are not kept between calls"). Corrected a stale comment in `ApiCallExecutorBranchCoverageTest` that still described a cookie-aware session.

**Behaviour note:** httpcalls never persisted cookies across calls after #862; an API that relied on a session cookie between two calls must carry the credential explicitly (header or property). Nothing in the tests, `docs/agent-configs/` or `docs/httpcalls.md` relies on session cookies.

---

## ✨ feat(manager): UI for the 6.6 backend fixes — tool-call caps, typed property values, schedule disable reasons, one seat per agent, admin-action log, honest GDPR result, RAG store isolation (2026-10-04)

**Repo:** EDDI (`feat/manager-backend-gaps`)

### What changed and why

The open backend PRs of the 6.5 review (#944, #945, #946, #947, #950, #952) added fields, refusals and endpoints that the Manager could not show or set. Every new field is optional here and absent unless the author sets it — an older backend's strict configuration parser refuses unknown fields, so the Manager never writes one on its own — and every new response field degrades to "not reported" when an older EDDI does not send it.

- **LLM editor — tool-call caps (#946).** `maxToolCallsPerIteration` (default 20) and `maxToolCallsPerTurn` (default 100) in the Execution section, with the defaults as placeholders, what happens past a cap (`NOT_EXECUTED`, never reaches the approval gate, approval pauses counted), and a warning when `-1`/`0` switches a cap off. [`task-tool-call-limits.tsx`](../ui/manager/src/components/editors/llm/task-tool-call-limits.tsx)
- **Typed property values (#944).** Property-setter rows and every httpcall/MCP/LLM `preRequest`/`postResponse` instruction get a value-type select: text/template, integer, long integer (`valueLong`), decimal (`valueDouble`), float, boolean; objects and lists are shown and left to the JSON tab. Before, a typed row rendered an **empty** value field, and typing into it wrote `valueString` next to the typed slot — which the engine lets win. Choosing a type now leaves exactly one value slot; a row carrying several says which one runs. Out-of-range integers and longs past 2^53 are flagged. [`property-value-field.tsx`](../ui/manager/src/components/editors/property-value-field.tsx), [`property-value-utils.ts`](../ui/manager/src/components/editors/property-value-utils.ts)
- **MCP calls editor (#944).** `maxResponseSizeInBytes` (characters stored in memory, default 2,000,000), shown when the response is saved.
- **Schedules (#947).** A disabled schedule says why: *Agent undeployed* (comes back by itself at the next successful deploy) or *Access revoked* (names the creator and the fix). No reason means a person disabled it — including a schedule a 6.5 undeploy switched off, which needs one manual Enable; the row says so. The `400 {"error":"invalid_schedule","message"}` reason is shown inside the create/edit dialog instead of a generic toast.
- **Groups (#950).** The create dialog, the group wizard and the Workforce wizard no longer offer an agent another member already holds (keyed by `agentId` across member types, as the server keys seats); a repeated member is a pre-save problem, and the server's 400 is shown in the dialog.
- **Import (#950).** The merge preview explains the 6.6 merge scope when workspaces are enforced, and imports now send `X-EDDI-Space` — they bypass `ApiClient` for the zip body and never carried the selected space, so every import landed in the default space whatever the switcher said; with the merge scoped to the importing space that decided which copy a merge updated. Malformed-archive 400 reasons were already surfaced; they are now announced (`role="alert"`).
- **Audit page (#952).** A second tab, *Administrative actions*, over `GET /auditstore/admin-actions`: actor filter (server-side), method and time filters over the loaded rows, *Load older* paging, and explicit 403 (admins only) / 404 (older EDDI) / empty / error states. `?view=admin` deep-links to it. [`admin-actions-view.tsx`](../ui/manager/src/components/audit/admin-actions-view.tsx)
- **GDPR erasure result (#952).** `auditEntriesRedacted` tile, a plain-language paragraph on what happened to the audit ledger (redacted / kept under `erasure-mode=pseudonymize` / older EDDI that only pseudonymises), what *complete* now means, and a sentence per failed step (`auditRedaction` and others).
- **RAG editor (#945).** A `${…}` store location, a reserved `eddi_kb…` name and a non-plain pgvector table are flagged with the server's reasoning; the storage layout (own store by id vs. 6.5 name-based) is shown with its location; *Move to its own store…* sets `storeNamespace: "id"` only after a warning that the store starts empty, sources re-ingest and `/ingest` documents must be re-ingested. The store placeholders no longer suggest reserved `eddi_kb_…` names.
- **Review follow-ups.**
  - A failed `auditRedaction` step is no longer described as the `erasure-mode=pseudonymize` legal hold.
  - A finished one-time schedule reads "ran and is finished" instead of "disabled by a person". It is recognised by its shape (`oneTimeAt`, `lastFired` set, no `nextFire`), because `markCompleted` resets `fireStatus` to `PENDING`.
  - A saved 6.5 store location the 6.6 rules would refuse, such as `eddi_kb_…`, is shown as "keeps working as saved, would be refused if changed". The server re-checks a location only when it changes.
  - The *Administrative actions* tab is shown only to `eddi-admin` (`useHasRole`); its 401/403 state stays as the fallback. The pipeline trail is now a labelled `tabpanel`.
- **Platform Operator.** The cheat sheet now covers the tool-call caps and `NOT_EXECUTED`, the typed `value*` fields and MCP `maxResponseSizeInBytes`, and one seat per agent in groups. Revision 2 → 3. The admin-actions read is **not** added to the allow-list here: #952 adds it, and `findMissingEndpoints` would refuse operator activation on any backend without that endpoint if this PR merged first.
- **i18n:** 99 new keys in all 11 locales.
- **E2E (MSW):** `e2e/schedules.spec.ts` › "disabled schedule shows disabled status" asserted `getByText(/disabled/i).first().or(getByText(/dead.lettered/i).first())`. The new "Disabled by a person" note made the two halves match different elements, and `or` failed Playwright's strict mode. The test now checks sched-4's "Disabled" badge and `disabled-reason-sched-4`, and sched-3's `disabled-by-person-sched-3`.

### Design decisions

- **Never write a default.** Placeholders show server defaults; a blank field is absent from the JSON. That is what keeps every new field safe against a 6.5 backend's strict parser.
- **Client checks mirror the server, the server stays authoritative.** Duplicate members, RAG location rules and value ranges are checked as the backend checks them, and the backend's own refusal is still rendered where it can arrive.
- **Method/time filters are client-side.** The endpoint filters by actor only; the view says the other filters narrow the loaded rows and stops offering *Load older* once nothing further back can match the time window.


### Not done

- **#953 (A2A in-flight bound / 503):** no Manager surface. The bound is a server setting (`eddi.a2a.max-concurrent-requests`) observed through metrics, and the Manager has no A2A traffic view to hang it on; a one-off counter would duplicate the Grafana panel.
- **GDPR `resealed` / `keptUnverified`:** computed by the backend but only logged, not returned by `DELETE /admin/gdpr/{userId}`; the result paragraph explains re-signing in words instead.

---

## 💬 fix(chat): UX review of the standalone Chat UI (2026-10-04)

**Repo:** EDDI (`fix/chat-ui-ux`) — `ui/chat`, [`ui/chat/README.md`](../ui/chat/README.md), [`security.md`](security.md)

### What changed and why

A review of the standalone chat widget found a run of problems that cost users text, orientation or accessibility. Fixed in `ui/chat`:

- **Reading while it streams.** Auto-scroll fired on every token with smooth scrolling, fighting anyone who scrolled up. The view now follows only while the reader is at the bottom (an instant jump), a send re-engages it, and the scroll-to-bottom button shows when it is not following. Streaming bubbles re-parse markdown at most every 50 ms instead of per token.
- **`postMessage` token vs auto-start.** With `?tokenOrigin=` the conversation used to start before the host could send the token, so the start (and the review-notice lookup) went out unauthenticated. The widget now posts `{"type":"eddi-chat-ready"}` to each allowed origin by name (never `*`, repeated every second), holds the start until `eddi-chat-token` arrives, and gives up after 5 seconds and starts without one. Documented in the README and [`security.md`](security.md).
- **A failed send no longer eats the message.** Any 4xx, a server error or a dropped connection before the first token withdraws the user bubble and puts the text back in the composer. If tokens had already arrived, the partial answer stays, "Connection lost — the response may be incomplete" is appended, and the conversation is re-read so the next send does not 409. The streaming, non-streaming and managed transports behave the same.
- **Typed failures have their own copy**: 409 (the backend's operator text, naming `POST /agents/<id>/resume`, is no longer shown), 401 ("session has expired"), 403, 404 (agent not ready), 413, 429, 503, and 410, which now always sets ENDED.
- **Stop works on every transport.** An `AbortSignal` is passed through `sendMessage` and `sendManagedAgentMessage`; a late reply for a stopped turn is ignored and stopping is not reported as a failure.
- **Start/empty states.** A failed start offers "Try again". An agent with no greeting shows "Say hello to get started" instead of "Starting conversation…" forever. A paused conversation whose approval status has not arrived shows an indicator.
- **Header.** `?title=` is rendered (and sets the tab title), and `?hideLogo=true` shows the text title instead of an empty bar, as the README always promised.
- **Safer controls.** "New conversation" asks first when the conversation has messages; an agent-requested input field has "Type a message instead"; the secret toggle has a fixed label with `aria-pressed`, is disabled mid-turn, and its tooltip now says what the vault does (hidden in the chat, masked in stored history) rather than "encrypted".
- **Accessibility.** IME composition (CJK, mobile keyboards) no longer sends on the confirming Enter, in the composer and the requested field. Avatars are `aria-hidden` and each bubble says who spoke ("You said:" / "<Agent> said:"). The composer textarea has a label. Focus returns to the composer after a secret send or leaving the requested field. The paused card is no longer a nested live region and its minute-by-minute countdown is hidden from assistive technology in favour of a fixed clock time.
- **Look.** Inputs are 16px on phones (no iOS zoom), long quick replies wrap, accent tints use `color-mix()` so any CSS colour works, `?theme=` beats a remembered preference and the toggle cycles dark, light, system.
- **Notices are not agent bubbles.** Text the widget wrote (failed sends, undo failures, cancellations, upload problems) renders as a system notice.
- **Undo/redo** now rebuild quick replies and the requested input field from the snapshot, and undoing to an empty conversation clears the old bubbles.
- **Copy buttons** on agent messages and code blocks.
- **i18n.** The widget's own text is a small string table (`src/i18n.ts`: English, German, French, Spanish), chosen by `?lang=` or the browser language, falling back per key to English; `<html lang>` follows. Documented in the README.

### Design decisions

- One module-level locale rather than React context: the SSE and HITL copy helpers live outside React and must translate too.
- Notices are a `kind: "notice"` flag on `ChatMessage`, so existing transcript code is untouched.
- The restart confirmation is inline, not a modal, because the widget is often a small iframe; an untouched conversation restarts without asking.
- The ready-signal repeats until the token arrives because the parent's listener may not exist when the iframe's first message goes out.

### Deliberately not done

- `button` output items stay bold text: `onPress` is a free-form map for clients that implement it, with no contract this widget could honour, so there is nothing safe to click.
- The 403 on start cannot be told apart as an origin rejection (the response does not say), so its copy names both causes without accusing the user.
- Only English, German, French and Spanish are translated; the Manager's other seven locales (ar, hi, ja, ko, pt, th, zh) fall back to English until their tables are added.

**Files:** [`ChatWidget.tsx`](../ui/chat/src/components/ChatWidget.tsx), [`i18n.ts`](../ui/chat/src/i18n.ts), [`MessageBubble.tsx`](../ui/chat/src/components/MessageBubble.tsx), [`ChatInput.tsx`](../ui/chat/src/components/ChatInput.tsx), [`SecretInput.tsx`](../ui/chat/src/components/SecretInput.tsx), [`PausedCard.tsx`](../ui/chat/src/components/PausedCard.tsx), [`ChatHeader.tsx`](../ui/chat/src/components/ChatHeader.tsx)

---

## fix(manager): config editors and the Agent wizard (2026-10-04)

**Repo:** EDDI (`fix/manager-editors-wizard`), `ui/manager`

### What changed and why

Findings from a UX review of the Manager's config editors and the Agent setup wizard.

**LLM editor.** `modelName` (`deploymentName` for Azure OpenAI), `apiKey` and `temperature` were only reachable through a collapsed generic key/value grid. They now have their own "Model" section above the prompt: a model field with the provider's suggestions, the existing vault/secret picker for the key (no connection references, which the backend refuses in model parameters), and a temperature field that validates its input. The model key, credential key and temperature come from a per-provider table checked against the backend builders (`components/editors/llm/model-params.ts`): `model` for Ollama, `modelId` for Bedrock, Hugging Face and Vertex, `deploymentName` for Azure, `modelName` otherwise; the credential is `apiKey`, `accessToken` (Hugging Face) or `authToken` (Jlama, optional), and is not offered for Ollama, Bedrock, Vertex and Oracle GenAI, which authenticate elsewhere. Only the keys the section surfaces for the provider leave the generic grid, and a grid row cannot be renamed onto them. A new task starts with the `send_message` action and the default provider's model instead of no actions and no model; a task with no actions or no model shows a warning. Changing the provider swaps the model only when it is exactly the previous provider's default.

**Shared components.** `ActionTags` existed four times (LLM, rules, API calls, property setter) and none of them kept text typed but not yet added, so an edit was lost on blur and the dirty check never saw it. One component in `components/editors/action-tags.tsx` now commits on Enter, comma, blur and the Add button, splits a pasted comma list, ignores Enter during IME composition, and suggests actions emitted elsewhere in the same config. `components/editors/editor-field.tsx` adds a `Field` that wires `id`/`htmlFor`/`aria-describedby`.

**Rules editor.** New condition configs use `nextFreeKey` (`key${count}` overwrote `key1` after `key0` was deleted). `occurrence` (inputmatcher, actionmatcher), `contextType` and the capability `strategy` are selects; a stored custom value stays selectable. Changing a condition's type asks first when the user entered something beyond the type's presets. Lint warnings: a rule with no `actionmatcher` on `lastStep`, and a comma list in `actionmatcher.actions` (AND semantics). Rules and groups can be moved up and down, since order decides which rule fires first.

**Accessibility.** Labels tied to inputs in the LLM, rules, API-calls, MCP-calls, output and property-setter editors (`htmlFor`/`id` where the label is visible, `aria-label` in compact rows); names on the icon-only remove and expand buttons.

**Output editor.** Invalid JSON in the onPress / fallback field was silently reverted on blur; it now stays in the field with an inline error until fixed or cleared.

**Agent wizard.** There is now one deploy mechanism: the Review step has a deploy target (default **Test**, was Production) and two buttons, "Create without deploying" and "Create & deploy to test/production". The Auto-Deploy toggle on the Features step is gone. Review lists the API key as "None", "Not needed", "Vault reference X" or "Entered here", never the key. The model field is prefilled with the provider's default (also after a provider switch). Step circles have an accessible name and `aria-current`; the API-key label points at the field; the Model step is a `<form autocomplete="off">` so the browser stops warning about a password field outside a form.

**Agents list.** The card footer wraps instead of clipping "Undeploy from production" and wrapping the timestamp at 375px.

### Design decisions

- Deploy is decided by the button, not a switch, because a switch on an earlier step could disagree with the button pressed later.
- A comma list in an actionmatcher is warned about, not blocked: AND is legitimate for an action sequence.
- Labels in dense rows use `aria-label` with the visible text rather than a layout rewrite.

### Deliberately skipped

- The `<label>` elements used as captions over groups of controls in the LLM editor (they label no single control) were left as they are.
- Index-keyed rule and condition components keep their expanded state by position when reordered; only group expansion follows the group.

---

## fix(manager): error surfacing, session handling and shared dialogs (2026-10-04)

**Repo:** EDDI (`fix/manager-errors-auth-dialogs`), `ui/manager`

### What changed and why

From a UX review of the Manager:

- **Auth.** A failed Keycloak init used to render the whole app unauthenticated, so every page read "Something went wrong". It now shows a "Sign-in failed" screen with **Sign in again**. The API client renews the token before each request (`updateToken(30)`, so a token that expired in a throttled tab is not sent), and on a 401 forces one refresh and retries once. When that fails the Manager shows a non-destructive "session expired" banner over the still-mounted app instead of calling `keycloak.logout()` immediately, which dropped unsaved work.
- **ErrorState** accepts the error object and says 401 / 403 / 404 / "can't reach EDDI" in its own words, with the server's message beneath. About 25 callers now pass `error`.
- **Global mutation fallback** (`lib/query-client.ts`): a failed mutation that nobody reported is toasted with the server message. It stays quiet for `meta: { silent: true }`, for mutations with a hook-level `onError`, and when the caller toasted an error within 50 ms (call-site handlers cannot be introspected, so this is how double toasts are avoided). 401s are left to the session banner.
- **Dashboard** no longer shows zeros for failed reads: all-failed is an error state, a partial failure shows "—" on the affected card plus a retry notice. The vault strip says "checking" while loading, the skeleton draws 3 cards (as many as render) and conversation states are translated.
- **Orphans**: a failed scan renders the error, a refused purge shows the server's reason (409), each orphan links to its detail page, and Purge All is an `AlertDialog` that needs the orphan count typed.
- **AlertDialog** ignores Escape / backdrop / X while its action is pending, and its default labels and the sr-only "Close" are translated. "Authenticating…" is translated too.
- **Capabilities** shows agent names instead of raw ids (no external-link icon), has an explanatory empty state, labelled search / strategy controls and `aria-expanded` on rows.

### Decisions

- The QueryClient moved into `lib/query-client.ts` (a `MutationCache` needs to be built with it); `main.tsx` only imports it.
- Not done: the `404 GET /variablestore/variables/default/platform.operator` console line. `readOperatorConfig` already treats the 404 as "not configured"; the line is the browser's own network log and cannot be suppressed without a backend change (e.g. a 200 with an empty body).
- Raw-`fetch` modules (SSE, `text/plain` chat) still read `api.getAuthHeader()` without a pre-flight refresh.

---

## 🐛 fix(manager): group page, approvals inbox and Platform Operator drawer UX review (2026-10-04)

**Repo:** EDDI (`fix/manager-group-approvals-operator`) — `ui/manager` only, no backend change.

### What changed and why

Findings from the Manager UX review, for the group detail page, the Workforce board hand-offs, the approvals inbox and the Platform Operator drawer.

**Approvals**
- The nav badge counted only 1:1 approvals, so it disagreed with the page it links to. It now counts the same merged, de-duplicated set the inbox shows: 1:1 pauses, group phase approvals and HUMAN members' turns.
- Inbox rows lead with the agent's or group's name (the id is the second line, under a translated "Conversation" header, no longer a bare "ID"). Names, group ids, member ids and tool names are searchable. A HUMAN-turn row shows the member's display name instead of the raw id.
- A group phase approval can be reviewed in place: a "Review" row shows the question, the paused phase, the last entries of the transcript and the same `ApprovalBanner` the 1:1 tool-call rows use (note field, per-task verdicts), so the decision is no longer made on the pause reason alone.
- On a phone the table lays out as cards (labelled cells) instead of scrolling sideways, so Approve/Reject sit with their row.
- Workforce "Review it" now carries `&conversation=<id>` so the group page opens the paused discussion, not the newest.

**Composers**
- The discussion composer, the Workforce board composer and the follow-up composer keep the typed text (and files) until the request is accepted, and restore it when it is not. "Streaming live" toasts fire on the first SSE frame, not on the click. New store field `GroupStreamState.connected` and `whenStreamAccepted()` / `streamErrorOf()` in `use-group-discussion-stream.ts` make "accepted" observable.
- The group page composer defaults to a NEW discussion; an explicit New / Continue toggle appears when the selected discussion can be continued. Picking a discussion from the history (or arriving by link) means continue.
- The follow-up target list offers AGENT members only (HUMAN members have no agent to ask).

**Group page**
- A dropped live connection shows the same `role=status` notice as the Workforce board.
- The transcript only follows new entries while the reader is at the bottom, with a "New messages" button otherwise; the body is a polite `role=log` region, a pause is announced, and focus/scroll moves to the approval or human-turn banner. The HTML toggle reports `aria-pressed`.
- The history list loads older discussions ("Load older discussions", 20 at a time); the mobile history button is labelled, reports `aria-expanded` and closes on Escape.
- SYNTHESIZING discussions can be cancelled; deleting a running discussion stops it first (and says so). Escape leaves fullscreen unless a dialog or the history dropdown owns it.
- Opening one config editor no longer discards another's draft (the other Edit buttons are disabled while one is open); hiding the panel, going fullscreen or closing the sheet asks before discarding an open editor. A link to the Workforce settings (where name, members, moderator, style and rounds are editable) was added to the panel.
- After a human-turn submit the page says the discussion is resuming. The backend resumes it asynchronously and has no stream to reattach to, so the page keeps following the persisted conversation (polling); the mutation now stays pending until the refetched conversation lands.

**Platform Operator**
- Escape no longer closes the drawer when a dialog is open over it or the key was already consumed; the unsent draft lives in the operator chat store, so it survives closing the drawer; Enter during an IME composition no longer sends.

### Design decisions
- "Edit basics / members" is a link to Workforce settings rather than a second editor on the group page: that screen already edits every core field through `update_group`, and a second one would have to keep step with it.
- Draft restore is done by holding the draft until a promise settles (`onSubmit` / `onSend` / `onFollowup` may return `Promise<boolean | void>`; only `false` or a rejection keeps it), so the composers stay usable by callers that return nothing.
- Not changed: `system-prompt.ts` / `tool-scopes.ts` (nothing the Platform Operator documents moved), so `operator-revision.json` is untouched.

### Deliberately skipped
- Attachments chosen with a refused Workforce board start are not restored (the question text is); files are re-staged from `File` objects the board no longer holds once its view is replaced by the error screen.

---

## fix(manager): in-app navigation no longer discards unsaved edits; shell, tour, palette and top-bar fixes (2026-10-04)

**Repo:** EDDI (`fix/manager-navigation-guard`), `ui/manager`

### What changed and why

Workstream A1 of the Manager/Chat UX review: routing, navigation chrome and the unsaved-changes guard.

- **Unsaved-changes guard now covers in-app navigation.** The app moved from `<BrowserRouter>` to a data router (`createBrowserRouter` + `RouterProvider`, `src/app-router.tsx`), so a router blocker is available. There is exactly ONE blocker for the whole app, mounted by `NavigationGuardDialog`; `useUnsavedChangesGuard(isDirty)` only registers a dirty token (keyed by `useId()`) in a store, and the blocker holds a path change while any token is registered. (A router consults only the last registered blocker, so one blocker per guard let a later clean guard unprotect a dirty page.) A sidebar link, breadcrumb, command-palette jump or the Back button on a dirty page now asks Stay / Discard (`NavigationGuardDialog`); only a path change is held, so switching a tab through a query parameter is not. The hook's signature is unchanged, so every page that already calls it is protected. `allowNextNavigation()` is the one-shot escape hatch for programmatic navigation whose edits are already resolved (delete, confirmed discard); it is wired into the connection, workflow and resource detail pages.
- **A crashing page no longer wipes the shell.** `SuspendedOutlet` carries an `ErrorBoundary` keyed on the location, so the sidebar and top bar survive and any navigation recovers.
- **Mobile navigation drawer**: closes on route change, is a modal `dialog` with a focus trap, takes focus on open, closes on Escape and gives focus back. The language selector, hidden in the top bar below `sm`, is reachable in the drawer. `isMobile` starts from the real width (no desktop-sidebar flash); sidebar collapse state is now remembered.
- **Guided tour**: keys and the body scroll lock attach only while the target is on screen; Enter on a focused Back / Skip button no longer advances; the chapter ends (not marked done) on route change; a missing target is waited for with a `MutationObserver`, then the step is skipped after 8 s (the chapter is abandoned only if it was the last step), so the dashboard tour still works on a phone where the sidebar target sits in the closed drawer; the Help-menu replay waits for the page instead of a fixed 300 ms; the tooltip takes focus.
- **Unknown routes** show a Page not found screen inside the shell (`/manage/*`, `/workforce/*`, top level) instead of silently redirecting to `/welcome`. `/welcome?choose` shows the chooser even when a landing preference is saved.
- **One route-label registry** (`src/lib/route-registry.ts`) now feeds the breadcrumb, document title, command palette and sidebar. `/manage/groups/wizard` no longer reads "Agent Wizard", nine sections no longer show raw lowercase segments, and an agent detail page shows the agent's name.
- **Command palette**: built on cmdk's `Command.Dialog` (modal, focus trap, focus return); Create New Agent opens the wizard (the old `?action=create` target was read by nothing); lists every page; agents are searched on the server (30 rows, one per agent, debounced, only while open); Ctrl+K is left alone inside Monaco.
- **Top bar**: language selector has an accessible name; the user menu is a Radix `DropdownMenu` (arrow keys, Escape, focus return); the current page name and a compact status dot show on phones; breadcrumb label is translated.
- **Toasts follow the resolved theme.** `ThemeProvider` storage access is wrapped in try/catch.
- **Mode switcher** is a pair of links, not an ARIA tablist, and no longer rewrites the saved landing preference (only the chooser's explicit choice does).
- **Stacked dialogs**: the unsaved-changes dialog handles Escape in the capture phase and stops it, and `AccessibleDialog` ignores an already-handled Escape, so one press closes one dialog.
- Smaller: collapse state per section is stored by stable id (old index saves are still read); Help-menu completion is announced; the untranslated "Checking version...", "Expand/Collapse sidebar" and "Breadcrumb" strings are translated in all 11 locales.

### Design decisions

- `<App />` keeps its `<Routes>` table under a single catch-all data route rather than converting every route to route objects: no route, lazy page or layout changed, and every existing `MemoryRouter`-based test still renders `<App />`.
- `NavigationGuardDialog` mounts the blocker only when a data router is present (`UNSAFE_DataRouterContext`), so component tests with a bare `MemoryRouter` keep working (the hook still registers its token and `beforeunload`); the data-router behaviour has its own tests, including a clean guard mounted after a dirty one.
- The dirty-page prompt reuses `UnsavedChangesDialog` with a "Stay" label.

### Deliberately skipped / follow-ups

- Server-side agent search relies on the descriptor listing's existing `filter` text match.
- The workforce shell keeps its own responsive handling; only its not-found route was added.

---

## Manager: conversations, monitoring, user data, GDPR and chat UX fixes (2026-10-04)

Repo: EDDI (`ui/manager`), branch `fix/manager-conversations-userdata-chat`. Fixes from the Manager UX review.

**Destructive actions**
- User Memory: the row trash button now opens an `AlertDialog` naming the key before the permanent delete, and only the row being deleted is disabled (previously one pending delete disabled every row). "Delete All" shows the user id and entry count, and deletes the id it was opened for, not the debounced input.
- GDPR erasure requires typing the user id back (same pattern as the vault reset). An erasure whose counters are all zero, which is what a mistyped id produces, shows a neutral "No data found for this user id" instead of a green "Erasure Complete".
- Monitoring: the purge of ended conversations is now its own bordered section whose title, description and confirmation say it applies to all agents (the backend purge has no agent scope; there is no count or dry-run endpoint, so none is shown). The days field can be emptied while typing and is validated on Purge.

**Conversations list and detail**
- Filters, search, agent, version, page and page size live in the URL (`q`, `state`, `agent`, `version`, `page`, `size`); the detail page's Back link returns to that exact list (passed via router state, validated to a `/manage/conversations` path). Monitoring keeps `agent` and `version` in the URL.
- A later page that comes back empty shows "No more results" with a way back instead of the "deploy an agent" empty state; deleting the last row of a later page steps back automatically. The backend still reports no total, so Next stays enabled on a full page.
- Card delete button is no longer nested inside the card link (stretched-link overlay on the id); the detail delete button has an accessible name. Agent names resolve for up to 1000 agents through one memoised map instead of the first 50 and a per-row regroup.
- Detail page polls every 3 s while `IN_PROGRESS`, the transcript search is labelled and announces a match count, and the Markdown export carries per-step timestamps.

**User data**
- One user id is shared by the Memories, Properties and Conversations tabs and kept in `?user=`; tabs have ids and accessible names on mobile (the panel's `aria-labelledby` pointed at nothing).
- The create-binding dialog uses the shared `AccessibleDialog`, an `AgentPicker` for the agent, and `htmlFor` labels.

**Chat**
- The transcript is a polite `role="log"`; Enter no longer sends mid-IME-composition in the chat panel, the drawer and the secret field; the secret-mode toggles are named and expose `aria-pressed`; the agent selector is keyboard-operable (search box, arrows, Home/End, Enter, Escape returning focus).

**Deliberately skipped**
- "New chat opens with a user message before the welcome": a mock artefact. The MSW handler's `conv1` fixture is the conversation the panel reopens (most recent READY one) and it carries a user turn; the logic that builds messages is correct, and the handler already returns welcome-only for freshly started conversations.
- Conversation state "shown raw": every list/detail/monitoring surface in this stream already localises it.
- No export or count preview for GDPR erasure: the existing export endpoint is the full bundle, and the memory count covers one category only.

---

## Manager: group and workforce wizards, group templates and workspaces UX fixes (2026-10-04)

**Repo / branch:** EDDI, `fix/manager-group-wizards` (UX review stream C1; Manager only, no backend change).

**Group wizard (`pages/group-wizard.tsx`)**

- **No duplicate agents on retry.** When creating the moderator failed, the wizard returned without
  writing the members it had already provisioned back into its state, so clicking Create again
  provisioned and deployed every member a second time. Every failure path now persists members and
  moderator progress (one `abort` helper). Regression test `group-wizard-ux.test.tsx` fails (12 setup
  calls instead of 7) with the fix reverted.
- **API keys and providers.** A new advisor on a provider that needs a key is now blocked on the
  Members step with a per-member sentence (the same `providerNeedsKey` rule the Workforce wizard
  uses), instead of the backend rejecting the setup request after earlier members were deployed. Keys
  for keyless providers are no longer sent. A "same provider and key for all new agents" block
  applies one provider/model/key to every advisor still to be created. Provider pickers (members,
  moderator, bulk, and the Workforce wizard's team builder) offer only providers
  `isProvisionableBySetup` accepts, so Vertex AI is no longer offered.
- **Why Next is disabled.** The wizard lists what is missing under the step (name, HITL phases and
  timeout, style, member count, each member's name / principal id / group / agent / key, turn
  timeout). The same list gates Next.
- **Unsaved work.** Both wizards guard reload and tab close once anything is configured, and ask
  before Back, Cancel and "Back to groups" discard it (`useLeaveConfirm`, built on
  `useUnsavedChangesGuard` plus the existing `UnsavedChangesDialog`). After a confirmed leave or a
  successful create the guard is released first, so a router-level block never prompts twice.
- **Stepper and styles.** Visible step titles (sm and up), per-step `aria-label`, `aria-current`.
  Style cards are a `radiogroup` of `role="radio"` / `aria-checked`, carry a "best for" line, and the
  selected style shows its full flow. Max Rounds explains what a round is and shows an estimated call
  count (members x rounds, plus one for a moderator).
- **Turn timeout** is a number plus a minutes/hours/days picker that serialises to ISO-8601
  (`DurationField`, `lib/iso-duration-parts.ts`).
- **i18n and labels.** Default member and moderator prompts and placeholders are translated and no
  longer say "a Engineering expert"; member name, role, prompt and model inputs have labels; the
  Agent/Group type toggle is localised and exposes `aria-pressed`. The Guided Setup card on the Groups
  page has group-specific copy.
- **Two template systems** are now named: the wizard's client presets are "starter presets" with a
  link to the packaged Group Templates, and the templates page links back to the wizard. Role chips
  are humanised and de-duplicated (`Forecasting x4`, `Devil advocate`, `lib/role-labels.ts`).

**Other pages**

- **Group templates:** the selected template is a `?template=` search param, so browser Back returns
  to the gallery, a reload keeps the selection, and the error view's Back link goes somewhere
  different from where the user is.
- **Group workspace:** subject, description and prompt-template inputs are labelled; `P{n}` has a
  tooltip and the priority field says higher numbers are picked first; the cadence time zone is a
  select defaulting to the browser zone instead of free text defaulting to UTC.
- **Workspaces:** deleting a variable asks for confirmation, like deleting a secret.

**Decisions / deliberately skipped**

- Backlog task edit/delete was **not** added: there is no per-task update or delete endpoint on REST or
  MCP (`lib/api/group-workspace.ts` documents it). The page now says tasks cannot be edited or removed
  once added. Needs a backend change if wanted.
- The Workforce wizard stepper already has visible labels, `role="list"` and `aria-current`; nothing
  to change there.
- The key requirement is checked client-side only for AGENT slots in Create New mode; a slot already
  created by an earlier attempt is not re-validated.
- Existing group wizard tests that walk to the Review step now apply a bulk key first, because the
  Members step no longer lets a keyless Anthropic advisor through.

---

## Manager ops/admin pages: logs, audit, schedules, triggers, secrets, connections, channels, quotas (2026-10-04)

Repo: EDDI (`ui/manager`), branch `fix/manager-ops-admin-pages`. Workstream D2 of the UX review.

**Logs.** History rows show the date (year only when not the current one); every timestamp carries its ISO-8601 time with offset in its title, and the Live export and per-row copy now write `[2026-10-04T11:11:05.123+02:00] [LEVEL] message` instead of a bare time. The "1 errors / 2 warns" counters are real plurals (`logs.errorCount`, `logs.warnCount`, all 11 locales with their plural categories). When the SSE retry budget is spent (`onexhausted`) the Live tab now says "Disconnected" with a Reconnect button instead of pulsing "Connecting…" forever (`session-log-store` and `use-logs` expose `exhausted` / `reconnect`; `StreamBadge` gained an `exhausted` prop). History filters and the active tab live in the URL (`?tab=history&agent=&conversation=&instance=&level=`). A text search that matches nothing shows a "no loaded entries match" state and the box says it searches loaded entries only. The per-row copy button is visible on keyboard focus and on touch, copy failures toast, and agent columns show names (new `useAgentNames` hook; id in the title). The debugger's `LiveLogViewer` is no longer an `aria-live` region over a 500-line stream: it announces a throttled error count through a separate status region.

**Audit.** Timestamps show full date, seconds and UTC offset with the ISO form in the title (new `lib/date-format.ts`). "By Agent" lists every agent, deployed ones in their own group, instead of only deployed ones. The search (mode, agent, version, conversation) is in the URL.

**Schedules.** The one-time Date & time is read as wall-clock time in the selected Time Zone (Intl-based `zonedLocalInputToIso`, DST-safe) instead of the browser's zone, with help text naming the zone; the list shows `oneTimeAt` in the schedule's zone with the zone name. Last Fired now carries the same zone label as Next Fire. Error toasts append the server's reason. Every form label is tied to its control; the agent field is the shared `AgentPicker`; the agent column shows names; the active tab is `?tab=failed`.

**Secrets / Variables.** The hand-rolled delete overlay (focus never moved in, so Escape did nothing and Tab reached the page) is the shared Radix `AlertDialog`; the delete error path goes through `secretsErrorMessage`; Allowed Agents shows names; clipboard failures toast.

**Channels / Connections / Quotas / Triggers.** Channel delete no longer leaves an unhandled rejection and an open dialog on failure (toast + close, as connections). `channel-detail` and `quotas` call `useUnsavedChangesGuard`; channel-detail computes dirtiness against the stored configuration. Quota fields keep their text while typing, treat empty as unlimited (`-1`, the backend sentinel) rather than `Number("") === 0`, reject negatives (other than -1) and fractional counts, and block Save with an inline error; labels tied to inputs. The trigger dialog disables Save and says why when no agent is chosen. Connection delete names the connection and toasts success. Channel-detail labels tied to inputs.

Decisions: new strings use inline plural keys per locale (zh/ja/ko/th carry `_one` equal to `_other`, as elsewhere). `schedules.oneTimeHelp` and `logs.errors`/`logs.warnings` are now unused keys, left in place. The MSW Daily Health Check fixture (`nextFire = now + 12h`) was the source of the "Sunday 8:51 PM" next fire for a weekday cron; the display logic was correct, so only the fixture was fixed. The Logs "Reconnecting…" beside live entries in mock mode is the MSW SSE stub not holding a stream open (entries come from the REST seed); not a display bug, not changed.

Documented in `docs/scheduling.md` (one-time input zone).

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
