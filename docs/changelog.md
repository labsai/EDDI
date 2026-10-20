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
| [October 2026](changelog/2026-10.md) | 135 | 629 KB |
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

## 🛡️ feat(manager): LLM input/output guardrails editor and `guardrail_triggered` chat notice (2026-10-19)

**Repo:** EDDI (`feat/manager-llm-guardrails`, based on `feat/llm-input-output-guardrails`, PR #1064, because that PR had not merged when this was written)

The backend shipped input/output guardrails on LLM tasks with only a TypeScript type mirror in the Manager, so designers had to edit JSON. This adds the editor and surfaces the new SSE event.

### What changed

- **Guardrails section on the LLM task editor** ([`task-guardrails-section.tsx`](../ui/manager/src/components/editors/llm/task-guardrails-section.tsx)): two ordered lists, `inputGuardrails` and `outputGuardrails`. Each row shows type and action, and can be reordered (order is run order), edited or removed. Each guardrail is edited in a side sheet ([`guardrail-sheet.tsx`](../ui/manager/src/components/editors/llm/guardrail-sheet.tsx)) so the task editor itself stays one flat list.
- **The sheet** edits type, action (with a one-line explanation per action and phase; `reprompt` is not offered on the input side, where the engine treats it as `block`), `maxRetries` (only while the action is `reprompt`), `blockMessage`, `failMode`, `repromptMessage`, and the type's own parameters: `prompt-injection` (extra patterns, mode), `pii-redaction` (kinds, replacement), `regex-filter` (patterns, replacement), `max-length` (`maxChars`), `json-object` (none), and `classifier` (provider picker reusing `ProviderSelect`, base URL, model under the key the chosen provider reads, API key through `SecretKeyPicker` so it is a `${vault:…}` reference, risk, prompt template, unsafe/safe label, label pattern, timeout, prices, retrieved-context toggle on the output side). Warnings for: classifier without a provider, classifier `unsafeLabel` other than `yes` without a `safeLabel` (the deploy is refused), `redact` on a type with nothing to rewrite with, `failMode: open` on a blocking guardrail, a regex filter with no pattern, a type on the wrong side.
- **Streaming caveat**: an output guardrail whose action is `block`, `redact`, `reprompt` (or absent, which is `block`) makes the task buffer its answer; the section says so in amber while that is the case, and plainly otherwise.
- **Chat**: `guardrail_triggered` is handled in `use-chat` and attached to the agent bubble of the turn; [`ChatMessage`](../ui/manager/src/components/chat/chat-message.tsx) shows one quiet line per fired guardrail (phase, type, action). The payload carries no screened text. The standalone Chat UI (`ui/chat`) was left alone: it is an end-user widget whose block message already is the answer, and it surfaces no other internal pipeline events as notices.
- i18n: 109 keys (`llmEditor.gr*`, `chat.guardrail*`) in all 11 locales.

### Design decisions

- **Write only what the author sets.** A new guardrail is `{ "type": … }` with nothing else; blanking a field removes it, an emptied `parameters` map is dropped, an emptied list removes `inputGuardrails`/`outputGuardrails` rather than writing `[]`, and moving off `reprompt` drops `maxRetries`. An older server's strict parser refuses unknown fields (same rule as `task-tool-call-limits.tsx`).
- All `parameters` stay plain strings, as the backend reads them; an unknown `type` and any unknown parameter are preserved untouched.
- The classifier model key follows `modelParamSpec(provider)`, and switching provider moves the model and credential to the new provider's keys.
- Edits apply live (no draft/apply step) because the list behind the sheet reflects them and the editor already has its own save/dirty handling.

### Not done / next

- The `classifier` type exists on `feat/llm-classifier-guardrail`, not yet in a PR. Until that backend lands the editor does not offer it (it is left out of `GUARDRAIL_TYPES`); a stored `classifier` entry still shows as a custom type and keeps its settings panel. The classifier PR adds it back.
- The operator system prompt is unchanged: its guardrail text was already added with the backend PRs.
- Live-turn notices only: a conversation reloaded from history does not show them (the audit ledger holds the record).

---

## 📊 feat(ci): Slack digests estimate real Docker pulls next to the raw count (2026-10-19)

**Repo:** EDDI (`feat/metrics-sweep-filter`)

### What changed and why

The daily digest went from single-digit pull counts to +1,805 overnight. The
arithmetic was right, but the source was not people. Since 2026-10-18 02:47 UTC,
something has pulled all 198 tags of `labsai/eddi` at once, every ~2 hours. The
workflow logs show pull_count rising in steps of exactly 198–200, and Docker
Hub's `tag_last_pulled` puts every tag's last pull within the same millisecond.
The Oct 7 jump of +3,947 has the same shape. Outside those sweeps the counter
rises by a few pulls a day.

The digests keep the absolute count and now add two lines:

- **Likely real pulls**: the pull_count increase minus bot sweeps and our own
  CI pulls, with the number of sweeps filtered out.
- **Versions pulled**: the tags real pulls went to (e.g. `latest ×12, 6.5.0 ×3`).
  The number is how many runs saw that tag pulled, not a pull count, because
  Docker Hub does not publish per-tag counts.

How it works, in [`docker-pull-notify.yml`](../.github/workflows/docker-pull-notify.yml):

- A new step, **Fetch Docker Hub tags**, reads every tag's `tag_last_pulled`
  after it reads pull_count, and records when it finished. Both Docker Hub
  reads now have a 30-second deadline, so a stalled connection can't hold the
  job, and through the concurrency group every later run, until GitHub's
  6-hour limit.
- **Persist metrics history** appends one line per run to `data/runs.csv` on
  the `metrics` branch. Each line splits the increase since the previous line
  three ways. An interval in which ≥ 20 tags were pulled is a sweep, costing one
  pull per tag. A long gap holding several sweeps is estimated from what is
  left once any carried debt is paid, divided by the tag count. Otherwise each
  `-b<N>` build tag pulled counts as one CI pull, because `ci.yml`'s post-push
  check pulls every build it publishes. The rest is real, and the other tags
  pulled are recorded.
- `bot_debt` / `debt_since` cover Docker Hub showing a sweep's timestamps
  before its pull_count catches up, possibly by more than one run. Debt that
  can't be subtracted yet is always applied to the next interval, which holds
  the late count however long it is. Anything still unpaid carries further only
  while it is younger than 2 hours. The two failure modes are lopsided:
  dropping debt too early books a whole sweep (~200) as real pulls, while
  holding it too long hides a few real ones. A malformed last line starts a new
  baseline instead of blocking the log.
- **Plan digests** sums the log over the same span as the raw delta beside it:
  every line after the first run of the `from` day, up to and including the
  first run of the `to` day (the daily rows are first-run-of-the-day
  snapshots). Consecutive windows share no line and miss none, so an interval
  crossing midnight is counted exactly once. If the log has no line on the
  `from` day, the window is empty rather than borrowing an interval from after
  it. A window that has to start after its first day (no line until later in
  a weekly window) is labelled partial from that line. A log younger than the
  window gives a figure labelled "counted since …". With no log, the line is
  left out and the digest is otherwise unchanged.

### Design decisions

- **The run log lives on the `metrics` branch, not in the Actions cache.** Each
  line's baseline is the previous line. The cache is evicted routinely, which is
  how the digest baselines broke before. One commit per run, about 70 a day, is
  acceptable on an orphan data branch.
- **A failed fetch writes no line.** The next interval just gets longer, and a
  failed push heals the same way.
- **Estimates, labelled as such.** A tag pulled by a person and then swept in the
  same interval is lost, and a short burst of real pulls in a sweep interval is
  only roughly separated. Exact per-tag counts would need Docker Hub's publisher
  analytics, e.g. via the Docker-Sponsored Open Source program.

### Verification

The changed step scripts were extracted from the workflow and run in
`ubuntu:24.04` with the runner's tools. They were tested against the live
Docker Hub tag API (198 tags) and a synthetic sequence: quiet interval, CI
build-tag pull, one sweep, a sweep whose count lags one run and then two, two
sweeps in one gap, a late count in the same interval as a new sweep, debt kept
while young and dropped after 2 hours, a refused duplicate timestamp, a failed
fetch, and a malformed last line. Mutants restoring one-run carry, no expiry,
and sweeps counted from the raw delta each fail the suite. Every Slack payload
variant was parsed as JSON. actionlint reports only the four SC2129 style notes
`main` already had.

---

## fix(engine): price the conversation without converting every step on the current-step path (2026-10-19)

**Repo:** EDDI · **Branch:** `fix/security-review-authz` (carried by #1081 to repair `main`)

### What changed and why

#1066 (group cost ledger) and #1093 (convert only the current step for a turn's response) merged
within minutes of each other, each green on its own. Together they broke `main`:
`ConversationMemoryUtilities.conversationCost` sums `audit:cost` over every step of the snapshot, and on
the current-step path that list is the lazy view #1093 introduced, so pricing the conversation converted
every step again. `ConversationMemoryCurrentStepConversionTest.convertsOnlyCurrentStep` failed, and the
default turn response lost the saving #1093 was for.

- `LazyStepSnapshots.conversationCost()` sums the live steps' `audit:cost` entries with one
  `IConversationStepStack.getExactDataPerStep` read. Nothing is converted. A step holds at most one value
  per key, which is exactly what the full conversion sums per step, with the same non-finite and
  negative filtering.
- `conversationCost(List)` uses it when it is handed the lazy view; every other caller is unchanged.
- New test: with costs on every step, the current-step path returns the same total as the full
  conversion and still reads only the newest step.

---

## fix(runtime): refuse a conversation start at capacity before any read (2026-10-19)

**Repo:** EDDI · **Branch:** `feat/turn-admission-hardening` (PR #1085)

### What changed and why

A profile of a 5,000-user burst against a node held to 2 CPUs (JFR, 2026-10-19) found that turns got
only about 17 % of the CPU. Starting conversations took 19 %, and the HTTP event loop took 27 %. Most of
those starts were refused for capacity, and each client came back after `Retry-After`. #1085 already moved
the capacity check before any read for `say`, `rerun` and streaming. A start, though, still ran the
agent access check, resolved the user, read the GDPR restriction from `usermemories` and looked up the
agent before `admitTurn` refused it.

- `RestAgentEngine.startConversationWithContext` now calls `refuseIfAtCapacity` first. A refused start
  answers the same 503 `capacity_exceeded` as before, now with no database read. `admitTurn` stays the
  authoritative check.
- `RestAgentEngineCapacityPrecheckTest` pins both cases: refused before the access guard and before
  `startConversation`, and going ahead when there is capacity. Removing the call fails both tests.
- [`docs/metrics.md`](metrics.md) names the start path among the pre-checked entry points.

### Design decisions

- The internal starts (group members, schedules, sub-agents, A2A) do not pass through this method and are
  unchanged. They reach `admitTurn` as before.

---

## perf(audit): the audit ledger signs through signIfKeyPresent; burst-collapse investigation (2026-10-19)

**Repo:** EDDI · **Branch:** `perf/audit-signing-missing-key`

### What changed and why

An investigation of throughput collapses under a 5,000-user burst
([`scripts/loadtest/results/collapse-2026-10-19.md`](../scripts/loadtest/results/collapse-2026-10-19.md))
found that every audit entry did a vault read that was certain to miss. `AuditLedgerService.submit`
signs each entry with the agent's Ed25519 key, on the turn's own thread, and almost no agent has a key.
This branch fixed that with a 60 s missing-key cache. Meanwhile #1055 landed an equivalent cache on
`main` (`AgentSigningService.missingKeyUntil`, with a guard against a lookup racing a key generation),
so the merge keeps `main`'s implementation. What this branch still adds:

- `AgentSigningService.signIfKeyPresent`, used by the audit ledger. While a "no key" answer is
  remembered it returns `null` without throwing. Through `sign`, a remembered miss still builds three
  exceptions per entry (`SecretNotFoundException`, `PrivateKeyLoadException`, `AgentSigningException`),
  about 8,000 of each in one profiled burst. `sign` keeps its contract.
- The investigation's results file. Host contention triggers the collapses; on 2026-10-18 that is
  stated as a hypothesis, since no host telemetry covers the worst runs. On scarce CPU, EDDI spent most
  of it outside turns. Its follow-ups are the unfired 60 s turn timeout, `/q/metrics` going unresponsive
  and turns that run after their caller has gone.
- [`docs/configuration-reference.md`](configuration-reference.md) described
  `eddi.audit.agent-signing-enabled` as signing agent configurations. It signs audit entries, and the
  row now says so, including the 60 s re-check.

### Design decisions

- On the merge conflict with #1055, `main`'s cache won outright: it also guards the race in which a
  lookup that started before `generateKeyPair` publishes its miss afterwards. This branch's own cache, a
  Caffeine cache with a 10,000-key cap that came out of review, was dropped rather than merged in.
- Tests: three `signIfKeyPresent` cases in `AgentSigningServiceTest` (null without a second vault read,
  a present key, a vault failure still thrown), and `AuditLedgerServiceBranchTest` covering the
  unsigned-entry path.

---

## docs(loadtest): adaptive turn limit judged with Retry-After-honouring clients — dropped (2026-10-18)

**Repo:** EDDI · **Branch:** `docs/adaptive-limit-retry-measurement` (on top of `feat/turn-admission-hardening`)

### What changed and why

The adaptive turn limit parked on 2026-10-17 lowers `eddi.conversation.max-in-flight-turns` while the
mean MongoDB pool wait exceeds 100 ms. It had been judged with k6 clients that ignore `Retry-After`. It
has now been measured again with the benchmark suite's `--retry-503 10` clients (PR #1086): limiter off
against on, `down -v` before every run, 2,500 conversations × 10 turns and a 5,000-user burst, 3 s mock
LLM, three rounds. The results and the method are in
[`scripts/loadtest/results/admission-2026-10-17.md`](../scripts/loadtest/results/admission-2026-10-17.md).
No code changes.

- **Not shipped.** On a quiet machine the limiter gained 12–15 % goodput in the steady scenario, cut its
  p99 and left the p95 even. But it abandoned turns in every burst run (603 and 102) and in the
  contended steady run (1,464), against 0 with it off. That fails the ship criterion: the limiter must
  beat "off" without raising abandoned turns.
- **The pool-wait signal does not track database saturation.** A per-second sampler put it next to
  mongod `opcounters`, `globalLock` queue and CPU. Pool wait correlated negatively with mongod ops/s in
  nine of ten runs. The wait was high while mongod ran at a fraction of its peak, with free pooled
  connections and an empty lock queue. It measures CPU contention in the EDDI JVM, which is why another
  stack's load test drove the limit down to 92. `mongodb_driver_pool_waitqueuesize` moves with it, so it
  is no better.

### Design decisions

- No replacement signal was built. A signal that really measured MongoDB saturation would not have
  fired in these runs (mongod stayed far below its peak), which is the same as "off". The steady gain
  points at CPU instead: a static `max-in-flight-turns` sized to CPU, rather than to heap, is the next
  thing to measure.

### What's next

- Delete the local branch `wip/adaptive-turn-limit` (commit `63659ba18`) once the user agrees.
- Measure a CPU-sized static in-flight limit against `auto` with the same retrying clients.

---

## docs(loadtest): static, CPU-sized in-flight turn limit measured against `auto` — default unchanged (2026-10-18)

**Repo:** EDDI · **Branch:** `docs/static-turn-limit-measurement` (on top of `feat/turn-admission-hardening`)

### What changed and why

The adaptive limiter measured earlier the same day gained steady goodput while it held in-flight turns at
about 900–1,900. That suggested a static `eddi.conversation.max-in-flight-turns`, sized to CPU rather
than heap, might keep the gain without a controller. This was measured against the default `auto`
(2,602 at `-Xmx6g`) on 20 cores, with the benchmark suite's `--retry-503 10` clients (PR #1086).
Scenarios: 2,500 conversations × 10 turns, and a 5,000-user burst, both with a 3 s mock LLM. Each run
started from `down -v`, configurations were interleaved, and there were 2–7 uncontended rounds per
configuration (36 runs). The results are in a new section of
[`scripts/loadtest/results/admission-2026-10-17.md`](../scripts/loadtest/results/admission-2026-10-17.md).
No code changes.

- **Steady:** 2000 and 1500 tie with `auto` (median 228–234 turns/s, p95 including backoff about
  20–22 s). 1000 and 600 abandon turns (45 and 619) without gaining throughput, and 600 loses a third.
  No static limit reproduces the adaptive limiter's 12–15 % gain.
- **Burst:** 2000 has the higher unpaired median (333 against 275 turns/s) and a narrower range
  (281–355 against 185–427). Paired by round it is a tie: three wins each, median difference +2.5
  turns/s. It also abandons turns (9 over six runs against 0 over seven) and has 3–4 s worse p95 and p99
  totals. 1500 and below abandon 70–2,000 turns per burst.

### Design decisions

- **No new default.** No candidate beats `auto` without raising abandoned turns, the same criterion the
  adaptive limiter failed. With closed-loop clients that honour `Retry-After`, a lower limit moves the
  waiting from EDDI's queue into client backoff while throughput stays where the CPU puts it.
- **About 2,000 is a deployment choice, not a default.** It is documented as an option for operators
  who want a predictable burst floor more than the best case.

### What's next

- Nothing on admission control. A CPU-aware limit would need EDDI's own share of each turn's latency,
  which EDDI cannot measure without timing every LLM call.

---

## ⚡ perf(startup): derive the audit keys while the vault derives its KEK (2026-10-18)

**Repo:** EDDI (`perf/startup-parallel-kdf`)

### What changed and why

Thread dumps of the main thread during boot showed it in PBKDF2 twice, one
after the other: the audit ledger's HMAC key (`AuditKeyring.initialize` →
`AuditHmac.deriveHmacKey`) and the vault KEK (`EnvelopeCrypto.deriveKeyFromString`
from `VaultSecretProvider.onStartup`). Each is 600,000 iterations of
HMAC-SHA256, a few hundred milliseconds, and they share no input.

- **`AuditKeyring.deriveEarly`** is a new `StartupEvent` observer at
  `APPLICATION - 100`, ahead of the vault's at `APPLICATION`. It starts
  `initialize()` on a virtual thread and returns, so the audit keys are derived
  while the main thread loads the vault salt and derives the KEK.
- **The join is the existing lock.** `AuditKeyring.onStartup` (`APPLICATION + 100`)
  and every key accessor call `initialize()`, which waits on the keyring's
  monitor until the derivation is done. The volatile `initialized` fast path and
  the `synchronized` double-check are unchanged, so the keys are complete before
  readiness and before the first entry is signed.
- **`initialize()` is no longer a `@PostConstruct`.** Arc instantiates the bean
  on the thread that first touches it — the observer's, the main one — so a
  `@PostConstruct` derivation would have run inline again. Every method that
  reads a key already called `initialize()` first; `fromMasterKey` still does.
- **Failure is not swallowed.** If the early derivation throws, `initialized`
  stays false and the next caller derives again on its own thread and sees the
  exception, as before.

The KDF, salts, iteration count and therefore every key are unchanged:
existing signatures and vault entries verify as before.

### Tests

`StartupKeyDerivationTest` pins both derivations to known answers computed
outside the JVM (Python `hashlib.pbkdf2_hmac`), checks that keys derived through
`deriveEarly` equal those from an inline `initialize()`, and that `deriveEarly`
returns without doing the work itself. Mutation-checked: 600,001 iterations in
`AuditHmac` or in `EnvelopeCrypto`, and running the early derivation inline, each
fail it.

### Measured

Fast-jar boots of `origin/main` and this branch, built with `-DskipUi=true`,
against a local MongoDB 7 with a vault master key set, 8 runs each in
alternating order after one discarded warm-up (Windows 11, JDK 25.0.1, a host
shared with other work):

| | `started in` median (min) | to `/q/health/ready` median (min) |
|---|---|---|
| before | 4.03 s (3.51 s) | 4.90 s (4.42 s) |
| after | 3.52 s (3.17 s) | 4.48 s (4.21 s) |

About 0.4–0.5 s off a ~4 s boot. The spread between runs is of the same
order, so read it as "one PBKDF2 derivation leaves the critical path", not as
a precise figure. On a cold, contended container the derivations are slower,
and so is the saving.

---

## chore(deploy): production-sized runtime defaults for the image, Helm and Kustomize (2026-10-17)

**Repo:** EDDI · **Branch:** `chore/enterprise-runtime-defaults`

### What changed and why

The defaults fitted a laptop demo, not the load profile the throughput work measured. EDDI waits on
LLMs far more than it computes, so memory, not CPU, decides how many conversation turns a node runs
at once. Each in-flight turn holds its conversation (about 1 MB for short conversations in the
load-test harness). The old defaults were a 1 GiB limit at the image's 50 % heap ratio, i.e. a
512 MB heap, and ParallelGC, which stops every thread on a full collection.

- **Image** ([`Dockerfile`](../src/main/docker/Dockerfile)):
  - `JAVA_MAX_MEM_RATIO=75`.
  - `GC_CONTAINER_OPTIONS=-XX:+UseG1GC`, replacing run-java's ParallelGC.
  - `-XX:+ExitOnOutOfMemoryError` on `JDK_JAVA_OPTIONS`, so the flag survives an operator's
    `JAVA_OPTS`. A JVM out of heap exits and is restarted, instead of holding its turns and leases.
- **Helm** `eddi.resources` and the **Kustomize base**: request 1 CPU / 3 GiB, limit 4 CPU / 3 GiB.
  - Memory request = limit, for Guaranteed QoS.
  - A CPU limit is kept because the JVM sizes its GC, carrier and Jlama threads from it.
- **Production overlay:** request 2 CPU / 4 GiB, limit 4 CPU / 4 GiB.
- **Quickstart:** 1 GiB request, 2 GiB limit (was 384 Mi / 1 Gi).
- **docker-compose.yml:** `ulimits.nofile` 65536.
- **Docs:** a "Sizing a node" section in [`docs/kubernetes.md`](kubernetes.md#sizing-a-node), and
  the Jlama memory note in [`docs/langchain.md`](langchain.md). In-process Jlama weights are
  memory-mapped outside the heap, so lower the ratio and add the model size to the limit.

### Design decisions

- **G1, not ZGC, as the default.** G1 is the JDK's own server default and fits heaps of a few GB.
  ZGC is documented as the override for heaps of 8 GB and more.
- **Keep a CPU limit.** Removing it avoids CFS throttling, but the JVM would then size its threads
  for every core of a large node. The limit stays, set generously (4) with a request of 1.
- **No bump to the dependency charts** (MongoDB, PostgreSQL, Keycloak). Size them per installation.

### Not yet verified

- **The image's JVM command line** (heap ratio and GC through run-java) was not checked against a
  real container. Docker was unavailable when this was committed.
- **G1 vs ParallelGC under load** (p99 and the longest pause at 1000 conversations) is still to be
  measured with `scripts/loadtest`.

---

## 📦 chore(deps): dependency refresh before the 6.6.0 release (2026-10-17)

**Repo:** EDDI (`chore/release-6.6.0-deps`)

### What changed and why

A last pass over every pinned dependency before tagging 6.6.0. `pom.xml` already
says `6.6.0` (the 6.5.0 post-release PR set it), so this changes no project
version, only libraries and the Node toolchain.

- **Maven.** Every version literal in `pom.xml` was checked against Maven
  Central. Dependabot had already brought Quarkus (3.40.1), langchain4j
  (1.22.0 / 1.22.0-beta32), the Jackson overrides and the plugins to their
  latest releases. The only patch or minor still open was **classgraph
  4.8.196 → 4.8.199**.
- **Node 24.21.0 → 24.22.0** (latest 24 LTS patch), moved in all the places
  `BuildQualityGatesTest` keeps equal: `pom.xml` `node.version`, `ci.yml`
  `NODE_VERSION` with `NODE_SHA256_LINUX_X64` (the linux-x64 `.tar.gz` line of
  the release's `SHASUMS256.txt`), and `mise.toml`. Both UI READMEs name the
  pinned version and were updated to match.
- **Manager and Chat UI.** `npm update --save` inside the existing semver
  ranges, run with Node 24.22.0 / npm 11.19.1. This includes React 19.3.1,
  Vite 8.3.4, Vitest 5.0.5 (covers the open Dependabot vitest-group PRs),
  eslint 10.13, typescript-eslint 8.71.2, Playwright 1.64.1, Radix UI minors,
  TanStack Query, i18next / react-i18next, lucide-react 1.59 (Manager),
  zustand and the fontsource packages. The Manager lockfile also deduplicates
  about 30 nested entries. The earlier advisory overrides still apply: the Chat UI
  ships a single `katex` 0.19.0 and the Manager resolves `postcss-selector-parser`
  7.1.6.

### Deliberately not updated (majors that need their own migration)

| Dependency | Current → available | Why not now |
|---|---|---|
| `com.networknt:json-schema-validator` | 1.5.9 → 3.0.8 | Rewritten API; the shared-artifact validators need porting |
| `de.undercouch:bson4jackson` | 2.18.0 → 3.x | Built on Jackson 3, which the `ban-jackson3` enforcer rule blocks |
| `com.github.victools:jsonschema-*` | 4.38.0 → 5.0.0 | Also built on Jackson 3 (`tools.jackson`), so the same enforcer rule blocks it |
| `io.github.classgraph:classgraph` | → 5.x / 6.x | Major release; patch line taken instead |
| `io.projectreactor.netty:reactor-netty-http` | 1.2.18 → 1.3.x | Needs Netty 4.2; Quarkus still manages 4.1 (see the pom comment) |
| `typescript` | 5.9 → 7.0 | Native compiler port; its own PR |
| `msw` (Manager) | 2.15 → 3.x | Major release; the mock layer needs migrating |
| `lucide-react` (Chat UI) | 0.577 → 1.x | Major release with renamed icons |
| `@types/node` | 24 → 26 | The runtime is Node 24; the types follow it |

### Verification

`./mvnw clean compile`; Chat UI lint, typecheck, all tests and build; Manager
typecheck, lint, `i18n:check`, build and the full vitest run (592 files, 8551
tests); `BuildQualityGatesTest` and the repo-wide guard tests (243 tests, three
failures in `DeploymentManifestsTest`'s PowerShell `create-secrets.ps1` stub
tests, which fail the same way on an untouched `origin/main` checkout on the
Windows machine used, so they are environmental). The full backend unit suite,
the integration tests and the E2E jobs were left to CI.

**Files:** [`pom.xml`](../pom.xml), [`.github/workflows/ci.yml`](../.github/workflows/ci.yml),
[`mise.toml`](../mise.toml), [`ui/manager/package.json`](../ui/manager/package.json),
[`ui/chat/package.json`](../ui/chat/package.json), both `package-lock.json` files,
both UI READMEs.

---

## docs(planning): conversation storage layout design, steps as records (2026-10-17)

**Repo:** EDDI · **Branch:** `docs/conversation-storage-design`

### What changed and why

[`planning/memory-architecture-plan.md`](../planning/memory-architecture-plan.md) gains **§11, Conversation
Storage Layout (Phase G)**. This is the long-term design the throughput plan's WS6/WS7 asked for. Today
one conversation is one document (MongoDB) or one JSONB value (PostgreSQL), loaded in full twice per
turn and, on PostgreSQL, rewritten in full on every append.

Load tests on the WS2 build measured the cost over 200-turn conversations:
- turns slow down 1.6–1.9× on MongoDB and 2.2–2.5× on PostgreSQL;
- PostgreSQL runs at 61–83 % of MongoDB's throughput;
- 62 % of PostgreSQL's database time goes to the append;
- PostgreSQL stores 256 kB of live data in 28 MB.

The design:
- **Layout:** a small header (CAS-updated every turn, with new `stepCount` and `lastInteraction`
  fields) and one record per step (MongoDB `conversationsteps`, keyed `{c, i}`; PostgreSQL
  `conversation_steps` with primary key `(conversation_id, step_index)`).
- **Reads:** a turn loads the header and a window derived from the agent's existing
  history/summary settings; older steps are fetched lazily by page, so every consumer keeps its
  semantics and absolute indexes.
- **Writes:** an append writes only new records, in one transaction on PostgreSQL and through a
  unique-key claim on MongoDB (no transactions needed). Undo, redo, rerun and HITL commits become
  narrow updates instead of full rewrites.
- **Data and rollout:** full history stays stored and readable through every surface (AGENTS.md
  §4.7). Migration is online behind `eddi.conversation.storage.layout`, with conversion on first
  write, a background sweep and its reverse for downgrades. Header digests later replace the
  commonest all-history scans.

Also:
- Phase G is added to §7 and its targets to §10.
- §3.4.1 now says that compaction changes what the LLM is given, not what is stored. It used to say
  the summary "replaces the raw history in `IConversationMemory`", which contradicted §4.7.

### Next

G1 (header counters) is small and independent. The rest waits for WS3a (one full load per turn).

---

## Manager UI for agent test suites ("golden conversations") (2026-10-17)

**Repo:** EDDI (`ui/manager`) · **Branch:** `feat/manager-agent-tests`, based on `feat/agent-test-harness` (labsai/EDDI#1065) — merge after it.

The regression harness from #1065 had no Manager UI; suites and runs were reachable only over REST and MCP. This adds it (WS6 phase 6.2, UI part).

**What changed**

- **Test suites are a resource type** (`testsuite`, store `/testsuitestore/testsuites`) in `RESOURCE_TYPES`, so list, create, edit, version history and delete come from the existing Resources pages. The form editor (`components/editors/test-suite-editor.tsx`, registered in `EDITOR_MAP`) covers defaults, tool mocks, cases, turns and every expectation field; the JSON tab stays available. Expectation fields are folded away per turn (progressive disclosure). Half-typed JSON never overwrites the last valid value.
- **Agent detail → "Golden conversations"** (`components/agent-tests/agent-tests-section.tsx`), collapsed by default and outside the draft fieldset: pick a suite, Run (`POST /agenttests/runs`, runs the saved agent version against the suite's current version), run history from `GET /agenttests/runs?agentId=`. The list polls while any run is `RUNNING`.
- **Run result side sheet** (`run-result-sheet.tsx`): per case → repetition → turn pass/fail, each failed expectation with expected and actual side by side, cost and duration, and tool calls that had no mock highlighted. `ABORTED`, `ERROR` and a cost-ceiling stop each get a notice saying the agent was *not judged* (or why the run stopped) and what to do.
- i18n: 129 keys in all 11 locales.
- Mocks: `test/mocks/agent-test-fixtures.ts` + `agentTestHandlers` (passing, failing, aborted, error and ceiling runs; a started run turns `PASSED` after 4 s). The OpenAPI contract test stays green with no new exemptions.

**Decisions**

- `ResourceTypeConfig` gains `duplicable: false` and `jsonSchema: false`: the suite store has no `POST /{id}` duplicate and no `GET /jsonSchema`, so the Manager does not offer or request them rather than shipping a 404 (the snippet/parser drift in the contract test's exemption list is the precedent we avoided repeating).
- **`api-client` keeps a 202 body when it parses.** It used to return `undefined` for every 202, which dropped the run id `POST /agenttests/runs` answers with. 202s with an empty or non-JSON body behave as before.
- `ClusterDrawer` gained an optional `widthClass` so the run sheet can be wider; no other caller changes.
- Operator prompt and allow-list now include agent test suite and run access, so `operator-revision.json` is bumped past main's revision with the new provisioning fingerprint.
- `en.json` and the other locales lose one duplicated block of `decisionSummary*` keys (the file already defined them twice; JSON keeps the last one) as a side effect of re-serialising — no value changes.

**Verified:** `npm run typecheck`, `lint`, `i18n:check`, and the unit suites for resources, editors, lib, hooks, i18n, mock contract and the new components; plus a headless-Chrome walkthrough in MSW mock mode (passing run, failing turn, aborted, error, run-in-progress → passed, suite editor, suite list). Screenshots are in the session notes folder.

**Not done / next:** generate-a-suite-from-a-conversation, an LLM-judge expectation and a deployment gate (all listed as follow-ups in `docs/agent-testing.md`); a Playwright e2e spec for the new screens.

---

## 💰 feat(manager): cost controls — per-conversation ceiling and a legible tenant budget (2026-10-17)

**Repo:** EDDI (`feat/manager-cost-controls`) — Manager UI for the backend in the tenancy cost-metering PR (`feat/tenancy-cost-metering`)

### What changed and why

Two cost settings shipped on the backend without a screen, and the tenant budget was still presented as a plain number.

- **Agent detail → new "Limits" section** ([`agent-config-sections.tsx`](../ui/manager/src/components/editors/agent-config-sections.tsx)) with `maxCostPerConversationUsd`. Empty means unlimited and **removes the field** from the document (never `null` or `-1`); `0` is kept as a real ceiling meaning no priced spend at all; a negative or half-typed value is flagged and not written. The section says that only priced models count. Parsing lives in [`cost-ceiling.ts`](../ui/manager/src/lib/cost-ceiling.ts).
- **Tenant quotas page.** The monthly-cost field now says what it counts (priced spend only), and the usage card gains a *Monthly cost budget* status that names which of four states the tenant is in: no budget, budget set but enforcement off, budget reached (models are serving fallback answers, tool calls refused), or spend versus remaining. It lists what is not metered and points at the `eddi.llm.spend.refused` metric — there is no REST endpoint for recent refusals, so none is shown.
- All 14 new strings are in all 11 locales.

### Design decisions

- A new section rather than a field inside an existing one: the ceiling bounds the whole agent whatever its memory, session or approval settings are.
- The budget block states the situation instead of leaving it to be inferred from a progress bar that turns red at 90%.

**Not done:** recent spend refusals on the quotas page need a backend endpoint; today they are only a metric and a per-step marker (`llm:spend_refused:<taskId>`).

---

## Platform Operator explains 503 capacity_exceeded and node sizing (2026-10-17)

**Repo:** EDDI · **Branch:** `feat/operator-capacity-limits`

The 2026-10-17 throughput work made `eddi.conversation.max-in-flight-turns` (heap-derived with `auto`) the node's turn ceiling, but the Platform Operator knew nothing about it: asked why a turn came back 503 `capacity_exceeded`, or how big a node should be, it had no text and no tool to answer from.

**What changed**

- `ui/manager/src/lib/operator/system-prompt.ts` — a new "Turn capacity" section in the default body, in both scopes. It tells the three backend sources of `capacity_exceeded` apart by their `message` (the in-flight limit, `eddi.coordinator.max-active-conversations` for a new conversation, a node shutting down) and keeps an administrator drain apart, since that answers 409 with `Retry-After`, not 503. It states that the refusal is retryable backpressure with `Retry-After`, gives the `auto` formula as `InFlightTurnLimit` computes it ((max heap − 256 MB) ÷ `in-flight-turn-memory-mb`, floor 32), the 10–20 % headroom advice from the [configuration reference](configuration-reference.md), the meters and alert (`eddi_conversation_max_in_flight_turns`, `eddi_turn_rejected_total{reason="in_flight_limit"}`, `EddiTurnCapacityReached`), and the remedies in order: turn latency, replicas ([clustering](clustering.md)), heap, with the database pool kept in step.
- Two lines are added only when their tool is granted: what the coordinator status read shows (`activeConversations`, `queueDepths`, not the in-flight limit) and what the log read shows: a refused say/rerun is a WARN `Turn rejected for conversation …` carrying the refusal message, a refused new conversation under the coordinator cap is a WARN, and only a conversation start refused by the in-flight limit stays at DEBUG.
- `operator-revision.json` bumped to 20 (main took 18 for the model-catalogue refresh and 19 for #1079), so existing operators are offered the upgrade.
- Tests in `system-prompt.test.ts`.

**Decisions**

- **No allow-list change.** The numbers live at `/q/metrics`, which is not in EDDI's OpenAPI spec, so it cannot become a generated tool; the prompt says the operator cannot read them and names what the admin should look at. Exposing the limit and the in-flight count on `GET /administration/coordinator/status` would be the way to let the operator see them — a backend change, left as a follow-up.
- The per-conversation queue cap, the bounded MongoDB pool wait and the pool-wait meters (`feat/turn-admission-hardening`, PR #1085) are not described: that branch has not merged. They need a follow-up revision bump once it lands.
- Docs named in the prompt are top-level pages only: the image copies `docs/*.md`, so the monitoring guide is not served to the operator.

---

## perf(runtime): overload hardening — capacity check before any read, per-conversation queue cap, bounded MongoDB pool wait (2026-10-17)

**Repo:** EDDI · **Branch:** `feat/turn-admission-hardening`

### What changed and why

A load test and JProfiler session on 2026-10-17 (1,000 to 5,000 concurrent conversations, 3 s mock
LLM) found that once turns run on virtual threads (#1045), the MongoDB connection pool is where a turn
spends its time under load. Indexed single-document reads averaged 2.2–3.3 s inside the driver, while
mongod's own profiler logged them at 15–20 ms.

- **Capacity check before any read.** `IConversationService.refuseIfAtCapacity` (a no-op default;
  `ConversationService` implements it against `eddi.conversation.max-in-flight-turns`). `RestAgentEngine`
  (`say`, `rerun`) and `RestAgentEngineStreaming` call it before the ownership check. Until now, a turn
  refused at the limit first read its descriptor and loaded its conversation. Admission in `admitTurn`
  stays the authoritative, atomic check.
- **`eddi.conversation.max-queued-turns-per-conversation` (default 20).** Turns of one conversation run
  one at a time, so a client that keeps sending without waiting for answers (a retry storm, a bot loop)
  only built a queue of turns, each holding a permit and a loaded conversation. One more than 20 waiting
  is now refused in `AbstractQueuedConversationCoordinator` with the existing 503 `capacity_exceeded`,
  counted as `eddi_turn_rejected_total{reason="conversation_queue"}`. The cap applies in both
  coordinators.
- **`eddi.mongodb.max-wait-time-ms` (default 10000).** The driver waited up to 120 s for a pooled
  connection, twice the turn timeout, so a saturated pool held turns long after their callers had given
  up. A `waitQueueTimeoutMS` in the connection string still wins.
- **`eddi.datastore.pool.wait` timer and `eddi.datastore.pool.wait.timeouts` counter** (MongoDB,
  `PoolWaitListener`). Micrometer's pool listener showed how many operations were waiting, never for how
  long. Both are on the Full Metrics dashboard's connection-pool panel. PostgreSQL already exports
  `agroal_blocking_time_*`.

### Measured

`scripts/loadtest`, 3 s mock LLM, 6 GB heap, everything on one Windows laptop (Docker Desktop), with
its own compose project and ports. "Legacy" is this build with `eddi.mongodb.max-wait-time-ms=0`, i.e.
the driver's 120 s.

| Scenario | Legacy (120 s pool wait) | This change (10 s) |
|---|---|---|
| 2,500 conversations × 10 turns | 69 turns/s · p50 26.9 s · p95 65.7 s · 668 × 408 | **191 turns/s · p50 7.5 s · p95 21.9 s** · 73 × 408, 7 × 5xx, 1 × 503 |
| Burst: ramp to 5,000 users in 30 s, hold 60 s | 75 turns/s · p50 48.2 s · p95 73.7 s | **240 turns/s · p50 13.3 s · p95 20.1 s** · 727 × 503 at the in-flight limit |

The bounded wait accounts for this difference: it is the only setting that differs between the two
columns, and each run started on a fresh database. With the driver's 120 s, the p95 (66 s) ran past
the 60 s turn timeout. Turns whose callers had already given up kept queueing for connections, and that
abandoned work crowded out live turns. Each figure comes from a single run on a laptop, so read the
ratio, not the absolute numbers. Details are in
[`scripts/loadtest/results/admission-2026-10-17.md`](../scripts/loadtest/results/admission-2026-10-17.md).

### Design decisions

- **No adaptive limit, yet.** A limiter that lowered the in-flight limit while the mean pool wait
  exceeded a target was built, tested and measured, then left out of this change. Under the harness,
  which drives clients that ignore `Retry-After`, it cut goodput (2,500 conversations: 191 → 27 turns/s;
  5,000-user burst: 240 → 20 turns/s). In the burst, the pool-wait signal stayed around 470 ms while
  MongoDB served only about 500 operations/s. It is parked on a local branch, `wip/adaptive-turn-limit`.
  Judging it needs load-test clients that honour `Retry-After`; the benchmark-suite work adds them.
- **Queue cap default 20, not 5** (the throughput plan's WS4b proposal). Coordinator tests that queue
  20 turns on one conversation are legitimate FIFO tests, and a chat channel can deliver several messages
  from a fast typist. 20 is beyond any human pace and still stops a loop.

### What's next

- The Platform Operator's prompt does not explain `capacity_exceeded`, nor any of the turn limits. A
  follow-up should add it, together with an operator revision bump.
- WS4a deadline gaps (skipping turns whose deadline expired while queued) touch `ConversationStepRunner`,
  which the per-turn round-trip follow-up to #1055 is changing; take them after that lands.

---

## 🐛 fix(cluster): leases that survive a busy node, turns that stop when they lose them, one turn per idempotency key across nodes (2026-10-17)

**Repo:** EDDI · **Branch:** `fix/cluster-resilience`

### What changed and why

An assessment of cluster mode (NATS leases with fencing, event bus, degraded mode) found
the design sound and its failure scenarios tested. It also found a handful of places where
a node under strain, or a NATS that hangs instead of refusing, does worse than the
[clustering page](clustering.md) promises. Per-turn database work is
#1055's and node sizing is #1053's; neither is touched here.

- **Lease heartbeats get a thread of their own** ([`NatsConnectionManager`](../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java),
  [`KvLeaseManager`](../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java)).
  They shared a 2-thread scheduler with presence, the console's pollers, the HITL recovery
  sweep (a database scan) and every on-connect hook (up to 10,000 synchronous outbox
  publishes, bucket provisioning, the stale-lease sweep). Right after NATS came back,
  exactly when it is slowest, those hooks could hold both threads past the 20 s lease
  TTL and a live node lost every lease at once. Now the leases run on
  `leaseScheduler()` (one thread, nothing else on it) and the on-connect hooks on two
  threads of their own. A heartbeat pass also renews the held leases side by side on the
  virtual-thread I/O executor instead of one after another, so a NATS that answers in
  2 s per call no longer stretches a pass over hundreds of leases past the TTL. The pass
  waits at most one heartbeat interval, so a stalled call never delays the next tick; a
  lease whose renewal is still in flight is skipped, never renewed twice at once.
- **A drained node hands its leader lease over** (`KvLeaseManager.setDraining`). It
  stopped running the leader's work (`tryAcquireKey` answers empty while draining) but
  kept the `leader.hitl-recovery` lease alive through its heartbeat, so no node ran HITL
  crash recovery for as long as the drain lasted — contrary to the method's own Javadoc.
- **Shutdown releases leases side by side, within a bound** (`releaseAll`): up to three
  NATS calls per lease, sequentially, could outlast the termination grace against a slow
  NATS. A lease it misses still expires by TTL.
- **A cancelled turn stops its tool loop** ([`ToolLoopRunner`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java)).
  Lease loss, `/cancel`, a GDPR stop and the watchdog all raise the turn's cancel flag, but
  the loop only checked the thread's interrupt flag, so an agent loop ran all its remaining
  rounds — more model calls and side-effectful tools — for a turn whose result is
  discarded. It now checks the flag before every model call and every tool. The call
  already in flight finishes; interrupting it would also interrupt the persistence and
  dead-lettering that follow on the same thread.
- **Bounded NATS waits on the turn's thread.** A failed turn's dead letter retried its
  publish ten times; against a NATS that keeps the connection and answers nothing, each
  attempt ran into the 2 s request timeout — about 24 s on the failing turn's thread. The
  retry ([`JetStreamDeadLetterStore`](../src/main/java/ai/labs/eddi/engine/cluster/JetStreamDeadLetterStore.java))
  is now bounded by about 2 s and skipped when the client is disconnected or the stream
  answered with a refusal other than "not found" (a full stream is not re-provisioned on
  every refused publish); the entry stays
  in the node-local ring and is forwarded later. Audit position allocation
  ([`AuditClusterSupport`](../src/main/java/ai/labs/eddi/engine/cluster/AuditClusterSupport.java))
  stops asking NATS for one request timeout after a failure, so a turn's audit entries cost
  one timeout, not one each (they are recorded unsequenced meanwhile, as they already were
  on failure).
- **Dead-letter stream size cap**: new `eddi.coordinator.dead-letter.max-bytes` (1 GiB).
  The stream was bounded by age only, on the NATS volume the leases share. When full it
  refuses new entries (`DiscardPolicy.New`) rather than dropping the oldest an operator may
  still replay.
- **Event bus** ([`JetStreamEventBus`](../src/main/java/ai/labs/eddi/engine/cluster/events/JetStreamEventBus.java)):
  the outbox is also flushed every 2 s while connected (an event whose async publish failed
  without a disconnect waited for a reconnect that might never come), at most one flush at
  a time; and resyncs coalesce — requests that arrive while one is queued join it, so N
  nodes asking for a resync after a long outage flush each node once more, not N times.
  Each flush is a wave of cache misses against the shared database.
- **`Idempotency-Key` holds across nodes** ([`ConversationService`](../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
  [`TurnIdempotencyService`](../src/main/java/ai/labs/eddi/engine/internal/TurnIdempotencyService.java)).
  A duplicate that reached another node while the first request ran there missed that
  node's in-flight registry, became an owner, queued for the lease and, once it held it,
  ran the turn a second time over the newer document. The turn builder now re-checks the
  key when it rebuilds over a superseded document: under the lease, the check and the run
  are one step cluster-wide. The duplicate is answered as skipped (409 + `Retry-After`) and
  its retry is served the stored answer.
- **Autoscaling** (Helm chart 2.8.0 → **2.9.0**): `autoscaling.extraMetrics` and
  `autoscaling.behavior` are rendered as given, and each resource target may be `null`. A
  node waiting on LLMs runs hundreds of turns at little CPU, so a CPU-only HPA may never
  scale up; the docs show how to scale on in-flight turns
  (`eddi_runtime_executor_active{pool="turn"}`) through a custom-metrics adapter. The chart
  refuses an HPA with no metric at all, which CI's chart-guard step now asserts.
- **Docs**: [Clustering](clustering.md) gains database-connection sizing (a stock
  PostgreSQL's `max_connections=100` is exhausted by a third replica at the default pool of
  50; PgBouncer only in session mode, because EDDI takes session-level advisory locks), an
  Autoscaling section, a "NATS hangs" failure mode, and the behaviour above;
  [Kubernetes](kubernetes.md) and the [configuration reference](configuration-reference.md)
  list the new values. WS5 of `planning/throughput-optimization-plan.md` is marked
  superseded by cluster mode. The Platform Operator's prompt learns the dead-letter cap and
  that a drain hands over leadership (operator revision bump).

### Tests

- `KvLeaseManagerTest`: renewals side by side (6 leases at 300 ms each in well under the
  sequential 1.8 s), a lease with a renewal in flight is skipped, `releaseAll` side by side,
  a drain releases the leader lease but keeps conversation leases and the heartbeat does not
  take it back.
- `JetStreamDeadLetterStoreTest`: a hanging NATS holds the turn about 2 s, not 7+; a
  disconnected node does not retry or provision; the stream config carries the size cap and
  `DiscardPolicy.New`.
- `AuditClusterSupportTest`: after an outage, five more entries cost no NATS call; allocation
  resumes after the pause.
- `JetStreamEventBusTest`: five resync requests during a flush cause exactly one more flush;
  an outbox entry is flushed by the sampler without a reconnect.
- `ConversationServiceIdempotencyTest`: a duplicate queued on "another node" does not run
  again once the document holds its key; a queued turn with a different key still runs.
- `ToolLoopRunnerToolPolicyTest`: a turn cancelled during a tool runs neither the rest of the
  batch nor another model call.
- `DeploymentManifestsTest`: the HPA template's extra metrics, behavior, optional targets
  and no-metric refusal; chart version 2.9.0.

### Not done here

- Distributing one group discussion's member turns across nodes (today the whole discussion
  runs on the node that started it) — a design change, not a fix.
- A multi-node throughput benchmark — in progress separately (`scripts/loadtest`, 1→8
  nodes).

---

## fix(test): two unit-test classes that failed on every Windows checkout (2026-10-17)

**Repo:** EDDI · **Branch:** `fix/flaky-local-unit-tests`

Both failures reproduced on a clean `origin/main` and on no CI run: each is a test that
built its fixture in a form only Linux accepts. Neither is a product bug; in both cases
the product code under test behaved correctly and the fixture never reached it.

### What changed and why

- **`McpApiToolBuilderExternalRefTest.oversizedJsonSpecWithEscapedKeyIsStillScanned`.**
  `specReferencing` spliced a `@TempDir` path into a JSON string unescaped. On Windows
  that path is `C:\Users\…`, and `\U` is not a legal JSON escape, so the spec was not
  JSON at all. The cases the raw-text pass catches still passed; this one rests on the
  decoded-tree pass alone (its `$ref` key is Unicode-escaped), which could not build a
  tree and refused the spec as "could not be parsed" instead of reporting the external
  reference. `rejectUnsafeInlineRefs` failing closed there is correct. The helper now
  JSON-escapes backslashes, so every case reaches the reference it is about.
- **`DeploymentManifestsTest` — "secret generator — fail-closed classification"**
  (three PowerShell cases). The Windows `kubectl.cmd` stand-in recognised the existence
  probe with a bare `find`. A Maven started from Git Bash hands its PATH down, with
  Git's Unix `find` ahead of `System32\find.exe`; that one rejects the argument, the
  stub's `errorlevel 1` branch took the error for "not the probe", and every probe
  exited 0. Every stub mode therefore read as "a Secret already exists", so the
  NotFound path refused to install and the unreachable/forbidden paths aborted with the
  wrong message. `create-secrets.ps1` classified each answer it was given correctly
  (checked by hand against the stub with a PowerShell-launched PATH). The stub now
  calls `%SystemRoot%\System32\find.exe` by full path. CI's ubuntu runner never takes
  this branch, which is why it stayed green.

### Design decisions

- Fix the fixtures, not the code: the guard and the generator were right, and loosening
  either to accommodate a malformed fixture would have weakened a security check.

---

## fix(groups): count every turn of a group member toward the group's cost ceiling (2026-10-17)

**Repo:** EDDI · **Branch:** `fix/group-cost-ledger-accumulation`

### What was wrong

`GroupCostLedger.accumulateMemberCost` fed a group's `totalCost`, which the
`maxCostPerDiscussion` ceiling (I1) is checked against, through two faults:

- **It read the wrong value.** It took `audit:cost` from the last step of the
  member turn's snapshot and recorded it by replacement, on the assumption that
  the value was the conversation's running total. It is not: `LlmTask`
  accumulates `audit:cost` on the *current step*, and every new step starts
  without one. A member that spoke in several turns had only its latest turn
  counted. The comment in `MemberTurnExecutor` and the ledger's own Javadoc
  repeated the wrong assumption, and the tests stubbed the same wrong model
  (a growing "cumulative" `audit:cost`), so they passed.
- **Since 2026-09-26 it read nothing at all.** Member turns call `say` with
  `returnDetailed=true`, and the redaction of that projection (`f94036125`)
  withholds every `audit:*` key from the step data. From then on the ledger
  found no cost, so member spend counted $0 toward the ceiling. Only the
  discussion's own system costs (summarizer, stance summaries) still counted.
  Every unit test mocked `say` with a hand-built snapshot, so none of them
  went through the redaction.

### What changed

- `SimpleConversationMemorySnapshot` gains `conversationCost`, the sum of
  `audit:cost` over **every** step of the conversation, including the steps
  `returnCurrentStepOnly` leaves out. It is `@JsonIgnore` and in-process only,
  so it does not widen what any REST, MCP or SSE caller receives. The redaction
  is unchanged. `ConversationMemoryUtilities.convertSimpleConversationMemory`
  fills it, skipping NaN, infinite and negative values. It is `null` when no
  step carried a cost.
- `GroupCostLedger.accumulateMemberCost` records that total. It no longer reads
  step data. Replacement is now correct, because the value really is the
  conversation's running total. That holds in every case:
  - harvesting the same turn twice;
  - a skipped turn, whose snapshot shows the previous turn;
  - a HITL pause, whose resumed spend arrives with the member's next turn.
- `GroupCostLedger.retireConversation`: when `MemberTurnExecutor` swaps a
  member's ended private conversation for a fresh one under the same key, it
  first moves the ended conversation's entry to `key:endedConversationId`. That
  is the `key:child` shape nested groups already use, and the Manager's
  per-member digest already sums it. Before, the fresh conversation's first
  total overwrote the member's whole earlier spend.
- **Late callbacks from a retired conversation.** A turn that timed out on the
  ended conversation can still finish, and its callback arrives under the same
  key. The ledger compares the snapshot's conversation id with the member's
  active one and routes a mismatch to `key:<thatConversationId>`. That entry
  keeps the larger value, so the callback and the retirement can run in either
  order without double-counting or overwriting the replacement's total.
- **No window between retirement and replacement.** Retirement always leaves
  a retired entry, $0 if nothing was spent yet. A callback decides where its
  cost goes by whether that entry exists, and it reads and writes under the
  same monitor retirement holds. A late callback therefore lands correctly
  even before the replacement's id is published, while the member's
  conversation map still names the ended conversation.

### Design decisions

- **Not widening the redaction.** Letting `audit:cost` through, the way
  `audit:token_usage` is, would also expose the operator's configured pricing
  to any chatting user who sets `returnDetailed=true`. An in-process field gives
  the group engine what it needs and exposes nothing.
- **A conversation total, not per-turn deltas.** The snapshot carries no step
  index, so adding per-turn deltas could not tell a repeated harvest or a
  skipped turn's stale step apart from new spend. A total recorded by
  replacement is idempotent by construction.

### Tests

- `GroupCostLedgerTest` builds its snapshots through the real projection, with
  the flags a member turn uses. It checks that a two-turn member is counted in
  full ($0.05 + $0.07 = $0.12), and that two $0.06 turns trip a $0.10 ceiling
  that neither turn trips alone.
- `ConversationMemoryUtilitiesTest`: the sum covers steps outside
  `returnCurrentStepOnly`, poisoned values are skipped, and the field never
  reaches JSON.
- `MemberTurnExecutorTest`: an ended conversation's spend survives its
  replacement.
- Mutation-checked. Each of these reverts fails the new tests:
  - restoring the last-step read;
  - summing only the projected steps;
  - dropping the `retireConversation` call;
  - removing the late-callback routing;
  - recording a retired entry by plain replacement instead of the larger value;
  - routing by the conversation map alone;
  - skipping the $0 retired entry.

---

## 📱 fix(manager): every screen size, RTL, and state handling (2026-10-17)

**Repo:** EDDI (`fix/manager-layout-a11y`)

Half of a review of both UIs for click latency, every screen size and every
state. This PR holds the Manager's layout, RTL, state-handling and
accessibility fixes; the click-latency fixes and the Chat UI are in the
companion PR on `fix/ui-click-responsiveness`. The two share no file and build
independently.

### Every screen size and RTL (Manager)

- The chat drawer was a fixed 420px column, wider than a phone screen, and its
  Close button was unreachable. It is now a full-screen overlay below `md`, and
  Escape closes it. The operator panel no longer runs off the start edge on
  phones.
- Hover-only card and row actions (the kebab menus, deletes) did not exist on
  touch screens. The new `reveal-on-touch` utility in `index.css` shows them
  under `(hover: none)` and on keyboard focus, with 32px targets.
- Agent Studio rendered the mobile tabs and the desktop chat panel together at
  768–1023px, mounting two chat panels. Both now switch at `lg`.
- The top-bar breadcrumb truncates instead of pushing the controls off screen.
  List tables scroll horizontally and hide their secondary columns below `md`.
  App shells use `h-dvh`, custom modals cap at `100dvh-2rem`, the main gutter is
  `p-4 md:p-6`, page headers wrap, and labelled three-column grids stack on
  phones.
- RTL: switch thumbs no longer slide out of their track, directional arrows and
  chevrons are mirrored, the toaster sits at the inline end, and the onboarding
  badge is centred.

### State handling and accessibility (Manager)

- The API-call header/query/body editor could silently overwrite a value: after
  a delete, Add reused an existing key name, and renaming on every keystroke
  merged rows. It now uses `nextFreeKey` and `RenamableKeyInput`, as the
  connection and rules editors already did.
- Escape inside the agent picker or a secret picker no longer closes and wipes
  the schedule form or the create-channel wizard (`defaultPrevented` is
  honoured). Neither form now discards edits on a backdrop click or Escape;
  the property-setter long-value modal does the same.
- Inline group-config edits are covered by the unsaved-changes guard. The
  version diff dialog uses `AccessibleDialog`.
- An Enter that confirms an IME composition (Chinese, Japanese, Korean) no
  longer commits half-typed text in the chip and tag inputs.
- The dashboard shows an error with a retry instead of "nothing here" when a
  load fails. Resource type counts no longer read "0" while loading. The A2A
  copy buttons report a clipboard failure.
- About 40 icon-only buttons gained accessible names. A count-plus-word
  concatenation was replaced with plural keys in all 11 locales (including
  `_many` and the Arabic categories).

---

## 🩹 fix(manager): long tool names wrap in a group member's "using <tool>…" line (2026-10-17)

**Repo:** EDDI (`feat/groups-member-tool-call-events`)

Browser-checked the member card during a streamed discussion. A long MCP tool name could not wrap and pushed the line out of the card, so it now breaks. The mock discussion stream emits a `member_tool_call` frame and holds it long enough to see. Files: [`agent-response-card.tsx`](../ui/manager/src/components/groups/agent-response-card.tsx).

---

## 🔒 fix(security): authorization gaps from the 2026-10-17 review — directory, workflow references, group USE checks, `platform.*` variables, strict group ownership, reserved principals, per-turn USE (2026-10-17)

**Repo:** EDDI (`fix/security-review-authz`)

Findings M2, M3, M5, L2, L3 and L4 of the 2026-10-17 security review. The authorization changes (M2, M3, M5, L2, L4) are no-ops for an administrator, and the workspace ones (M3, L4) are no-ops unless `eddi.workspaces.enabled=true` with authorization on. L3 is not an authorization check: a token whose principal is in a reserved namespace is refused whatever roles it carries.

### M2 — the user directory could be harvested by any editor

`GET /workspaces/directory` answered a one-character prefix with every matching person's name, username and e-mail, whether or not workspaces were enforced. Now:

- `eddi.workspaces.directory.enabled` has **no default**: unset, it follows workspace enforcement ([`UserDirectory`](../src/main/java/ai/labs/eddi/engine/security/spaces/directory/UserDirectory.java) reads it as `Optional<Boolean>`, falling back to `WorkspaceSettings.isEnforcing()`). `application.properties` no longer sets it. A deployment without enforced workspaces records no one and suggests no one; shares are taken as typed, which is what an inactive directory always did.
- People are searched from **three** characters (`MIN_USER_QUERY` 1 → 3). Shorter queries return only the caller's own teams, not a 400, so the Manager's share box (which queries from the first keystroke) keeps working unchanged.
- A **non-administrator** is offered only their own teams and the people who share one of them. The restriction runs in the store — new `IUserDirectoryStore.searchWithinTeams` on MongoDB (`teams $in`) and PostgreSQL (`teams && ?`) — so a page is never filled with outsiders and then emptied. `eddi-admin` keeps the full search, known teams included.
- The **e-mail** (`detail`) is returned only to an administrator or to somebody who shares a team with the person — in suggestions and in a resource's grant list (`ResourceSharingService`, via `UserDirectory.detailFor(user, caller, isAdmin)`). Anyone else gets the username.
- Typing an exact username, verified e-mail or `team:<name>` still resolves (`resolveSubject` unchanged): only *suggestion* is narrowed.

Docs: [`workspaces.md`](workspaces.md#finding-people-the-user-directory), [`configuration-reference.md`](configuration-reference.md).

### M3.1 — a workflow could reference another space's private configuration

[`RestWorkflowStore`](../src/main/java/ai/labs/eddi/configs/workflows/rest/RestWorkflowStore.java) now checks every `eddi://` reference a workflow names — each step's `config.uri` and each extension element's `config.uri` (parser dictionaries) — through `ResourceAccessGuard` on create, update (and so `updateResourceUri` and `AgentCascadeService`, which reach it over the loopback REST client with the caller's identity) and duplicate (deep or not). Configuration needs `VIEW`; an `ai.labs.agent`/`ai.labs.group` reference needs `USE`. An update need not re-justify a resource id the stored version already referenced — keeping (or re-versioning) a reference somebody with access made grants nothing new — but a new id must pass, including the prefix trick `updateResourceUri` allowed (a target id that is a prefix of the stored one replaces it with a different resource). References are keyed by type *and* id: `USE` grants nothing about a configuration, so an agent URI naming a configuration's id must not stand in for the configuration and get it the weaker check.

### M3.2 — moderator, facilitator, RECRUIT and duplicate skipped the USE check

- [`RestAgentGroupStore`](../src/main/java/ai/labs/eddi/configs/groups/rest/RestAgentGroupStore.java)`.requireUseOnMembers` also checks `moderatorAgentId` and `facilitator.agentId`, skips `HUMAN` members (their id is a principal, not an agent), and `duplicateGroup` now runs it. MCP `create_group`, `update_group` and `create_group_from_template`, and `RestGroupTemplates`, all save through this store, so they are covered too.
- [`FacilitatorEngine`](../src/main/java/ai/labs/eddi/engine/internal/groups/FacilitatorEngine.java) takes the same `(agentId, principal) -> boolean` use check `RecruitAgentTool` has (M-A1) and asks it for the discussion owner before the deployment check, so a refusal does not reveal whether a private agent is deployed. `GroupConversationService` supplies it from a field-injected `ResourceAccessGuard` and the owner's own captured identity (`CallerIdentity.isAdminActingAs`).

### M5 — `platform.*` global variables were editor-writable

[`RestGlobalVariableStore`](../src/main/java/ai/labs/eddi/configs/variables/rest/RestGlobalVariableStore.java) refuses a non-admin `PUT` or `DELETE` of any key starting with `platform.` (case-insensitive, any tenant) with a JSON `403` — `{"error": …, "reservedPrefix": "platform."}` — and a `[SECURITY]` WARN. `platform.operator` is what the Manager trusts for which agent is the Platform Operator and which prompt an Upgrade re-provisions. Reads are unchanged. No MCP tool writes variables. Every write and delete, refused or not, is already on the audit ledger through `AdminActionAuditFilter` (pinned by a test). Docs: [`global-variables.md`](global-variables.md#rest-api).

### L2 — non-strict owner check on group-conversation reads and MCP group tools

`McpGroupTools.requireConversationOwner` (follow-up, continue, close, delete) and `read_group_conversation`, and `RestGroupConversation.readGroupConversation`, now use `requireOwnerOrAdminStrict`: a record with no owner is an administrator's alone. The lenient branch of `RestGroupConversation.validateGroupConversationOwnership` had no caller left (every call passed `hitlOperation=true`) and was removed with its parameter.

### L3 — reserved principal namespaces

New [`ReservedPrincipals`](../src/main/java/ai/labs/eddi/engine/security/ReservedPrincipals.java) is the one list of principal prefixes EDDI makes up for itself — `openwebui:`, `slack:`, `webhook:`, `agent-test:`, `group:`, `system:`, `a2a:`, `mcp:`, `anonymous-`, `gdpr-erased:`, and the whole name `anonymous` — built from the existing constants where they exist. `NamelessPrincipalAugmentor` rejects an authenticated identity whose principal carries one (case-insensitively) with `401` and a throttled `[SECURITY]` WARN naming the prefix and issuer. `ReservedPrincipalsTest` scans `src/main/java` for `"<word>:" +` concatenations and `*PREFIX* = "<word>:"` constants and fails on any that is neither reserved nor listed in the test as not a principal. `webhook:` and `agent-test:` come from open PRs #1059 and #1065 and are reserved ahead of them. Docs: [`security.md`](security.md#the-token-must-name-the-user).

### L4 — USE was checked only when a conversation started

New `ConversationAccessGuard.requireConversationTurnAccess`: the owner-or-admin check `requireConversationOwner` makes, plus — under enforced workspaces, for a non-admin — `requireAgentUseAccess` on the agent the conversation runs on (from the descriptor's `agentResource`, else the memory's `agentId`; a conversation that names neither is refused). Used by `RestAgentEngine.say`/`sayWithinContext`/`rerunLastConversationStep`, `RestAgentEngineStreaming.sayStreaming`, and MCP `talk_to_agent` and `chat_with_agent` (existing conversation). Nothing is cached across requests, so revoking a share stops the next turn. The service-level owner check inside `ConversationService.say` is unchanged — it also serves internal callers (Slack, schedules), which must not be USE-gated.

### Design decisions

- **Short directory queries return less, not 400.** The Manager searches from the first keystroke; an error there would surface as a failed request per keystroke.
- **Workflow updates check only newly referenced ids.** Checking every reference would lock a collaborator with `EDIT` out of a workflow whose owner referenced something only the owner may read; it would protect nothing, since the reference already exists.
- **L4 lives in `ConversationAccessGuard`**, next to the owner check it extends, so REST, streaming and MCP cannot drift apart; the workspace guard is reached through a lazy `Instance<ResourceAccessGuard>`.
- **Not done here:** the Manager is unchanged (it needs no change for M2; a hint to type three characters would help). The Platform Operator's prompt does not describe `platform.*` being admin-only; its writes run with the administrator's token, so nothing it does is refused.

**Tests:** `UserDirectoryTest` (new `Harvest` group), `UserDirectoryStoreContract.searchWithinTeams` (run against real MongoDB and PostgreSQL), `RestWorkspacesTest`, `RestWorkflowStoreReferenceAccessTest`, `RestAgentGroupStoreUseCheckTest`, `FacilitatorEngineTest` (two RECRUIT cases), `RestGlobalVariableStoreTest` (M5 cases), `AdminActionAuditFilterTest`, `McpGroupToolsTest` and `RestGroupConversationTest` (unowned records), `NamelessPrincipalAugmentorTest`, `ReservedPrincipalsTest`, `ConversationAccessGuardTest` (new `requireConversationTurnAccess` group), `RestAgentEngineTest`; `RestAgentEngineStreamingTest` updated for the new guard method.

---

## 🔒 fix(security): group-discussion attachments get an owner, quotas and a delete path (2026-10-17)

**Repo:** EDDI (`fix/security-review-data-lifecycle`) — security review 2026-10-17, finding M4 and its REST edge.

### What changed and why

A group discussion's inline attachments were stored with the five-argument `IAttachmentStore.store(...)`, which records no user. So no per-user quota counted them (one `eddi-user` could store 100 MB per discussion, without bound), deleting the discussion left the blobs behind with nothing pointing at them, and nothing keyed on the user could find them.

- **Owner.** `GroupAttachmentBinder` stores with the discussion's `userId`, so `eddi.attachments.max-per-user` and `max-total-bytes-per-user` count group uploads. A quota refusal no longer silently drops the file: the blobs the request had already stored are removed, the just-created discussion is failed, and the caller gets `400` (`GroupAttachmentRejectedException`; a `group_error` event on `/stream`). A blob the rollback could not remove is logged at ERROR and stays owned by the failed discussion, where its delete and the user's erasure still reach it.
- **REST edge.** `RestGroupConversation` re-checks the inline attachments against `eddi.attachments.max-size-bytes` per file and `max-per-conversation` / `max-total-bytes-per-conversation` for the discussion before anything is decoded or stored (`GroupAttachmentLimits`, computed from the base64 lengths), as it already re-checks its other request ceilings. The body itself stays bounded by `RequestBodyLimitGuard` before it is read. Human input (`submitHumanInput`) carries no attachments, so there was nothing to bound there.
- **Delete.** `GroupLifecycleOps.deleteGroupConversation` deletes the discussion's blobs before the document. A failure keeps the document (500, retryable) rather than orphaning the blobs.
- **`storageRef` guard.** `materializeAttachments` used to pass a `storageRef`-only attachment straight to `grantAccess` for every member. It now accepts one only from a trusted source conversation that can read the blob — the parent discussion, for a nested `GROUP` member, which `MemberTurnExecutor` passes through a new `discuss(...)` overload — and grants the child discussion access so the next level can repeat the check. A reference with no trusted source (anything a client could send) is refused. Unreachable from REST today; the guard keeps it that way.

### Files

[`GroupAttachmentBinder.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupAttachmentBinder.java), [`GroupAttachmentLimits.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupAttachmentLimits.java), [`GroupLifecycleOps.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java), [`GroupConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java), [`MemberTurnExecutor.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java), [`RestGroupConversation.java`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java), [`IGroupConversationService.java`](../src/main/java/ai/labs/eddi/engine/api/IGroupConversationService.java); docs [`attachments-guide.md`](attachments-guide.md), [`group-conversations.md`](group-conversations.md).

### Not done here

- Blobs stored by earlier releases still have no owner recorded, so they do not count towards a per-user quota. Deleting their discussion, or erasing its owner, now removes them.
- `close` still keeps the blobs (it keeps the transcript readable); only `delete` and erasure remove them.
- `McpGroupTools`' `delete_group_conversation` description and `docs/mcp-server.md` still name only artifacts and ephemeral agents (left to the workstream that owns the MCP group tools).

---

## 🔒 fix(gdpr): erasure and export reach group discussions; keyed database-log pseudonym (2026-10-17)

**Repo:** EDDI (`fix/security-review-data-lifecycle`) — security review 2026-10-17, findings M4 (erasure half) and L9.

### What changed and why

- **Erasure reaches group blobs (M4).** `GdprComplianceService.deleteUserData` resolved attachment owners from `getConversationIdsByUserId`, which returns 1:1 conversations only, so a discussion's shared files — stored under the group conversation's id — survived an Art. 17 erasure that reported success. The cascade now lists the user's group conversations (`GroupConversationStore.listAllForUser`, paged, exact-match re-check) before step 5c deletes them, and deletes the blobs of each discussion and of every member conversation it created. A failed lookup is reported as the step `groupConversationAttachments`. If that lookup fails, or a blob delete under a discussion or member id fails, step 5c keeps the discussion documents (reported as `groupConversations`, kept for a re-run) — they are the only record of the ids the blobs are stored under, the way step 4c keeps the snapshots on an incomplete vault sweep (review follow-up).
- **Export completeness (L9b).** The Art. 15/20 bundle now carries `groupConversations` (question, transcript, state, dates) and `sharedArtifacts` (content and revision history, via the new `ISharedArtifactStore.listAllForUser`), each capped at 1,000 with `groupConversationsTruncated` / `sharedArtifactsTruncated`, plus `failedCategories` for a store that failed. The group listing pages at the cap plus one row, not the storage maximum, since the store reads every row of a page in full. All three count against `complete()`. The attachment inventory covers the discussions' blobs and their member conversations'. `omittedCategories` shrinks to `schedules` and `journalEntries`, so the endpoint still answers 207 until those land. MCP `export_user_data` reports the same fields.
- **Keyed pseudonym (L9a).** The database-log pseudonym (and the pseudonym in GDPR log lines and compliance records) was `AuditHmac.pseudonymFor`, an unsalted SHA-256 any list of candidate e-mail addresses reverses. New `GdprPseudonymizer`: the audit ledger's keyed pseudonym (`gdpr-erased:k1:…`) when the ledger signs; otherwise `gdpr-erased:s1:…`, an HMAC under a random per-deployment salt kept with `ISecretPersistence.setMetaValueIfAbsent("gdpr-pseudonym-salt", …)` — the metadata store and insert-if-absent pattern `VaultSaltManager` uses for the KEK salt, present whether or not the vault is on; the unsalted form only if that store fails (WARN).

### Design decisions

- **No migration of old pseudonyms.** Rows erased earlier keep `gdpr-erased:<sha256>`: the raw id is gone from them, so they cannot be re-keyed, and a re-run erasure matches on the raw id only.
- **Audit-ledger step 7b keeps the unsalted token.** Its blanket update reaches only v1–v4 and unsigned rows (the store already gives v5 rows the keyed pseudonym of their signing key), and a v3/v4 row signs exactly the unsalted token; anything else would break its signature. In the default `redact` mode step 7a has already moved every reachable row to the keyed pseudonym.

### Files

[`GdprComplianceService.java`](../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java), [`GdprPseudonymizer.java`](../src/main/java/ai/labs/eddi/engine/gdpr/GdprPseudonymizer.java), [`UserDataExport.java`](../src/main/java/ai/labs/eddi/engine/gdpr/UserDataExport.java), [`AuditHmac.java`](../src/main/java/ai/labs/eddi/engine/audit/AuditHmac.java), [`GroupConversationStore.java`](../src/main/java/ai/labs/eddi/configs/groups/mongo/GroupConversationStore.java), [`ISharedArtifactStore.java`](../src/main/java/ai/labs/eddi/configs/groups/ISharedArtifactStore.java), [`SharedArtifactStore.java`](../src/main/java/ai/labs/eddi/configs/groups/mongo/SharedArtifactStore.java), [`McpGdprTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpGdprTools.java), [`IRestGdprAdmin.java`](../src/main/java/ai/labs/eddi/engine/gdpr/IRestGdprAdmin.java); docs [`gdpr-compliance.md`](gdpr-compliance.md), [`troubleshooting.md`](troubleshooting.md).

### Not done here

- `MongoUserMemoryStore` / `PostgresUserMemoryStore` still log the unsalted pseudonym in their erasure log lines (server log only, not stored data).
- The Manager's GDPR export view does not render the new sections yet.

---

## 🔒 fix(vault): a secret create says where the secret may go; discover-endpoints pinned as never resolving (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-h1-leftovers`, built on `fix/security-review-2026-10-17-high`). Covers the two **H1** leftovers from the 2026-10-17 security review.

### What changed and why

**1. `POST /apicallstore/apicalls/discover-endpoints`: checked, no fix needed.**
The question was whether it resolves a `${vault:…}` or `${vars:…}` value from the
request and sends it to a URL the caller picked, the way `discover-tools` did.
It does not. `authHeaderRef` must already be a reference (a literal is a 400),
and `McpApiToolBuilder.parseAndBuild` copies it verbatim into the generated
calls' `Authorization` header. The spec fetch (`OpenApiSpecFetcher`) sends only
`Accept` and `User-Agent`. The reference is resolved later, when an agent makes
the call, and the secret's grant and `allowedHosts` are checked at that point. A new
test in `RestApiCallsStoreBranchTest` pins this: discovery from an inline spec
whose server is `https://attacker.example` returns both references exactly as
written.

**2. A secret create needs `allowedHosts` under `enforce`. This is a breaking change.**
`allowedAgents` limits which agents may reference a secret, but any editor can
write a config that sends a referenced secret to a URL they choose. So
a secret created without `allowedHosts` went wherever the next config edit
pointed it. Under `eddi.vault.grant-enforcement=enforce` (the default), a create
through `PUT /secretstore/secrets/{tenantId}/{keyName}` or
`PUT /spacestore/secrets/{key}` now gets a **400** when `allowedHosts` is omitted or
`[]`. `["*"]` (alone, never as a pattern) is the explicit opt-out and is
stored as the empty list. `warn` and `off` keep the old behaviour, and a rotation may
still omit the list. The user agreed to the break before it was made.

- `VaultGrantRequests.requireHostsOnCreate` is the host-side twin of
  `requireGrantOnCreate`, shared by both endpoints.
- `VaultHostAllowlist` gains `ANY_HOST`, `isExplicitlyUnrestricted` and
  `requestedHosts` (which maps `["*"]` to `[]`, and otherwise works like
  `validatedHosts`). `SetupSecretHosts` now delegates to it rather than keeping
  its own copy. The grant endpoint also accepts `["*"]` and treats it like `[]`,
  which clears the restriction.
- Space secrets could not carry `allowedHosts` at all. `SecretWrite` gains the
  field, and a 3-argument constructor is kept. `SpaceSecret` now returns the field.
- **Manager:** there is a shared `AllowedHostsField` with two choices, *Only these
  hosts* or *Any host*. It is used in Add Secret, in the secret picker's inline
  create, and on the Workspaces page, and the submit button stays disabled until
  one is chosen. The picker fills in the host from the config's URL
  for the MCP server URL, the A2A agent URL and the LLM `baseUrl`/provider
  default. The Workspaces page create now also sends `allowedAgents: ["*"]`: it
  sent no grant at all, so it already failed under `enforce`. Re-saving an existing space key sends no
  grant and no hosts, so the stored lists are kept. The MSW handlers model the `enforce` rules.
  The Operator prompt tells an admin a new secret needs its hosts. The operator revision is bumped.
- `seed-demo-agent.sh` sent neither list and failed under `enforce`. It now sends both,
  with the host taken from `EDDI_DEMO_LLM_TYPE`, or from
  `EDDI_DEMO_LLM_ALLOWED_HOST` when that is set (wired through
  `docker-compose.openwebui.yml` and `.env.example`). The cluster demo and
  `AuditAndSecurityIT` now send both lists too.
- Docs: `secrets-vault.md` (new *Required on a create* section), `workspaces.md`,
  `troubleshooting.md`, `upgrading-from-6.4.md` §7.1.

### Verification

- `./mvnw test` on `*Secret*Test,*Vault*Test,*Space*Test,AgentSetup*Test,SetupSecretHosts*Test,McpSetupToolsTest,ComposeStackTest,RestApiCallsStore*Test`:
  6114 run, and one fails: `RestApiCallsStoreBranchTest.arbitraryPasswordInTheSpecUrlIsNotEchoed`.
  That test resolves a `.test` host over the network and fails locally with
  `UncheckedIOException`. This change does not touch it.
- Mutation check: with `requireHostsOnCreate` removed from both endpoints,
  `createWithoutHostsRefused`, `createWithEmptyHostsRefused` and
  `SpaceResourcesTest.createNeedsAllowedHosts` fail.
- Manager: `npm run typecheck`, `lint` and `i18n:check` pass, and so do the targeted vitest
  files (secrets, picker, workspaces, use-secrets, MSW handlers, i18n,
  operator-revision). The full vitest suite and the Playwright run are left to CI.

### Decisions


### Folded in when this branch was synced with `main` (2026-10-19)

#1079 merged as a squash while this branch still carried its earlier commits,
and `main` had moved on, so the sync merge resolves the overlap (operator
revision 23 after later syncs past #1091, #1078 and #1056, `main`'s dialog sizing, `nameSeg` encoding in `space-resources.ts`)
and carries the fixes below:

- **`main` was red** (#1066 against #1093: the response's cost sum converted
  every step that #1093 had made lazy). This branch fixed it first, but #1081
  merged the same fix (`conversationCost` reads a lazy step list's total from
  the live steps), so the second sync takes `main`'s version and drops the
  duplicate.
- **`/setup-api` apiAuth was locked to the LLM host** by an explicit
  `allowedHosts` (#1079): the request's list names where the LLM key may go, so it
  no longer applies to `apiAuth`, which keeps the generated calls' target hosts;
  only an explicit `["*"]` carries over (`AgentSetupService.apiAuthRequestedHosts`).
- **Timing limits** in `ModelOutputParserFenceTest` and `ConversationRecallToolTest`
  rose from 100 ms to 1 s: the cubic case they guard against takes seconds, and
  100 ms was a flake on a loaded CI runner.
- **Workspaces → space secret Save** stays disabled until the secret list has
  loaded: while it was loading or had failed, a save went out as a create with an
  open agent grant and could widen a key someone had narrowed.
- **The #1079 fragment** now says its decision about REST creates is superseded
  here, and that its operator revision landed as 19.

---

## 🔒 fix(vault): wizard-vaulted keys carry a destination list; discovery probes resolve no vault reference (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-high`) — security review 2026-10-17, finding **H1**

### What changed and why

Every key the setup wizard vaulted was stored with `allowedAgents ["*"]` and no
`allowedHosts`. An `eddi-editor` can read the LLM configuration holding the
`${vault:…}` reference, so they could copy it, set `baseUrl` to their own host,
deploy, send one message, and receive the key (reproduced live). The MCP
`discover-tools` probe was a second path needing no agent: it resolved a
`${vault:…}` reference from the `X-Mcp-Authorization` header and sent the
plaintext to the URL in the request body.

- **`AgentSetupService`** stores every secret it creates (the LLM `apiKey`; the
  `apiAuth` on `/setup-api`) with `allowedHosts`: the host of the base URL the
  request names, else the provider's own endpoint (new `SetupSecretHosts` —
  OpenAI, Anthropic, Gemini, Mistral, Hugging Face, jlama's model download, and
  the catalog of named OpenAI-compatible providers). For `apiAuth` it is the host
  of each generated httpcall's `targetServerUrl`.
- **New `allowedHosts` field** on `SetupAgentRequest` and `CreateApiAgentRequest`
  (appended last, positional constructors kept): an explicit list overrides the
  derived one, `["*"]` stores the secret open. Under
  `eddi.vault.grant-enforcement=enforce` an open secret is never created
  silently — when nothing can be derived and the request does not say `["*"]`,
  setup returns a 400 before writing anything. `warn`/`off` store it open with a
  `[SECURITY]` warning. Not exposed on the MCP setup tools.
- **Checksum reuse** now reuses an entry only when it is restricted to the same
  hosts, so a key an earlier release vaulted open is not handed to new agents.
- **`ISecretProvider.storeIfAbsent`** gained an `allowedHosts` overload, written
  in the same atomic insert by `VaultSecretProvider` (the default refuses a list
  rather than store the secret open).
- **`RestMcpCallsStore.discoverTools`** refuses a `${vault:…}`/`${eddivault:…}`
  or `${vars:…}` value in `X-Mcp-Authorization` with a 400 instead of resolving
  it.
- **Platform Operator** prompt: never set `allowedHosts`; ask for the endpoint
  when a setup is refused for an unknown destination. Revision 17 → 18 on this
  branch; `main` had moved to 18 in parallel, so the merge landed it as 19.
- Docs: `docs/secrets-vault.md` ("Where a wizard key may be sent"),
  `docs/mcp-client.md`.

### Design decisions

- **Derive, don't ask.** The wizard knows where the key is going. A required
  field would break every existing caller; a derived list closes the hole for
  all of them. The list only constrains a configuration that *names* an
  endpoint; one without uses the provider's built-in endpoint and is not checked
  (`ChatModelRegistry.ENDPOINT_KEYS`), so a slightly incomplete default list
  cannot break an agent.
- **Existing entries are not migrated.** A `vaultKeyName` or `${vault:…}`
  reference the admin names is used as it is. Keys already vaulted open stay
  open until an admin narrows them via `PUT …/grant`.
- **`allowedHosts ["*"]` is wizard-request syntax only.** The secrets REST API
  still rejects `*` in `allowedHosts`, and still accepts a create without the
  field. Refusing that under enforce too would be a breaking change for the
  Manager's Secrets page, so it is left as a follow-up. *Superseded by
  `2026-10-17-fix-security-review-h1-leftovers.md`: a REST create under
  enforce now requires `allowedHosts`, with `["*"]` as the explicit opt-out.*

### Tests

`AgentSetupAllowedHostsTest` (new, 11): derived and requested lists, enforce
refusal, explicit wildcard, warn mode, reuse of open versus restricted entries,
the named-key insert. `RestMcpCallsStoreTest`: a reference in the header is
refused before the manager is called, and a literal still probes.
`VaultSecretProviderAllowedHostsTest`: `storeIfAbsent` persists the list.
`McpSetupToolsTest` now asserts the end-to-end `["api.openai.com"]`. Reverting
the store argument or the probe check fails these tests (mutation-checked).

### Not done here

- `POST /apicallstore/apicalls/discover-endpoints` was not checked for the same
  probe pattern.
- The review's defence-in-depth items: requiring `eddi-admin` to save a
  non-default `baseUrl` next to a vault reference the caller did not create, and
  a Manager warning badge for "unrestricted destination".

---

## 🔒 fix(ssrf): the metadata guard refuses a host java.net.URI cannot read (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-high`) — security review 2026-10-17, finding **H2**

### What changed and why

`UrlValidationUtils.rejectCloudMetadataTarget` is the only check on the
httpCalls path while `eddi.security.ssrf-protection.enabled=false` (the
default). It read the host with `java.net.URI` and returned quietly when that
host was null. `URI` gives a null host for any registry-style authority: an
underscore (`x_y.attacker.com`), a percent-encoded host, or an IP literal with a
trailing dot. The Vert.x client (`HttpClientWrapper`) parses the same string
with `java.net.URL`, gets a host, resolves it and connects. An editor-written
`targetServerUrl` naming such a host, with an A record of `169.254.169.254`,
therefore reached the cloud metadata service.

- **`rejectCloudMetadataTarget`** now throws for an unparseable URL, and for an
  `http(s)` URL whose `URI` host is null or blank. When `java.net.URL` reads a
  different host, that host is checked as well. A URL with another scheme is
  still left to the caller.
- **`HttpClientModule.requireNotMetadataHop`** refuses a redirect hop with no
  host instead of following it. A hop with an unreadable host is refused through
  the same check.
- Docs: `docs/security.md`.

### Behaviour change

An httpCall, MCP server or A2A peer whose host contains an underscore (for
example a Docker service name such as `my_api`) is now refused even with SSRF
protection off. Give it a hyphenated alias. The JDK `HttpClient` used by every
other path already refused such URLs.

### Tests

`UrlValidationUtilsTest.unreadableHostIsRefused` covers `x_y.example`,
`169.254.169.254.`, `169%2e254%2e169%2e254`, a single-slash `http:/` and an
unparseable URL. Its resolver answers "public" for everything, so only the new
rule can refuse them. `HttpClientModuleTest` covers the same hosts as redirect
hops, plus a hop with no host. The old assertion that an unparseable URL "is
left to the caller" was inverted. Reverting the null-host throw fails both
classes (mutation-checked).

---

## 🔒 fix(llm): linear JSON fence stripping; cap the recall tool's query (2026-10-17)

**Repo:** EDDI (`fix/security-review-2026-10-17-high`) — security review 2026-10-17, finding **H3**

### What changed and why

`ModelOutputParser.stripFence` ran every reply of a `convertToObject` LLM task
(and every `FormatRetryRunner` retry) through a regex with three quantifiers
competing for one whitespace run. A reply of three backticks, N spaces and a
letter cost O(N³): 462 ms at 1,000 spaces, 3.7 s at 2,000, about an hour at
16,000. That CPU is spent on the turn thread, and any chat user can trigger it
by prompt injection, as can a hostile model endpoint.

- **`stripFence`** is now `startsWith` / `endsWith` / `substring`, plus an
  ASCII case-insensitive check for the `json` tag. It returns the same result as
  the old regex for every input: the regex's leading `[ \t]*\r?\n?` and trailing
  `\s*` only moved whitespace that the `trim()` removed anyway.
- **`ConversationRecallTool`** reads only the first 500 characters of the
  model-written `query` (`MAX_QUERY_LENGTH`). Its range pattern was quadratic
  over a long digit run (10.9 s at 40,000 characters). A null query now means
  "the last summarized turns" instead of a `NullPointerException`.

### Tests

`ModelOutputParserFenceTest` (new, 24) keeps the old regex as an oracle and
compares it with the new code on 23 fixtures, including non-ASCII look-alikes of
`json` and a vertical tab. It also runs the 50,000-space input through
`stripFence` and through `parse()`, each within 100 ms. `ConversationRecallToolTest`
adds a 40,000-digit query (100 ms budget), a turn range followed by a 40,000-char
tail, and a null query. Removing the cap fails the budget test at 10.4 s. The
fence budget was not mutation-run: the old regex needs hours at 50,000 spaces.

### Not done here

The L14 alternation-loop regexes from the same review are untouched.

---

## 🔒 fix(security): outbound requests, parsers and templates from the 2026-10-17 review (2026-10-17)

**Repo:** EDDI (`fix/security-review-outbound-parsers`)

### What changed and why

One commit per finding of the 2026-10-17 security review (M8–M11, L13–L16). PR #1079 (`UrlValidationUtils`, `requireNotMetadataHop`, `ModelOutputParser`, `ConversationRecallTool`) is not redone here.

- **M9.1 — group artifact JSON schemas could `$ref` the network or the disk.** `ArtifactValidators` now builds its networknt factory with an `AllowSchemaLoader` in front that admits only the bundled meta-schemas (`classpath:draft/…`, `classpath:draft-0…`); an `http(s):`, `file:` or other `$ref` throws before anything connects or is read. Refs are resolved eagerly at save time, so the save fails with an actionable message, and a ref first reached at write time refuses the write. [`ArtifactValidators.java`](../src/main/java/ai/labs/eddi/configs/groups/ArtifactValidators.java), [group-conversations.md](group-conversations.md)
- **M9.2 — pgvector host injection.** pgjdbc splices server names into its JDBC URL unencoded, so `host = "db/eddi?sslfactory=…&x="` injected driver properties and `a.com,10.0.0.5` dialled a host the metadata guard never saw. `EmbeddingStoreFactory.requirePlainHost` accepts one hostname (RFC 1123 labels, `_` allowed for Docker service names) or IP literal, nothing else, for pgvector and Qdrant. [`EmbeddingStoreFactory.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java), [rag.md](rag.md)
- **M9.3 — httpCall redirects with SSRF protection off.** A new redirect-handler guard (`HttpClientModule.refusingPrivateHops`) classifies each hop as public, private or loopback and follows a non-public hop only when the request that redirected (its connected address, else its resolved host) was in the same class, or the host is on `eddi.security.ssrf-protection.allowed-hosts`. A host-less `Location` is refused. Comparing with the previous hop is stricter than comparing with the first target. The DNS lookup of the hop and the client's own lookup are separate (JVM address cache); not pinned. [`HttpClientModule.java`](../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java), [httpcalls.md](httpcalls.md#redirects)
- **M9.4 — credential header detection.** `ApiCallExecutor` flagged a header as carrying a vault secret only when the resolved set grew, so a `${vault:k}` already resolved in the body did not disable redirects for a custom header reusing it. It now checks the header's own text for a reference. [`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java)
- **M10 — templates.** (a) `str:fmt`/`str:format` and the `String.fmt`/`format` instance forms are served by new `BoundedFormat` resolvers registered ahead of the generated ones: an upper bound for the output is computed from the format specifiers and refused against `eddi.templating.max-output-chars` before `String.format` allocates (pre-check, not removal — formatting numbers is common in prompts). (b) `quarkus.qute.timeout` is now effective: the render's deadline is a template-instance attribute checked on every value resolution (the bounded resolvers) and every loop iteration. (c) `processTemplate` reports a `StackOverflowError` as a template error; `validateTemplate` too. Section nesting is not capped at parse time — Qute's parser is not recursive, the render is, and catching the overflow covers it. [`BoundedFormat.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/BoundedFormat.java), [`RuntimeTemplateEngineFactory.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/RuntimeTemplateEngineFactory.java), [`TemplatingEngine.java`](../src/main/java/ai/labs/eddi/modules/templating/impl/TemplatingEngine.java), [security.md](security.md#render-limits)
- **M11 — PDF budgets on the attachment and tool paths.** `AttachmentTextExtractor` runs every PDF operation under the ingestion path's `PdfStreamBudget` pre-scan and `PdfDecodeBudget` (64 MB per stream, 128 MB per document), now public for that reuse, and the `(startPage, endPage)` variant reads at most `eddi.attachments.extraction.max-pages` pages. [`AttachmentTextExtractor.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/impl/AttachmentTextExtractor.java)
- **L14 — regex stack overflow on long JSON strings.** The `"((?:[^"\\]|\\.)*)"` alternation loop in `ConfidenceEvaluator` and `LifecycleManager.PROVIDER_ERROR_MESSAGE` recursed once per character; both now use `JsonStringField`, a linear scan. `summarizeException` and `reportFailureToCaller` no longer let a `StackOverflowError` escape the failure handler. [`JsonStringField.java`](../src/main/java/ai/labs/eddi/utils/JsonStringField.java)
- **M8 — export scrubbing and sync rebinding.** `SecretScrubber` judges structured strings part by part — a relative `request.path` query string, a form-encoded body, a JSON document inside a string (`request.body`, `serviceAccountJson`) — treats `Cookie`/`Set-Cookie` headers as credentials, and runs the full `SecretRedactionFilter.redact` (template expressions masked) over request material only — `path`, `body`, URLs/endpoints, header and query-parameter maps, embedded documents. Every other string (prompts, descriptions, outputs, rule values) gets only a credential-*shape* pass (`SecretRedactionFilter.redactCredentialShapes` plus export-side shapes: `AIza…`, `gh*_`/`github_pat_`, `xox?-`, `AKIA`/`ASIA`, `gsk_`, `xai-`, JWTs, the password of `mongodb(+srv)://user:pass@`), so prose such as "never echo the password: policy" round-trips byte-identical. Vault references and template expressions stay legible. For `/backup/import/sync*`, the facade cannot pin an address, so every request to the source now carries `SourceUrlValidator.connectPolicy`: `STRICT` when private targets are refused, `METADATA_ONLY` when they are allowed or the origin is allow-listed — re-checked by the facade right before each send. [`SecretScrubber.java`](../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java), [`SourceUrlValidator.java`](../src/main/java/ai/labs/eddi/backup/impl/SourceUrlValidator.java), [agent-sync-guide.md](agent-sync-guide.md)
- **L15 — `SharedKvCache.decode`.** The value class named in a NATS KV entry is matched against the exact classes the shared caches store (`Boolean`, `String`, `A2ATaskRecord`, `PrincipalIndex`, `PaginatedResponse`, `ChannelTarget`) instead of package prefixes; anything else is refused and logged at ERROR. [`SharedKvCache.java`](../src/main/java/ai/labs/eddi/engine/caching/SharedKvCache.java)
- **L16 — templated JSON bodies.** Documented that a runtime value in a JSON body must go through `{json:serialize(...)}` without quotes of its own, and deployment now WARNs per httpCall whose JSON body has a `{…}` expression inside a string literal. [`ApiCallsTask.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallsTask.java), [httpcalls.md](httpcalls.md#values-in-a-json-body-are-not-escaped)
- **L13 (partial) — LLM provider response size.** The OpenAI, Anthropic, Gemini, Mistral and Ollama builders use `BoundedJdkHttpClient`: the langchain4j `JdkHttpClient` built from an `HttpClient.Builder` whose client wraps every body subscriber with a byte cap (`eddi.llm.max-response-bytes`, 32 MB; over the whole stream for streaming). Not capped: MCP responses (`StreamableHttpMcpTransport` builds its own `HttpClient` and exposes no hook), Azure OpenAI and other SDK-based providers, and Jlama's Hugging Face download. [`BoundedJdkHttpClient.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/BoundedJdkHttpClient.java)

### Review fixes (PR #1083)

- `SecretRedactionFilter`'s two `sk-` shape rules now start at a word boundary (`(?<![A-Za-z0-9_])`, the convention `EXPORT_CREDENTIAL_SHAPES` already used). Unanchored, `redactCredentialShapes` — which M8 made run over every exported prose string — turned `risk-assessment-workflow-handler` into `risk-<REDACTED>`; log redaction keeps its coverage since real keys start at a boundary.
- `SecretScrubber.scrubStructuredText` returns the original string, whitespace included, when an embedded JSON document comes back unchanged; it returned the trimmed text, so a body template ending in a newline exported trimmed and `containsCredential` reported it.
- `LifecycleManager`'s `StackOverflowError` fallback digest carries `taskType` and `errorType: stack_overflow` like the regular digest, so `TurnError.fromTaskError` keeps the task metadata. `LifecycleManagerFailureReportFallbackTest` drives it with an exception whose `getResponse()` overflows inside `LlmFailureClassifier.classify`.
- The scrubber test fixture uses AWS's documented example key `AKIAIOSFODNN7EXAMPLE` (gitleaks allowlists it) instead of a made-up `AKIA…` that Secret Scanning flagged.

### Not done / open

- M9.3 and M8 re-check rather than pin: the facade and the Vert.x client resolve names themselves, so a window remains between the guard's lookup and the client's (normally served by the same JVM address cache).
- L13: MCP tool results, Azure OpenAI and Jlama downloads remain uncapped; an httpCall whose `future.get(timeout)` expires is still not cancelled on the Vert.x side.
- M8 provider-key coverage in free text is whatever `SecretRedactionFilter` matches; `AIza…`-style keys in a query parameter named `key` are caught once those patterns land there.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
