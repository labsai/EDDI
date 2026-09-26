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
  - No change to stored config, ZIP, REST or MCP shapes.
  - Stored conversation snapshots gain an optional `verbatim: true` on result
    entries. Older documents load unchanged.
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

**Files:** [`IData.java`](../../src/main/java/ai/labs/eddi/engine/memory/IData.java),
[`Data.java`](../../src/main/java/ai/labs/eddi/engine/memory/model/Data.java),
[`ConversationMemorySnapshot.java`](../../src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java),
[`ConversationMemoryUtilities.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java),
[`OutputGenerationTask.java`](../../src/main/java/ai/labs/eddi/modules/output/impl/OutputGenerationTask.java),
[`OutputTemplateTask.java`](../../src/main/java/ai/labs/eddi/modules/templating/OutputTemplateTask.java),
[`PropertySetterTask.java`](../../src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java),
[`PrePostUtils.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java),
[`CreateSubAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/CreateSubAgentTool.java),
[`PromptSnippetService.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/PromptSnippetService.java),
[`MemoryItemConverter.java`](../../src/main/java/ai/labs/eddi/engine/memory/MemoryItemConverter.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`CounterweightService.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CounterweightService.java),
[`ResourceSharingService.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`SharingChangedEvent.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/SharingChangedEvent.java),
[`prompt-snippets-guide.md`](../prompt-snippets-guide.md).

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

```decision-log
| 2026-09-26 | Under enforced workspaces a render gets only snippets from the agent's space, its owner's own snippets (personal-space agents only) and legacy snippets; granted/published snippets from other spaces are never auto-injected | C4c + pre-push review: snippets are injected by name, so a grant or publish is a push into other tenants' prompts (incl. the counterweight preset names) | Ranking grants/published below closer tiers (they remain the only candidates for unclaimed names such as the presets); scoping by the chatting user |
| 2026-09-26 | Caller-supplied and already-rendered output is marked verbatim per data entry, persisted in ResultSnapshot, and never templated again | C4a + review: context output was rendered as Qute; a HITL resume or a second templating pass re-rendered data | Key-prefix convention (collides with an action named "context", suffix is client-chosen); a transient flag (lost on tool-call resume) |
```
