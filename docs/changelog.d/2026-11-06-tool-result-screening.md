## 🐛 fix(llm-tools): the tool-result guardrail screens the whole result before truncation; the summarizer honours a `${vars:...}` provider type (2026-11-06)

**Repo:** EDDI (`fix/tool-result-screening`)

### What changed and why

**The guardrail ran on the cut text, so everything past the cut was ungoverned (LLM-3).**
`ToolLoopRunner` redacted secrets, truncated, and only then ran the tool-result
guardrail. With `truncationStrategy: paginate` the whole remainder went into
`PaginatedResponseStore` unscreened, and pages 2..N came back through
`fetch_tool_response_page`, a built-in tool. Under the documented narrowing
`directiveAppliesToSources: ["mcp", "a2a", "http"]` a built-in result is not
screened for directives, so an instruction an MCP server placed after the first
page reached the model unredacted, in an envelope that said `builtin`. The
`summarize` strategy likewise sent the unscreened text to its summarizer model.
The default configuration (an empty source list means every source) was not
affected.

`ToolResultGuardrail.inspect` is split in two. `screen` runs the directive rule
on the whole result and applies `warn`/`redact`/`block`; `mark` adds the
provenance envelope. The runner now screens first, truncates the screened text,
and marks what is left, so stored pages and summarizer input are governed text
under the tool's real source. A summary is screened once more, because it is new
text a model wrote from third-party content. Both methods keep the
degrade-to-allow behaviour.

Behaviour changes: `block` now also fires on a directive in the part a
`headTail` or `truncate` cut drops. The `tool_result` trace entry shows the
screened text (without the envelope). `eddi_guardrail_toolresult_count_total`
counts a `summarize` summary as a second screening.

**The summarizer ignored global variables in the provider type (LLM-11).**
`ToolResponseTruncator` passed `task.getType()` unresolved to
`ChatModelRegistry.getOrCreate`, so a task typed `${vars:provider}` failed to
build a summarizer and `summarize` silently became `truncate`. The type is now
resolved through `GlobalVariableResolver`, as `LlmTask` resolves it. Parameters
need no such step: the registry resolves `${vars:...}` and vault references in
them itself, while a Qute expression in a parameter stays unrendered (now noted in
the class Javadoc). `PaginatedResponseStore` moved into the same constructor, which
removed a null-fallback branch for a field CDI always fills and the reflection
the tests used to set it.

### Design decisions

- **Screen before truncation and mark after, rather than screening each page as
  it is fetched.** Per-page screening would need the original source carried
  through the page store and would still miss a directive split across a page
  boundary. Screening once, whole, covers every strategy at once.
- **Re-screen only the summary.** The cut strategies only remove text from an
  already-screened result, so scanning their output again would cost a second
  regex pass and a second metric count on the default path for nothing.
- **Resolve the type in the truncator**, the one place that builds the summarizer
  model, rather than in the runner.

**Files:** [`ToolResultGuardrail.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/ToolResultGuardrail.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`ToolResponseTruncator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolResponseTruncator.java), tests in `ToolLoopRunnerGuardrailWiringTest`, `ToolResultGuardrailTest`, `ToolResponseTruncatorTest`, `ToolResponseTruncatorExtendedTest`, `HelperModelCallRetryTest`; docs [`langchain.md`](../langchain.md), [`mcp-client.md`](../mcp-client.md), [`metrics.md`](../metrics.md).
