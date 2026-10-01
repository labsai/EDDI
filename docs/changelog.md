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
| [September 2026](changelog/2026-09.md) | 122 | 589 KB |
| [August 2026](changelog/2026-08.md) | 212 | 837 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 🔒 fix(infra): CodeRabbit review findings on PR 865 (2026-09-28)

**Repo:** EDDI (`fix/security-infra`)

### What changed and why

- **GCP: the Keycloak admin password no longer travels in instance metadata.**
  It was generated on the operator's machine and embedded in the startup script,
  which GCE serves to every process on the VM (containers included) from the
  metadata server, and the temp copy was mode 644. The startup script now
  generates it **on the VM** into root-only `/root/.eddi-keycloak-admin` (once;
  later boots reuse it) and the banner prints the `gcloud compute ssh …
  --command='sudo cat …'` to read it. This also retires the `/dev/urandom`
  fallback that filtered raw bytes and usually came up short of 16 characters.
- **GCP: existing firewall rules are reconciled, not skipped.** A rule left
  world-open by an earlier run was reused as-is while the summary claimed a
  scoped firewall; `ensure_firewall_rule` now compares source ranges and updates
  the rule. The port-80 rule alone is `0.0.0.0/0`, because Let's Encrypt's HTTP-01
  validation comes from its own servers — it serves only the ACME webroot and a
  301; 443 stays scoped. The now-pointless `:8180` rule is no longer created.
- **Keycloak is published on loopback** (`KEYCLOAK_BIND`, default `127.0.0.1`) in
  [`docker-compose.auth.yml`](../docker-compose.auth.yml), so its `admin/admin`
  dev default is never reachable off-host by accident.
- **`install.sh` logs in to Keycloak with the bootstrap credentials** it was
  started with (`KC_BOOTSTRAP_ADMIN_*`, default admin/admin) instead of a literal
  admin/admin, so a provisioned VM's CORS/redirect setup no longer silently
  fails; `eddi update --eddi-version=` validates the tag before writing `.env`.
- **Helm refuses an OIDC-off render without both high-value opt-ins** — the
  default install otherwise rendered and then crash-looped on
  `HighValueSurfaceGuard`. Docs' `helm install` lines now carry the required
  values. A live `helm upgrade` of a release whose MongoDB predates
  authentication refuses to render until the user is created
  (`mongodb.authMigrated`, procedure in [`docs/kubernetes.md`](../docs/kubernetes.md)).
  MongoDB credentials are URL-encoded in the connection string.
- `auto-approve-copilot.yml` also treats a rename **out of** `.github/` as a CI
  change (`previous_filename`), and fails closed when the changed-file list hits
  the API's 3,000-file cap.

---

## 📝 docs(governance): release tags carry no `v` prefix (2026-09-28)

**Repo:** EDDI (`docs/governance-bare-release-tags`)

### What changed and why

[`GOVERNANCE.md`](../GOVERNANCE.md) said releases are tagged `v6.0.0`, `v6.0.1` and
`v6.0.0-RC1`. That contradicts AGENTS.md §1, [`release-versioning.md`](release-versioning.md) and
`ci.yml`, which triggers only on tags matching `[0-9]*`. A `v`-prefixed tag starts no workflow at
all: no image, no cosign signature, no SLSA attestation and no GitHub release. The push succeeds
anyway, so nothing reports the problem. GOVERNANCE.md is where a new maintainer looks for "how do we
release", so it was the one place still pointing them at the tag that does nothing.

The page now uses bare tags (`6.0.0`, `6.0.0-RC1`), says why the prefix matters, and links to
`release-versioning.md` for the process.

### Checked, not changed

A repo-wide search for `v6.`/`v5.` versions and `git tag` instructions, excluding the changelog,
found no other place that tells someone to push a `v`-prefixed tag. The remaining hits are:

- "Available since v6.0.0" status lines, which name a release, not a tag to create.
- `# v6.0.3` comments on pinned GitHub Actions.
- The existing warnings against the prefix, in `release-versioning.md` and `release-signing.md`.

---

## 🐛 fix(ci): the Slack notification exceeded Block Kit's 10-field limit (2026-09-28)

**Repo:** EDDI (`fix/slack-notify-field-limit`)

### What was wrong

`ci.yml`'s `notify-slack` job posts a Block Kit message whose status icons sit in one section
block's `fields` array. That array had 11 entries. Slack's section-block reference caps `fields`
at 10 ("Maximum number of items is 10"), and a message over the cap is rejected as a whole
(`invalid_blocks`), not truncated. The step posts with `curl -sf`, so the result was not a shorter
message: no failure or release notification reached Slack at all. The only trace was a failed
`Slack Notification` step, and nothing was told about it.

### What changed

- [`ci.yml`](../.github/workflows/ci.yml): the statuses are split into two section blocks,
  with every icon kept.
  - Build and test gates: Build & Test, Integration Tests, UI Gate, Build Image, Backend E2E.
  - Scan and publish gates: Secret Scan, Vuln Scan, CodeQL SAST, Docker Push, Smoke Test,
    Red Hat Preflight.

  The payload is still built by `jq -n` from `--arg` values, with no inline interpolation, and a
  comment at the payload records the limit.
- [`BuildQualityGatesTest`](../src/test/java/ai/labs/eddi/BuildQualityGatesTest.java)
  `slackSectionsStayWithinTheFieldCap` checks two things:
  - every `fields` array in `notify-slack` has at most 10 entries;
  - every `--arg *_icon` the job computes is rendered in some section, so a later split cannot
    silently drop a status.

  Mutation-checked both ways: `origin/main`'s workflow fails with "a notify-slack section has 11
  fields", and removing the Red Hat Preflight field fails the rendered-icons check.

No test asserted the payload's shape before this. actionlint reports the same 60 pre-existing
findings on `ci.yml` before and after the change, and none new.

---

## 🐛 fix(ci): weekly digest reported "(0)" for every metric; make both digests reliable (2026-09-28)

**Repo:** EDDI (`fix/weekly-digest-durable-baseline`)

### What happened

The Monday Slack digest showed every delta as `(0)`: pulls, stars, forks, views
and clones. The run logs show why. The Actions cache entry that held the
week/day baselines had been evicted; the repository cache sits at its 10 GB
cap, so this is routine. A cold-start run at 14:55 UTC reseeded both baselines
to the current values. The Monday 07:00 cron, delayed by GitHub to 15:03, then
found a baseline that was non-zero but only eight minutes old. The existing
sanity check only catches a baseline of **0**, so it could not catch a baseline
of "now". The real week (the 2026-09-21 row against 2026-09-28's) was +1,170
pulls, +4 stars and +10,329 clones.

Reviewing the workflow against "both digests must always work" turned up more:

- **A dropped cron lost the digest.** Each digest hung off one `0 7 * * *` /
  `0 7 * * 1` event, and GitHub delays and drops scheduled runs. The workflow's
  own comments record a 9.6-hour gap, and today's event arrived 8 hours late.
- **A zero could enter the history.** On a cache miss, a Docker Hub blip
  carried forward `0`, and `Persist metrics history` wrote it as the day's row.
  Used as a baseline, that row would read "+400,871 pulls". The GitHub stats
  step had no guard at all.
- **Daily windows overlapped.** The digest compared live values against a
  row, giving a window of about 31 hours that overlapped the next day's.
- **A failed history fetch was read as "no branch"** and started a fresh
  orphan branch.
- **Nothing stopped a double send, and a failed Slack post was never
  retried.**

### What changed and why

All in [`docker-pull-notify.yml`](../.github/workflows/docker-pull-notify.yml):

- **Digests are computed from the `metrics` branch only**, row to row. Daily:
  yesterday's row to today's. Weekly: the previous Monday's row to this
  Monday's. Rows are first-run-of-the-day snapshots, so windows are contiguous
  and a re-run gives the same numbers. A missing past day falls back up to two
  days, bad (zero) rows are skipped, and the message names the dates actually
  compared. A window ending today waits for today's row instead of ending
  early.
- **No digest cron.** Any 15-minute run sends a digest once it is due (daily
  from 07:00 UTC, weekly from Monday 07:00 UTC). A new `data/digests.json` on
  the `metrics` branch records the last window sent, so each digest goes out
  exactly once, however late the first run arrives. A Monday with no runs at
  all is caught up on Tuesday.
- **Claim, send, release.** The marker is pushed *before* the Slack post and
  reverted if the post fails. A failed push therefore sends nothing, a failed
  post is retried by the next run, and a stuck push can never re-post the
  digest every 15 minutes. An unreadable marker file stops all digests with an
  error, rather than reading as "never sent". Without `SLACK_WEBHOOK_URL`
  nothing is planned or claimed, so the digests stay due and go out once the
  secret is set, instead of being recorded as sent.
- **Only fresh values become history.** The Docker Hub and GitHub stats steps
  report `ok=false` when they carry a value forward, and `Persist` then leaves
  the day's row to a later run. The GitHub stats step gets the same
  digits-only guard as Docker Hub. `git ls-remote` now tells a missing branch
  (create it) apart from an unreachable one (fail).
- The cache now holds only the previous run's values for the 15-minute
  analytics delta and the milestone marker; the `week_*`/`day_*` fields and
  the zero-baseline heuristics are gone.

After merge there are no markers yet, so both digests are due at once: the first
run past 07:00 UTC that finds that day's history row sends the daily and the
weekly for 2026-09-21 → 2026-09-28 with the real numbers. The row is written
only from fresh, numeric metrics; until a run writes it, the digests wait for a
later run instead of going out early.

Verified locally: actionlint/shellcheck clean apart from the pre-existing
SC2129 style notes; the plan step under 11 fake clocks and histories (normal,
already sent, not yet due, dropped Monday, today's row missing, zero row, no
activity, forced, empty history, corrupt markers, no webhook); both Slack payloads parsed
as JSON; persist and claim end-to-end against a bare copy of the branch
(append, same-day no-op, not fresh, unreachable remote).

---

## 🎨 fix(ui): one icon per meaning across the Manager and the Chat UI (2026-09-28)

**Repo:** EDDI (`fix/ui-icon-semantics`)

A review of every icon in both UIs — 208 distinct lucide icons in the Manager, and the Chat UI's emoji and Unicode glyphs — against what each one stands for where it is used. It started from two sidebar entries (Active Conversations and Coordinator) sharing the Activity pulse, and found that pattern repeated, plus three places where an icon lookup was silently falling back.

### Defects, not taste

- **Resource-type icons came from six tables that disagreed.** The resource card knew six of the ten types and fell back to `GitBranch`, so every RAG, MCP, snippet and parser card on a list page was drawn as a rule set. The Resources grid's own map lacked snippets and parsers. The pipeline drew parser, behavior, workflow, dictionary and RAG as the same page glyph. The detail page aliased RAG to the dictionary's book. All of them now resolve through one table, [`resource-type-icons.ts`](../ui/manager/src/lib/resource-type-icons.ts); `ResourceTypeConfig.icon` (a string key into those maps) is gone.
- **The chat activity panel never showed a step's icon or colour.** SSE `task_start`/`task_complete` carry the task's `getType()` (`langchain`, `behavior_rules`, `httpCalls`, …), and the row looked that up as an `eddi://` extension id. It never matched, so every step rendered as the unknown type in grey. The new `extensionTypeForTask` in [`extensions.ts`](../ui/manager/src/lib/api/extensions.ts) maps the backend's runtime types (read from the task classes, not from the mocks, which use `ai.labs.*` ids) to extension types.
- **The Audit trail keyed its badges on `behavior`, `httpcalls` and `propertysetter`**, which the backend never sends, so those entries all showed the default `#`, and MCP and RAG had no entry. Badges now resolve through the same function; the colours stay on the page, and the icons come from the shared table.

### Meaning collisions resolved

| Where | Was | Now | Why |
|---|---|---|---|
| Active Conversations | `Activity` | `Radio` | Shared its icon with Coordinator. `Radio` already means "live" on the Logs page |
| Coordinator | `Activity` | `Network` | The queue / message bus |
| Approvals, `AWAITING_HUMAN`, every HITL badge | `HandMetal` (🤘) | `Hand` | A raised palm: waiting on a human. The Chat UI's paused card uses the same |
| Caller-supplied credential | `Hand` | `UserRoundKey` | Frees the hand for HITL |
| Groups | `Boxes` in the nav, `Users` on cards and detail | `Users` everywhere | One concept, one icon |
| User Data | `Users` | `UserRoundSearch` | Would have collided with Groups |
| Workforce mode | `Users` | `Briefcase` | The mode switcher sits in the same sidebar as Groups |
| Standing-team workspace | `Boxes` | `SquareKanban` | It is the team's work board |
| Sync | `RefreshCw` | `ArrowRightLeft` | `RefreshCw` is the Refresh button in 42 files. The Sync page's own header already used this icon |
| Capabilities | `Layers` | `Blocks` | `Layers` stays with model cascade |
| GDPR / Privacy | `ShieldAlert` | `ShieldUser` | `ShieldAlert` is the generic "dangerous request" warning |
| Language picker | `Globe` | `Languages` | `Globe` is API calls |
| User memories | `Brain` | `NotebookPen` | `Brain` is the LLM |
| Properties | `Settings` / `Database` | `Tags` | A gear read as "settings" |
| Dictionary | `BookOpen` | `BookA` | `BookOpen` is the Documentation link |
| MCP calls | `Plug` | `ServerCog` | `Plug` is Connections |
| Agent status `NOT_FOUND` | `Square` | `CircleDashed` | The square is the Stop button |
| Wizard steps | Identity `Brain`, Model `Settings2`, Review `Rocket`, Template `Sparkles` | `IdCard`, `Brain`, `ClipboardCheck`, `LayoutTemplate` | Each step says what it is. `Rocket` stays on the Deploy action |
| Studio / debugger "Pipeline" | `GitBranch` | `Workflow` | `GitBranch` is Rules |

Near-duplicate variants were unified where they meant the same thing: `CheckCircle`→`CheckCircle2`, `Users2`/`UsersRound`→`Users`, `User2`→`User`, `Edit3`→`Pencil`, `Cog`→`Settings`.

### Chat UI: emoji replaced by lucide-react

The widget used 📎 ⏳ 🔒 🔓 👁 🧠 💬 ⏸ next to ◑ ■ ↓ ➤ ▶ ↩ ↪ ↻. Emoji render in colour and differently on every OS, so they ignored the widget's theme colours. The "hidden" eye, 👁‍🗨, is an eye in a speech bubble rather than a crossed-out eye. Send was ➤ in one input and ▶ in the other, and "New conversation" used ↻, which reads as retry. These are now `lucide-react` icons at `size="1em"`, so they follow each control's existing responsive font-size and `currentColor`. The eye shows the action (open eye = reveal), matching its `aria-label`. New conversation is `MessageSquarePlus`, the same as in the Manager. Cost: +9.6 kB raw, **+1.6 kB gzipped**. The lockfile gained only the lucide entries: npm's own rewrite also reshuffled unrelated `peer` flags and line endings, so it was restored and edited by hand, and `npm ci` accepts it.

### Deliberately unchanged

- **Discussion-style and group-template emoji** (🗣️ 😈 ⚖️ …) stay. They are illustrations that tell seven styles apart at a glance, not UI controls, and they appear inside prose strings.
- **`Sparkles`** stays on the Platform Operator and on "AI / auto" affordances. That is the convention, and nothing in the sidebar shares it.
- **The 📎 and ⚠️ inside chat message text** stay. They are message content, and the withdraw-and-restore logic reads them.
- **Deprecated lucide aliases** (`AlertTriangle`, `Loader2`, …) were not mass-renamed. They still resolve in 0.577, and renaming ~100 files would change no pixels and only conflict with open branches.

### Guards

- [`sidebar-icons.test.tsx`](../ui/manager/src/components/layout/__tests__/sidebar-icons.test.tsx): no two sidebar destinations may share an icon.
- [`resource-type-icons.test.ts`](../ui/manager/src/lib/__tests__/resource-type-icons.test.ts): the icons in the shared table are distinct, every `RESOURCE_TYPES` slug is covered, and `"constructor"` does not resolve.
- [`chat-activity-icons.test.tsx`](../ui/manager/src/components/chat/__tests__/chat-activity-icons.test.tsx) and the new `extensionTypeForTask` cases: real backend task types resolve to their icon and colour.

Each guard was mutation-checked: it fails with the old icon or the old lookup put back, and passes on the fix.

**Verification:** Manager — typecheck, lint (0 warnings), **6,875 tests / 429 files**, i18n check. Chat UI — typecheck, **278 tests**, production build. Checked in a browser against MSW mock data: all 26 sidebar entries report distinct icons in the live DOM (collapsed and expanded), the Resources grid shows ten distinct type icons, and the Chat UI composer, secret mode, header and empty state render in the theme colour. The icons that only appear mid-conversation (undo/redo, stop, paused card, thinking indicator) were covered by the component tests only, not checked visually, because that needs a backend.

---

## 🔒 fix(engine): authorize managed-agent calls before creating conversations (2026-09-27)

**Repo:** EDDI (`fix/managed-agent-auth-first`)

### What changed and why

`GET` and `POST /agents/managed/{intent}/{userId}` (`loadConversationMemory`,
`sayWithinContext`) resolved the caller's managed conversation first and checked
the caller afterwards. Resolving it has side effects: when no conversation exists
it starts one in the engine and stores a `UserConversation`, and when the existing
one has ended it deletes that `UserConversation` and creates a replacement. Only
then did `checkUserAuthIfApplicable` throw `UnauthorizedException` for an
anonymous caller (OIDC on, non-production environment). An anonymous request
could therefore create or replace a conversation and still get a 401.

That ordering is why the Manager's `ApiClient` stopped replaying 401'd writes in
PR #855 (`fix/manager-shell`): its retry assumed a 401 meant the handler never
ran, and this endpoint was the counterexample. The client-side guard stays; this
is the server-side fix.

`initUserConversation` now authorizes before each side effect:

- **No conversation yet:** the deployment is picked from the intent's trigger
  first (`selectAgentDeployment`), the caller is checked against that
  deployment's environment, and only then is the conversation started, with
  that same deployment. The random pick among a trigger's deployments happens
  once, so the check and the start cannot disagree on the environment.
- **Ended conversation:** the same, before the delete. The replacement's
  environment decides, not the ended conversation's, because the replacement
  is what the request acts on. That matches what the old code eventually checked.
- **Live conversation:** checked against its stored environment. Only reads (the
  `UserConversation`, the engine's conversation state) come first.
- A final check on the conversation actually returned still runs. It also
  covers a conversation that a concurrent request stored first (the
  `ResourceAlreadyExists` fallback).

`sayWithinContext`'s generic `catch (Exception)` turned the
`UnauthorizedException` into an opaque **500**. It now calls
`response.resume(e)`, which RESTEasy Reactive routes through the exception
mappers (`AsyncResponseImpl.resume(Throwable)` calls `handleException`). So the
POST answers **401**, exactly as the GET's throw does.

`endCurrentConversation`, `undo`/`redo` and `isUndoAvailable`/`isRedoAvailable`
already read, then checked, then acted, so they are unchanged.

### Behaviour for authorized callers

Authorized callers see the same calls, the same creation order (delete, engine
start, stored `UserConversation`) and the same responses. One call moved: when an
ended conversation is replaced, the trigger is now read *before* the delete
rather than after it. So a missing trigger now fails without deleting the ended
`UserConversation`, where before it deleted it and then failed. The error itself
is the same: the store's not-found exception for the GET, the opaque 500 for
the POST. Every later request fails the same way either way.

### Residual

- **Race path:** if a concurrent request stores its conversation between this
  request's start and store, this request's engine conversation is orphaned
  (as before), and the final check runs against the other request's environment.
  A 401 there follows an engine start. This needs a trigger with mixed
  environments and a concurrent anonymous request, and the stored
  `UserConversation` is untouched.
- The HTTP layer normally rejects anonymous callers first. `IRestAgentManagement`
  is `@RolesAllowed` and the catch-all path policy is `authenticated`, so this
  in-method gate is defence in depth. It is reached when
  `authorization.enabled` or the path policy has been loosened.

### Tests

`RestAgentManagementExtendedTest` has a new nested class, "Authorization before
side effects", with 20 cases. Anonymous callers with OIDC on and a
non-production environment get a 401 with no `createUserConversation`,
`deleteUserConversation` or engine start, for both methods, with no existing
conversation and with an ended one. Authorized callers are parameterized over
authenticated/test, anonymous/production and OIDC-off, for both methods and
both the create and replace paths, and each is checked for call order. The
missing-trigger error path is also covered. The mutation check was run three
ways. Removing the two pre-creation checks fails the 4 create/replace
rejection tests. Removing the `resume(e)` branch fails the 3 POST rejection
tests. The original class fails 5, and every authorized-path test passes
against it.

**Files:** [`RestAgentManagement.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestAgentManagementExtendedTest.java`](../src/test/java/ai/labs/eddi/engine/internal/RestAgentManagementExtendedTest.java),
[`security.md`](security.md) (RestAgentManagement Gate: the check now runs before any side effect)

---

## 🐛 fix(engine): start managed conversations with a per-request copy of the trigger context (2026-09-27)

**Repo:** EDDI (`fix/managed-agent-auth-first`, second commit)

### What changed and why

When `RestAgentManagement.createNewConversation` started a managed conversation, it
wrote the caller's language straight into `agentDeployment.getInitialContext()`.
That deployment belongs to the `AgentTriggerConfiguration` returned by
`RestAgentTriggerStore.readAgentTrigger`, which is the instance held in the
shared `agentTriggers` cache, not a copy. Its `initialContext` is a plain
`HashMap`. As a result:

- concurrent requests raced on one map, so a conversation could start with
  another user's language;
- a request without a language replaced an earlier value with a null-valued
  `lang` context;
- the `HashMap` was written from several request threads without
  synchronization;
- the cached trigger drifted from the stored one until its cache entry was
  replaced.

Each request now copies the deployment's context
(`new HashMap<>(initialContext)`, or an empty map when the deployment has none),
puts `lang` into the copy, and passes the copy to
`startConversationWithContext`. The trigger in the cache is never written to.

**Null language:** kept as it was for the engine. With no language, the engine
still receives a `lang` entry of type `string` whose value is null. Leaving the
key out would change what a started conversation sees in its context. What was
wrong was writing that entry into the shared map, not sending it.

A deployment whose `initialContext` is null (possible for a stored trigger that
omits the field) used to throw an NPE on the `put`. It now starts the
conversation with a map that holds only `lang`.

### Tests

The new nested class "Trigger context is copied per request" in
`RestAgentManagementExtendedTest` covers five paths:

- `loadCreate`, `sayCreate` (language taken from the input context), and
  `loadReplaceEnded`;
- `nullLanguage`: the engine still receives a null-valued `lang`;
- `nullInitialContext`.

Each test asserts two things:

- the deployment's `initialContext` equals its value before the call and has no
  `lang` key;
- the map captured on `startConversationWithContext` is a different instance,
  still carries the designer-set entry, and has `lang` with the expected value.

**Mutation check:** restoring the in-place `put` fails all five tests. Four fail
on "the cached trigger's context was modified", and `nullInitialContext` fails
with the NPE.

**Files:** [`RestAgentManagement.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestAgentManagementExtendedTest.java`](../src/test/java/ai/labs/eddi/engine/internal/RestAgentManagementExtendedTest.java)

---

## 🐛 fix(manager): import dialog kept the old instance's agents after the sync source changed (2026-09-27)

**Repo:** EDDI (`fix/manager-import-sync-reset`)

### Why

The Import dialog's "Sync from remote instance" path wired the URL and token
fields straight to their setters. The remote agent list, the source agent and
version picked from it, the local target and any preview built on them were
only cleared when the dialog closed. After connecting to instance A, editing
the URL or the token left A's agents on screen with "Preview Changes" still
armed, so A's agent could be previewed and imported against instance B.

The Sync page already handles this on the ops-pages branch (`handleSourceChange`
in `sync-page.tsx`). The dialog did not.

### What changed

[`import-agent-dialog.tsx`](../ui/manager/src/components/agents/import-agent-dialog.tsx):
a URL or token edit now goes through `handleSyncSourceChange`, which mirrors the
Sync page. It applies the edit, then, if anything was derived from the previous
connection, clears the remote agent list, source agent and version, sync target,
the preview and the state built from it (selection, expanded diff, workflow
order), and the step error. It also resets the sync preview and execute
mutations. As on the Sync page, nothing is reset when there is nothing to drop.

Resetting the preview mutation also detaches a preview that is still in flight.
In TanStack Query v5, `reset()` removes the observer, so that request's
per-call `onSuccess` no longer fires, and a reply for the old source cannot move
the dialog onto the preview step.

[`sync-config-panel.tsx`](../ui/manager/src/components/agents/sync-config-panel.tsx):
the connect request the panel sends is now tied to the URL and token it was sent
with. Each edit starts a new generation, and a reply or error from an older one
is dropped. Without this, a connect reply that arrived after an edit filled the
list the dialog had just cleared with the old instance's agents. This hunk is
ported byte-for-byte from the ops-pages branch (#854), together with its
panel test, so the two branches merge cleanly in either order and neither has
to wait for the other.

**Tests:** [`import-agent-dialog-sync-source.test.tsx`](../ui/manager/src/components/agents/__tests__/import-agent-dialog-sync-source.test.tsx)
runs against the real hooks and the MSW sync handlers. The sibling test file
mocks the whole backup hook module. The new tests:

- A URL edit and a token edit after connect, pick and preview each drop the
  list and the selection. After reconnecting, the source and target selects
  come back empty and "Preview Changes" stays disabled.
- A preview still in flight when the URL changes does not land.
- A connect reply still in flight when the URL or the token changes does not
  land.

The in-flight tests hold the MSW reply behind a gate that exists from the start.
They wait until the request has reached the handler before editing, and until
the reply has left it before asserting, so the stale reply is really delivered
and the test cannot pass on a reply that was never sent.

Mutation check:

- Reverting the dialog fix fails the three dialog-state tests.
- Reverting the panel guard fails both connect tests.
- Dropping only the preview mutation reset fails the in-flight preview test.

---

## 🔒 fix(security): a deleted conversation no longer opens to every caller; `/active` and `/end` gated (2026-09-26)

**Repo:** EDDI (`fix/conversation-access-guard`)

### What changed and why

**C1a: soft delete bypassed the conversation owner check.** `ConversationAccessGuard.requireConversationOwner`
returned `null` ("allowed") whenever the live descriptor was missing. A soft delete (the default of
`DELETE /conversationstore/conversations/{id}`, and what the Manager's delete does) archives the descriptor
but keeps the memory snapshot, so from then on any authenticated caller could read the raw memory, run turns
as the owner over `POST /agents/{id}` (longTerm memory written under the owner's id), use SSE, the tool
history, the MCP conversation tools and the audit trail, and permanently delete the conversation.

- The guard now resolves the owner from the live descriptor and, if that is gone, from the archived copy
  (`readDescriptorWithHistory`). A soft-deleted conversation is owner-checked exactly like a live one.
- With no descriptor at all (live or archived), only an admin is let through (so an operator can still reach
  an orphaned snapshot or the audit trail of a deleted conversation). Everyone else gets a 404. With
  authorization disabled `isAdmin` is true, so nothing changes there.
- `requireExistingConversationOwner` resolves through the same method, so the two variants cannot drift.
- Soft delete now **ends** the conversation first, through `IConversationService.endConversation`, so a
  paused conversation's approval is resolved (attributed to the deleting caller, `system:delete` if there is
  no named one) and an in-flight turn does not write back. It also records ENDED on the descriptor before
  archiving it.
- **Conversations soft-deleted by earlier releases** are still READY. The conversation-id `say` and
  `sayStreaming` entry points (REST, SSE, MCP, Slack, `/v1`) now check for them: a conversation with no live
  descriptor but an archived one is ended on the first turn attempt and refused like any ended conversation.
  This is a lazy migration, at the cost of one descriptor read per turn on those entry points. The internal,
  agent-driven overloads (group members, schedules, A2A) are not checked.
- The retention sweep read "no live descriptor" as "orphan, delete now". Soft-deleted conversations now reach
  it as ENDED, so it checks the archived descriptor's age as well: they age out on the normal schedule, and an
  archive with no date is treated as expired. Aged-out soft deletes are counted in the sweep's total.
  Snapshots with no descriptor anywhere are still removed straight away (and not counted).
  **Retention effect:** a conversation ended and then soft-deleted used to be purged by the next daily sweep.
  It now stays until `deleteEndedConversationsOnceOlderThanDays` (default 365) has passed since its last
  interaction. Documented in `configuration-reference.md` and `gdpr-compliance.md`.
- **Stale managed-conversation mappings.** A permanently deleted or swept conversation leaves its
  intent→conversation mapping behind (only GDPR erasure removes it). The guard's new 404 escaped MCP
  `chat_managed` before the mapping was dropped, which failed every later call for that user. It now counts as a
  stale mapping: the mapping is deleted and a new conversation started. The REST twin
  (`RestAgentManagement.isConversationEnded`) failed on a missing conversation before this branch; it now
  recreates as well.
- The MCP conversation tools answer a guard 404 with `"Conversation not found"` and log it at debug. Before,
  it fell into the generic handler, which logged an ERROR with a stack trace for every probed id.

**NEW (audit trail readable after delete).** Audit entries are not deleted with a conversation, and the MCP
`read_audit_trail` and `read_agent_logs` (conversation-scoped) tools passed the missing descriptor.
Closed by the guard change: a non-admin gets "not found", while an admin can still read them for compliance.
The ledger is append-only by design (EU AI Act), so the entries are guarded, not deleted. The REST
`/auditstore` is already `eddi-admin` only.

**C1b: `/active` and `/end` had no role.** `GET /conversationstore/conversations/active/{agentId}` and
`POST …/end` let any authenticated principal (even one without an `eddi-*` role) list every user's open
conversation ids and end any of them. `/end` trusted the client's `conversationState`. A paused
conversation sent as `READY` skipped the HITL cleanup. Any conversation sent as `AWAITING_HUMAN` wrote a
forged `hitl.approval` cancellation to the audit trail.

- Both now take `@RolesAllowed({"eddi-admin", "eddi-editor"})` plus EDIT access on the agent. That is the
  same gate as undeploy, which already ends every active conversation of an agent. **The EDIT check is enforced
  only with workspaces on (`eddi.workspaces.enabled=true`, off by default).** Without it, the role is the
  whole gate and any editor can list and end any agent's open conversations. That is the reach
  undeploy-with-end already gives an editor, and a listed id grants nothing else, because every per-conversation
  endpoint is owner-or-admin.
- `/end` reads only the conversation ids from the request. It reads each conversation's state through the
  state projection and its agent from the conversation descriptor (live or archived), without loading the
  memory snapshot. Only a conversation with no descriptor at all falls back to the snapshot.
- **Authorization is all-or-nothing:** EDIT is checked for every conversation's agent before anything is
  ended. **Ending is per conversation and continues on error:** unknown and already ENDED ids are skipped.
  Every other conversation goes through `endConversation`, which decides server-side whether a pause is being
  terminated and attributes it to the calling principal (`system:admin-end` only if there is no named caller).
  The response body lists `ended`, `skipped` and `failed`, with status 200, or 500 if anything failed.
  Marking the descriptor ENDED is best-effort, since the listing re-derives the state from the snapshot.
  Undeploy-with-end now refuses to undeploy when the end reports a failure. The old raw
  `setConversationState(ENDED)` for non-paused conversations also skipped the in-flight signal and the state
  cache. A null body is a 400.

**NEW (soft-deleted active conversation broke undeploy).** `getActiveConversations` read the live descriptor
of every open conversation and threw NotFound for one that had been soft-deleted while open. That failed the
listing, and undeploy-with-end with it (500), and any user could trigger it with their own conversation. It
now falls back to the archived descriptor (`lastInteraction` null if neither exists). `endActiveConversations`
likewise ends a soft-deleted conversation.

**C1c: ownerless snapshot after a failed start.** `startConversation` stored the memory, then wrote the
descriptor (which records the owner). If that write failed, the snapshot stayed with no owner on record. The
start now discards the snapshot, the state-cache entry and any armed HITL timeout before rethrowing. The guard
change also denies non-admins any such snapshot.

**M-E5 plus NEW: 500 instead of 404.** The conversation-id overloads of `undo`, `redo`, `isUndoAvailable` and
`isRedoAvailable`, `PATCH /agents/{id}/state` (`resetState`) and `GET /llm/tools/history/{id}`
dereferenced the null snapshot the store returns for an unknown id. They now answer 404.

### Decisions

- **`/active` and `/end` are editor + agent EDIT, not admin-only** as the review suggested. Undeploy
  (`eddi-admin`/`eddi-editor` + EDIT) already ends all of them, and calls these methods in-process. The
  Manager's conversation-monitoring page calls both, and editors should keep it. The real hole was "no role at all" plus trusting client state.
- **Admins keep access to descriptor-less conversations.** The guard gives them `null`, so an operator can
  still inspect orphans and the audit trail of a deleted conversation. `requireExistingConversationOwner`
  (attachments) stays a 404 for admins too, as before.
- **Owners keep read access to their soft-deleted conversations.** The snapshot is kept on purpose, and it is
  now ENDED, so it can be read but not continued.

**Files:** [`ConversationAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/security/ConversationAccessGuard.java),
[`RestConversationStore.java`](../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java),
[`IRestConversationStore.java`](../src/main/java/ai/labs/eddi/engine/memory/rest/IRestConversationStore.java),
[`ConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`RestAgentEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`RestToolHistory.java`](../src/main/java/ai/labs/eddi/modules/llm/rest/RestToolHistory.java),
[`McpConversationTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`RestAgentManagement.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestAgentAdministration.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java),
[`gdpr-compliance.md`](gdpr-compliance.md), [`configuration-reference.md`](configuration-reference.md).
Regression tests are in `ConversationAccessGuardTest`, `RestConversationStoreTest`, `RestAgentEngineTest`,
`McpConversationToolsOwnershipTest`, `McpConversationToolsTest`, `ConversationServiceTest`,
`RestToolHistoryTest`, `RestAgentManagementExtendedTest` and `RestAgentAdministrationTest`.

---

## 🐛 fix(gdpr): erasure reaches connection grants and in-flight work; `_gdpr_` keys are reserved (2026-09-26)

**Repo:** EDDI (`fix/gdpr-erasure`)

Three gaps in the GDPR framework, from the 2026-09-25 code review (H9a, H9b, H9c).

### H9a: connection grants were outside erasure and export

OAuth connection grants hold a live refresh token for the user's account at a third
party. `deleteUserData` never touched them, so an erased identity kept a working
credential, and `exportUserData` did not list them.

- `IConnectionGrantStore` gains `findAllByPrincipal` and `deleteAllByPrincipal`, which
  span every tenant, like the rest of the cascade. They are implemented in Mongo (plus
  a new `idx_grant_principal` index) and Postgres (plus a new `idx_cg_principal`).
- The cascade adds a `connectionGrants` step, and `GdprDeletionResult` adds
  `connectionGrantsDeleted`, which also appears in the MCP `delete_user_data` payload.
- The export adds `connectionGrants`, the linked accounts as metadata only (tenant,
  connection, status, scopes, dates). Fields are copied one by one, so no token
  ciphertext or IV can reach the bundle.
- Not done: EDDI does not revoke consent at the provider. The docs say the user can
  revoke it there.

### H9b: in-flight work rewrote erased data

A turn running during an erasure wrote its longTerm properties back at teardown.
`UserMemoryTool` writes mid-turn. A running group discussion upserted its document on
the next phase, so the transcript came back.

- New SPI `UserErasureParticipant`, found through CDI like
  `SealedDataRotationParticipant`. The cascade calls it first, as step 0.
  `ConversationService` cancels this node's in-flight turns for the user, using the
  cooperative cancel flag. That flag already skips the longTerm write-back and discards
  the snapshot. `GroupConversationService` sends `CANCEL_IMMEDIATE` to the user's running
  discussions. If a participant throws, the step is recorded as failed
  (`inFlightConversations` / `runningGroupDiscussions`).
- `UserMemoryTool` refuses writes once its turn is cancelled
  (`ContextualToolsProvider` passes `memory::isCancelled`).
- `GroupConversationStore.update` no longer creates the document. It replaces an
  existing row via `storeIfCurrentVersion(SINGLE_VERSION)` and throws
  `GroupConversationGoneException` when the row is gone. This covers discussions on
  other replicas, and the plain delete endpoint as well as erasure.
- The cascade re-sweeps user memories at the end (step `userMemoriesResweep`). A step-0
  signal cannot stop a write that is already in progress, or one made by a turn on
  another replica.
- Residual: a turn on another replica that finishes after the re-sweep can still
  upsert its longTerm properties. Conversation snapshots already refuse to recreate a
  deleted document. Closing this fully needs a cross-node erasure tombstone.

### H9c: the Art. 18 flag could be forged, overwritten or deleted

`isProcessingRestricted` read the first row under `_gdpr_processing_restricted`,
whatever its category. So a model calling `rememberFact` could lock its own user out
with a GDPR 403. A global `rememberFact` or a REST `mergeProperties` overwrote the admin's
row in place, because global rows are keyed on `(userId, key)`. REST/MCP entry deletes,
REST `deleteProperties`/delete-all, and whole-set Dream pruning could all lift a
restriction without the audited endpoint. `unrestrictProcessing` removed only one row.

- `IUserMemoryStore.upsert` and `mergeProperties` refuse reserved keys in both backends,
  with `ReservedMemoryKeyException` (an `IllegalArgumentException`). The only write path
  for them is the new `upsertReserved`, used by `GdprComplianceService`.
- `isProcessingRestricted` counts only rows in the `gdpr` category, and considers every
  one of them. `unrestrictProcessing` deletes every row under the key.
- `UserMemoryTool` (`rememberFact`/`forgetFact`), `RestUserMemoryStore` (upsert/delete →
  400), `RestPropertiesStore.mergeProperties` (400) and `McpMemoryTools` refuse
  reserved keys. The delete-all surfaces now use `deleteAllExceptReserved`, and the
  store's `deleteProperties` keeps reserved rows. The MCP `delete_all_user_memories`
  description now says so. Dream never maintains reserved keys. A reserved longTerm
  property is skipped at teardown with a WARN rather than failing the turn. The legacy
  properties migration skips reserved keys instead of counting them as failures.
- Postgres retention and `deleteProperties` now escape the LIKE underscores. The old
  `'_gdpr_%'` also matched keys such as `agdpr1`, and the retention sweep never pruned
  those.
- Save-time rejection of a property-setter config that names a `_gdpr_` key was not
  added. It is skipped at runtime.

### Pre-push review follow-up

- **Deleted mid-run.** A running discussion whose document is deleted mid-run now ends
  as a cancel. `executeDiscussion` handles `GroupConversationGoneException`, also when
  it arrives wrapped, from any write inside the leg. The R2 cancel branch tolerates a
  document that is already gone. The listener gets `onCancelled` rather than
  `onGroupError`, and the leg no longer logs an ERROR, bumps the failure metric or
  throws a 5xx. Previously the second `update()` inside the catch threw Gone again and
  left SSE streams hanging. `GroupLifecycleOps.failConversation` has a corrected
  comment.
- **Late audit entries.** A cancelled turn still flushes its audit buffer while it
  unwinds. `AuditLedgerService.markUserErased` makes this node write the user's
  pseudonym for one hour, both at submit and when queued entries are drained. The
  v3 HMAC still verifies, because it covers the identity token, not the raw id.
- **Pending OAuth flows.** `IOAuthStateStore.deleteByPrincipal` (Mongo, Postgres)
  runs as step `oauthStates`, before the grants are deleted. A late callback can no
  longer mint a grant for an erased principal.
- **Signalling continues past errors.** `GroupConversationService.stopInFlightWork`
  keeps signalling after a store read error and reports the failure once, at the end.
- **Stricter restriction check.** A row counts as an Art. 18 restriction only if it
  also has no `sourceAgentId`, which is the shape only `restrictProcessing` writes.
- **`__service__` is refused.** Erasure and export reject this system principal:
  400 on REST, an error on MCP, and an `IllegalArgumentException` in the service.
- **LIKE escape character.** The Postgres LIKE escape is now `!`, which does not
  depend on `standard_conforming_strings`.
- **Access check before key check.** REST `upsertMemory` runs the ownership check
  before the reserved-key 400.
- **Docs.** Stale "GDPR delete-all" wording in `IRestUserMemoryStore` (OpenAPI),
  `docs/user-memory.md` and `docs/mcp-server.md` is fixed, and the step numbering in
  `docs/gdpr-compliance.md` is corrected.
- **Remaining residuals.** A turn or callback still in flight on another replica is
  not covered. Neither is a callback that claimed its state before step `oauthStates`
  and stores its grant after step `connectionGrants`, a window the length of one token
  exchange.

### CodeQL follow-up

- **Two `java/sensitive-log` alerts in `GroupHitlCoordinator.cancelDiscussion`.**
  The erasure sweep (`GroupConversationService.stopInFlightWork`) walks the keys of
  the map of running discussions, then named `activeTokens`. CodeQL's heuristic reads
  any variable whose name contains "token" as a credential, so each key it yielded
  counted as a secret, and the two log lines in `cancelDiscussion` that print the
  conversation id became alerts. The keys are group-conversation ids and the values
  are cooperative stop flags (`DiscussionControlToken`); nothing secret is logged.
  The map is renamed `discussionControls` in the five classes that share it (the
  reflective test handles follow), which states what it holds and removes the false
  source, instead of suppressing the alert. The two log lines also now pass the id
  through `LogSanitizer`, like the rest of the class. No behaviour changes.
- **`McpMemoryToolsBranchCoverageTest` still expected the old delete.** Two of its
  cases stubbed `countEntries` and verified `deleteAllForUser`, which
  `delete_all_user_memories` no longer calls since it switched to
  `deleteAllExceptReserved`. They now stub and verify that method, and assert that
  `deleteAllForUser` is never called from this surface.

### Compatibility

- REST/MCP shapes only gain fields: `connectionGrantsDeleted`, and `connectionGrants`
  in the export. The old constructors of both records remain.
- Writes of `_gdpr_*` keys through the memory and property REST/MCP endpoints now
  answer 400 or an error, where they used to succeed.
- Stored rows need no migration. Pre-existing forged rows outside the `gdpr` category
  are ignored by the check and removed by unrestrict.

### Overlap

H14b on `fix/group-conversation-state` also changes group-discussion delete and
resurrection. This branch makes only the store-level non-upsert change and the erasure
cancel, and leaves delete-endpoint cancel tokens and CAS writes to that branch.

---

## 🐛 fix(manager): align the Manager's API client and MSW mocks with the real backend contract (2026-09-26)

**Repo:** EDDI (`fix/manager-api-contract`) — Manager only (`ui/manager`), no backend change.

The Manager's suite was green while a dozen calls disagreed with the backend: the
MSW mocks encoded the same wrong contract as the app. This branch fixes the app
side of each mismatch and makes the mocks answer the way the backend does, so the
tests would now fail on a regression.

### What changed and why

- **Undo / redo (UI review High 1).** `POST /agents/{id}/undo|redo` answer an
  empty 200 (moved) or 409 (nothing to move); the client typed them as returning a
  snapshot, so the hook threw on `undefined` *after* the server had undone the
  step and every further click undid another turn. `undoConversation` /
  `redoConversation` now resolve `true`/`false`, and the hook re-reads the whole
  conversation (`returnCurrentStepOnly=false`) for the transcript and both flags,
  ignoring the read if the user switched conversation meanwhile. Undo availability
  is now set after every send (streaming `done` and non-streaming) from the
  backend's `undoAvailable`, with a `steps > 1` fallback (was `> 0`, which offered
  Undo on a fresh conversation). Tests: `chat.test.ts`, `use-chat.test.tsx`
  (`useUndoConversation`, `useRedoConversation`, `undoRedoFlags`, "enables Undo
  once a turn lands").
- **Agents / Workflows lists stopped at 50 (High 2).** The infinite queries sent
  `allPages.length * 50` as `index`; the backend's `index` is a page index
  (`skip = index * limit`). Now `allPages.length`. The sync page and the import
  dialog's target pickers only ever loaded the first page, so they now use
  `useAllAgentDescriptors`, which keeps paging until a short page — past 50
  local agents, sync no longer creates duplicates. Tests: `use-agents.test.tsx`
  ("asks for page 1…", `useAllAgentDescriptors`).
- **Version pickers offered only the latest version (High 3).** They asked the
  store listing `descriptors?filter=id&version=v`, which has no `version`
  parameter. New `getDescriptorVersions(id, latest)` reads
  `/descriptorstore/descriptors/{id}?version=v` per version (bounded concurrency,
  unreadable versions skipped); used by agent, workflow and resource version lists.
  Tests: `descriptors.test.ts`, `use-agents.test.tsx`, `use-workflows.test.tsx`.
- **`manager-user` (High 11).** Group start / follow-up / continue fell back to
  `userId: "manager-user"`, which `OwnershipValidator` rejects with 403 for every
  non-admin (and stamps admins' runs with a synthetic owner). The field is now
  omitted unless a caller names a user, so the backend resolves the signed-in
  caller. Tests: `groups-wave-parity.test.ts` ("discussion owner").
- **Properties page.** `GET /propertiesstore/properties/{userId}` returns raw
  values, not `Property` wrappers, and 204 for a user with none. Types, rendering
  and the empty case fixed; the "Delete all" dialog now says it removes every
  global user-memory entry of the user (11 locales). Tests: `properties.test.ts`,
  `pages/__tests__/properties.test.tsx`.
- **Audit `toolCalls`.** The backend field is a map `{calls: [...]}` of trace
  events; new `auditToolCalls()` normalises it (pairs results with calls, skips
  budget/HITL markers) for the pipeline trace and the prompt viewer. Tests:
  `audit.test.ts`, `prompt-viewer.test.tsx`.
- **Memory Inspector** now sends `returnCurrentStepOnly=false` (backend default is
  `true`, so it only ever showed the last step). Test: `conversations.test.ts`.
- **Validation errors.** `extractErrorMessage` now reads Quarkus's Bean Validation
  report (`violations[].message`, plus the RESTEasy Classic shapes), so `@Valid`
  400s on group saves / discuss / attachment limits show the constraint message
  instead of "Bad Request". Test: `api-client-response.test.ts`.
- **`getToolHistory`** returns a `ToolExecutionTrace` object (`toolCalls`,
  totals), not an array; type and mock fixed. Tests: `tool-metrics.test.ts`,
  `use-tool-metrics.test.tsx`, `use-admin-hooks.test.tsx`.
- **Deployment status at any version.** Every save bumps the version, and the
  per-version status endpoint then says NOT_FOUND although an older version is
  live. `useDeploymentStatuses` and the chat picker now consult
  `GET /administration/{env}/deploymentstatus` (one shared listing per
  environment) and, for an environment with nothing at the asked-for version
  (NOT_FOUND), adopt an older READY version, flagged `deployedVersion`; the card's chip shows `vN` and its deploy toggle still acts
  on the card's own version. Tests: `deployment-environments.test.ts`,
  `use-agents.test.tsx`, `agent-card.test.tsx`.
- **Dashboard counts.** The conversation count asked for 1000 but the backend
  clamps to 100, and "100" was shown as a total. It now asks for 100 and renders
  a page-filling count as "100+" (agents/workflows likewise at 1000). Tests:
  `dashboard.test.ts`, `pages/__tests__/dashboard.test.tsx`.
- **MSW handlers.** Mocks now use page-index pagination, clamp conversation
  listings at 100, return empty 200s for undo/redo, raw property values (204 for
  an empty user), the `{calls}` audit shape, the trace-object tool history, an
  environment deployment listing, and 201 for `POST /backup/import`. 48 shadowed
  duplicate handlers were deleted (a second schedule store, coordinator, audit,
  quota, currentversion, descriptor and tool-metrics copies, the `agents/:id`
  memory-inspector copy, the factory `/descriptors` copy). Four of the removed
  copies were the ones that actually *answered*, so their routes now answer from
  the `backupSyncHandlers` copy instead: `POST /backup/export/:agentId`
  (Location `agent-export.zip`, was `test-agent-1.zip`),
  `GET /backup/export/:filename`, `POST /backup/import/preview` (the current
  `ImportPreview` shape, was a pre-6.x one) and `POST /backup/import` (201 with
  `imported-<timestamp>`, was 200 with a fixed `imported-agent`; a merge still
  lands on `agent1` v2). `openapi-contract.test.ts`'s `KNOWN_DUPLICATE_ROUTES`
  ratchet shrinks from 58 entries to the 10 deliberate `:id` fall-through layers.

### Review follow-up

- **Agent detail Environments panel** now uses `isLiveAtRequestedVersion`: an
  environment live at an older version shows `vN` and offers **Deploy** for the
  page's version, never **Undeploy**. Undeploying a version that is not running
  is accepted by the backend, which then disables all of the agent's schedules
  while the old version keeps serving. Test: `agent-detail.test.tsx`
  ("shows an older live version per environment…").
- **Sync auto-match waits for the whole local list** (`isComplete`); remote
  agents received earlier are matched when it completes, and a failed page shows
  an error with Retry instead of matching a partial list (2 new keys, 11 locales).
  Tests: `sync-page.test.tsx`.
- **`getDescriptorVersions` skips only 404s**; any other error fails the list.
  Test: `descriptors.test.ts`.
- **Undo/redo**: one move at a time (a call while one is in flight is dropped),
  the chat panel disables both buttons while either is pending and toasts
  failures, and a failed re-read after a move disables both buttons (the
  transcript can no longer be trusted). Undo, redo and rerun all ignore their
  result when the user switched conversation meanwhile; binding them to the chat
  store's conversation epoch (fix/manager-chat) is a follow-up. Tests:
  `use-chat.test.tsx`.
- **Deployment fallback adopts only READY** listing rows — an adopted,
  unpolled IN_PROGRESS kept the card's toggle disabled. Documented gap: the
  listing holds the highest deployed version whatever its status, so when that
  one is ERROR an older READY version is not visible (no backend "latest READY"
  listing). Test: `deployment-environments.test.ts`.
- **Deployment fallback replaces only NOT_FOUND, never at the asked-for
  version** (second review). A READY listing row *at* the requested version
  contradicts the exact NOT_FOUND it would replace, so it is stale: undeploying
  v4 set the exact status to NOT_FOUND optimistically while the shared, cached
  listing still said v4 READY, and the card showed v4 live again until the
  refetch. `withAnyDeployedVersion` now takes the requested version and skips
  such rows. And an ERROR at the requested version is kept instead of being
  replaced by an older READY one — it had turned a failed v4 deploy into a green
  production chip and hidden `env-chip-error-*`. The listing is now fetched only
  when some environment is NOT_FOUND. Tests: `deployment-environments.test.ts`,
  `use-agents.test.tsx` (undeploy with a cached listing; ERROR kept),
  `use-chat.test.tsx` (`useDeployedAgents`), `agent-card.test.tsx`.
- **Chat panel serialises undo, redo and rerun with sends** (second review).
  Each of the three finishes by replacing the transcript with a fresh read, so
  while one is in flight the composer is disabled, quick replies are hidden, the
  Retry button is hidden during a move, and Undo/Redo are unavailable during a
  rerun. Before, a send or quick reply issued during an undo raced its re-read.
  Tests: `chat-panel.test.tsx` ("holds every other way…", "offers no undo,
  redo or send while a rerun is in flight").
- **`auditToolCalls`** attaches `tool_error` reasons (budget, quota, HITL cap)
  to the refused call, or lists an orphan refusal. Test: `audit.test.ts`.
- **`useAllAgentDescriptors` has its own cache key**, so the Agents list does
  not inherit (and re-fetch on every invalidation) every page Sync loaded. Test:
  `use-agents.test.tsx`.

### Compatibility

No REST, config or MCP shape changes. Compatible with current `main` and with the
open backend PRs: #840 (workspace scoping) may narrow the deployment listing or
descriptor reads to what the caller can see — the fallback then simply does not
apply; #839 (`pauseId`), #842 (schedules) and #836 (vault adoption) touch
endpoints this branch does not change; #831 does not affect the discuss body.
Group discussions started from the Manager with auth **off** are now owned by
`anonymous` (the backend's default) instead of `manager-user`.

### Not in this branch

The chat picker still de-duplicates agents by name (fix/manager-chat); the
`groups.ts` NUL byte is chore/ci-hardening's. Group/workforce create-flow error
messages benefit from the violation parsing, but their generic-error wrappers
belong to fix/manager-workforce-groups.

---

## 🧪 fix(manager): a passing test file no longer fails UI Manager Checks on a late toast timer (2026-09-26)

**Repo:** EDDI (`fix/manager-toast-timer-race`) — `ui/manager/` test only

### The failure

`UI Manager Checks` sometimes exited 1 while every test passed (426 files, 6858 tests on
PR 847's run 36271130343), on one unhandled error:

```text
ReferenceError: window is not defined
 ❯ resolveUpdatePriority  react-dom-client.development.js
 ❯ dispatchSetState
 ❯ sonner/dist/index.mjs  (the Toast's unmount timer)
This error originated in "src/pages/__tests__/resource-detail-save-not-live.test.tsx"
```

### Cause

`resource-detail-save-not-live.test.tsx` renders `<Toaster duration={600_000} />` and calls
`toast.dismiss()` in `afterEach`, because sonner's toast store is module-global and a toast
left over from one test breaks the next. A dismissed toast is removed only after sonner's
`TIME_BEFORE_UNMOUNT` timer (200 ms), and that callback updates React state. Between tests
the next test keeps the environment alive long enough; after the file's **last** test
nothing waits for it, so whether it fires before or after jsdom is torn down depends on
runner timing. Fired afterwards, React's update-priority lookup reads `window` and throws.

Instrumenting sonner locally showed it plainly: before the fix the last test's timer was
still pending when the file finished (3 started, 2 fired); after it every timer fired with
`window` present (11 of 11, at the real 200 ms and at a widened 800 ms).

### Fix

`afterEach` still dismisses, then waits until no `[data-sonner-toast]` element remains —
which only happens once the unmount timer has run — so the state update lands while jsdom
is up. Nothing else changed. The three other test files that render `<Toaster>` never call
`toast.dismiss()`; their toasts unmount with the component, which starts no removal timer,
so they do not have this race.

### Verification

- The file run 20 times in a row: 20 passed, no "Unhandled Errors" section.
- `npx eslint` on the file and `npx tsc -b`: clean.

---

## 🐛 fix(manager): pages move onto the version a save created (2026-09-26)

**Repo:** EDDI (`fix/manager-version-after-save`) — UI review High 4, plus the Save & Deploy `ERROR` handling

### The problem

Every config PUT creates a new version and names it in `Location`; the backend
refuses a write to a version that is no longer current with a 409. Several
Manager flows ignored that header and kept the version they were opened with,
so the edit appeared to revert on the next refetch and the next action failed.

### What changed, per flow

- **Cascade save** ([`cascade-save.ts`](../ui/manager/src/lib/api/cascade-save.ts)).
  Two failure modes, both fixed:
  - *Exact match.* The agent's workflow reference was replaced only on an exact
    string match, and nothing checked that anything had been replaced, so an
    agent that spelled the reference differently was re-saved without the edit,
    and Save & Deploy shipped it. Both parents are now read and checked **before
    the resource is written**. The workflow step must end in
    `/{store}/{plural}/{id}` (the old substring test also matched ids that merely
    start with this one). The agent reference is matched by parsed id and version
    in any spelling. A missing reference throws `CascadeReferenceError` with
    nothing written, so no orphaned resource version is left behind.
  - *Recovery.* A hop that fails after the resource was written throws
    `CascadeSaveError`, carrying the versions that now exist and a
    `retryContext`. When only the agent hop failed, the new optional
    `CascadeContext.agentWorkflowVersion` records that the agent still points at
    the previous workflow version, so the retry repoints it instead of refusing.
    The resource editor, the Studio panel and the prompt editor
    (`useUpdateAgentPrompt`) adopt these versions, so a retry completes instead
    of 409ing until a reload discards the edit. Path B ("update usages") now
    passes `agentWorkflowVersion` for a second agent sharing a workflow that has
    already moved on. Before, the exact-match bug skipped that agent silently.
- **Agent Studio** ([`agent-studio.tsx`](../ui/manager/src/pages/agent-studio.tsx),
  [`studio-editor-panel.tsx`](../ui/manager/src/components/studio/studio-editor-panel.tsx)).
  The page, not the per-stage panel, now owns the workflow and agent versions, and the panel
  reports the next cascade context through `onCascadeContextChange`. The
  workflow query is keyed by version, so a reopened stage edits the version the
  save created. Placeholder data keeps the pipeline on screen while it loads.
- **Group detail** ([`group-detail.tsx`](../ui/manager/src/pages/group-detail.tsx)).
  `useUpdateGroup` now resolves with the version the save created and seeds the
  cache under it. The inline HITL, phase and advanced editors report that version
  through `GroupConfigPanel.onVersionChange`, and the page writes it to the URL.
  `deleteGroupWithMembers` deletes the group **first**, so a refused group
  delete no longer leaves every member agent soft-deleted.
- **Workforce settings** ([`workforce-settings.tsx`](../ui/manager/src/pages/workforce/workforce-settings.tsx)).
  After a save the URL moves to the new version. The form adopts the server's
  (normalised) copy while it still holds exactly what was saved, and never while
  an edit is in progress. Before, it stayed dirty forever and a second save or a
  delete 409'd.
- **Agent config sections**
  ([`use-agent-section-save.ts`](../ui/manager/src/hooks/use-agent-section-save.ts)).
  The detail page's inline sections, and the A2A section, save through one hook.
  Its state is **per agent at module scope**, shared by every section, not per
  section: saves to one agent run one at a time, in order, whichever section
  made them. Each save is chained to the version the previous one created for as
  long as the page lags behind it. The fields this render changed are merged onto
  the document that version holds: the last save's, or the cached copy of the
  page's version when the section is still showing placeholder data from another
  version (so a stale placeholder can no longer overwrite a newer version).
  Failures are toasted, and `isPending` counts queued saves as well as the one
  in flight. A 409 (another client wrote a newer version) also drops the chain
  and invalidates the agent queries: a failed save refetches nothing by itself,
  so every later save would repeat the 409 until something else refreshed the
  page. Any other failure keeps the chain, because the version the last save
  created is still current and the page may not have caught up to it yet. Before, a quick second edit 409'd and was silently dropped.
  `useUpdateAgent` seeds the new version in the cache. Picking the latest version
  in the agent-detail picker now means "follow the latest" rather than pinning
  its number.
- **Save & Deploy** ([`use-save-and-deploy.ts`](../ui/manager/src/hooks/use-save-and-deploy.ts)).
  A deployment `ERROR` stops polling and reports "Deployment failed" at once.
  The throw had sat inside the try whose catch swallowed it, so a failed deploy
  was polled for 30 s and reported as "Deploy timed out". Only a failed status
  *read* is retried now.

### Review round

- **Stale parents are refused before anything is written.** `loadParents` also
  reads `/currentversion` for the workflow and the agent. If either is newer than
  the version the page addresses (changed in another tab, say), the cascade
  throws `CascadeReferenceError` (`workflowChanged` / `agentChanged`). Before,
  the resource and workflow were written and only the agent PUT 409'd, and a
  retry with the handed-out context did the same again, leaving an orphan per
  attempt. An unreadable or lower answer is not treated as evidence; the PUTs
  still guard.
- **The cascade's refusals are translated.** `CascadeReferenceError` carries a
  `code` and `params`. `describeSaveError` (`src/lib/save-error.ts`) renders them
  from the new `cascadeSave.*` keys in all 11 locales, for the resource editor,
  Save & Deploy and the Studio.
- **Studio.** The panel is keyed by stage and resource id instead of the full
  URI, so a save no longer remounts it (which discarded anything typed meanwhile
  and reset the saved indicator). `useCascadeSave` seeds the version it created,
  so the editor does not drop to a loading state either. The page also remembers
  the newest version each resource was saved at, so returning to a stage after a
  workflow-hop failure does not address the superseded resource version.
- **The prompt editor's recovery survives unmount.** It is held at module scope,
  keyed by agent and LLM id, instead of in the hook instance.
- **Version lists refresh after a partial failure.** `useCascadeSave` also
  invalidates on a `CascadeSaveError`, so the picker offers the version the page
  moved onto.
- **Workforce settings: the post-save guard waits for the URL once** (an
  `awaitingUrl` flag), instead of refusing every version lower than the last
  save. A move back to an older version is followed again.

The strict `Location` parser moved from `cascade-save.ts` to
[`location-version.ts`](../ui/manager/src/lib/api/location-version.ts), unchanged, so the
group and agent hooks can share it.

### Backend compatibility

Every version the UI now uses comes from a `Location` header, which is always
the live version. So the flows behave the same against current `main` and
against the pending backend change that makes descriptor PATCH answer 409 on a
non-current version. No REST shape, stored config or ZIP format changed.

### Not done here

- A group URL without `?version=` still defaults to 1. That is correct for a
  freshly created group, but a hand-typed link to a later version will 409 on
  save. Resolving through `/currentversion` is a follow-up.
- `useAgentVersions` (the agent-detail version list) belongs to
  `fix/manager-api-contract`. The "follow latest" change relies only on its
  first entry being the newest.

---

## 🐛 fix(manager): Workforce and group discussions — Stop cancels, streams hand over, configs save what was entered (2026-09-26)

**Repo:** EDDI (`fix/manager-workforce-groups`)

Fix-plan item 22: UI review High 10, the Workforce/group Medium items, G2, G4,
P2, IME and the group search debounce. Manager only, apart from three
backend strings (P2).

### Stop and "+ New" stop the discussion (UI High 10)

- The board's **Stop** only closed this tab's SSE connection: the discussion
  kept running and spending on the server, and the board froze on the last
  frame while still saying new answers would appear. Stop now calls
  `POST /groups/{id}/conversations/{gcId}/cancel` (confirmed first, like the
  Manager's cancel) and closes the stream only once the cancel succeeded. A
  409 means the run already ended on its own — the stream is closed, but the
  state is left to the stored document rather than claimed as CANCELLED.
- Stop pressed before `group_start` has named the conversation is remembered
  (`cancelRequested`) and sent as soon as the id arrives, instead of closing a
  connection to a run nothing could then reach.
- **"+ New"** during a live discussion asks first and stops it, instead of
  leaving it running and letting a second run start beside it.
- New store action `cancelStream`; `abortStream` keeps its meaning (detach,
  used when switching discussions) and is no longer what the board's Stop does.
- **Stop without a stream (review follow-up):** a discussion running with no
  stream in this tab — the connection dropped (`interrupted`), or the board
  adopted it from the stored list after a reload — shows Stop too, and "+ New"
  asks before leaving it. `cancelStream(groupId, gcId)` cancels such a run by
  its known id, without creating a store entry for a discussion this tab never
  streamed. A successful cancel clears `interrupted`, so the "connection lost,
  keeps updating" notice does not outlive it.
- "Stop and start new" confirmed before `group_start` named the conversation
  now starts the new discussion once the pending cancel lands; a cancel that
  fails drops the intent and leaves the run on screen.
- A deferred cancel the server refuses is reported like a refused immediate
  one — the "Could not stop the discussion" toast, through a separate
  `cancelError` field the board clears once shown — and no longer through the
  stream's `error`, which the board renders as the discussion itself failing
  while it was in fact still running.
- **Stop targets the discussion on screen (review follow-up):** with one
  discussion streaming in this tab and another running one opened from
  Sessions, Stop cancelled the stream — the run the user was not looking at —
  and left the selected one running. The board now passes the selected
  discussion's id, fixed when the confirmation opens, and `cancelStream`
  cancels a named discussion that is not the streamed one by id, leaving the
  live stream alone. A finished discussion browsed during a stream shows no
  Stop ("Back to live discussion" leads to the running one). "+ New" still
  stops the tab's stream when there is one, since clearing the board detaches
  from it.
- **Stop stays on its board (review follow-up):** switching task forces keeps
  the board mounted and rebinds the stream hook, so a Stop confirmation left
  open would have sent the old board's discussion id under the new board's
  group. The confirmation is dismissed when the board changes, and a pending or
  in-flight "Stop and start new" now clears only the board it was asked on,
  not the one the user moved to.
- **A 409 is checked, not believed (review follow-up):** the cancel endpoint
  answers 409 both for a discussion that had ended and for a cancel that lost a
  state race on a paused one (`GroupHitlCoordinator.cancelDiscussion`). The
  board now reads the stored state before saying "already ended"; if the
  discussion is still going it reports a failed Stop (new key
  `Workforce.board.stopRaced`, all 11 locales), keeps Stop on offer, and "Stop
  and start new" does not clear the board. Only a stored terminal state counts
  as ended: a read that fails leaves the Stop unconfirmed (new key
  `Workforce.board.stopUnconfirmed`, all 11 locales) and is handled like a
  discussion still going, never taken as the 409's word that it ended.

### Streams that end, and members that "type forever"

- `speaker_complete.outcome` (`TIMEOUT` / `SKIPPED` / `ERROR`, from the
  group-conversation-state backend branch) closes the member's placeholder as
  SKIPPED or ERROR with no content — a failure is never shown as something the
  member said. A completion with no content from an older backend closes as
  SKIPPED too. An unknown outcome value reads as a contribution.
- Against a backend without that field, a PARALLEL member released by the
  batch deadline got no completion at all and its placeholder typed for the
  rest of the discussion. `phase_complete` now closes the phase's open
  placeholders, and every terminal event (`group_complete`, `group_error`,
  `cancelled`, `awaiting_approval`, `human_input_requested`) closes all of them.
- A connection that ends **without a terminal event** (proxy timeout, pod
  restart) is now `interrupted`, not a silent "not streaming": the board shows a
  notice and follows the stored conversation (which polls while it runs); the
  Manager's group page selects it. A network error **after** the first frame is
  treated the same way — it used to mark a still-running discussion FAILED. A
  failure before any frame (the request was refused) is still FAILED.
- The board used to keep the frozen live transcript after a stream settled, so
  follow-ups and later phases never appeared. It now switches to the stored
  document as soon as that has caught up with what the stream delivered
  (`deliveredRowCount`), so the switch never flashes an older transcript. The
  Manager's group page applies the same caught-up rule
  (`persistedHasCaughtUp`) when it hands an interrupted stream over, instead
  of showing a stored copy that is still behind until the next poll.
- A PARALLEL member whose turn threw shows ERROR live only with #843's
  `outcome` field. Against current `main` the backend sends no completion and
  stores the failure as an unattributed row, so the live placeholder closes as
  SKIPPED on `phase_complete` and the stored copy corrects it on the switch —
  there is nothing live that says which member failed.
- A refused stream start now carries the backend's own sentence
  (`streamRefusalMessage`) instead of "400 Bad Request".

### Board and thread

- **Attachment-only sends** are blocked in the composer with a hint — the
  backend requires a question and its 400 left the board on a full-page error
  with no actions. That error screen now has "Start over" (and "View past
  sessions"): it lives in the per-board stream store, which outlives
  navigation, so it used to be a dead end.
- **REJECTED** gets its own composer message on the board ("This
  recommendation was rejected"), as on the Manager page (P2).
- **Version links:** the board, the advisor thread and the history page are
  reached by links without `?version=` and read version 1 — the group's FIRST
  version, with its original name, roster and phases. They now resolve the
  current version (`GET /groupstore/groups/{id}/currentversion`,
  `useResolvedGroupVersion`) and never fetch version 1 while it is in flight.
  The history page learned that version from the enriched descriptor listing —
  up to 201 requests for one number; it is now one.
- **Thread errors:** a thread that could not be opened showed an empty,
  healthy-looking thread with a dead composer; it now says why, with Retry and
  New conversation. A stored conversation that no longer exists (404/410) is
  replaced with a fresh one. Moving from one advisor's thread to another (same
  page, new route param) kept the first advisor's messages and conversation:
  initialisation is now keyed per (board, member) and re-checked after every
  await. "New conversation" is bound the same way: pressed on one advisor and
  still in flight when the reader moves on, it registers the new conversation
  for that advisor without writing it into the next one's view.
- **IME:** the Enter that confirms a Chinese/Japanese/Korean composition no
  longer sends the question, in the board composer, the Manager's discussion
  input (both textareas) and the thread input (`lib/ime.ts`).

### Group page (P2)

- A rejection is confirmed: the backend ends a rejected run with
  `group_complete` state REJECTED and never sends `hitl_resume`, which was the
  only thing the page waited for.
- "New Discussion" pressed on one group no longer suppresses auto-selection on
  the next group the (still-mounted) page navigates to.

### Overview (G2, G4)

- The round's QUESTION row (stored at phaseIndex 0, phaseName "Question") no
  longer names phase 0 "Question" or marks it started.
- A picked round is reset when the panel moves to another discussion.
- An earlier round no longer borrows whole-discussion records: its stances are
  extracted from its own turns, its cost is unknown (null) rather than the
  total, and it shows its own synthesis and no later verdict. That includes
  the decision card the surfaces hand the overview as `outcome` (built from
  the stored or streamed decision, i.e. the newest round's): the overview
  shows it only while the newest round is selected.
- The Workforce history viewer passes the group's `style` to the overview.

### Configs that did not save what was entered

- **NEGOTIATION preset:** materializing the phases (which enabling any approval
  point does) stored Arbitration with `inputTemplate: null`, so the moderator
  ran the generic synthesis prompt instead of the arbitration brief.
  `NEGOTIATION_ARBITRATION_TEMPLATE` mirrors `TEMPLATE_ARBITRATION`, and a test
  compares it with the Java text block (path resolved from the test file;
  escapes other than line continuations are refused rather than guessed at;
  the closing delimiter's indentation counts, per JLS 3.10.6). Groups already
  stored that way are **repaired on read** (`repairNegotiationArbitration` in
  `normalizeGroupConfig`): only the exact preset phase — NEGOTIATION style,
  "Arbitration", MODERATOR SYNTHESIS, `skipIf AGREEMENT_REACHED`, no prompt of
  its own — gets the prompt back, and the stored document is healed by the next
  save from any editor. Until then the backend still runs those groups with the
  generic synthesis prompt; a backend read-time default is a follow-up.
- **Assignment mode:** `normalizeGroupTaskConfig` rebuilt the block from the
  three fields it normalizes, so Workforce settings saved BID back as ROLE. It
  now carries every other field through.
- **Retro:** the backend has no "off" for retro — a null `retroConfig` means the
  default caps. The checkbox is now "Custom retro lesson limits" and says that
  unticking keeps the defaults (remove the RETRO phase to stop it). Saving
  spreads the existing block, so `maxLessonChars` survives.
- **Vote options:** the explicit-options textarea was bound to the cleaned list,
  deleting the newline and trailing space as they were typed — a second option
  or a two-word option could not be entered. It keeps its own draft now, and an
  EXPLICIT ballot with fewer than two options blocks the save with a message
  (the backend's 400).
- The phase, advanced and HITL editors show the backend's error sentence instead
  of "Something went wrong".

### Create flows (validation messages)

- `groupSaveProblems` mirrors `AgentGroupStore`'s hard rejections — DEBATE /
  DEVIL_ADVOCATE preset roles (only for groups without explicit phases),
  unassigned members, HUMAN members' name and principal id, HUMAN members in
  task-force or peer-targeted phases (preset-expanded). The create dialog, the
  group wizard and the Workforce wizard show them on the last step and hold
  Create back. The two wizards create and deploy member agents before saving
  the group, so this runs before the first agent is created. Their `onError`
  shows the backend's sentence.

### Search and paging

- The Groups page search is debounced (300 ms) and keeps the previous results
  while a new filter loads: every keystroke was a fresh enriched listing, one
  descriptor request plus one config read per group.
- Owner-filter pagination (frontend half): **no production change.** The
  history still fetches `index 0` with a limit that grows by 20 per "Load
  more", and shows "Load more" while the response fills that limit — correct
  once the backend filters by owner in the query (the group-conversation-state
  branch), so it was left as is. A test now pins that behaviour, and the MSW
  handler honours `index`/`limit` as the row offset the backend uses. Against
  the current backend a non-admin can still get a short page, which the client
  cannot detect.

### Backend strings (P2)

- `RestGroupConversation` close 409 text, `IRestGroupConversation` close 409
  description and the MCP `start_group_discussion` description now name
  REJECTED; the MCP text also names the two waiting states, so a polling client
  does not wait for COMPLETED or FAILED forever.

### Not changed

- `ui/manager/src/lib/api/groups.ts:1622` (the literal NUL byte) is untouched;
  this branch edits other parts of the file byte-safely. Item 17 owns it.
- The group page's own `?version=` default of 1 is left to the version-after-save
  branch (item 19).
- `api-client.ts`'s `extractErrorMessage` still does not parse Quarkus
  `violations[]` bodies (not in this item).

**Files:** [`use-group-discussion-stream.ts`](../ui/manager/src/hooks/use-group-discussion-stream.ts),
[`workforce-board.tsx`](../ui/manager/src/pages/workforce/workforce-board.tsx),
[`workforce-thread.tsx`](../ui/manager/src/pages/workforce/workforce-thread.tsx),
[`use-discussion-digest.ts`](../ui/manager/src/hooks/use-discussion-digest.ts),
[`group-config.ts`](../ui/manager/src/lib/group-config.ts),
[`hitl-config.ts`](../ui/manager/src/lib/hitl-config.ts),
[`group-detail.tsx`](../ui/manager/src/pages/group-detail.tsx)

---

## 🔒 fix(security): close a group of authorization/IDOR gaps across REST, tools and health (2026-09-26)

**Repo:** EDDI (`fix/security-access-control`)

### What changed and why

A batch of verified access-control gaps were hardened. Each is scoped so that
legitimate flows (the Manager, admins, internal orchestrators, auth-disabled dev)
keep working, while the missing owner/role/redaction checks are added.

- **Active-conversation endpoints role-gated.** `GET /conversationstore/conversations/active/{agentId}`
  and `POST /conversationstore/conversations/end` were reachable by any authenticated
  token. The same gap was closed independently on `main` by the soft-delete owner-check
  fix (`fix-conversation-access-guard`), which also adds an agent EDIT check under
  workspace enforcement and server-side state for `/end`; that is the contract kept:
  `eddi-admin`/`eddi-editor` (the tier the Manager's conversation-monitoring page runs
  as), plus EDIT on the agent when workspaces are on. This batch adds
  `SecurityAccessControlAnnotationsTest`, which pins the role set by reflection.
- **`converse_with_agent` cross-user access.** The tool now requires a model-supplied
  `conversationId` to belong to the same user the tool is bound to, so the LLM can no
  longer continue another user's conversation by supplying its id. New conversations
  (started as the bound user) and internal group-orchestrator callers are unaffected.
  A conversation that records no owner is refused too (fail closed): its ownership
  cannot be established, and the model supplied the id. So is any supplied id when the
  tool has no bound user (the parent conversation records none): there is nothing to
  compare the owner against. Starting a new conversation is unaffected.
- **Schedule `persistentConversationId`.** Create now nulls a caller-supplied
  `persistentConversationId` (mirroring import); the fire path additionally refuses to
  reuse a persistent conversation owned by a different user than the schedule.
- **Schedule ownership.** Reads (`readSchedule`, `readAllSchedules`) and the remaining
  state-changers (`delete`, `enable`, `disable`, `retry`, `dismiss`) now apply the same
  owner check `create`/`update`/`fire` already used, so an editor cannot see or mutate
  another user's schedule; unowned/system schedules stay shared. The listing's owner
  filter is pushed into the store query (`ScheduleOwnerScope`, Mongo and Postgres), like
  the HITL-timeout redaction, so `limit`/`offset` count only the caller's visible rows: a
  non-admin gets a full page of their own schedules rather than a short or empty one that
  the paging contract would read as the end of the list.
- **Agent-trigger ownership.** Triggers carry no owner field, so `delete`/`update` are
  now gated on USE access to the agents the stored trigger routes to — a foreign editor
  can no longer re-point or remove another team's trigger.
- **`UserMemoryTool` visibility guardrail.** The tool now applies the configured
  `defaultVisibility`, restricts writable visibilities to a configurable allow-set
  (default `self`), and refuses overwriting a `global` key owned by another agent unless
  configured — including a global key whose entry records no owning agent (legacy or
  migrated data), since the guard cannot show it is this agent's. Two new `guardrails`
  fields, documented in `docs/user-memory.md`: `allowedVisibilities`, `allowGlobalKeyOverwrite`.
- **`returnDetailed` step-data exposure.** The detailed conversion now drops sensitive
  internal keys (`audit:*`, `*:trace:*`, `*Error`) and runs values through
  `SecretRedactionFilter`, matching the SSE path; full-fidelity debugging remains on the
  owner/admin-gated raw endpoint. Redaction recurses into `Map` keys and values,
  collection elements and `Object[]` elements, so a secret embedded in a structured value
  (e.g. a deserialized httpCall response body under an agent-chosen key, or a
  token-keyed map) is masked too, not just top-level strings. A value under a
  credential-named key (`apiKey`, `token`, `secret`, `password`, `authorization`, at any
  depth) is masked outright even without a credential shape, since walking a map
  separates the name from the value the name-bound filter rules need; the filter's
  usual exemptions (under 8 characters, a vault reference) still apply. A map, list or
  array under a credential-named key is replaced as a whole, so a credential used as a
  map key beneath it cannot leak either.
- **Semantic parser endpoint.** `POST /parser/{parserId}` was role-less; it now requires
  `eddi-admin`/`eddi-editor` and a `VIEW` check on the specific parser configuration.
- **Postgres health readiness.** The anonymous readiness payload no longer returns the
  JDBC URL or raw exception text — status only; the failure is logged server-side.
- **A2A `tasks/send`.** The JSON-RPC endpoint now requires a real role (`eddi-user` and
  up) rather than mere authentication, and the reply returns only the text output instead
  of the serialized `ConversationOutput` map.
- **`deleteConversationLog`.** Now uses the strict owner check, so a legacy no-owner
  conversation is not deletable (soft or permanent) by an arbitrary token. The strict
  check resolves the owner the same way as the soft-delete fix on `main` (live
  descriptor, else the archived one; no descriptor at all is a 404 unless admin), so a
  soft-deleted conversation can only be permanently deleted by its owner or an admin.
  A pre-v5.1.6 descriptor that records no owner (live or archived) takes its owner from
  the memory snapshot — the same fallback the listing uses — so the recorded owner can
  still delete their own legacy conversation; with no owner anywhere it stays admin-only.
- **Schedule delete status codes.** `DELETE /schedulestore/schedules/{id}` rethrows a
  downstream 403/404 instead of flattening it into a 500, like the other mutation paths.

### Tests

Focused unit tests added/extended, each mutation-checked (revert the fix, confirm the
test fails, restore): `RestScheduleStoreTest`, `ScheduleOwnerScopeTest`, the Mongo and Postgres
`ScheduleStoreTest` container tests, `ConverseWithAgentToolOwnershipTest`,
`UserMemoryToolTest`, `RestAgentTriggerStoreTest`, `ConversationMemoryUtilitiesTest`,
`ConversationAccessGuardTest`, `SecurityAccessControlAnnotationsTest`,
`RestSemanticParserTest`, `A2ATaskHandlerTest`, `RestConversationStoreOwnershipTest`,
`PostgresHealthCheckTest`.

**Files:** [`ConversationAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/security/ConversationAccessGuard.java),
[`ConversationMemoryUtilities.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java),
[`RestScheduleStore.java`](../src/main/java/ai/labs/eddi/engine/schedule/rest/RestScheduleStore.java),
[`UserMemoryTool.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java),
[`RestA2AEndpoint.java`](../src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java).

---

## 🔒 fix(slack): bind HITL decisions and event signatures to the owning integration; namespace Slack user ids (2026-09-26)

**Repo:** EDDI (`fix/security-channel-identity`)

### What changed and why

Three gaps in the Slack channel, each one a place where proving *who sent a request*
was mistaken for proving *what it may act on*.

**1. A Slack HITL decision could resume any paused conversation or group.** The
interactivity endpoint verified the signature and the approver list against the
integration named in the button value, but never checked that the decision's
subject (conversation id, or `group:<id>`) had anything to do with that integration.
An approver of integration A who could get a signed `block_actions` payload carrying
an edited value resumed conversations that belonged to integration B, to another
channel, or to no Slack integration at all.

Every approval card is now recorded when it is posted, in a new persisted store
(`ISlackApprovalRecordStore`, collection/table `slack_hitl_approval_records`, MongoDB
and PostgreSQL), keyed by `(integrationName, subject, pauseEpoch)`. On a decision,
`SlackInteractivityHandler` requires a live record for that integration and subject
whose pause identity matches the subject's *current* pause (`hitlPausedAt` for a
conversation, `GroupConversation.pausedAt` for a group). No record → refused and
logged as `SLACK_HITL_DECISION_REFUSED`. A record from an earlier pause → the card is
marked "already resolved" and the newer pause is not touched. A store failure fails
closed and leaves the card intact for a retry.

The record replaces the in-memory `approvalNotified` cache as the "one card per pause"
marker, so that guarantee now survives a restart too. It is written *before* the card
is posted and removed if delivery fails. A card whose record cannot be written is
posted without buttons. Group pauses got the same treatment: `HitlPauseEvent` now
carries `pausedAt` (a 4-arg constructor keeps old callers compiling), and
`SlackGroupDiscussionListener` records the card before posting it. Records expire
after `eddi.slack.hitl.approval-record-retention` (default `30d`).

**2. `/integrations/slack/events` accepted a signature from any configured app.** It
verified against the pooled set of every integration's signing secret, so a holder of
one integration's secret could drive another integration's channels, agents and
approval flow. After parsing, `RestSlackWebhook` now requires the signature to match
the secret of the integration (or legacy connector) that owns `event.channel`. An owned
channel whose owner has no secret is rejected, never re-admitted through the pool. For
an unowned channel (a DM) the webhook finds which integration's secret signed it and
passes that to `SlackEventHandler`, which routes the DM to *that* integration's default
target through the new `ChannelTargetRouter.resolveDefaultForDm(type, text, integrationName)`.
Previously it went to whichever integration came first in map order. Events with no
channel at all keep the pooled check only, since the handler drops them anyway.

**3. Slack users shared the OIDC principal namespace.** The raw Slack user id
(`U0ALICE`) was the EDDI `userId`. A Keycloak principal equal to a Slack id shared that
user's long-term memories and passed ownership checks on their conversations, and ids
from two workspaces could collide. It is now `slack:<team_id>:<user_id>`
(`SlackUserIdentity`), with the team taken from the envelope's `team_id`.

Compatibility, with no migration step to run:
- A thread mapping stored under the raw id is found, **re-keyed** to the namespaced id,
  and the thread keeps its conversation. That conversation keeps its raw-id owner and
  its memories.
- A **new** conversation does not inherit long-term memories stored under the raw id;
  they are not moved (see the adversarial review section, Finding B).

### Design decisions

- **Persisted, not cached**, as the task required. It reuses the existing
  `approvalNotified` bookkeeping instead of adding a second marker.
- **`UNKNOWN_PAUSE` records** (card posted before the bookmark was readable) only match
  a pause that began at or before the record was written. A card cannot be for a pause
  that did not exist yet.
- **No memory migration at all** (neither move nor copy): see Finding B below.
- **Residual, documented:** checking the pause and resuming are two separate steps.
  Closing that window would need an expected-pause parameter on `resumeConversation`.

### Not done

- The approval records are not in the GDPR erasure cascade. They hold only an
  integration name, a conversation or group id and a channel id, and they expire on
  their TTL.
- Neither structured legacy memory entries nor the `IUserMemoryStore` Properties blob
  (`readProperties`) are migrated.

**Files:** [`ISlackApprovalRecordStore.java`](../src/main/java/ai/labs/eddi/integrations/slack/hitl/ISlackApprovalRecordStore.java),
[`MongoSlackApprovalRecordStore.java`](../src/main/java/ai/labs/eddi/integrations/slack/hitl/MongoSlackApprovalRecordStore.java),
[`PostgresSlackApprovalRecordStore.java`](../src/main/java/ai/labs/eddi/integrations/slack/hitl/PostgresSlackApprovalRecordStore.java),
[`SlackInteractivityHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackInteractivityHandler.java),
[`SlackEventHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackGroupDiscussionListener.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java),
[`SlackUserIdentity.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackUserIdentity.java),
[`RestSlackWebhook.java`](../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java),
[`ChannelTargetRouter.java`](../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`GroupConversationEventSink.java`](../src/main/java/ai/labs/eddi/engine/lifecycle/GroupConversationEventSink.java),
[`DataStoreProducers.java`](../src/main/java/ai/labs/eddi/datastore/DataStoreProducers.java).
Docs: [`hitl.md`](hitl.md), [`slack-integration.md`](slack-integration.md).

### Item 4 — OpenAI-compat `X-OpenWebUI-User-Id` shared the OIDC principal namespace

`OpenAiAuthFilter` used the `X-OpenWebUI-User-Id` header verbatim as the EDDI
`userId`. Since a leaked shared `/v1` key lets a caller set that header to
anything, a caller could set it to an OIDC user's principal and reach that user's
conversations and long-term memories. The header value is now namespaced to
`openwebui:<id>` (`OpenAiUserIdentity`), so a self-asserted header can never equal
a bare OIDC principal. OIDC principals (in `authenticated` mode) and the
configured anonymous default are left unprefixed — the first is already verified,
the second is operator config. The documented trust model is unchanged: a leaked
key still impersonates any *Open WebUI* user; only the cross-namespace reach into
OIDC-owned identities is closed (`application.properties` `trust-user-headers`
comment still holds).

Compatibility is adopt-only, in `OpenAiConversationBridge`, only for the
`openwebui:`-namespaced path, and **opt-in**
(`eddi.openai-compat.adopt-legacy-header-mappings`, default `false`): a chat
mapping stored under the raw header id is adopted and re-keyed to the namespaced
id, and because the conversation keeps its raw-id owner its long-term memories
load without any move. See the adversarial review section below for why the
standalone memory move was removed, and the CodeRabbit section for why adoption
is opt-in.

Test `OpenAiAuthFilterTest.headerUserId_isNamespaced_soItCannotEqualABareOidcPrincipal`
proves an OpenAI-compat identity can never collide with a bare OIDC principal;
mutation-checked (reverting the namespacing in the filter fails it). Adopt paths
covered in `OpenAiConversationBridgeTest`.

**Files:** [`OpenAiUserIdentity.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiUserIdentity.java),
[`OpenAiAuthFilter.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiAuthFilter.java),
[`OpenAiConversationBridge.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java).

### Adversarial review fixes (A/B/C)

An adversarial review of this branch found that the memory-migration I added in
items 3 and 4 was itself exploitable, plus a name-collision hole in the item-1
binding. All three are fixed here.

- **A (BLOCK) — OpenAI-compat memory move was cross-namespace data theft.** In the
  default config a `/v1` shared-key caller sets `X-OpenWebUI-User-Id: <victim>`;
  `rawId` is then the fully attacker-chosen bare `<victim>`, which is exactly where
  OIDC principals keep long-term memories. The standalone "brand-new chat inherits"
  move called `getAllEntries(<victim>)`, upserted the result to the attacker's
  `openwebui:<victim>` and **deleted** it from the victim — one request relocated
  and erased an OIDC user's entire long-term memory. Fixed by **removing the
  standalone memory move entirely** (`OpenAiConversationBridge`). Only adoption of a
  conversation MAPPING under the exact intent remains — an OIDC principal never has
  an OpenWebUI mapping, and the adopted conversation keeps its raw-id owner so its
  memories load with no move. `IUserMemoryStore` is retained (unused for migration)
  so the no-migration invariant stays enforced by tests.

- **B (Should-fix) — same class in Slack.** `SlackEventHandler.migrateLegacyMemories`
  moved all bare-Slack-id entries keyed only on the raw id, and `team_id` is
  attacker-supplied in a validly-signed event, so a second integration's operator
  could pull a victim's legacy bare-id memories into their own
  `slack:<their-team>:<user>` namespace. Fixed by **removing the standalone move**
  (adopt/rekey keeps the raw-id owner, no move needed) and by **pinning `team_id`
  to the signing integration** where the webhook resolves it
  (`RestSlackWebhook.verifyOrigin` reads the owner/signer integration's
  `platformConfig.teamId`; the payload `team_id` is only a fallback when none is
  configured).

- **C (Should-fix) — HITL binding IDOR via non-unique display name.** The item-1
  binding keyed on the integration's mutable display **name**, and names were not
  unique. An attacker could name their integration identically to a victim's, so
  `getIntegrationByName` might resolve to the attacker while the approval record
  belonged to the victim. Fixed fail-closed: `ChannelTargetRouter.getIntegrationByName`
  now **refuses (returns empty) when more than one integration matches** a name, and
  `RestChannelIntegrationStore` **enforces global name uniqueness per channel type**
  on create/update/duplicate (duplicate copies get a `" (copy)"` suffix). An
  ambiguous name therefore resolves to no owning integration and the decision is
  refused before any record lookup. (Unique-resource-id keying was considered but
  the fail-closed ambiguity refusal plus enforced uniqueness closes the IDOR with a
  much smaller blast radius on a security branch.)

- **D (nit) — pause-check → resume TOCTOU** in the item-1 decision path is left as a
  documented residual: closing it needs an expected-`pausedAt` argument threaded
  into `resumeConversation`/`resumeDiscussion` and a CAS there, which is disproportionate
  here. Noted in `SlackInteractivityHandler`.

All three fixes are mutation-checked (revert → the new test fails → restore):
`OpenAiConversationBridgeTest.namespacedCaller_withNoLegacyMapping_neverTouchesBareIdMemories`
(A), `SlackUserIdentityTest.newConversation_neverTouchesBareIdMemories` (B),
`ChannelTargetRouterDeepBranchTest.duplicateNameRefused` (C). The approval-record
store also gains real-MongoDB coverage (`MongoSlackApprovalRecordStoreTest`:
`tryRecord` atomicity/idempotency/expiry/scoping via Testcontainers).

**Files:** [`OpenAiConversationBridge.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java),
[`SlackEventHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`RestSlackWebhook.java`](../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java),
[`ChannelTargetRouter.java`](../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`RestChannelIntegrationStore.java`](../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java).

### Re-review cleanups (residual #1 + doc/nit)

The re-review shipped A/B/C but flagged that team_id pinning was incomplete: when a
signing integration declared no `teamId`, the webhook still fell back to the
attacker-controlled payload `team_id`/event `team`, so an operator of any registered
integration could craft a validly-signed event for an **unowned** channel (a DM)
with a forged team+user and make the bot LOAD and echo that victim's long-term
memories (read-only now that the erase is gone, but still a cross-tenant read).

Completed the pin (`RestSlackWebhook` + `SlackEventHandler.slackUser`):
- A signed event whose payload `team_id` or event `team` **disagrees** with the
  signing/owning integration's declared `teamId` is now rejected (403).
- The workspace comes only from the webhook-pinned `origin.teamId()`; the
  `event.get("team")` fallback in `slackUser` is removed.
- An **unowned** channel whose signing integration declares no `teamId` is treated
  as **unbindable**: the payload team is not trusted and the identity is team-less
  (`slack:<user>`), which reaches no victim's `slack:<team>:<user>`. (An owned channel
  with no declared `teamId` was first left trusting its payload `team_id`; that was
  closed in the CodeRabbit round below.)

Also: removed two now-unused test imports in `OpenAiConversationBridgeTest`, and
corrected the `OpenAiUserIdentity`/`SlackUserIdentity` Javadoc that still described
the removed "memories are moved to the namespaced id" behavior — both now describe
the adopt-only reality.

Test `RestSlackWebhookTest.dmForgedTeamAgainstDeclaredWorkspaceIsRejected` (403) and
`dmForgedTeamOnTeamlessIntegrationResolvesToNoVictim` (team-less identity) cover the
residual; the second is mutation-checked.

### CodeRabbit review fixes (2026-09-28)

- **Owned Slack channel trusted the payload `team_id`.** With no declared
  `platformConfig.teamId`, an event in an owned channel took its workspace from the
  payload. The owner's signing secret proves the integration, not the workspace, so
  its holder could forge `team_id` + `user` and reach any `slack:<team>:<user>` —
  including users of integrations that *do* declare a `teamId`. The workspace now
  comes only from the declared `teamId`, for owned channels and DMs alike; without one
  the identity is team-less (`slack:<user>`). Residual, documented: all team-less
  integrations share that namespace, so `teamId` should be declared on every
  integration of a multi-workspace deployment (`docs/slack-integration.md`, which now
  lists `platformConfig.teamId`). Test
  `RestSlackWebhookTest.ownedChannelOnTeamlessIntegrationIgnoresPayloadTeam`,
  mutation-checked.
- **Legacy `/v1` mapping adoption could hand over an OIDC user's chat.** In
  `http-policy=authenticated` the bridge writes mappings under the bare OIDC
  principal, with the same intent. After a switch to shared-key mode, a caller
  sending `X-OpenWebUI-User-Id: <principal>` (and a matching chat key, which falls
  back to `default`) adopted that conversation — and through its owner, the user's
  memories. A raw mapping does not record its origin, so adoption is now opt-in:
  `eddi.openai-compat.adopt-legacy-header-mappings` (default `false`). Upgrading
  Open WebUI deployments whose `/v1` never ran in OIDC mode can enable it to keep
  in-flight chats on their conversations; otherwise such a chat starts a new
  conversation on its next message. Test
  `OpenAiConversationBridgeTest.namespacedCaller_doesNotAdoptARawMapping_byDefault`,
  mutation-checked.
- **A failed re-key dropped the legacy mapping.** When writing the namespaced mapping
  failed, the bridge deleted the raw mapping anyway, so the next request found
  neither. It now re-reads the namespaced mapping and deletes the raw one only when it
  points to the same conversation. Tests `rekeyFailure_keepsTheLegacyMapping`
  (mutation-checked) and `rekeyRace_dropsTheLegacyMappingOnceTheSameConversationIsConfirmed`.
- **OIDC principals could carry the `openwebui:` prefix.** An OIDC user named
  `openwebui:alice` was the same EDDI identity as the shared-key caller sending
  `alice`. `OpenAiAuthFilter` now refuses (401) an OIDC principal carrying the
  reserved prefix. Test
  `OpenAiAuthFilterTest.oidcMode_refusesAPrincipalCarryingTheReservedOpenWebUiPrefix`,
  mutation-checked.
- **Duplicate-name derivation scanned every integration per candidate.** It now reads
  the used names once; `validateUniqueName` stays the authoritative check.
- **Round 2.** A failed raw-id lookup during adoption now fails the request (retryable)
  instead of starting a conversation whose mapping would shadow the legacy one
  (`failedLegacyLookup_failsTheRequestInsteadOfShadowingTheLegacyMapping`). The
  channel-name uniqueness check refuses the save (503) when its scan cannot run,
  rather than allowing it (`uniquenessScanFailureRefusesSave`), and so does a
  transient failure reading one existing integration; only an integration that no
  longer exists, or whose document no longer deserialises, is skipped
  (`transientEntryReadFailureRefusesSave`). An adopted chat's
  conversation stays owned by the raw id, so GDPR export/erasure must address both
  `openwebui:<id>` and `<id>`; this is documented, not automated, because deriving
  the raw id could reach an OIDC principal's data.
- **Round 4 — Slack approval buttons are bound to their card.** Records were matched
  by `(integration, subject)` and the subject's current pause, but the clicked card
  was never identified, so an approver clicking an OLD card of the same conversation
  or group ("delete file A") while a NEW pause was live ("delete everything")
  approved the new action without seeing it. Each record now carries a random
  128-bit card id, generated before the record is written and embedded in the
  buttons (`<integration>|<subject>|<cardId>`); a click is accepted only when its
  card id is on the record for the current pause. A button without a card id is
  refused. The id travels with the card, so there is no post-then-update window and
  no second write. Stores: MongoDB `cardId` field, PostgreSQL `card_id` column (added
  with `ADD COLUMN IF NOT EXISTS` to a table from an earlier build of this branch).
  Tests `SlackInteractivityHandlerTest.staleCardOfSameConversation_…`,
  `staleCardOfSameGroup_…`, `unboundLegacyValue_isRefused`, `unknownCardId_isRefused`
  (mutation-checked), plus `PostgresSlackApprovalRecordStoreUnitTest` and card-id
  round-trips in `MongoSlackApprovalRecordStoreTest`. Deferred: channel-name
  uniqueness is still read-then-write, so two concurrent creates can persist the
  same name; that fails closed (an ambiguous name binds no decision) and needs a
  name-claim collection on both backends to make atomic.

---

## 🔒 fix(security): close client-side leaks and framing/CSP gaps in the UIs (2026-09-26)

**Repo:** EDDI (`fix/security-frontend`) — backend, `ui/manager/`, `ui/chat/` and config together

### What changed and why

A batch of verified client-side security findings across the two frontends, one
backend masking fix, and the deployment CSP.

**1. Secret-mode input no longer persists or echoes in plaintext (MEDIUM).**
A turn flagged secret (🔒 secret mode, or a password `inputField` whose property
scope was not `secret`) stored its raw text as public `input:initial` step data
and replayed it to the client on reload; only the echoed `input` output was
masked. `Conversation.scrubSecretUserInput` now rewrites `input:initial` /
`input:normalized` and the echoed `input` to the `<secret input>` placeholder in
the turn's `finally` — after the pipeline (so parser / property vaulting still
see the plaintext transiently) and before the audit flush (whose
`inputWasScrubbed()` keys off that placeholder to redact the recorded input). It
also **drops the parser-derived forms** (`expressions:parsed`,
`expressions:matches`, `intents`, `properties:extracted` and the `expressions` /
`intents` outputs): the parser emits `unknown(<token>)` expressions embedding the
normalized secret, which a free-text secret (API key / password) never matches
out of, and which are persisted and shown in the admin raw-step view — scrubbing
only `input:initial` left them behind. Mirrors `PropertySetterTask.dropParsedForms`.
The Manager and Chat UI honour the placeholder so masking survives a reload.
[`Conversation.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java),
[`conversations.ts`](../ui/manager/src/lib/api/conversations.ts),
[`snapshot.ts`](../ui/chat/src/api/snapshot.ts),
[`secrets-vault.md`](../docs/secrets-vault.md).

**2. Chat UI bearer-token handling hardened (MEDIUM).** A `?token=` bearer is now
stripped from the URL on load (`history.replaceState`) so it cannot linger in
history/Referer/logs, and a `postMessage` handshake from an allow-listed parent
origin (`?tokenOrigin=`) is offered as the safer channel. Residual login-CSRF via
`?token=` is noted for a follow-up PKCE/OIDC flow. `docs/security.md` corrected
(the Chat UI has no `keycloak-js` login).
[`ChatWidget.tsx`](../ui/chat/src/components/ChatWidget.tsx),
[`security.md`](../docs/security.md).

**3. Manager no longer auto-starts a conversation from URL params (MEDIUM).**
`?agentId=` now only *preselects* the agent; starting is an explicit user action.
`?agentName=` is ignored and the display name is resolved from the deployed-agents
list. [`chat-panel.tsx`](../ui/manager/src/components/chat/chat-panel.tsx).

**4. CSP + framing hardening (LOW).** `form-action 'self'`, `base-uri 'self'` and
`object-src 'none'` added to every CSP (none fall back to `default-src`). A
dedicated `/chat` CSP filter makes the widget embeddable via an operator-set
`frame-ancestors` allow-list (`EDDI_CHAT_FRAME_ANCESTORS`, default `'none'`), and
`X-Frame-Options` moved from a global header onto the default/Swagger filters so
`DENY` holds everywhere except `/chat`.
[`application.properties`](../src/main/resources/application.properties).

**5. Group-transcript opt-in HTML no longer allows forms / inline style (LOW).**
The DOMPurify HTML path forbids `form`/`input`/`button`/`textarea`/`select` and
the `style` attribute.
[`agent-response-card.tsx`](../ui/manager/src/components/groups/agent-response-card.tsx).

**6. Markdown images no longer auto-load as live `<img>` (LOW).** All LLM/agent
output markdown renderers in both UIs render images as links via a shared
override, closing zero-click image exfil independent of CSP.
[`markdown-safe.tsx`](../ui/manager/src/lib/markdown-safe.tsx),
[`MessageBubble.tsx`](../ui/chat/src/components/MessageBubble.tsx).

**7. Chat UI `?apiServer=` restricted to same-origin relative paths (LOW).**
`sanitizeApiServer` is now an allow-list — the value must be a clean absolute path
(single leading `/`, no control/whitespace/backslash). The previous deny-list was
bypassable with a leading control char (`?apiServer=%09https://attacker`): the
scheme/`//`/`\` tests missed the tab, but the WHATWG URL parser strips it and
`${base}${path}` resolved to the attacker origin, leaking the bearer token.
[`ChatWidget.tsx`](../ui/chat/src/components/ChatWidget.tsx).

### Tests

Backend `ConversationSecretInputTest` (persisted `input:initial` is the
placeholder, and a non-vacuous test drives the mocked lifecycle to emit
`unknown(<secret>)` expressions and asserts the derived forms are wiped) and
`CspPolicyTest` (form-action/base-uri/object-src present, per-path XFO, chat
framing default `'none'`); Chat UI `snapshot`, `MessageBubble` and
`ChatWidget.helpers` vitest specs (incl. the `?apiServer=` control-char bypass);
Manager `chat-panel`, `agent-response-card` and `conversations` vitest specs. Key
behavioural tests were mutation-checked (revert → red → restore), including the
parser-derived-forms scrub.

---

## 🔒 fix(infra): close default-open deployment surfaces across compose, installer, GCP, Helm, Kustomize and CI (2026-09-26)

**Repo:** EDDI (`fix/security-infra`)

### What changed and why

A pass over the deployment / IaC / CI-CD surfaces found the `AuthStartupGuard` /
`HighValueSurfaceGuard` protections were undercut by shipped defaults that
pre-set the unauthenticated opt-outs and bound services to all interfaces, so the
guards never protected a default deployment. Fixes, config/script/YAML only:

- **Compose bind to loopback.** [`docker-compose.yml`](../docker-compose.yml)
  and [`docker-compose.postgres-only.yml`](../docker-compose.postgres-only.yml)
  now publish EDDI on `127.0.0.1` by default with an `EDDI_BIND` override;
  exposing an unauthenticated instance off-box is now an explicit choice. Add-on
  overlays ([`chroma`](../docker-compose.chroma.yml),
  [`ollama`](../docker-compose.ollama.yml), [`nats`](../docker-compose.nats.yml),
  [`monitoring`](../docker-compose.monitoring.yml),
  [`openwebui`](../docker-compose.openwebui.yml)) bind their published ports to
  `127.0.0.1`; Prometheus drops `--web.enable-lifecycle`; Grafana admin creds are
  overridable (dev default documented); the Open WebUI image is pinned off the
  rolling `:main`. `Dockerfile.demo` now runs as a non-root user.

- **GCP provisioner** ([`gcp/provision-vm.sh`](../gcp/provision-vm.sh)):
  firewall rules are scoped to the caller's detected IP by default
  (`--source-ranges` to override, `--i-understand-public` to open to `0.0.0.0/0`);
  `create` refuses an open-access VM unless `--with-auth` or `--i-understand-public`
  is given; a random `KC_BOOTSTRAP_ADMIN_PASSWORD` is generated per VM (printed
  once) instead of `admin/admin`; the public Keycloak vhost blocks `/admin`; the
  success banner no longer prints stale `eddi/eddi` credentials.

- **Installer** ([`install.sh`](../install.sh)): `.env`/`.eddi-config` created
  mode `600` before the vault key is written; `EDDI_BRANCH` derives from a pinned
  `EDDI_VERSION` (release tag) rather than always `main`, in the installer and the
  generated `eddi update` CLI; auth wizard clarifies the localhost-only default.

- **CI/CD** ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)): a
  release tag build now fails unless the tagged commit is an ancestor of
  `origin/main`; cosign signs the pushed image **digest** rather than a tag; the
  cosign identity regex is tightened from `tags/.+` to the release-tag pattern
  (also in NOTES.txt, [`docs/release-signing.md`](../docs/release-signing.md),
  [`docs/build-reproducibility.md`](../docs/build-reproducibility.md)).
  [`.github/workflows/auto-approve-copilot.yml`](../.github/workflows/auto-approve-copilot.yml)
  pins the auto-approval to the reviewed commit (`commit_id`) and skips PRs that
  touch `.github/**`.

- **Helm** ([`helm/eddi`](../helm/eddi)): the chart refuses to render when
  `ingress.enabled && !oidc.enabled` unless `eddi.security.allowUnauthenticated`
  is set explicitly; `EDDI_MCP_ALLOW_UNAUTHENTICATED` /
  `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED` are no longer derived from OIDC being
  off (default `false`, require explicit opt-in); NOTES.txt warns when
  `ingress.tls` is empty. In-chart MongoDB now runs with `--auth` and a required
  `mongodb.rootPassword`, its credentialed connection string moving to the
  projected Secret; NetworkPolicies isolate the MongoDB / PostgreSQL / NATS /
  Keycloak pods to the EDDI pod.

- **Kustomize**: PKCE `S256` added to the `eddi-frontend` client across all three
  realm copies ([compose](../keycloak/eddi-realm.json),
  [helm](../helm/eddi/files/eddi-realm.json),
  [k8s](../k8s/overlays/auth/eddi-realm.json)); NetworkPolicies isolate the
  [MongoDB](../k8s/overlays/mongodb/mongodb-networkpolicy.yaml) and
  [NATS](../k8s/overlays/nats/nats-networkpolicy.yaml) overlays.

- **Container image** ([`src/main/docker/Dockerfile`](../src/main/docker/Dockerfile)):
  application files are copied `--chown=root:0` so the runtime user (185) cannot
  overwrite its own jars; `USER 185` and the digest-pinned base are unchanged.

### Deliberately deferred / not changed (verified against current files)

- Keycloak Helm `KC_BOOTSTRAP_ADMIN_PASSWORD` stays `value: {{ required … }}` — a
  deliberate two-path design (`secretKeyRef` on kustomize, `required` value on
  Helm) that `DeploymentManifestsTest` enforces.
- Seeded `viewer/viewer` and `user/user` and `eddi-frontend` direct-access-grant
  are kept: the Auth E2E tier (`ui/manager/scripts/make-test-realm.mjs`,
  `e2e/auth`) depends on both, and the realm-drift tests require one shared realm.

---

## 🔒 fix(infra): review follow-ups on the loopback default and demo image (2026-09-26)

**Repo:** EDDI (`fix/security-infra`)

### What changed and why

Follow-ups from adversarial review of the change above:

- **The loopback default broke the GCP remote-deploy path.** With EDDI bound to
  `127.0.0.1`, the provisioner's health poll against `http://<external-ip>:7070`
  never succeeded and the advertised dashboard/API/MCP were unreachable on the
  VM. [`gcp/provision-vm.sh`](../gcp/provision-vm.sh) now exports
  `EDDI_BIND=0.0.0.0` into the VM startup script (off-box exposure stays governed
  by the IP-scoped firewall and the auth/`--i-understand-public` gate);
  [`install.sh`](../install.sh) exports and persists `EDDI_BIND` to `.env` so
  `eddi restart` keeps it. Monitoring (Grafana/Prometheus) stays loopback-bound on
  the VM and the success banner now says SSH-tunnel/localhost instead of falsely
  advertising the external IP; the pointless 3000/9090 firewall rule is dropped.
- **`Dockerfile.demo`** now pre-creates `/opt/eddi/data` (`chown 185:0`) before
  `USER 185`, mirroring the production image — a non-root process cannot create
  the audit dead-letter directory at runtime, and the miss silently drops audit
  entries.
- **Helm `NOTES.txt`** warns that the shipped realm seeds `viewer/viewer` and
  `user/user` with known passwords and that they must be disabled/re-passworded
  for production (removing the seed users stays deferred — the Auth E2E tier needs
  them; the tracked follow-up is to derive the E2E realm from a passwordless
  shipped realm and disable ROPC on `eddi-frontend`).
- **Cross-branch dependency:** the secrets branch adds a vault master-key strength
  gate that rejects the placeholder key
  [`docker-compose.openwebui.yml`](../docker-compose.openwebui.yml) defaults
  to; that file now sets `EDDI_VAULT_ALLOW_WEAK_MASTER_KEY=true` (an opt-out that
  branch is adding; harmless as an unknown env var until it merges) so the demo
  keeps booting.
- **CI Deployment-Manifests render.** Making `mongodb.rootPassword` required broke
  the `manifest-lint` job's `helm template`/`helm lint` runs, which render the
  chart without it. [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
  now passes `--set mongodb.rootPassword=<placeholder>` on every render that
  leaves MongoDB enabled (the default renders and the guard-failure cases alike, so
  each guard case still fails on the guard it tests, not on the missing password),
  mirroring how it already supplies the vault key and the PostgreSQL password.

---

## 🔒 fix(security): close SSRF, unbounded-download and resource-exhaustion gaps on outbound paths (2026-09-26)

**Repo:** EDDI (`fix/security-ssrf-outbound`)

Eight verified findings across the outbound-request and archive-import paths. Grouped by what they let an
end user, the LLM, or a config author reach or exhaust.

### What changed

- **Unbounded response downloads (MEDIUM).** The attachment forwarder, web-scraper tool and PDF reader
  all buffered a whole response before checking its size, and the PDF path staged an unbounded file on
  disk first. A shared
  [`BoundedBodyReader`](../src/main/java/ai/labs/eddi/engine/httpclient/BoundedBodyReader.java)
  now reads through a byte cap and a wall-clock deadline (the same shape as the crawler's
  `SafeHttpPageFetcher.readBounded`), exposed on
  [`SafeHttpClient`](../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java) as
  `sendValidatedBounded`/`sendBounded` returning a `BoundedResponse`.
  [`AttachmentForwarder`](../src/main/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarder.java),
  [`WebScraperTool`](../src/main/java/ai/labs/eddi/modules/llm/tools/impl/WebScraperTool.java) and
  [`PdfReaderTool`](../src/main/java/ai/labs/eddi/modules/llm/tools/impl/PdfReaderTool.java) use
  it, capped to the per-file/tool limits (`eddi.attachments.max-forward-bytes`, new
  `eddi.tools.web-scraper.max-response-bytes`, new `eddi.tools.pdf-reader.max-download-bytes`).
  [`AttachmentTextExtractor`](../src/main/java/ai/labs/eddi/modules/llm/tools/impl/AttachmentTextExtractor.java)
  now loads PDFs with a temp-file scratch cache (PDFBox's default is unlimited in-memory) and stops at a
  page cap (`eddi.attachments.extraction.max-pages`, default 500) as well as the existing char cap.
- **httpcalls / A2A buffered the full body before the size check (LOW).**
  [`HttpClientWrapper`](../src/main/java/ai/labs/eddi/engine/httpclient/impl/HttpClientWrapper.java)
  now streams the Vert.x response into a size-capped sink (`CappedBufferSink`) that fails the transfer
  mid-stream at `maxLength` instead of buffering then rejecting.
  [`A2AToolProviderManager`](../src/main/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManager.java)
  reads the Agent Card and task responses through `BoundedBodyReader` at its 1 MB cap.
- **OpenAPI spec parsing fetched external `$refs` and read local files (MEDIUM).**
  [`McpApiToolBuilder.parseSpec`](../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java)
  now sets `parseOptions.setSafelyResolveURL(true)` so every `$ref` the parser follows goes through
  swagger-parser's blocked-URL resolver, and runs `rejectCloudMetadataTarget` on the spec location
  before fetching — `isValidHttpUrl` alone let `http://169.254.169.254/...` through. Stale Javadoc
  corrected.
- **Shared cookie jar across all users (MEDIUM).**
  [`HttpClientModule`](../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java)
  built one application-scoped `WebClientSession`, so a `Set-Cookie` from user A's httpcall was
  replayed on user B's call to the same host. It now uses a plain `WebClient` with no cookie store;
  `VertxHttpClient` and `HttpClientWrapper` updated to the plain type.
- **URL-validator range/family gaps (LOW).**
  [`UrlValidationUtils`](../src/main/java/ai/labs/eddi/modules/llm/tools/UrlValidationUtils.java)
  now unpacks and re-checks every IPv6 embedding of an IPv4 address (IPv4-compatible `::/96`, NAT64
  `64:ff9b::/96` and `64:ff9b:1::/48`, 6to4 `2002::/16`, Teredo `2001::/32` server and client), and
  blocks the missing IPv4 ranges `198.18.0.0/15`, the limited broadcast `255.255.255.255` and
  `192.0.0.0/24`, plus the Azure WireServer `168.63.129.16` and OCI `192.0.0.192` metadata endpoints.
  (The rest of `240.0.0.0/4` is deliberately left reachable — the shipped validator contract, pinned
  by `UrlValidationUtilsDeepBranchTest`, treats reserved-future-use space as allowed.)
- **Metadata guard missing on model/vector-store endpoints (LOW).** The OpenAI and Ollama language-model
  builders, `EmbeddingModelFactory` (Ollama), `EmbeddingStoreFactory` (pgvector, Elasticsearch, Qdrant,
  Chroma) and `AgentSetupService` (llmBaseUrl) now call `rejectCloudMetadataTarget` where the endpoint
  URL is built — a guard, not an SSRF lockout, so legitimate internal hosts stay reachable.
- **Redirect leaked a custom credential header (MEDIUM, ssrf-protection-off path).**
  [`ApiCallExecutor`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java)
  now disables redirect-following for any request whose header carries a connection, vault or caller
  credential — Vert.x strips only Authorization/Cookie/Proxy-Authorization cross-origin, so a custom
  `X-Api-Key` would otherwise survive a redirect to another host.
  [`SafeHttpClient`](../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java)'s
  cross-origin strip set gained the common custom-credential header names.
- **ZIP import had no bomb limits (MEDIUM).**
  [`ZipArchive.unzip`](../src/main/java/ai/labs/eddi/backup/impl/ZipArchive.java) now caps entry
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

### Follow-up (CodeRabbit review round)

- **Inline `$ref` guard now inspects the decoded document, not only the raw text.** A JSON
  or YAML escape in the key (a Unicode- or hex-escaped `$`) hid `$ref` from the text scan
  while swagger-parser still resolved it — so `"<escaped>ref": "/etc/passwd"` slipped past.
  `McpApiToolBuilder.rejectUnsafeInlineRefs` keeps the text scan as a first gate and then walks
  the tree built exactly as `OpenAPIV3Parser.readContents` builds it (`DeserializationUtils`,
  then the plain JSON/YAML mapper it falls back to), checking every decoded `$ref`. Content
  neither deserializer accepts is refused (the parser would reject it anyway). New
  `McpApiToolBuilderTest` cases for the escaped key in JSON and YAML, and an escaped internal
  `#/` ref that must still pass.
- **`WebScraperTool` flags truncated pages.** A body cut off by the size cap or the read
  deadline used to be decoded and handed to the model as if it were the whole page. Each tool
  now appends `[Note: the page response was truncated …; the content above is partial.]`, and a
  multi-byte UTF-8 character split by the cut is dropped rather than decoded to U+FFFD. New
  `WebScraperToolExtendedTest.TruncatedResponseTests`.
- The decision-log row on redirects now describes the design that ships (hop-level strip plus
  the reference-credential redirect disable), not the superseded first cut.

### CI fixups

- Documented the three new config keys in
  [`configuration-reference.md`](configuration-reference.md)
  (`eddi.tools.web-scraper.max-response-bytes`, `eddi.tools.pdf-reader.max-download-bytes`,
  `eddi.attachments.extraction.max-pages`) — `ConfigurationReferenceCoverageTest` requires every
  read property to be written down.
- `HttpClientWrapper` gained a package-private `executePipedSend` seam so the streamed-body
  behaviour is unit-testable: only the Vert.x transport (which needs a live exchange) is stubbed,
  while `doSend`'s body encoding and the full `handleResponse` logic (Content-Length check,
  body-from-sink, size-cap → 503, header handling) run for real. `HttpClientWrapperSendBranchTest`
  rewritten to the streaming flow and now also asserts the sink actually caps an oversize body.

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

---

## 🔒 fix(templating): data is never rendered as a template; snippets are scoped to the agent's workspace (2026-09-26)

**Repo:** EDDI (`fix/template-injection`)

### What changed and why

Qute templates are for what an agent designer writes. Several paths also rendered
*data*: text that a chat client, a user, an upstream API or an LLM controlled.
Whoever controlled that data could write Qute and have the server evaluate it
against the full template data model: `{vars.*}` (deployment-wide global
variables), `{snippets.*}`, `{properties.*}`, or `{#for i in 2000000000}` and
`{s.repeat(...)}` to pin a worker or exhaust the heap.

- **C4a — context-supplied output and quick replies.**
  - The problem: any `/say` context key starting with `output` (or
    `quickReplies`) of type `object` becomes agent output, and the templating
    task rendered it.
  - `OutputGenerationTask` now stores those entries with the new
    `IData#isVerbatim()` flag set, and `OutputTemplateTask` skips verbatim
    entries.
  - This also covers the output that an httpcall's `postResponse` build
    instructions produce. It travels the same `context:output` path and has
    already been rendered once, with the HTTP response substituted in.
  - The flag is **persisted** in `ResultSnapshot`, as `verbatim`, omitted from
    the stored document while false. A tool-call HITL resume reloads memory and
    re-enters the pipeline *after* the output task (review finding #2).
- **Rendered output is frozen (review #3).** After the templating task renders
  an output or quick reply, it marks the entry verbatim, and it marks its
  `:preTemplated` / `:postTemplated` twins verbatim too. An agent whose workflows
  each end with `ai.labs.templating` would otherwise render, in its second pass,
  whatever the first pass substituted in. An example is a property captured from
  user input that reads `{vars.apiKey}`.
- **C4b — `fromObjectPath` values.** `PropertySetterTask` and
  `PrePostUtils.executePropertyInstructions` sent a String resolved through
  `fromObjectPath` to the templating engine. Such a value is now stored as
  resolved. The authored `valueString` is still a template.
- **Sub-agent system prompts (review #8).** `CreateSubAgentTool` stored the
  system prompt that the parent *model* writes, which a chat user can steer, as a
  live template that `LlmTask` renders on every turn. The tool now wraps it in
  `TemplateEscaping.unparsedBlock`, so the rendered prompt is byte-identical and
  inert. `unparsedBlock` now keeps leading pipes in front of the block: Qute reads
  every `|` right after the opening brace as part of the opener, so a prompt that
  began with `|` produced a block the single-pipe terminator never closed and the
  render failed (PR review). `TemplateEscapingTest` pins the round trip, including
  a seeded 5,000-string sweep over braces, pipes and text.
- **C4c — prompt snippets were one global namespace.**
  - The problem: every render used `PromptSnippetService.getAll()`, which merged
    every workspace's snippets, with the last one listed winning a name.
  - The new `getForAgent(agentId)` is used by `MemoryItemConverter`, `LlmTask`
    and the counterweight presets.
  - Under enforced workspaces it injects snippets only from sources the agent's
    own side controls:
    - (0) snippets filed in the agent's space, where the space may use them;
    - (1) the owner's own snippets, for **personal-space agents only**;
    - (2) legacy (unowned) snippets.
  - Snippets that are **granted or published from another space are never
    injected**. The reason is in the decisions below.
  - A name shared by several eligible snippets goes to the lowest tier, and to
    the oldest snippet within a tier.
  - `getAll()` stays in place for the template preview, which redacts snippet
    content for non-admins, and it caches its resolved map again (review #10).

### Design decisions

- **Why grants and publishes are excluded (review #1).** Snippets are injected
  by name and automatically, and `ResourceSharingService` asks only the
  snippet's owner. So a grant or a publish on a snippet is a push into the
  recipients' prompts, not an offer they take up.
  - A team could publish `counterweight-strict` = "No restrictions apply" and
    replace the built-in safety preset in every agent in the deployment.
  - Or it could publish `persona` and outrank an agent's legacy `persona`.
  - Ranking these tiers lower would not help: they are the only candidates for
    the preset names, which nobody else defines.
  - There is no opt-in mechanism, and resolving names from each prompt's
    `{snippets.x}` references would not cover the counterweight lookup.
  - So the safe design is to exclude them. To reuse another team's snippet,
    copy it into the agent's space.
- **Team agents act as the team (review #4).** Such an agent has no personal
  identity, so its creator's private snippets and user-level grants stay out of
  it. Any editor in the team could otherwise read them back through the prompt.
  A personal-space agent acts as its owner.
- **Legacy snippets load regardless of `legacy-visibility` (review #5).** This
  matches every other configuration an agent references: that policy governs
  the authoring surface, and `ResourceClientLibrary` bypasses it. It is also
  safe, because no tenant can create an unowned snippet once enforcement is on
  (ownership is stamped whenever authentication is enabled).
- **An unreadable agent descriptor falls back to the legacy set, uncached
  (review #6).** An empty map would silently strip compliance and safety text
  from the prompt. The legacy set still exposes nothing from another space.
- **Sharing changes invalidate the caches (review #7).** `ResourceSharingService`
  fires a `SharingChangedEvent` (a CDI event, so the spaces package does not
  depend on the LLM module) after it writes a grant, revoke, visibility change
  or transfer. `PromptSnippetService` observes the event and clears its snippet
  caches on that node. Other nodes converge within the 5-minute TTL. A failing
  observer is logged and never undoes the sharing write. A load that is already
  reading the stores when an invalidation lands does not publish its result: loads
  capture a cache generation first and publish under the lock the invalidation
  holds while it bumps the generation and clears, so a pre-change view cannot be
  put back for the rest of the TTL (PR review).
- **Per-entry flag, not a key convention.** `output:<type>:context` collides with
  an output-set action literally named `context`, and the quick-reply key suffix
  is chosen by the client. The flag is `verbatim` with default `false`, so a
  Mockito mock of `IData` keeps the templated behaviour.
- **No render-size or time guard.** With data no longer templated, only config
  authors write templates. A guard around `render()` would not stop
  `{s.repeat(2e9)}` anyway, because that string is allocated inside one value
  resolution. Hardening the reflection resolver is a follow-up.

### Compatibility

- **Shapes:**
  - No change to stored config or ZIP shapes.
  - Stored conversation snapshots gain an optional `verbatim: true` on result
    entries. Older documents load unchanged.
  - The same additive field appears wherever the raw snapshot is served:
    `GET /conversationstore/conversations/{conversationId}`,
    `GET /agents/{conversationId}/approval-status?detail=full` and the MCP
    `get_approval_status` tool with `detail=full`. It is omitted while false.
    The simple conversation views and every other REST and MCP response are
    unchanged.
- **Behaviour:**
  - Context-supplied output and `fromObjectPath` strings containing `{...}` are
    now delivered literally. Neither behaviour was documented.
  - A sub-agent's `{...}` markers are now literal.
  - A second templating pass no longer re-renders.
- **Snippets, under enforced workspaces:**
  - An agent no longer gets snippets from other spaces, including granted and
    published ones.
  - A team-space agent no longer gets its creator's personal snippets.
  - With workspaces off, nothing changes except that duplicate names now
    resolve deterministically to the oldest snippet.

**Files:** [`IData.java`](../src/main/java/ai/labs/eddi/engine/memory/IData.java),
[`Data.java`](../src/main/java/ai/labs/eddi/engine/memory/model/Data.java),
[`ConversationMemorySnapshot.java`](../src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java),
[`ConversationMemoryUtilities.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java),
[`OutputGenerationTask.java`](../src/main/java/ai/labs/eddi/modules/output/impl/OutputGenerationTask.java),
[`OutputTemplateTask.java`](../src/main/java/ai/labs/eddi/modules/templating/OutputTemplateTask.java),
[`PropertySetterTask.java`](../src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java),
[`PrePostUtils.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java),
[`CreateSubAgentTool.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/CreateSubAgentTool.java),
[`PromptSnippetService.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/PromptSnippetService.java),
[`MemoryItemConverter.java`](../src/main/java/ai/labs/eddi/engine/memory/MemoryItemConverter.java),
[`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`CounterweightService.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/CounterweightService.java),
[`ResourceSharingService.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`SharingChangedEvent.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/SharingChangedEvent.java),
[`prompt-snippets-guide.md`](prompt-snippets-guide.md).

**Tests:**
- `ContextSuppliedOutputTemplatingTest`: real output and templating tasks on a
  real Qute engine, including a JSON persistence round trip and a double
  templating pass.
- New cases in `PropertySetterTaskTest`, `PrePostUtilsTest`,
  `CreateSubAgentToolHitlTest`, `ResourceSharingServiceTest`,
  `CounterweightServiceTest` and `MemoryItemConverterNamespacesTest`.
- `PromptSnippetServiceTest$WorkspaceScoping`, with one test per tier plus the
  grant, publish, legacy-vs-publish, team-vs-personal, admin-only-legacy,
  unreadable-descriptor and invalidation cases.

---

## 🔒 chore(security): allowlist the known gitleaks false positives of PRs #835, #838, #840 and #847 (2026-09-26)

**Repo:** EDDI (`chore/gitleaksignore-fix-prs`)

### What

The Secret Scanning job restores [`.gitleaksignore`](../.gitleaksignore) from the base
branch before it scans a pull request, so a PR cannot allowlist its own false positives: the
entry only takes effect once it is on `main`. Four open PRs were red for that reason alone.
This adds their fingerprints to `main` so their scan passes when re-run:

- **#835** (`fix/outbound-http-hardening`): a stand-in OpenWeatherMap key in
  `WeatherToolExtendedTest`, used to prove the operator's key never reaches the model or the log.
- **#838** (`fix/secret-scope-vault`): one fixture token in `PrePostUtilsSecretScopeTest` and
  `PropertySetterTaskSecretScopePathsTest`, standing in for a user-entered secret-scope value.
- **#840** (`fix/workspace-authz-scoping`): a 32-hex stand-in for an export archive key's random
  part in `RestExportServiceTest`. The block is byte-identical to the one #840 carries and sits at
  the end of the file, so the two merge without a conflict; the other groups are inserted above
  the PR #750 block for the same reason.
- **#847** (`fix/deployment-defaults`): four `curl-auth-user` hits in `install.sh`, where the
  installer probes Grafana with its factory default admin login in order to rotate it to a
  generated password.

Each flagged line was read and confirmed to be a test fixture or Grafana's published default,
never a real credential. Local gitleaks, run per branch over `origin/main..<branch>` with the new
ignore file, reports 0 findings for all four.

---

## 🔒 fix(security): engine-reserved context keys are no longer accepted from clients (2026-09-26)

**Repo:** EDDI (`fix/security-context-keys`)

### What changed and why

Several conversation context keys are written by EDDI itself when it drives a
conversation on its own behalf — group member turns and delegated sub-agent
conversations — and the engine then trusts them to decide what a turn may do:
which group's shared memories are visible, which dynamic-agent policy governs the
turn, which agents count as created by the conversation, and how deep a
delegation chain already is. Those same keys were accepted verbatim in the
context of any client request, so a caller could assert them and be believed.

- **New `ClientContextGuard`** removes the reserved keys (`groupId`,
  `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`,
  `dynamicCreatedAgentIds`, `delegationDepth`) at every point where context
  crosses from a client into the engine: conversation start and turns over REST
  (plain and streaming, which also covers managed conversations) and the MCP
  managed-conversation path's trigger context. Internal callers reach
  `IConversationService` directly and keep setting them. The request still
  succeeds; only the reserved entries are dropped.
- **Group policy carries over.** A conversation governed by a group's
  dynamic-agent policy now keeps the most recent policy on a turn that carries no
  group context (such as one its owner sends into the member conversation
  directly), instead of falling back to the permissive standalone default. An
  entry that is present but is not a context entry resolves to the disabled
  policy rather than being skipped for an older or the standalone one.
- **`groupId` properties no longer select a memory scope.** The `usermemory`
  tool's group scope used to fall back to a `groupId` conversation property;
  properties are client- and input-settable, so only the orchestrator-injected
  context value counts now.

### Behaviour changes operators should know

- `{context.<reserved key>}` renders empty for a client-started turn. The
  Manager's Platform Operator drawer sends the viewed group as `groupId`, so its
  "(group …)" hint in the operator prompt is now blank; moving that hint to a
  non-reserved key is a follow-up for the Manager.
- A deployment whose callers are all trusted can re-permit specific keys with
  `eddi.conversation.client-context.permitted-reserved-keys` (default: none; a
  startup warning is logged when set).

**Files:**
[`ClientContextGuard.java`](../src/main/java/ai/labs/eddi/engine/security/ClientContextGuard.java),
[`RestAgentEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`RestAgentEngineStreaming.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java),
[`McpConversationTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`DynamicAgentToolsProvider.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/DynamicAgentToolsProvider.java),
[`ContextualToolsProvider.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ContextualToolsProvider.java),
[`passing-context-information.md`](passing-context-information.md#reserved-context-keys),
[`user-memory.md`](user-memory.md#group-memory)

**Tests:** `ClientContextGuardTest`, `RestAgentEngineClientContextTest`,
`McpConversationToolsReservedContextTest`, `DynamicAgentGroupPolicyCarryOverTest`,
and `ContextualToolsProviderGroupIdTest` (property case inverted). Each was
mutation-checked against a reverted fix.

---

## 🔒 fix(templating): restricted runtime template engine; data is never rendered as a template (2026-09-26)

**Repo:** EDDI (`fix/security-qute-engine`)

### What changed and why

EDDI renders Qute templates parsed at runtime from agent configuration. Two things about that path were hardened.

**1. Data is no longer treated as template text.** A template is what an author wrote; what reaches it at render time is data. This layer converged on the implementation that landed on `main` with #832 (`fix/template-injection`): `fromObjectPath` values are stored as resolved in both `PropertySetterTask` and `PrePostUtils.executePropertyInstructions`, and output and quick replies that arrive through context (caller-supplied, or built by a `postResponse`) carry #832's persisted `IData#isVerbatim()` marker, which `OutputTemplateTask` honours and also sets on every entry it renders. This branch originally carried its own per-turn marker (`isPreRendered`); it was dropped in favour of `isVerbatim`, which is a superset (it survives a HITL resume). This PR's own contribution is layer 2 below plus the MCP variable-name sanitizing; its data-path tests (`PropertySetterTaskDataIsNotTemplateTest`, `PostResponseDataIsNotRenderedTwiceTest`, now asserting `isVerbatim`) are kept as additional coverage of #832's fix.

Generated text that does end up in a template's source is kept literal: the system prompt a model chooses in `create_sub_agent` is stored inside an unparsed block (`TemplateEscaping.unparsedBlock`, also from #832), and `McpApiToolBuilder` reduces OpenAPI parameter names to plain identifiers before copying them into `{...}` placeholders (collisions get distinct suffixes; the query key itself keeps the spec's name). A path placeholder the spec does not declare as a parameter never takes a declared parameter's variable name — `{item-id}` beside a declared `item_id` becomes `{item_id_2}` and stays unfilled, instead of silently addressing the resource `item_id` names.

**2. Runtime templates use EDDI's own, restricted engine.** The Quarkus-injected engine is meant for build-time-validated application templates and exposes more than agent configuration should reach, including the `config:` namespace. `RuntimeTemplateEngineFactory` now builds a separate engine from it through allow-lists:

- namespaces: `vault`, `eddivault`, `connection`, `vars`, `caller`, `uuidUtils`, `json`, `encoder`, `str`, `time`; `config:`, `inject:` and `cdi:` resolve to nothing, and `str:eval` is dropped;
- sections: `if`, `for`/`each`, `let`/`set`, `with`, `when`/`switch` (no `include`, `insert`, `eval`, `fragment`, `cache`, user tags);
- `ReflectionValueResolver` replaced by `PropertyAccessValueResolver`: record components, public no-argument getters and public fields only — no method with arguments, no non-getter methods, no `getAndX` read-modify-write methods (`AtomicInteger.getAndIncrement` is getter-shaped but mutates), no reflection-sensitive types;
- a missing value always renders empty, independent of `quarkus.qute.property-not-found-strategy`;
- per-render bounds: `eddi.templating.max-output-chars` (default 2,000,000; output length and any single evaluated string) and `eddi.templating.max-iterations` (default 100,000; summed over nested loops, checked before a loop starts). `0` disables a bound.

The audit of shipped configs, `docs/agent-configs`, the docs and the tests found only features on these allow-lists in use (`?:`, `{#for}` with `_count`/`_index`, `{#if}`, `.size`, `.raw`, `{|…|}`, EDDI's string methods and `uuidUtils`/`json`/`encoder`, the pass-through references).

### Behaviour change operators should know about

- A property instruction whose `fromObjectPath` value happened to contain template syntax used to have it evaluated; it is now stored literally. Move any intended template into `valueString`.
- A runtime template using `config:`, `inject:`/`cdi:`, `{#include}`/`{#eval}`, or calling a method with arguments on a non-string object no longer resolves (namespaces render empty; unknown sections fail the render like any template error).
- Renders exceeding the new bounds fail like any template error.

### Tests

`RuntimeTemplateEngineFactoryTest` (engine built exactly as production builds it, from a source that carries a real `config:` namespace, `inject:`, `str:eval` and full reflection — each test asserts the feature is live in the source first), `PropertySetterTaskDataIsNotTemplateTest`, `PostResponseDataIsNotRenderedTwiceTest` (real `PrePostUtils` → `OutputGenerationTask` → `OutputTemplateTask` on a real memory), `RestTemplatePreviewTest$RestrictedEngine`, `McpApiToolBuilderVariableNameTest`, `CreateSubAgentToolPromptEscapingTest`, and `RuntimeTemplateEngineIT` against the running application (the only place Quarkus's generated extension resolvers exist). Each security test was mutation-checked against a reverted fix.

**Files:** [`RuntimeTemplateEngineFactory.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/RuntimeTemplateEngineFactory.java), [`PropertyAccessValueResolver.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/PropertyAccessValueResolver.java), [`BoundedLoopSectionHelperFactory.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/BoundedLoopSectionHelperFactory.java), [`TemplatingEngine.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/TemplatingEngine.java), [`application.properties`](../src/main/resources/application.properties), [`McpApiToolBuilder.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java); docs: [`security.md`](security.md#runtime-template-engine), [`output-templating.md`](output-templating.md), [`properties.md`](properties.md), [`httpcalls.md`](httpcalls.md), [`configuration-reference.md`](configuration-reference.md), [`AGENTS.md`](../AGENTS.md).

### Known limits / next

- An OpenAPI `servers[0].url` containing braces still reaches the httpcall URL template; with the restricted engine it can no longer read anything, but it is not escaped.
- A model-written sub-agent prompt that itself mentions a `${vault:…}` reference renders with visible `{|`/`|}` markers around it (the known double-wrap limit of `LlmTask.escapeConfigReferenceMentions`); nothing is evaluated.

---

## 🔒 security(secrets): vault master-key, checksum, crypto and reference hardening (2026-09-26)

**Repo:** EDDI (`fix/security-secrets-crypto`)

### What changed and why

A pass over verified secrets/cryptography findings in the vault and related
subsystems. Each item is a hardening; no configuration format changes for agent
authors, and existing vaults keep working.

- **Master-key strength gate at startup.** [`VaultMasterKeyStrength`](../src/main/java/ai/labs/eddi/secrets/crypto/VaultMasterKeyStrength.java)
  rejects a weak or publicly-known vault master key (too short, too low-entropy, or a
  well-known demo/placeholder such as the `docker-compose.openwebui.yml` key).
  [`VaultSecretProvider`](../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java)
  fails startup in production and warns in development/test, mirroring `AuthStartupGuard`.
  A production opt-out `eddi.vault.allow-weak-master-key` (default `false`, mirroring
  `eddi.security.allow-unauthenticated`) downgrades the failure to a WARN so a
  brownfield deployment already running a weak-but-functional key can boot, rotate to a
  strong key via `POST /secretstore/secrets/admin/rotate-kek`, and then remove the flag
  — rather than being wedged (the key cannot be changed without a booted vault).

- **Keyed, tenant-bound secret checksum.** The plain unsalted `SHA-256(plaintext)`
  stored beside each secret is replaced by an HMAC over `tenantId + NUL + plaintext`
  ([`VaultChecksum`](../src/main/java/ai/labs/eddi/secrets/crypto/VaultChecksum.java))
  keyed with a **random deployment key persisted KEK-wrapped** (encrypted with the KEK
  like a DEK, never in the clear), so it can no longer be brute-forced offline nor used
  to link equal values across rows/tenants. Because the key is wrapped rather than
  KEK-derived it **survives KEK rotation** — `rotate-kek` re-wraps it with the new KEK
  alongside the DEKs, so it unwraps to the same value before and after and every `h1:`
  checksum keeps verifying and a legitimate same-value re-setup does not spuriously fail. Versioned (`h1:` prefix) with a legacy bare-SHA-256 fallback,
  so existing rows keep verifying and migrate on next write. The checksum is no longer
  returned over REST. De-duplication and value-match go through
  `ISecretProvider.matchesChecksum` (the caller no longer holds the key).

- **Ciphertext bound to its row (GCM AAD).** Secrets are now sealed with
  `tenantId|keyName|dekId` as GCM Additional Authenticated Data, so a ciphertext
  cannot be swapped onto another key/row by someone with database write access. A
  no-AAD decrypt fallback keeps pre-existing rows readable with no migration.

- **Reserved namespace for agent signing keys.** Agents' Ed25519 private keys live
  under a reserved `agent-signing-key:` key-name prefix that
  [`SecretResolver`](../src/main/java/ai/labs/eddi/secrets/SecretResolver.java)
  refuses to resolve from a configuration or template, closing a path where an editor
  could exfiltrate an agent's own signing key via an httpcall header. The internal
  signing service still reads the key directly through the provider. Peer-verification
  logs an unsigned prior entry at ERROR under `requirePeerVerification`, and
  `docs/architecture.md` is corrected to describe peer verification as **detect-and-log,
  not a gate** (it does not yet drop/block unverified entries — see residuals).

- **Global variables that resolve to a secret are admin-only to write.** Because
  agent-secret grants are checked at deploy time, a non-admin editor could otherwise
  redirect a deployed agent's `${vars:key}` at an ungranted secret by editing the
  variable afterward. Storing a global variable whose value contains a `${vault:…}`,
  `${eddivault:…}` or `${connection:…}` reference now requires the `eddi-admin` role.

- **Sensitive-data leak fixes.** The export scrubber no longer lets a vault reference
  exempt a plaintext secret sitting beside it in the same value — the remainder is
  split on punctuation as well as whitespace (so `sk-live-…,` is still judged as a
  key), and in a credential-named field (`password`, an `Authorization` header, …)
  only an auth-scheme word such as `Bearer` and separators may sit beside the
  reference, so a short low-entropy password next to one is redacted too; Slack
  event, follow-up and group-discussion logging records message length only, at every
  level (no text preview, not even at DEBUG); and the pipeline task-error
  OpenTelemetry span carries the redacted audit summary and only the exception type,
  never the raw exception message.

### Migration / back-compat notes

- **Checksum:** legacy bare-SHA-256 rows keep verifying; new writes are keyed. The
  checksum key is a random deployment key persisted KEK-wrapped and **re-wrapped during
  KEK rotation**, so rotation does **not** invalidate stored checksums (verified by a
  KEK-rotation + same-value re-verify test). First use on an upgraded deployment lazily
  generates and wraps the key; it touches no tenant DEK, so it has no boot/store side
  effects. The key is created **only when the store confirms none exists**, through a
  new atomic `ISecretPersistence.setMetaValueIfAbsent` (Mongo `$setOnInsert` on the
  unique meta-key index, Postgres `ON CONFLICT DO NOTHING`): a failed read or a failed
  unwrap of an existing key (e.g. a KEK mismatch) now fails the operation instead of
  replacing the key, and racing nodes converge on the first key written rather than
  the last. There is no KEK-derived fallback key any more — a checksum written under
  one would be unverifiable later — and a store that keeps no metadata refuses to write
  a keyed checksum rather than use a key that would not survive a restart (legacy
  checksums still verify without the key).
- **KEK rotation:** `rotate-kek` now refuses, in production, a new master key the
  startup strength gate would reject (the `allow-weak-master-key` opt-out is for booting
  on a weak key to rotate off it, never for rotating onto one). The checksum key is
  unwrapped and re-wrapped during the verification phase, so a malformed or
  unwrappable one aborts the rotation before any DEK is written; and a failed write
  during the commit — any failure, including an unchecked store error, not only a
  `PersistenceException` — rolls the already-written DEKs back to the old KEK, so the
  vault stays readable under its configured master key and the rotation can be
  retried. Every rotation failure surfaces as `SecretProviderException`.
- **GCM AAD:** existing (no-AAD) rows decrypt via fallback; DEK rotation rebinds AAD
  to the new generation. The grant list is intentionally not part of the AAD so grant
  edits (which do not re-encrypt) keep values readable.

### Known residuals / deferred (not implemented here)

- **Audit HMAC key-id + per-deployment salt** (LOW): deferred. The `v4:` HMAC string
  format is pinned by tests and a per-entry key id would need an `AuditEntry` schema
  field; the most acute exposure (the publicly-derivable demo-key-derived HMAC key) is
  already removed by the startup master-key strength gate above. Recommended follow-up:
  encode a key id into the stored HMAC string and derive the key with a per-deployment
  random salt, keeping the fixed-salt key as the legacy verification key.
- **AAD downgrade tolerance** (LOW): the AAD decrypt path falls back to a no-AAD decrypt
  for legacy rows, so a legacy (no-AAD) ciphertext copied with its IV onto another row
  under the same DEK still decrypts there — exactly as every row did before this change.
  A per-row marker cannot close this, because it lives in the same database the
  attacker writes. The follow-up is an operator-controlled switch (configuration, not
  data) that disables the no-AAD fallback once every legacy row has been re-sealed with
  AAD — which DEK rotation already does.
- **Grant-list tampering** (LOW): `allowedAgents` is authenticated only at the
  deploy-time grant check, not bound into the ciphertext (binding it as AAD would break
  the no-re-encrypt `updateGrant`); a separate keyed MAC over the grant list is a
  future option.
- **Peer-verification hard enforcement** (#3b): still detect-and-log; dropping/blocking
  unverified entries needs a group input-path refactor. Docs now say so.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
