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

Dream consolidation gets the same check and records the ids of the entries a consolidated memory came from (entry
below).

## ✨ feat(memory): traceable Dream consolidation — cited originals and sourceEntryIds (2026-11-06)

**Repo:** EDDI (`feat/traceable-summaries`, stacked on `feat/user-memory-contested`)

### What changed and why

A Dream consolidation replaces its originals and then deletes them, so a number the model rounded or invented replaced
the user's fact for good, and nothing recorded what a consolidated memory was made of. `dream.citeSources: true` applies
the rolling summary's check to every consolidation group. See
[User Memory → Traceable consolidation](../user-memory.md#traceable-consolidation-citesources).

- **Model** — [`UserMemoryEntry`](../../src/main/java/ai/labs/eddi/configs/properties/model/UserMemoryEntry.java) gains
  `sourceEntryIds` (never null, `[]` by default; the 15-argument constructor stays). Every ordinary write clears it —
  a value stated anew is not derived from those sources.
- **Stores** — MongoDB and PostgreSQL write it on every path `assessment` is written on (upsert, `insertIfAbsent`,
  `upsertIfOwnedBy`, `replaceIfUnchanged`, the mappers) and the flat `mergeProperties` clears it. PostgreSQL adds
  `source_entry_ids JSONB DEFAULT '[]'` with `ADD COLUMN IF NOT EXISTS`; a MongoDB document without the field reads as
  `[]`. Covered by `DatastoreParityTest` on both backends. The GDPR export carries it (the record is serialised whole).
- **Dream** — with `citeSources` the request numbers the originals (`"ref": 0..n-1`, not their ObjectIds), every
  consolidated entry must carry `"sources"`, and `SummaryCitations.checkConsolidatedEntry` checks it (refs exist, every
  number of the value verbatim in the cited originals' key and value). A separate parser reads the `sources` array; the
  plain parser and request are untouched while the flag is off. Duplicate keys merge their sources.
- **Policy** — `dream.onValidationFailure`, default `fallback_to_previous` (the group is skipped, originals kept);
  `retry_once` is bounded by `maxCostPerRun`, the legacy call cap and the tenant budget and costed like any call;
  `keep` writes anyway (an entry left without valid refs records the whole group). Counted on
  `eddi.summary.citation.checks{source="dream"}`.

### Design decisions

- The Dream default differs from the conversation default (`keep`) on purpose: a rolling summary is a derived view over
  a conversation that is kept in full; a consolidation deletes what it read.
- `sourceEntryIds` points at deleted documents (except an original the consolidation overwrote in place). That is what
  recording provenance can mean without changing what consolidation deletes; the ids identify the facts in logs,
  backups and exports.
- A retry that throws skips the group but does not abort the phase: the group's first call succeeded, so it is no
  configuration fault.
- After merging #1295's `asStatement()` (every store value write is stored `ACCEPTED`): `asStatement()` keeps
  `sourceEntryIds`, because provenance is not a verdict and Dream's consolidation write goes through those same store
  methods. The REST boundary (`UserMemoryWriteRules.withDefaults`) clears it, so a client echoing a consolidated
  entry's ids onto an edited value does not keep them. MCP and every other writer build their entries without sources.
  `DatastoreParityTest` pins both halves on MongoDB and PostgreSQL.

### Review follow-up (2026-11-12)

- **Citation failures no longer log content.** The WARN lines for a failed citation check (conversation summary and
  Dream), a failed retry and an unparseable or unserialisable Dream answer now carry ids, counts, the policy and the
  exception class only. The problem list, which quotes the summary text and memory keys, and the parser's message,
  which quotes the model's answer, moved to a DEBUG line (sanitised, capped).
- **`conversationRecall` and out-of-range citations.** A cited turn past the summarized turns used to be clamped onto
  the last summarized turn, so `[turn 40]` returned turn 4 as if it were the source. A citation wholly outside the
  summarized turns, or a bracket that is not a list of turn numbers, now names no turn: the tool says so and logs one
  DEBUG line. A range that only partly overlaps is still cut at the last summarized turn.
