## ✨ feat(memory): contested and superseded user memories instead of log-only contradictions (2026-11-05)

**Repo:** EDDI (`feat/user-memory-contested`)

### What changed and why

Dream detected user-memory contradictions (one key, different values — possible because `self`/`group` entries are keyed
per agent and the `global` row is shared) but only counted and logged them, and `contradictionResolution` was documented
as "Reserved". It is now implemented as a small claim ledger: no strategy deletes an entry or silently picks a winner.
See [User Memory → Contradiction resolution](../user-memory.md#contradiction-resolution).

- **Model** — [`UserMemoryEntry`](../../src/main/java/ai/labs/eddi/configs/properties/model/UserMemoryEntry.java) gains
  `assessment` (`ACCEPTED` / `CONTESTED` / `SUPERSEDED`; `null` and unknown read as `ACCEPTED`) and `supersededBy` (kept
  only on a superseded entry). The 13-argument constructor stays and builds an `ACCEPTED` entry, so every existing call
  site and old JSON body is unchanged. `conflicted` — already on the record and shown by the Manager, but never set —
  now mirrors `CONTESTED`.
- **Stores** — MongoDB and PostgreSQL read and write both fields on every path (upsert, `insertIfAbsent`,
  `upsertIfOwnedBy`, `replaceIfUnchanged`, the mappers); the flat `mergeProperties` resets to `ACCEPTED`. PostgreSQL adds
  the two columns with `ALTER TABLE … ADD COLUMN IF NOT EXISTS` (default `ACCEPTED` for existing rows). New
  `IUserMemoryStore.setAssessmentIfUnchanged` — a compare-and-set on `updatedAt` that deliberately does **not** bump
  `updatedAt`, one conditional statement on both backends.
- **Dream** — the decision is a pure function,
  [`UserMemoryContradictions`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/UserMemoryContradictions.java);
  `DreamService.resolveContradictions` (replacing `detectContradictions`) logs exactly as before and writes the changes.
  `keep_newest` (default) changes nothing; `mark_contested` flags both sides; `supersede_older` retires the older side
  only where the newer one reaches all of its readers, otherwise contests. Writes stay inside the agent-scoped set
  (`crossAgentMaintenance`, `_gdpr_` keys excluded) like pruning; a lost CAS counts in `conflictsSkipped`. Stale
  verdicts are lifted in every mode. Unknown values WARN and act as `keep_newest`. Contested and superseded entries are
  held back from summarization (`acceptedOnly`).
- **Loading** — `Conversation.mostSpecificPerKey` drops a superseded entry when its key has a non-superseded visible
  entry; contested entries load as usual plus a conversation-scoped `contestedMemories` map (`key → true`). Persisting a
  rewritten key drops it from the marker. `UserMemoryTool` marks contested/superseded lines in recall and search, and a
  re-statement of a contested value is a write (it confirms the fact).
- **Metrics** — `eddi.dream.contradictions.contested`, `.superseded`, `.cleared`, charted on the Full Metrics dashboard
  (Kubernetes copy re-synced) and listed in [metrics.md](../metrics.md). GDPR export carries the new fields
  ([gdpr-compliance.md](../gdpr-compliance.md)).

### Design decisions

- Default stays log-only; the name `keep_newest` is historical — Dream keeps no value, the loader's precedence decides.
- An assessment is not a write of the fact, so it leaves `updatedAt` alone (staleness pruning and `most_recent` recall
  unaffected).
- The competing value is never exposed to the agent — it may be another agent's `self` memory.
- Known limit: a property setter re-setting a contested property to its current value writes nothing, so it does not
  resolve the contest (use `rememberFact`, REST or MCP).

### Next

Manager: show `assessment`/`supersededBy` on the memory page (it already highlights `conflicted`).
