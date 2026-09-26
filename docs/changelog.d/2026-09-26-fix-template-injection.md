## 🔒 fix(templating): data is never rendered as a template; snippets are scoped to the agent's workspace (2026-09-26)

**Repo:** EDDI (`fix/template-injection`)

### What changed and why

Qute templates are for what an agent designer wrote. Several paths also rendered
*data* — text a chat client, a user or an upstream API controlled — so whoever
controlled the data could write Qute and have the server evaluate it against the
full template data model: `{vars.*}` (deployment-wide global variables),
`{snippets.*}`, `{properties.*}`, or `{#for i in 2000000000}` and
`{s.repeat(...)}` to pin a worker or exhaust the heap.

- **C4a — context-supplied output and quick replies.** Any `/say` context key
  starting with `output` (or `quickReplies`) of type `object` becomes agent
  output, and the templating task rendered it. `OutputGenerationTask` now stores
  those entries — and the ones an httpcall's `postResponse` build instructions
  produce, which travel the same `context:output` / `context:quickReplies` path
  and have already been rendered once with the HTTP response substituted in —
  with the new `IData#isVerbatim()` flag set. `OutputTemplateTask` skips verbatim
  entries, so they reach the user exactly as sent. Output authored in an output
  set is templated as before, including in a turn that also carries context
  output.
- **C4b — `fromObjectPath` values.** `PropertySetterTask` and
  `PrePostUtils.executePropertyInstructions` passed a String resolved through
  `fromObjectPath` to the templating engine. The documented pattern
  `"fromObjectPath": "memory.current.input"` therefore rendered user text, and a
  postResponse instruction reading an HTTP response rendered whatever the API
  returned. A path-resolved value is now stored as resolved; `valueString`, the
  authored alternative, is still a template. Besides closing the injection this
  stops user text such as `{hello}` from silently rendering to an empty string.
- **C4c — prompt snippets were one global namespace.** `MemoryItemConverter`,
  `LlmTask` and `CounterweightService` all used `PromptSnippetService.getAll()`,
  which put every workspace's snippets into every render, keyed by name with the
  last one listed winning. Under enforced workspaces that disclosed other teams'
  private snippets to any agent (including through C4a before this branch) and —
  new in the verified review — let a snippet in workspace B replace a same-named
  snippet in workspace A's system prompts. The new
  `PromptSnippetService.getForAgent(agentId)` returns only the snippets the agent
  could use, judged by `DescriptorAccess.effectiveLevel` against the agent's
  owner and space (a `CallerSpaces` built from the agent's descriptor), and
  resolves a shared name by closeness: the agent's own space, then the owner's
  snippets, then grants, then published, then legacy; oldest first within a
  tier. All three runtime callers now use it; the counterweight presets
  (`counterweight-cautious` / `-strict`) are resolved the same way, so another
  workspace cannot replace an agent's safety text. `getAll()` stays for the
  template preview, which already redacts snippet bodies for non-admins.

### Design decisions

- **A per-entry flag, not a key convention.** The context output key
  (`output:<type>:context`, `quickReplies:<suffix>`) can collide with an output
  set action literally named `context`, and the quick-reply suffix is chosen by
  the client. A flag on the stored entry says exactly which value came from
  where. It is phrased as `verbatim` (default `false`) rather than `templatable`
  (default `true`) so a Mockito mock of `IData` keeps the old, templated
  behaviour.
- **Not persisted.** The flag only matters inside the turn that stored the entry;
  a rerun and a HITL resume both re-run the output task, which sets it again.
  `ResultSnapshot` and the stored conversation shape are unchanged.
- **No render-size/time guard.** With data no longer templated, only config
  authors (editors) write templates. A size guard around `render()` would not
  have stopped `{s.repeat(2000000000)}` anyway — the string is allocated inside
  one value resolution, before any output consumer sees it — and a CPU-time guard
  needs render cancellation Qute does not offer. Hardening the reflection value
  resolver for author templates is left as a follow-up.
- **Snippets are judged against the agent, not the chatting user.** The user on
  a turn does not own the agent they talk to (the same reason
  `ResourceClientLibrary` bypasses `ResourceAccessGuard`), so the question is
  "could whoever the agent belongs to use this snippet". An agent with no
  descriptor is treated as unowned (published + legacy snippets only); one whose
  descriptor cannot be read gets no snippets for that turn and the view is not
  cached — an unverifiable owner is not an absent owner.
- **Oldest wins a tie**, so creating a same-named snippet later can never take
  over a name an agent already renders. With enforcement off every snippet stays
  visible; a duplicated name now resolves deterministically to the oldest
  instead of to whichever the listing returned last. Each ambiguous name is
  logged once (bounded).
- **Per-agent cache** (1000 agents, 5-minute TTL, cleared by
  `invalidateCache()` with the snippet cache). A change to the agent's own
  owner or space is picked up within the TTL; wiring the sharing endpoints to
  invalidate it is a possible follow-up.
- **Not done here:** save-time rejection of duplicate snippet names within one
  space, and making the template preview resolve snippets through the
  conversation's agent (it keeps `getAll()` plus its existing redaction).

### Compatibility

No stored config, ZIP, REST or MCP shape changes. Behaviour changes: a
context-supplied output or quick reply containing `{...}` is now delivered
literally instead of being rendered, and a `fromObjectPath` string is stored
literally. Neither was documented; a config that relied on it should move the
template into an output set or a `valueString`. Under enforced workspaces an
agent no longer sees snippets outside its reach; an agent that relied on
another team's snippet needs it shared, granted or published. With workspaces
off nothing changes except the deterministic choice between duplicate names.

**Files:** [`IData.java`](../../src/main/java/ai/labs/eddi/engine/memory/IData.java),
[`Data.java`](../../src/main/java/ai/labs/eddi/engine/memory/model/Data.java),
[`OutputGenerationTask.java`](../../src/main/java/ai/labs/eddi/modules/output/impl/OutputGenerationTask.java),
[`OutputTemplateTask.java`](../../src/main/java/ai/labs/eddi/modules/templating/OutputTemplateTask.java),
[`PropertySetterTask.java`](../../src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java),
[`PrePostUtils.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java),
[`PromptSnippetService.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/PromptSnippetService.java),
[`MemoryItemConverter.java`](../../src/main/java/ai/labs/eddi/engine/memory/MemoryItemConverter.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`CounterweightService.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CounterweightService.java),
[`prompt-snippets-guide.md`](../prompt-snippets-guide.md).
Tests: `ContextSuppliedOutputTemplatingTest` (real output + templating tasks and a
real Qute engine), new cases in `PropertySetterTaskTest`, `PrePostUtilsTest`,
`PromptSnippetServiceTest$WorkspaceScoping`, `CounterweightServiceTest` and
`MemoryItemConverterNamespacesTest`; existing LLM task tests re-stubbed for
`getForAgent` and the agent-aware `CounterweightService.apply`.

```decision-log
| 2026-09-26 | Prompt snippets visible to a render are those the agent's owner/space could use; a shared name resolves by closeness, oldest first | C4c: every workspace's snippets reached every render, last listed winning | Scoping by the chatting user (they do not own the agent); newest-wins (lets a later snippet take over a name) |
| 2026-09-26 | Caller-supplied output is marked verbatim per data entry and never templated | C4a: context output was rendered as Qute | Key-prefix convention (collides with an action named "context", suffix is client-chosen) |
```
