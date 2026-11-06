## ✨ feat(llm): traceable rolling summaries — cited turns, verbatim numbers, a deterministic check (2026-11-06)

**Repo:** EDDI (`feat/traceable-summaries`, stacked on `feat/user-memory-contested`)

### What changed and why

The rolling conversation summary was prose with no link back to the turns it condensed: `conversationRecall` could
drill back, but nothing told the model where to look, and nothing caught a number the summarizer rounded or invented.
`conversationSummary.citeSources: true` makes it extractive and checkable. See
[LLM Configuration → Traceable summaries](../langchain.md#traceable-summaries-citesources).

- **Prompt** — with `citeSources` the summarizer must end every point with the turns it came from (`[turns 4-7, 9]`,
  numbered as the summarizer input and `conversationRecall` number them: 0-based conversation steps), copy numbers
  verbatim and write "unclear in source" instead of guessing. The rules are appended to a custom `summarizationPrompt`
  too.
- **Check** — [`SummaryCitations`](../../src/main/java/ai/labs/eddi/modules/llm/impl/SummaryCitations.java), no model:
  every citation inside the summarized range, every bullet cited, every number verbatim in the cited turns' screened
  text. Citation indices, unbracketed `turn N` references and list markers are not numbers. A citation of a turn
  before the previous boundary is checked against the previous summary's text — the only source of those turns the
  summarizer saw.
- **Policy** — `onValidationFailure`: `keep` (default), `retry_once` (same screened content, spend-checked first, tokens
  added to the first call's so the step prices both), `fallback_to_previous` (previous summary and boundary stay).
  Counted on `eddi.summary.citation.checks{source,outcome}`, charted on the Full Metrics dashboard.
- **Recall** — `conversationRecall` takes a citation as it stands: `[turns 4-7, 9]` returns every turn it names, each
  once, clamped and capped at `maxRecallTurns`. A plain query behaves as before.
- **Wiring** — `LlmTask.resolveEffectiveSummaryConfig` copies the two new fields (it rebuilds the config when the
  provider or model is inherited, and would otherwise have switched the feature off silently).

### Design decisions

- Off by default; while off the prompt is byte-identical and the check never runs. `keep` is the default policy because
  a fallback retries on every turn and pays for it.
- Numbers match by exact text: `1,000` ≠ `1.000` ≠ `1000`, `10%` ≠ `10`, `-5` ≠ `5`. Strict on purpose — the prompt asks
  for verbatim copies, and a reformatted number is exactly what the check exists to catch.
- The check is line-scoped (a bullet plus its continuation lines), not sentence-scoped: deterministic and cheap, and a
  model that puts one citation at the end of its bullet is what the prompt asks for.

### What's next

Dream consolidation gets the same check and records the ids of the entries a consolidated memory came from (next entry).
