## 📝 docs(agents): the AI instructions no longer teach APIs that do not exist, and cost a third less to load (2026-09-28)

**Repo:** EDDI (`docs/ai-instructions-review`)

Every AI session in this repository loads the root `AGENTS.md` in full — through `CLAUDE.md`
and the Cursor rule — and a Manager session is additionally told to read `ui/manager/HANDOFF.md`
before starting. A review of all the instruction files against the code found three kinds of
problem: instructions that are wrong enough that following them produces code that does not
compile or does not work, stale counts and paths, and a context cost (84 KB for the root file,
178 KB for the handoff log) that most sessions pay for nothing.

### Wrong instructions, now corrected (root `AGENTS.md`)

- **§4.1 rule 4 said "no manual module registration".** Every lifecycle task is also registered
  by a `@Startup` bootstrap module (`ApiCallsModule`, `RagModule`, …) in the
  `@LifecycleExtensions` map; without one a workflow naming the step cannot be deployed. The
  §4.3 checklist and the new-feature file list now include the module.
- **§4.5's "Complete Task Example" did not compile** — `MyFeatureTask` was both the task class
  and the element type `getActions()` was called on. Replaced with a table pointing at the real
  API-call extension (task, module, configuration, store, REST pair, unit test), which is the
  smallest complete implementation and cannot drift from the code.
- **§4.4's PrePostUtils snippet called `executePostResponse`**, which does not exist (the method
  is `runPostResponse(memory, postResponse, templateData, httpCode, validationError)`), and
  discarded the map `executePreRequestPropertyInstructions` returns, so later templating would
  run on stale data.
- **§4.2** named `memory.getCurrentData(...)`, `addConversationOutput(...)` and
  `AgentOrchestrator.buildToolList` — none exist — and a `builtInTools` field (the fields are
  `enableBuiltInTools` / `builtInToolsWhitelist`, and a new tool joins `BuiltinToolsProvider`'s
  catalog). Tool assembly is now described as `buildToolSetup` → `ToolSourceProvider.contribute`.
- **§4.2 said `setPublic(true)` makes data "output-visible".** `isPublic()` is copied into
  snapshots and filters nothing; what reaches a client is decided by key prefix in
  `ConversationMemoryUtilities.convertSimpleConversationMemory` (`returnDetailed=true` returns
  everything). The instruction now says so, since "set it false to hide it" is the wrong lesson.
- **§4.1 rule 5 named a `ConversationCoordinator` class** — it is `IConversationCoordinator`,
  in-memory or NATS.
- **§4.3 / §4.6 assumed MongoDB and `@QuarkusTest`.** Stores extend `AbstractResourceStore` over
  `IResourceStorageFactory` (MongoDB and PostgreSQL; the `mongo/` package names are historical)
  and inherit `@ConfigurationUpdate`; task unit tests are plain JUnit 5 + Mockito. "Use Java
  records" is gone too: every configuration class under `configs/` is a POJO with getters, and
  `LlmConfiguration` is the one record.
- **§2 rule 9 told sessions to `git stash` / `git stash pop`.** The stash stack is shared by every
  worktree of a clone, so a bare pop can apply another session's work. It now says to park work
  as a `wip:` commit, or use a tagged stash applied by SHA.

### Smaller: the root file is 56 KB instead of 84 KB

- **§5 Agent Config Authoring (22 KB) moved to [`docs/agent-config-authoring.md`](../agent-config-authoring.md)**,
  a published page (added to `SUMMARY.md`) that is useful to human config authors too. §5 keeps
  a trigger ("writing agent JSON? read this first") and the five mistakes the page exists to
  prevent. Section numbers §1–§6 are unchanged because Java comments and tests cite them;
  every citation of the old §5.x subsections — in `ReservedActionLint`,
  `WorkspaceAccessIndexMigration`, three tests and four published pages (`conversation-memory`,
  `properties`, `httpcalls`, `open-webui-integration`) — now names the new page, and
  `DocumentationAccuracyTest` / `DocumentedRestPathsTest` grade the page instead of `AGENTS.md`.
- `docs/langchain.md` taught the same nonexistent `buildToolList` diagram and now matches §4.2.
- #858 corrected two paragraphs of the old §5 while this was open (rule groups fire only their first
  matching rule by default; runtime data is never template text, and runtime templates use a
  restricted engine). Both are carried into the new page, §5's pitfall list and — the Java half —
  §4.4, so the move does not revert them.
- **§3's "Completed" table** (phase history) became a table of what already exists, each row
  pointing at its doc — the part an assistant actually needs before building something parallel.
- Generic advice models already follow (null checks, log levels) collapsed to the EDDI-specific
  rules; the Docker section condensed; table padding removed; `architecture.md` (50 KB) is now
  "read the section for your area", not required reading in full.
- Also: stale test count (14,000 → ~18,000), the security-scan list (ZAP was removed from CI,
  and push/PR CodeQL runs in `ci.yml`, not `codeql.yml`), a roadmap row still listing the
  Manager approvals UI as to-do (it ships at `/manage/approvals`), the docs host
  (docs.labs.ai, not eddi.labs.ai), a branch-naming rule (rename a tool-generated `claude/*`
  branch before the first commit), a note that `-Dtest` runs skip the repo-wide guards, and
  the no-attribution rule extended to PR comments and review replies.

### Manager and Chat UI instructions

- **`ui/manager/AGENTS.md`**: `HANDOFF.md` is no longer required reading (it is 180 KB) or
  required writing — Manager work goes into a root changelog fragment like every other change,
  since every PR appending to one shared file is the conflict the fragments were built to end.
  `HANDOFF.md` carries a banner saying so. The quality-gate list is now what CI runs
  (`audit:prod`, `lint`, `i18n:check`, `typecheck`, `test`), the Stryker text no longer tells
  you to expect a CI job that does not exist, the stale `EDDI-integration-tests` repo row and
  the eight-directory file tree are replaced, `renderPage` is described with its real
  signature, the i18n and RTL rules are stated once instead of three times, and commits are
  scoped (`fix(manager):`) as they are in practice.
- **`ui/manager/CLAUDE.md` and the three skills**: the hard-coded counts (11 primitives, 14 shared,
  25 components, per-page tallies) had all drifted and are gone; `ResizeHandle`, `ChipInput`,
  `StepDots` and the connection-reference components, the Button `warning` variant and the
  `warning` / `popover` / `ring` tokens are documented; `eddi-data` no longer describes a
  pre-commit hook that was removed.
- **`ui/chat/AGENTS.md`**: the backend emits **nine** SSE events, not eight — `tool_call` has
  been sent since 2026-08-14 and this widget does not handle it yet (a code follow-up, not done
  here). Known `error` events carry a `code`. The query-parameter list is complete, the
  approvals route is the Manager's `/manage/approvals`, the personal `c:\dev\git` path is gone,
  and the CI gates (`typecheck`, `test`) are named.

### Tooling

- `.cursor/rules/project-context.mdc` points at the nested `ui/*/AGENTS.md` files.
- `.claude/skills/ship-pr/SKILL.md` lost a citation of another project's pull request and two
  "this changed" remnants.

### Follow-ups

- Handle the `tool_call` SSE event in `ui/chat` (`SSEEventType` in `src/types.ts` and the event
  `switch` in `src/components/ChatWidget.tsx`).
