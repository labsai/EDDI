# EDDI Backend — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**
>
> **New here?** Read the [README](README.md) first — it has setup, the quick start, and a feature tour. This file is the working guide for building *in* the codebase: architecture, code patterns, and conventions.
>
> **Working under `ui/`?** This file still applies (branching, pushing, commits, changelog). Also read [`ui/manager/AGENTS.md`](ui/manager/AGENTS.md) or [`ui/chat/AGENTS.md`](ui/chat/AGENTS.md).

**Contents:** [1. Project Context](#1-project-context) · [2. Workflow Protocol](#2-mandatory-workflow-protocol) · [3. Roadmap](#3-development-roadmap) · [4. Backend Java Guidelines](#4-backend-java-guidelines) · [5. Agent Config Authoring](#5-agent-config-authoring) · [6. Session Protocol](#6-session-protocol)

## 1. Project Context

**EDDI** (Enhanced Dialog Driven Interface) is a multi-agent orchestration middleware for conversational AI. This repo is the **Java/Quarkus backend** plus the two web UIs under `ui/`.

EDDI is a **config-driven engine**, not a monolithic application. Agent behavior lives in JSON configurations; Java code builds the _components_ and _infrastructure_ (the "engine") that reads and executes those configurations.

### Ecosystem

| Repo | Tech | Purpose |
| --- | --- | --- |
| **EDDI** (this repo) | Java 25, Quarkus, MongoDB or PostgreSQL | Backend engine, REST API, lifecycle pipeline |
| **[quarkus-eddi](https://github.com/quarkiverse/quarkus-eddi)** | Java 21, Quarkus Extension | Quarkus SDK — `@Inject EddiClient`, Dev Services, MCP bridge |
| **Manager** — [`ui/manager/`](ui/manager/) in this repo | React 19, Vite, Tailwind | Admin dashboard (served from EDDI at `/manage`; `/` redirects to the `/welcome` chooser, `/workforce` is the group-conversation workspace) |
| **Chat UI** — [`ui/chat/`](ui/chat/) in this repo | React, TypeScript | Standalone chat widget, served at `/chat` |
| **eddi-website** | Astro, Starlight | Marketing site at eddi.labs.ai. The documentation is this repo's `docs/`, published at docs.labs.ai |

> **The Manager and the Chat UI are directories of this repository** (`ui/manager`, `ui/chat`), not separate repos. They were `labsai/EDDI-Manager` and `labsai/EDDI-Chat-UI` until 2026-09-15; both histories were imported with their commits intact (`git log -- ui/manager`). Maven builds both into the jar — see Build & Test Commands.

> **Versions live in `pom.xml`** (Java, Quarkus, every dependency) — treat it as the single source of truth. This file names the Java baseline for context but deliberately does not restate specific Quarkus/library versions, which drift. See §2 rule 7.

### Key Architecture

- **Config-driven engine**: Agent logic is JSON configs, Java is the processing engine. When designing a new feature, always ask: "should this be configurable by the agent designer?" If yes, expose it as a config field with sensible defaults — don't hardcode behavior or pick a single "best" approach.
- **Lifecycle pipeline**: Input → Parse → Behavior Rules → Actions → Tasks → Output. The rules that keep it sound are §4.1.
- **Self-contained platform**: EDDI is a closed platform, not a library consumed by third-party code. Internal interfaces (`IUserMemoryStore`, `IResourceStore`, etc.) have no external consumers. Deprecation and replacement of internal APIs is safe — the only backward-compat concern is old JSON configs stored in the database or imported via ZIP.
- **CI/CD**: GitHub Actions (compile → test → Docker build → smoke test → push to Docker Hub). `[skip docker]` in commit message skips image builds. Tag-based releases (`6.2.0` → `labsai/eddi:6.2.0`) — the release job triggers on tags matching `[0-9]*`, so the tag must **not** be `v`-prefixed or nothing fires. **Never hand-edit the EDDI release version**: after a stable tag, `post-release.yml` opens a PR that points the docs/manifests at the release and moves `pom.xml` to the next minor; by hand, use `python scripts/bump-version.py` (see [`docs/release-versioning.md`](docs/release-versioning.md#after-a-ga-release)). The Helm chart's *own* `version` is different: any change under `helm/` still bumps it by hand, together with `EXPECTED_CHART_VERSION` in `DeploymentManifestsTest`. `ci.yml` also runs CodeQL (Java and UI), Gitleaks (`Secret Scanning`), Trivy (`Trivy Filesystem Scan`) and a CycloneDX SBOM; there is deliberately no DAST job. `codeql.yml` is only a weekly rescan; fuzzing (`clusterfuzzlite.yml`) and Scorecard (`scorecard.yml`) are separate workflows.

### Build & Test Commands

**Prerequisites & first run:** you need **JDK 25**, **Docker**, and a **MongoDB** instance. Full setup is in [README → Development](README.md#️-development); the quickest path is `docker run -d -p 27017:27017 mongo:7`, then `./mvnw compile quarkus:dev '-Djvm.args=--add-modules=jdk.incubator.vector'` (app on port **7070**, Dev UI at `/q/dev`; the flag gives `jlama` agents the Vector API the container image enables — see README).

**Toolchain:** commands use the bundled Maven wrapper — no local Maven needed. **On Windows, substitute `.\mvnw.cmd` for `./mvnw` below.** If you use [mise](https://mise.jdx.dev), `mise.toml` pins the exact JDK 25 + Maven and mirrors these as tasks (`mise run dev`, `mise run test`, …) that auto-install the toolchain — the lowest-friction path; treat `mise.toml` as canonical for tool *versions*.

**Once per clone:** run `git config core.hooksPath .githooks` to activate the pre-push safety hook (§2 rule 4) — a fresh clone does not enable it automatically.

The [README "Maven Command Reference"](README.md#maven-command-reference) is the canonical full command list (verify against it if a command changes); the essentials:

| Command | What it does |
| --- | --- |
| `./mvnw compile quarkus:dev '-Djvm.args=--add-modules=jdk.incubator.vector'` | Start dev mode with live reload — app on port **7070**, Dev UI at `/q/dev`. The flag is only needed for `jlama` agents, and `mise run dev` passes it |
| `./mvnw package -DskipTests -DskipUi=true` | Build the jar **without** the Manager and Chat UIs (the jar then serves no UI). `compile` and `test` never build the UIs anyway — see the note below the table |
| `./mvnw compile` | Compile only (fast feedback) — run before every commit per §2 rule 6. It is also where the style gates fire: Checkstyle's import rules and `formatter:validate` are both bound to the `validate` phase, which `compile` runs through, so an unused import or an unformatted file **fails the build here** rather than being silently rewritten. Fix with `./mvnw formatter:format` (formatting) or by deleting the import (Checkstyle) |
| `./mvnw test` | Unit tests (excludes `*IT.java`); JaCoCo report at `target/site/jacoco/index.html` |
| `./mvnw test -Dtest=ClassName` | Run a single test class |
| `./mvnw verify` | Compile + unit tests + package. **Integration tests do NOT run** — `skipITs` defaults to `true` |
| `./mvnw verify -DskipITs=false` | Full build **including** integration tests — requires Docker. The command CI runs |
| `./mvnw validate` · `./mvnw formatter:format` | The two blocking style gates — Checkstyle (`UnusedImports`/`RedundantImport` are `severity="error"`; `FileLength`/`LineLength` stay advisory) and `formatter:validate`, which **reports** drift and never edits your files · auto-format with the project Eclipse formatter, i.e. the fix for a `formatter:validate` failure |

> **The UIs build with Maven, at packaging time.** `ui/manager` and `ui/chat` are built in `prepare-package` and copied into the jar just before it is assembled (`frontend-maven-plugin` + `maven-resources-plugin` in `pom.xml`). So `compile`, `test` and `quarkus:dev` never run npm, while `package`, `verify` and `install` run `npm ci` and `npm run build` for both, about two minutes, unless you pass `-DskipUi=true`. No local Node is needed: Maven downloads Node 24 into `ui/node/` (gitignored). Dev mode serves `/manage` only if an earlier `./mvnw package` left the UI in `target/classes`; for frontend work run `npm run dev` in `ui/manager` (port 3000, proxies the API to :7070) or `ui/chat` (port 5174). Generated output is never committed any more — `src/main/resources/META-INF/resources` holds only `index.html`, `robots.txt` and `scripts/js/landing-redirect.js`. **Once, after pulling the migration into an older checkout, run `./mvnw clean`**: its fileset deletes the formerly committed bundles still on disk, which `quarkus:dev` would otherwise serve from the source tree.

> **Targeted runs skip the repo-wide guards.** `-Dtest=…` runs only what you name, so the tests that grade the whole repository — `ImportStyleTest`, `BuildQualityGatesTest`, the `Documentation*Test`s, `ChangelogFragmentTest` — do not run. Before pushing, run the plain `./mvnw test` or add them to the list; `.claude/skills/ship-pr/SKILL.md` groups them by what each one reads. And redirect Maven output to a file rather than piping it through `grep`/`head`: the pipe returns the filter's exit code, not Maven's, and can cut off the `BUILD FAILURE` lines.

> **Sandbox caveat:** integration tests (`*IT.java`) and any test that binds a loopback/HTTP socket need Docker and frequently cannot run in sandboxed agent environments — CI verifies those. Locally, rely on `./mvnw test` (unit tests) and treat a green CI run as the source of truth for the rest.

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work

1. **Read the key docs**:
   - [`docs/project-philosophy.md`](docs/project-philosophy.md) — **Supreme directive.** 9 architectural pillars governing all EDDI development
   - [`docs/changelog.md`](docs/changelog.md) — **Read the most recent entries first** (newest are at the top). Running log of changes, decisions, and reasoning across all repos and sessions. It holds only recent work, capped at 250 KB; older entries are archived per month under [`docs/changelog/`](docs/changelog/) and are indexed in an Archive table at the top of the live file. Skim the top 2–3 entries for current context — do not read the archives unless you are chasing a specific past decision.
   - [`docs/changelog.d/`](docs/changelog.d/README.md) — **entries newer than the live file.** A branch writes its entry here as its own file so that concurrent PRs do not conflict over one; a nightly job folds them into `changelog.md`. Anything sitting here is more recent than the top of that file, so list this directory too.
   - [`docs/architecture.md`](docs/architecture.md) — Architecture overview, configuration model, pipeline, and DB-agnostic design. It is long (~50 KB): read the sections covering the area you are changing, not the whole file
   - If working on the **Manager** (`ui/manager/`): also read [`ui/manager/AGENTS.md`](ui/manager/AGENTS.md) and its `CLAUDE.md`. For the **Chat UI**: [`ui/chat/AGENTS.md`](ui/chat/AGENTS.md)
2. **Check git status**: Run `git status` and `git log -5 --oneline` to see current branch state and recent work.

### During Work

3. **Branching**: Check `git branch --show-current` and `git log -5 --oneline` to understand the current branch context. **Do NOT commit directly to `main`.** If unsure which branch to use, ask the user.
   - **Always branch from `origin/main`**, never from another feature branch. Run `git fetch origin main` then `git checkout -b my-branch origin/main` to guarantee a clean base.
   - **Name branches by kind**: `feat/…`, `fix/…`, `chore/…`, `docs/…`, `refactor/…`, `test/…`. A tool-generated name — a worktree branch called `claude/<slug>`, for instance — must be renamed with `git branch -m` **before the first commit**, not merely before the first push: once pushed it cannot be taken back.
   - **External contributors** (no push access to `labsai/EDDI`): fork first, point `origin` at your fork and `upstream` at `labsai/EDDI`, and branch from `upstream/main`. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full fork-and-PR workflow.
4. **Push discipline — ask before pushing, never force-push**: Committing locally is free, but **pushing to the remote requires explicit human approval — always ask first** (separate from, and in addition to, the force-push ban). `git push --force` and `git push --force-with-lease` are **forbidden**. To avoid ever needing a force-push, follow these sub-rules:
   - **Never `git commit --amend` after pushing.** Amend only works on unpushed commits. If you already pushed, make a new commit instead.
   - **Never `git rebase -i` on a pushed branch.** Interactive rebase rewrites history. If the branch is pushed, history is immutable.
   - **Never `git reset` on a pushed branch.** Use `git revert` to undo pushed commits (it creates a new forward commit).
   - **Always `git pull --rebase` before pushing** if the remote has new commits.
   - A `.githooks/pre-push` hook blocks non-fast-forward pushes as a safety net — **opt-in per clone**: activate it once with `git config core.hooksPath .githooks` (see [Build & Test Commands](#build--test-commands)).
   - Opening the PR and working its review comments to the end is written down in [`.claude/skills/ship-pr/SKILL.md`](.claude/skills/ship-pr/SKILL.md) — plain Markdown, usable from any assistant.
5. **Commit often and selectively**: Every working unit gets a commit. Use conventional commits:
   ```
   feat(scope): description
   fix(scope): description
   chore(scope): description
   refactor(scope): description
   ```
   **Commits and PRs are attributed to the human author.** Do NOT add AI co-authorship trailers (e.g. `Co-Authored-By: <assistant>`) or tool-advertising footers (e.g. `Generated with…`, `🤖 …`) to commit messages, PR descriptions, PR comments or review replies — keep the history human-attributed.
   **Only stage files you actually worked on.** Never use `git add .` or `git add -A`. The working tree may contain changes from other people, other branches, or other tools — those are not yours to commit. Always:
   - Stage files individually: `git add path/to/file1 path/to/file2`
   - Run `git status` before committing — if any staged file is not part of your task, unstage it
   - Run `git log --stat -1` after committing to confirm the commit only contains your files
6. **Each commit must build**: Run `./mvnw compile` (or `./mvnw test` for backend) before committing. Never commit broken code. `compile` passes through the `validate` phase, so it also enforces the two style gates — an unused import fails Checkstyle and an unformatted file fails `formatter:validate`. Neither rewrites your sources: run `./mvnw formatter:format` to fix formatting, and delete the import Checkstyle names. (The formatter used to run its `format` goal on every build, which edited tracked files behind your back and put them in `git status` next to your real work — the reason rule 5 forbids `git add .`.)
7. **Verify factual claims against authoritative sources**: When writing documentation about the project's technology stack, dependencies, or CI configuration, **always verify against the canonical source** (`pom.xml` for dependencies, `ci.yml` for CI behavior, `Dockerfile` for container config). **Never infer from codebase grep results** — migration code, comments about "previous implementations," and backward-compatibility references describe what the project *used to* use, not what it currently uses. If a term appears 40 times in the codebase but zero times in `pom.xml`, the project does not use it.
8. **Update the changelog immediately before committing**: write a **new file** under [`docs/changelog.d/`](docs/changelog.d/README.md) and include it in the commit that contains the changes being documented. The entry must land on the **same branch** as the work it documents — never on a different branch after the fact.
   - **Never edit [`docs/changelog.md`](docs/changelog.md) by hand**, and never append to a file under `docs/changelog/` — those are archives. Entries used to go at the top of the live file, which meant every open PR inserting at the same point in the same file: git cannot merge that, so with several PRs in flight each one conflicted with every other over a document unrelated to the code under review. A fragment is a new file under a name no other branch picks, so the same two PRs merge without touching each other.
   - **Name it `docs/changelog.d/YYYY-MM-DD-<slug>.md`**, today's date with a lower-case slug unique to your branch (the branch name usually works). Inside it, write exactly what used to go into the live file — one or more `## <title> (YYYY-MM-DD)` entries — but give every relative link **one extra `../`**, because a fragment sits a directory deeper than `docs/changelog.md`. `ChangelogFragmentTest` enforces all of this, so a malformed fragment fails your build rather than the nightly job's.
   - **`## Decision Log` and `## Regression Notes` are running registers, not entries.** They live at the bottom of the live file, are never rotated out, and are appended to at a fixed point — so they conflicted for the same reason. Put your rows in a fenced ` ```decision-log ` or ` ```regression-note ` block inside your fragment and the collator files them.
   - **[`.github/workflows/changelog-collate.yml`](.github/workflows/changelog-collate.yml) runs nightly**: it merges every fragment into `docs/changelog.md` **by date** (a PR that sat open for weeks carries an old date and lands among its contemporaries, not on top), trims the live file back to **200 KB** whenever it is over that, regenerates the Archive table and `docs/SUMMARY.md`, and opens a PR. To do it by hand: `python scripts/collate-changelog.py` then `python scripts/rotate-changelog.py`. `ChangelogRotationTest` fails the build at **250 KB**; the 50 KB gap is headroom, so the session that tips the file over is not the one made to rotate it. **Never raise the cap** — it exists because a single file once reached 1.9 MB (~500k tokens) under a rule that obliges every session to add and never to remove.
   - **CI enforces this.** The `Changelog Discipline` job fails any PR that *adds* a `## ` heading (dated or not — anywhere in the file, the header included), a `---`/`===`-underlined heading, or a dated register row to `docs/changelog.md`; lines inside fenced code blocks are examples and do not count. It also runs `scripts/collate-changelog.py --check`, so a fragment the nightly job would refuse fails your PR. It counts added against removed, so editing the header, fixing a typo in a past entry's heading, correcting a register row and rotating an archive all net to zero and stay allowed. Adding an entry in place is the one thing that raises the count, and the one thing that conflicts. (It is not a required check, so it reports rather than blocks.)

   Each entry should include:
   - Date and short title
   - Repo and branch
   - What changed (files + reasoning)
   - Design decisions made
   - What's in progress / what's next (if interrupted mid-task)

#### Git Recovery — What To Do Instead of Force-Push

| Situation | ❌ Wrong (rewrites history) | ✅ Correct (moves forward) |
| --- | --- | --- |
| Committed to wrong branch, **not yet pushed** | — | `git branch fix/my-work` → `git checkout main` → `git reset --hard origin/main` (safe: nothing was pushed) |
| Committed to wrong branch, **already pushed** | `git reset && git push --force` | `git revert <sha>` on wrong branch, then cherry-pick onto correct branch |
| Need to undo a pushed commit | `git reset --hard HEAD~1 && git push --force` | `git revert <sha> && git push` (new commit that undoes the change) |
| Local branch diverged from remote | `git push --force` | `git pull --rebase` then `git push` |
| Want to clean up commits before merge | `git rebase -i && git push --force` | Use GitHub's "Squash and merge" button on the PR instead |

### After Completing Work (or if interrupted/switching sessions)

9. **Verify branch hygiene**: Before switching branches, run `git status` and park uncommitted work as a `wip:` commit on its own branch rather than a bare `git stash`. **The stash stack is shared by every worktree of this clone**, so a plain `git stash pop` can apply another session's changes. If you must stash, use `git stash push -u -m "<unique tag>"`, note the entry's SHA (`git stash list --format='%H %gs'`), restore with `git stash apply <sha>`, and drop only your own entry. The rest of the end-of-session routine is §6.

---

## 3. Development Roadmap

Follow this order unless the user explicitly requests something different.
**Backend first, then testing, then frontend. Website last.**

> This roadmap is indicative and hand-maintained — it may lag reality. [`docs/changelog.md`](docs/changelog.md) is the source of truth for what has actually landed.

### Already Built — Check Before Building

Most "new" capabilities have a foundation already. Search for it, and read its page, before designing a parallel one.

| Area | Where to read |
| --- | --- |
| Storage — MongoDB or PostgreSQL behind one abstraction, Caffeine cache, NATS JetStream event bus | [`docs/architecture.md`](docs/architecture.md) |
| Conversation memory, token-aware windowing, rolling summaries, recall tool, commit flags | [`docs/conversation-memory.md`](docs/conversation-memory.md), [`docs/memory-policy.md`](docs/memory-policy.md) |
| Persistent user memory, Dream consolidation, visibility scoping | [`docs/user-memory.md`](docs/user-memory.md) |
| LLM providers (19, incl. 8 named OpenAI-compatible), multi-model cascading, prompt snippets, template preview | [`docs/langchain.md`](docs/langchain.md), [`docs/model-cascade.md`](docs/model-cascade.md), [`docs/prompt-snippets-guide.md`](docs/prompt-snippets-guide.md) |
| RAG (config-driven retrieval, pgvector, httpCall RAG) | [`docs/rag.md`](docs/rag.md) |
| MCP server (80+ tools) and MCP client; A2A peer protocol | [`docs/mcp-server.md`](docs/mcp-server.md), [`docs/mcp-client.md`](docs/mcp-client.md), [`docs/a2a-protocol.md`](docs/a2a-protocol.md) |
| Group conversations — 7 discussion styles, votes, negotiation, facilitator, humans as members, shared artifacts, bid-based tasks, standing teams, dynamic agents | [`docs/group-conversations.md`](docs/group-conversations.md) |
| Human-in-the-loop — turn-level and per-tool-call approval gates | [`docs/hitl.md`](docs/hitl.md) |
| OpenAI-compatible `/v1` API (Open WebUI, OpenAI SDKs) | [`docs/open-webui-integration.md`](docs/open-webui-integration.md) |
| Scheduling and background work | [`docs/scheduling.md`](docs/scheduling.md) |
| Secrets vault, connections, audit ledger, GDPR/CCPA erasure and export, HIPAA / EU AI Act checks | [`docs/secrets-vault.md`](docs/secrets-vault.md), [`docs/connections.md`](docs/connections.md), [`docs/audit-ledger.md`](docs/audit-ledger.md), [`docs/gdpr-compliance.md`](docs/gdpr-compliance.md) |
| Security hardening — SSRF-safe HTTP client, auth startup guard, security headers | [`docs/security.md`](docs/security.md) |
| Agent sync (export/import, instance-to-instance), attachments, capability registry | [`docs/agent-sync-guide.md`](docs/agent-sync-guide.md), [`docs/attachments-guide.md`](docs/attachments-guide.md), [`docs/capability-match-guide.md`](docs/capability-match-guide.md) |
| Tracing and metrics — per-task OpenTelemetry spans, Micrometer | [`docs/monitoring/monitoring-guide.md`](docs/monitoring/monitoring-guide.md), [`docs/metrics.md`](docs/metrics.md) |

Tests: more than 20,000 JUnit test methods (unit and integration — `grep -rE '^\s*@(Test|ParameterizedTest)\b' src/test/java | wc -l` counts them; the Manager and Chat UI suites come on top), with >90% instruction / >80% branch coverage enforced (OpenSSF Gold).

### In Progress / Upcoming

| Phase | Area | Description |
| --- | --- | --- |
| — | Memory Architecture | RAG threshold, context selection, auto-compaction, property consolidation (see `planning/memory-architecture-plan.md`) |
| — | Session Forking | State snapshotting, conversation forking (see `planning/agentic-improvements-plan.md` §7) |
| — | Conversation Chaining | Cross-session context carry-over (see `planning/conversation-window-management.md` Strategy 3) |
| 9 | DAG Pipeline | Parallel task execution and the dependency graph. OpenTelemetry tracing and MCP circuit breakers already shipped |
| — | HITL — remaining | The reserved `inGroupTurns: INBOX` mode for member *tool-call* pauses. `VoteConfig.tiePolicy: HUMAN_DECIDES` is likewise still save-time rejected pending its own resume machinery |
| — | Guardrails | Config-driven input/output guardrails in LlmTask (see `planning/guardrails-architecture.md`) |
| 11b | Multi-Channel | Teams adapter (Slack already ships via HITL approval channels; see `planning/multi-agent-ux-improvements.md`) |
| 13 | Debugging & Visualization | Time-traveling debugger, visual pipeline builder |
| 14 | Website | Astro + Starlight documentation site |
| — | Native Image | GraalVM native compilation (see `planning/native-image-migration.md`) |

---

## 4. Backend Java Guidelines

### 4.1 Golden Rules (Non-Negotiable)

1. **Logic is Configuration, Java is the Engine** — Agent behavior (e.g., "if user says 'hello', call API 'X'") MUST NOT be hard-coded in Java. Agent logic belongs in **JSON configurations** (`behavior.json`, `httpcalls.json`, `langchain.json`). Java code creates the `ILifecycleTask` components that _read and execute_ this configuration. (Note: the config file is still named `langchain.json` but the implementing class is `LlmTask`.)
2. **Stateless Tasks, Stateful Memory** — `ILifecycleTask` implementations MUST be stateless. They are singletons shared by all conversations. All conversational state MUST be read from and written to the `IConversationMemory` object passed into the `execute` method.
3. **Action-Based Orchestration** — Tasks MUST NOT call other tasks directly. The system is event-driven. Tasks are orchestrated by string-based **actions**. A task (like `RulesEvaluationTask`) emits actions, and other tasks (like `OutputGenerationTask` or `ApiCallsTask`) listen for them.
4. **Dependency Injection via Quarkus CDI** — All components (`ILifecycleTask`s, `IResourceStore`s) use `@ApplicationScoped` and constructor `@Inject`, and Quarkus discovers the beans. **Lifecycle tasks are the exception to "no registration":** each one is also registered by a `@Startup` bootstrap module (`modules/<area>/bootstrap/*Module`, e.g. `ApiCallsModule`) that puts it into the `@LifecycleExtensions` provider map. Without that, a workflow naming the step type cannot be deployed at all.
5. **Thread Safety** — `IConversationCoordinator` (in-memory or NATS implementation) serializes turns _within_ a conversation and handles concurrency _between_ conversations. Code must be thread-safe and non-blocking. REST endpoints use JAX-RS `AsyncResponse`. Tasks execute synchronously but must not block for extended periods.

> These five rules operationalize Pillars 1, 3, and 5 of the [nine architectural pillars](docs/project-philosophy.md) (the supreme directive). The other pillars — deterministic governance (2), security-as-architecture (4), observability (6), progressive disclosure (7), persistent memory (8), portability (9) — are applied in §4.2, §4.4, and §4.7; read `project-philosophy.md` for the full set and the _why_ behind each.

### 4.2 Core Architecture

#### The Conversation Lifecycle

The `LifecycleManager` is the heart of EDDI. It processes a conversation turn by running a pipeline of `ILifecycleTask` implementations.

A **new pipeline capability** is implemented as a **new `ILifecycleTask`** (but check "Not Everything Is a Lifecycle Task" below first):

1. Create the task class implementing `ILifecycleTask`, and register it in a bootstrap module (§4.1 rule 4)
2. Implement `execute(IConversationMemory memory, Object component)` — `component` is what your `configure()` returned
3. Read from the current step: `memory.getCurrentStep().getLatestData(MemoryKeys.ACTIONS)` (typed keys live in `MemoryKeys`; a plain string key also works)
4. Perform task logic (e.g., call an LLM)
5. Write results back to the step (e.g., `currentStep.storeData(...)`, `currentStep.addConversationOutputString(...)`)

#### The Conversation Memory (`IConversationMemory`)

The **single source of truth** for a conversation:

- **`IConversationMemoryStore`** — loads/saves memory (MongoDB and PostgreSQL implementations; the deployment picks one)
- **`IConversationMemory`** — the "live" object for a conversation
- **`ConversationStep`** — an entry in the stack, holding `IData` objects for that turn
- **`IData<T>`** — generic wrapper for data in a step. Use `Data<T>` to create new objects
- **Reading**: `currentStep.getLatestData("key")` → check for null → `.getResult()`
- **Writing**: `currentStep.storeData(new Data<>("key", value))`. What a client sees in the conversation snapshot is decided by **key prefix**, not by `setPublic`: `ConversationMemoryUtilities.convertSimpleConversationMemory` passes `input:initial`, `actions*`, `output*` and `quickReplies*` and drops the rest, and `returnDetailed=true` returns everything. `setPublic(...)` is copied into snapshots but filters nothing — never rely on it to hide data
- **`ConversationProperties`** — long-term state (e.g., `agentName`, `userId`). Slot-filling uses `PropertySetterTask`

> **Critical distinction**: Conversation memory has **two audiences**. (1) **Pipeline tasks** (BehaviorRules, PropertySetter, etc.) see the **full memory** — all steps, all data keys. (2) **The LLM** sees only a **windowed view** assembled by `ConversationHistoryBuilder` — last N conversationOutputs converted to ChatMessages. When you think about "conversation too long," these are two different problems: the LLM context window (what the model sees) and the stored document size (storage/load time). Most context management strategies only affect #1.

#### The Configuration-as-Code Model

Agent definitions are versioned documents in the database. An "Agent" is a list of "Workflows". A "Workflow" bundles "Workflow Extensions" (JSON configs).

#### Core Workflow Extensions

- **`behavior.json`** → `RulesEvaluationTask` — the **primary orchestrator**. Its `actions` list is the event that triggers other tasks.
- **`httpcalls.json`** → `ApiCallsTask` — **Tool Definitions** with templated API calls.
- **`property.json`** → `PropertySetterTask` — **EDDI's importance extraction mechanism.** Config-driven slot-filling that explicitly selects which data to preserve as `longTerm` properties. These properties survive the LLM context window boundary — they're loaded at conversation init and available in all templates regardless of how many turns have passed. When designing context management or memory features, PropertySetter is **not just slot-filling** — it's how EDDI ensures critical facts outlive the conversation window.
- **`langchain.json`** → `LlmTask` — **Agent Definition** (prompt, model, tools, or legacy chat). (Config file retains the `langchain` name for backward compatibility.)

#### The Template Data Model

When tasks process templates (system prompts, HTTP call bodies, property instructions), `MemoryItemConverter.convert(memory)` produces a map with these top-level keys:

| Key | Type | Source | Example Access |
| --- | --- | --- | --- |
| `context` | `Map<String, Object>` | Input context variables set per turn | `{context.language}` |
| `properties` | `Map<String, Object>` | Conversation properties — raw values from `ConversationProperties.toMap()` | `{properties.preferred_language}` |
| `memory` | `Map` with `current`, `last`, `past` | Conversation step data from the pipeline | `{memory.current.output}`, `{memory.last.input}` |
| `snippets` | `Map<String, Object>` | Prompt Snippets — auto-injected from `PromptSnippetService` | `{snippets.cautious_mode}` |
| `vars` | `Map<String, Object>` | Global Variables — deployment-wide config from `GlobalVariableResolver` | `{vars.default-model}` |
| `userInfo` | `Map` with `userId` | Authenticated user identity | `{userInfo.userId}` |
| `conversationInfo` | `Map` with `conversationId`, `agentId`, etc. | Conversation metadata | `{conversationInfo.agentId}` |
| `conversationLog` | `String` | Formatted conversation history | `{conversationLog}` |

> **Key insight**: `longTerm` properties are loaded into `conversationProperties` at conversation init and are immediately available via `{properties.key}` in any template. You do NOT need a separate template namespace for persistent data — properties IS the namespace.

#### The Property Lifecycle

Properties have a well-defined lifecycle managed by `Conversation.java`:

```
1. Conversation.init()
   └─→ loadUserProperties()
       └─→ IUserMemoryStore.getVisibleEntries(userId, agentId, groupIds, recallOrder, maxEntries)
       └─→ Visibility scoping: self + group + global entries are loaded
       └─→ Converted to Property objects with scope=longTerm
       └─→ Available as {properties.key} in all templates

2. Pipeline runs
   └─→ PropertySetterTask sets properties based on actions
       └─→ scope=step (cleared per turn)
       └─→ scope=conversation (lives for session)
       └─→ scope=longTerm (persisted across conversations)
       └─→ scope=secret (auto-vaulted via SecretsVault)

3. Conversation turn ends
   └─→ storePropertiesPermanently()
       └─→ All longTerm properties saved via IUserMemoryStore.upsert()
       └─→ Visibility applied at persistence boundary (explicit or config default)
```

> **Key insight**: Persistent state is a **session concern** handled in `Conversation.java` init/teardown — NOT a pipeline task. If your feature needs to load/save cross-conversation state, extend the Conversation init/teardown logic. Do NOT create a new `ILifecycleTask` for session-level concerns.

#### Built-in Tool Execution Context

LLM tools (annotated with `@Tool` from langchain4j) always execute **inside a conversation pipeline**. The execution path is:

```
LlmTask.execute(memory)
  └─→ AgentOrchestrator.buildToolSetup(task, memory)
      └─→ every ToolSourceProvider.contribute(ToolAssemblyContext)
          (the modules/llm/impl/*ToolsProvider classes — builtin, http, mcp, a2a, dynamic,
           contextual, artifact, attachment, group-task; the context carries the memory)
  └─→ LLM invokes tool
  └─→ ToolExecutionService.executeToolWrapped()
      └─→ Rate Limiter → Cache Check → Execute → Cost Tracker → Result
```

`IConversationMemory` is always available when tools are assembled: `ToolAssemblyContext` carries it, so a tool that needs conversation state (e.g., `userId`, `agentId`) is constructed with it by its provider. There is no need for `ThreadLocal`, request-scoped beans, or tool parameters for implicit context.

> **Key insight**: LLM tools operate inside a conversation — they should NEVER take `userId` as a parameter. The conversation always knows who the user is. Only external interfaces (MCP, REST) that operate outside a conversation need explicit user identification.

#### Not Everything Is a Lifecycle Task

A common mistake when adding new features is to reflexively create a new `ILifecycleTask`. Before doing so, ask:

| Question | If yes → | If no → |
| --- | --- | --- |
| Does it process data **during** a pipeline turn? | `ILifecycleTask` | Not a task |
| Does it need to react to **actions** from BehaviorRules? | `ILifecycleTask` | Not a task |
| Does it load/save state at **session boundaries**? | Extend `Conversation.java` init/teardown | — |
| Is it background/scheduled work? | Use `ScheduleFireExecutor` | — |
| Is it a new LLM tool? | A stateless `@Tool` bean added to `BuiltinToolsProvider`'s catalog (agents select it with `enableBuiltInTools` / `builtInToolsWhitelist`), or a new `ToolSourceProvider` for a new kind of tool source | — |
| Is it a new REST/MCP endpoint? | Add REST resource or MCP tool class | — |
| Does it add a new agent-level setting? | Add field to `AgentConfiguration` | — |

#### Reusable Infrastructure — Use Before Building

Several infrastructure components are already built and should be reused, not duplicated:

| Infrastructure | What it does | Use it for |
| --- | --- | --- |
| **`ScheduleFireExecutor`** + **`SchedulePollerService`** | Cluster-aware scheduled task execution with fire logging, retries, and configurable conversation strategies (persistent vs new) | ANY background/scheduled work: Dream consolidation, async summarization, maintenance jobs. Never build custom schedulers. |
| **`ToolExecutionService.executeToolWrapped()`** | Rate limiting → cache check → execute → cost tracking pipeline for LLM tool calls | Any operation that needs rate limiting, caching, or cost tracking. |
| **`ToolCostTracker`** (via ToolExecutionService) | Dollar-based LLM cost tracking per conversation | Cost ceilings for background LLM jobs (use `maxCostPerRun` instead of `maxLlmCallsPerRun` — dollar amounts are more meaningful than call counts because different operations cost vastly different amounts). |
| **`SecretResolver`** | Vault-based secret resolution for API keys and credentials | Any feature that needs secrets (LLM providers, external APIs). |
| **Micrometer `MeterRegistry`** | Metrics collection (counters, timers, gauges) exposed at `/q/metrics` | Always add metrics to new features for observability. |
| **`SafeHttpClient`** | SSRF-safe HTTP wrapper — `Redirect.NEVER` + per-hop validated redirects, configurable timeout | ALL outbound HTTP from LLM tools and integrations. Never create `HttpClient.newBuilder()` in tool code. |
| **`UrlValidationUtils`** | Blocks private IPs, loopback, link-local, cloud metadata, non-HTTP schemes | Always call before fetching user-controlled URLs. |
| **`AuthStartupGuard`** | Fails startup if OIDC disabled in prod without explicit opt-out | Automatic — operators only need `QUARKUS_OIDC_TENANT_ENABLED=true`. |
| **`VaultSaltManager`** | Per-deployment PBKDF2 salt for KEK derivation | Managed by `VaultSecretProvider` — no direct usage needed. |

#### Group Conversations — Context Flow

`GroupConversationService` orchestrates multi-agent discussions. When an agent participates in a group, the group context flows into its conversation:

- `AgentGroupConfiguration` defines members (agents or nested groups), discussion style, and phases
- `GroupConversationService.discuss()` creates individual conversations for each member agent
- Group context (groupId, discussion phase, peer responses) is injected via the conversation's `Context` map
- `GroupConversationEventSink` streams SSE events for real-time group discussion visibility
- **Collaboration surfaces are opt-in by absence — but check which kind of absence.** For the two that expose **tools**, `artifactConfig` and `taskListConfig`, a null config means the tools are never *assembled*: they cost no prompt tokens and cannot be argued with, whereas a tool that exists and always says no invites retries. For `contextWindow` and `facilitator`, null means the behaviour does not run at all — no windowing pass, no checkpoint. But `retroConfig` and `humanMemberConfig` are **defaults, not switches**: a null `retroConfig` runs a RETRO phase with the default caps, and a null `humanMemberConfig` still pauses for a HUMAN member's turn — it just waits indefinitely. When adding a group capability, decide which of the three shapes you mean and say so in the field's Javadoc.

When a feature needs to know which group an agent belongs to (e.g., persistent memory with `group` visibility), the groupId comes from the `GroupConversation` context — not from `AgentConfiguration`. The group is a runtime concern, not a static configuration.

Adding a new `ILifecycleTask` is the **heaviest** option — it requires a configuration class, store interface and implementation, REST interface and implementation, a bootstrap module, an `ExtensionDescriptor`, and unit tests (§4.3). Many features fit better as extensions to existing infrastructure.

### 4.3 New Feature Checklist

A new `ILifecycleTask` requires ALL of:

- [ ] Configuration class (`*Configuration.java`) — the existing ones are POJOs with getters (only `LlmConfiguration` is a record); follow the neighbouring style
- [ ] Store interface (`I*Store extends IResourceStore<T>`)
- [ ] Store implementation extending `AbstractResourceStore<T>` (`@ApplicationScoped`). It runs on MongoDB **and** PostgreSQL through `IResourceStorageFactory`, so for these stores the `configs/<area>/mongo/` package name is historical (stores that inject `MongoDatabase` directly have a separate `Postgres*` twin). It inherits `@ConfigurationUpdate` on update/delete
- [ ] REST interface (JAX-RS, extends `IRestVersionInfo`)
- [ ] REST implementation (`@ApplicationScoped`)
- [ ] Bootstrap module (`@Startup`, `modules/<area>/bootstrap/*Module`) registering the task in the `@LifecycleExtensions` map
- [ ] `ExtensionDescriptor` (UI field definitions via `getExtensionDescriptor()`)
- [ ] Unit tests — plain JUnit 5 + Mockito, no `@QuarkusTest` (see §4.6)

> **Note on `@ConfigurationUpdate`:** it is declared in `IResourceStore` as an `@InterceptorBinding`, but **no `@Interceptor` class currently implements it** — today the annotation has no runtime behaviour and is purely a marker documenting "this method mutates stored configuration". Keep it for consistency with the existing stores, but do **not** rely on it to invalidate caches or fire events; caches such as `PromptSnippetService` use an explicit `invalidateCache()` plus a Caffeine TTL instead. Whether to implement the interceptor or drop the annotation is still open.

Every task implements `getId()` (returns `TaskId`), `getType()` and `execute()`. `configure()` and `getExtensionDescriptor()` have defaults in `ILifecycleTask`, but a task that loads a config resource overrides both.

### 4.4 Code Patterns

#### Action Matching

Schematic — `ApiCallsTask.collectMatchingApiCalls` is the real version (§4.5).

```java
IData<List<String>> latestData = currentStep.getLatestData(MemoryKeys.ACTIONS);
if (latestData == null) return;

List<String> actions = latestData.getResult();
for (var task : configuration.getTasks()) {
    if (task.getActions().contains("*") ||
        task.getActions().stream().anyMatch(actions::contains)) {
        executeTask(memory, task, currentStep, templateDataObjects);
    }
}
```

#### Configuration Loading

```java
@Override
public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
        throws WorkflowConfigurationException {
    Object uriObj = configuration.get("uri");
    if (isNullOrEmpty(uriObj)) {
        throw new WorkflowConfigurationException("No resource URI has been defined!");
    }
    URI uri = URI.create(uriObj.toString());
    try {
        return resourceClientLibrary.getResource(uri, MyFeatureConfiguration.class);
    } catch (ServiceException e) {
        throw new WorkflowConfigurationException(e.getLocalizedMessage(), e);
    }
}
```

#### Template Data Conversion

```java
@Inject IMemoryItemConverter memoryItemConverter;

public void execute(IConversationMemory memory, Object component) {
    Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
    // Use templateDataObjects with templating engine
}
```

**Only author-written config fields are templates; runtime data never is.** Never pass user input, a property value or an API response as the template *string* to `ITemplatingEngine.processTemplate` — substitute it as data. If generated text must be spliced into a template's source, wrap it with `TemplateEscaping.unparsedBlock`. Runtime templates run on the restricted engine from `RuntimeTemplateEngineFactory` (no `config:`/`inject:`/`cdi:` namespaces, no `{#eval}`/`{#include}`, per-render caps) — see [`docs/security.md`](docs/security.md#runtime-template-engine).

#### PrePostUtils

`PrePostUtils` (`modules/apicalls/impl`) runs the `preRequest` / `postResponse` property instructions shared by the API-call, MCP-call and LLM tasks:

```java
@Inject PrePostUtils prePostUtils;

// Before main logic — returns the template data rebuilt with the new properties; keep it
templateDataObjects = prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.getPreRequest());
// After main logic
prePostUtils.runPostResponse(memory, task.getPostResponse(), templateDataObjects, httpCode, validationError);
```

#### Metrics (Micrometer)

```java
@Inject MeterRegistry meterRegistry;
private Counter executionCounter;
private Timer executionTimer;

@PostConstruct
void initMetrics() {
    executionCounter = meterRegistry.counter("myfeature.execution.count");
    executionTimer = meterRegistry.timer("myfeature.execution.time");
}
```

#### Built-in Tool System

A built-in tool is an `@ApplicationScoped` bean with `@Tool` methods (langchain4j). Add it to the catalog in `BuiltinToolsProvider` with the whitelist key agents use to select it; every call then runs through `ToolExecutionService.executeToolWrapped()` (rate limiting, caching, cost tracking).

```java
@ApplicationScoped
public class MyTool {
    @Tool("Performs a specific operation")
    public String doSomething(String input) {
        return result;
    }
}
```

Pipeline: `Tool Call → Rate Limiter → Cache Check → Execute → Cost Tracker → Result`

#### Tool Security

- **Use `SafeHttpClient`** (`@Inject SafeHttpClient`) for ALL outbound HTTP — never create `HttpClient.newBuilder()` directly
- **Always validate URLs** with `UrlValidationUtils.validateUrl(url)` before fetching user-controlled input
- **Only allow `http`/`https`** — never `file://`, `ftp://`, etc.
- **Block private/internal addresses** — handled automatically by `SafeHttpClient.sendValidated()`
- **Never use `ScriptEngine`** — use a recursive-descent parser (e.g. `SafeMathParser`, a static inner class of `CalculatorTool` — inject `CalculatorTool`, not the parser)

```java
@Inject SafeHttpClient httpClient;

@Tool("Fetches data from a URL")
public String fetchData(@P("URL (http or https)") String url) {
    try {
        var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        var response = httpClient.sendValidated(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    } catch (Exception e) {
        return "Error fetching URL: " + e.getMessage();
    }
}
```

### 4.5 Reference Task — Copy the Real One

There is no synthetic example here on purpose: the last one did not compile, and nothing tested it. The API-call extension is the smallest complete, real implementation of everything §4.3 lists — read it before writing a new task:

| Piece | File (under `src/main/java/ai/labs/eddi/` unless noted) |
| --- | --- |
| Task | `modules/apicalls/impl/ApiCallsTask.java` — constructor injection, action matching, `configure()`, `ExtensionDescriptor` |
| Registration | `modules/apicalls/bootstrap/ApiCallsModule.java` |
| Configuration | `configs/apicalls/model/ApiCallsConfiguration.java` |
| Store | `configs/apicalls/IApiCallsStore.java`, `configs/apicalls/mongo/ApiCallsStore.java` |
| REST | `configs/apicalls/IRestApiCallsStore.java`, `configs/apicalls/rest/RestApiCallsStore.java` |
| Unit test | `src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallsTaskTest.java` |

### 4.6 What a New-Feature Change Contains

1. **Implementation plan** (2-3 bullet points) before the code
2. **All the pieces** — for a new task, everything in the §4.3 checklist
3. **Sample JSON config** whenever an agent designer can set the feature, with what the default does
4. **Tests** — unit tests are plain JUnit 5 + Mockito (`@Mock`/`mock(...)`, often `@Nested`) that verify memory reads and writes; `@QuarkusTest` is reserved for the few tests that need the container (startup guards). Anything that needs a database or HTTP goes in a `*IT.java` integration test (Testcontainers)
5. **Docs** — update the feature's page under `docs/` (and `docs/SUMMARY.md` for a new page) plus a changelog fragment (§2 rule 8)
6. **The Platform Operator** — whenever functionality is added, changed or removed, check whether the operator needs to know, exactly as you check the docs. It is an agent that operates *this* deployment from a system prompt ([`ui/manager/src/lib/operator/system-prompt.ts`](ui/manager/src/lib/operator/system-prompt.ts)) and an endpoint allow-list ([`tool-scopes.ts`](ui/manager/src/lib/operator/tool-scopes.ts)), and neither learns anything by itself: a renamed endpoint, a new config field an admin will ask about, a changed default or a new failure mode stays invisible to it until someone writes it down. Ask: would an admin ask the operator about this, and could it answer correctly — with a tool it holds, from text it has? Update the prompt, the allow-list (read [`ui/manager/AGENTS.md`](ui/manager/AGENTS.md) → Platform Operator first; it is an allow-list with deliberate exclusions) or both. **Any change to either is also a revision bump** in [`operator-revision.json`](ui/manager/src/lib/operator/operator-revision.json) — `operator-revision.test.ts` fails until you bump it — because that is what tells every existing deployment, in the Manager and in its startup log, that its operator should be upgraded

### 4.7 Best Practices & Common Pitfalls

#### EDDI-specific rules

- **Tasks are singletons** — never keep conversation data in fields; when checking-then-acting on shared state, synchronize.
- **`getLatestData()` returns null** when the key is absent — check before `.getResult()`, and handle null/empty action lists.
- **Don't let one task kill the pipeline** — wrap external failures in `LifecycleException`, degrade gracefully, and log with the conversation and agent id (JBoss `Logger`, never `System.out`).
- **Keep conversation memory small** — it is loaded on every turn; don't store large objects in it. Validate configuration in `configure()` with a descriptive `WorkflowConfigurationException`, and cache expensive resources (models, compiled templates) rather than rebuilding them per turn.

#### Imports

- **Always reference types and annotations by their simple name with a top-level `import`** — never inline a fully-qualified name (e.g. write `@Inject IAttachmentStore store;` with the imports, not `@jakarta.inject.Inject ai.labs.eddi.engine.attachments.IAttachmentStore store;`). FQNs in field declarations, method signatures, annotations, and generics hurt readability and are a common review comment.
- The **only** acceptable inline FQN is disambiguating two classes that share a simple name and are both used in the same file — and even then, prefer restructuring so only one is imported.
- Don't leave unused imports behind after a refactor; run `./mvnw formatter:format` and `./mvnw validate` (Checkstyle) before committing. Both are enforced, not advisory: `UnusedImports` and `RedundantImport` carry `severity="error"` in `checkstyle.xml` and the plugin fails on them, and `formatter:validate` fails on unformatted sources instead of rewriting them — so any `./mvnw compile`, `test` or `verify` will stop on either. **Mind the scopes, they differ:** `formatter:validate` grades `src/main/java` *and* `src/test/java`, but the Checkstyle gate sets `includeTestSourceDirectory=false`, so its import rules grade **`src/main/java` only** — an unused import in a test still compiles and still merges. Keep test imports clean by hand; the flag stays off until the 163 pre-existing violations in `src/test/java` are cleared, and `BuildQualityGatesTest` fails if the flag and this sentence ever disagree.

#### Production-Scale Thinking

When designing any new feature, always consider these before finalizing the design:

- **Race conditions**: If multiple agents/conversations can write the same data, what happens on concurrent writes? Use appropriate upsert key granularity.
- **Unbounded growth**: Can users/LLMs create unlimited entries? Add configurable caps with clear UX for when limits are reached (actionable error messages, not silent failures).
- **LLM abuse**: If an LLM can invoke a tool, it can invoke it 100 times in one turn with garbage data. Add per-turn write limits, value size limits, and input validation.
- **Cost at scale**: If a feature uses LLM calls in background jobs, what happens with 10,000 users? Use dollar-based cost ceilings (`maxCostPerRun`) instead of call counts — call counts are meaningless because different operations cost vastly different amounts. Add incremental processing (only process what changed since last run) and round-robin fairness.
- **Implicit context**: If code runs inside a conversation, don't pass `userId`/`agentId` as explicit parameters — the conversation always knows who the user is. Only external interfaces (REST, MCP) need explicit identification.
- **Unification over duplication**: Before creating a parallel system (e.g., new store alongside old store), ask: can the new system replace the old one? Prefer unified systems with legacy compat methods over dual storage. When two features need similar infrastructure (e.g., LLM-based summarization for both Dream consolidation and conversation context), build one shared service, not two parallel implementations.
- **Full data is never deleted by optimization**: Context management strategies (summarization, windowing) are about what the LLM _sees_, not what is _stored_. The full conversation is always preserved in the database. Summaries are derived views, not destructive transformations. If an agent needs to access the full original, it should be able to (via tools, REST API, or debugger).

### Key Files

| File | Purpose |
| --- | --- |
| `src/main/docker/Dockerfile` | Production JVM container image (digest-pinned base) |
| `src/main/resources/application.properties` | Quarkus config (CORS, health, OpenAPI, datastore) |
| `.github/workflows/ci.yml` | CI/CD pipeline (build, test, Docker push, smoke test) |
| `docs/` | Markdown documentation, published at docs.labs.ai |
| `ui/manager/`, `ui/chat/` | The Manager and Chat UI sources (React, Vite, TypeScript). Each has its own `AGENTS.md`; CI runs them in `UI Manager Checks`, `UI Manager E2E (MSW)`, `UI Chat`, `Backend E2E` and `Auth E2E (Keycloak)` |
| `docker-compose.yml` | EDDI + MongoDB local setup |
| `mise.toml` | Optional [mise](https://mise.jdx.dev) toolchain (pinned JDK 25 + Maven) + task shortcuts |
| `docs/agent-configs/` | Worked agent config sources — reference for AI; partially swept by two unit tests (scope in [`docs/agent-config-authoring.md`](docs/agent-config-authoring.md#reference-implementation)) |
| `src/main/java/.../engine/httpclient/SafeHttpClient.java` | Centralized SSRF-safe HTTP client wrapper |
| `src/main/java/.../engine/security/AuthStartupGuard.java` | Production auth enforcement guard |
| `.env.example` | Docker Compose env var reference (copy to `.env`; optional for basic local dev) |

### Docker & Container Security

The production image (`src/main/docker/Dockerfile`) uses a Red Hat UBI 10 base pinned by **SHA256 digest** for OpenSSF supply-chain compliance:

- Every `FROM` line must include `@sha256:...` — never use a bare tag like `:1.24`.
- The build has two stages — `docs`, and the unnamed runtime stage — and **both `FROM` lines carry the same pin**. Move them together: `base-image-check.yml` reads the last `FROM` but its `sed` rewrites every line carrying the pin, so bumping one leaves a stale base in the image and desynchronises the automation.
- `ContainerBaseIT` builds its image from this file via `EddiImageDockerfile.forTestContext()`, so the pin cannot drift — never restate the image reference in test code.
- RHEL 10 carries two constraints the `FROM` line documents in full and that any change here must preserve: a **x86-64-v3 host CPU floor** (glibc refuses to start below it) and a crypto policy that **disables the static-RSA TLS 1.2 suites** for the JVM as well as the OS.

**When Trivy flags a base-image CVE:** first look for a newer digest of the same tag (`docker pull registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24`, then `docker run --rm <image> rpm -q <package>`). If it carries the fix, update the `@sha256:` pin — done. If no fixed digest exists yet, add `microdnf update -y <package> && microdnf clean all` in the `USER root` section with a CVE comment as a **temporary** stopgap, and remove it once a fixed base ships. **Never remove the digest pin** to "auto-fix" a CVE.

---

## 5. Agent Config Authoring

Writing or editing agent JSON — `behavior`, `property`, `output`, `httpcalls`, `langchain`, `mcpcalls`, `rag` or `workflow` configs, the fixtures under `docs/agent-configs/`, or an import ZIP? **Read [`docs/agent-config-authoring.md`](docs/agent-config-authoring.md) first.** It holds the template syntax, the rule-based lifecycle, behavior-rule safety rules, property-setter patterns, the ZIP layout, v6 URIs, the workflow step types and the reference implementation.

The five mistakes it exists to prevent:

1. **`{properties.x}` is a raw value.** `{properties.x.valueString}` fails at runtime. Templates are Qute `{…}`, not Thymeleaf `[[${…}]]`.
2. **Every behavior rule needs an `actionmatcher` on `lastStep`.** A rule with only an `inputmatcher` fires on any step of the conversation. And within a group only the **first** matching rule fires by default (`executeUntilFirstSuccess`) — put independent rules in separate groups.
3. **A comma list in `actionmatcher` means AND** (a contiguous sublist), not OR. Use a shared action or an `OR` connector.
4. **Any `{…}` placeholder in output or a prompt needs `eddi://ai.labs.templating` as the last workflow step**, or users see the raw template.
5. **Reserved actions are not quick-reply expressions** — `CONVERSATION_START`, `CONVERSATION_END`, `STOP_CONVERSATION` and `PAUSE_CONVERSATION` (the HITL gate; see [`docs/hitl.md`](docs/hitl.md)).

---

## 6. Session Protocol

**If picking up from a previous session:**

1. Run `git log -5 --oneline` and `git branch --show-current` to see recent commits and the active branch
2. Run `git status` to check for uncommitted changes
3. Check which phase/item from Section 3 is currently in progress
4. Read [`docs/changelog.md`](docs/changelog.md) — and list [`docs/changelog.d/`](docs/changelog.d/README.md), which holds anything newer — for latest changes and decisions

**If ending a session (or at a natural break point):**

1. Commit all working code (even partial) with `wip:` prefix if incomplete
2. Add your entry under [`docs/changelog.d/`](docs/changelog.d/README.md) (§2 rule 8) with:
   - What was completed (with commit hashes)
   - What's next (the specific Phase/Item from Section 3)
   - Any open questions or decisions needed
3. **Suggest a new conversation** if:
   - A phase is complete
   - A major item (3+ SP) is done and tests pass
   - Context is getting long (many files explored)
