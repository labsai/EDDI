## 🔒 fix(templating): data is never rendered as a template (2026-09-26)

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

### Compatibility

No stored config, ZIP, REST or MCP shape changes. Behaviour change: a
context-supplied output or quick reply containing `{...}` is now delivered
literally instead of being rendered, and a `fromObjectPath` string is stored
literally. Neither was documented; a config that relied on it should move the
template into an output set or a `valueString`.

**Files:** [`IData.java`](../../src/main/java/ai/labs/eddi/engine/memory/IData.java),
[`Data.java`](../../src/main/java/ai/labs/eddi/engine/memory/model/Data.java),
[`OutputGenerationTask.java`](../../src/main/java/ai/labs/eddi/modules/output/impl/OutputGenerationTask.java),
[`OutputTemplateTask.java`](../../src/main/java/ai/labs/eddi/modules/templating/OutputTemplateTask.java),
[`PropertySetterTask.java`](../../src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java),
[`PrePostUtils.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java).
Tests: `ContextSuppliedOutputTemplatingTest` (real output + templating tasks and a
real Qute engine), new cases in `PropertySetterTaskTest` and `PrePostUtilsTest`.
