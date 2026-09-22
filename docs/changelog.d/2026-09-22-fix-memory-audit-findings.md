## 🐛 fix(memory): findings from a live end-to-end audit of the memory features (2026-09-22)

**Repo:** EDDI (`fix/memory-audit-findings`)

### Context

Every memory variation was exercised against a live instance of `main` (MongoDB, Ollama and
Claude): REST, MCP, property scopes, visibility, the LLM memory tools, Dream, the rolling summary,
windowing, strict write discipline, GDPR and the v5 migration. Each fix below reproduces a defect
observed there, and carries a regression test.

### What changed and why

- **Upsert hijack (MongoDB).** A `self`/`group` write was keyed on `(userId, key, sourceAgentId)`
  with no visibility term, so it matched the `global` entry the same agent had created and flipped
  the shared memory to private — every other agent lost it. Reproduced through REST and through
  the `rememberFact` tool ("keep a private note" under a key once shared). The filter now excludes
  global documents, which is the identity PostgreSQL already enforced with its partial unique
  index.
  [`MongoUserMemoryStore.java`](../../src/main/java/ai/labs/eddi/configs/properties/mongo/MongoUserMemoryStore.java)
- **Dream consolidation deleted every memory of a group.** Reproduced on Claude and Ollama: six
  unrelated facts in, zero out, fire reported `COMPLETED`. The model reused an original's key, the
  upsert overwrote that original in place, and "delete every original" then deleted the
  consolidation as well. Write targets are now resolved before anything is written: an original
  that receives a consolidated entry is kept, a key that would land on a memory outside the group
  (another category, another agent's global entry) skips the group untouched, duplicate keys in the
  model's answer are merged, and a failed insert restores the originals it overwrote. The answer
  is no longer truncated to `summarizeTargetEntries` — truncation dropped facts the model had
  preserved while the originals were deleted anyway; the target is now part of the prompt.
  [`DreamService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java)
