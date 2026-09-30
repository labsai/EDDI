## 🐛 fix(operator): knowledge-base diagnosis, a working screen context, and an upgrade path for existing operators (2026-09-30)

**Repo:** EDDI (`fix/operator-rag-knowledge`)

### Why

A review of the Platform Operator against what shipped since its knowledge-base section (2026-09-17) found four gaps. The largest was not about RAG at all: **no prompt improvement ever reached an operator that already existed.** Activation stores the instructions on the config, and the form re-seeded every Reconfigure from that stored copy (`initial.promptBody || default`). So every operator activated before 09-17 still had no knowledge-base section, and none had the 5.5 model catalogue.

### What changed

**What the operator knows about knowledge bases** ([`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts), [`tool-scopes.ts`](../../ui/manager/src/lib/operator/tool-scopes.ts))

- **The three-part binding.** A KB reaches an agent only through the KB document, an `eddi://ai.labs.rag` workflow step, and the LLM task's `knowledgeBases` / `enableWorkflowRag`. Any missing piece fails silently. The prompt stated only the step. It now walks the three in order, as [`rag.md`](../rag.md#troubleshooting)'s table does.
- **Names match on the document's own `name` field.** That is not the descriptor name the prompt told the operator to search by, and the two can differ. `httpCallRag` and `maxRagContextChars` are named as well.
- **`in-memory` is flagged.** It is the default `storeType`, and it empties itself on restart, after 30 idle minutes, and on any secret change.
- **"Did the documents land?" is now answerable.** New read grants `GET /ragstore/rags/{id}/sources/{sourceId}/runs` and `…/files`, each with its own predicate and prompt line. The existing ingestion-status read needs an id that only a direct `POST …/ingest` returns, so the operator could never use it; its line now says so. A KB with no `sources` is stated as not checkable. Run, preview, upload, file delete and purge stay excluded, and a test pins that.
- **Connecting an existing KB** (read-write only) is described when the operator holds the KB reads and both `PUT /workflowstore/workflows/{id}` and `PUT /llmstore/llms/{id}` (`grantsKnowledgeBaseBinding`). The prompt also says that a deploy can be refused by vault-grant enforcement, which the operator cannot fix, and that binding makes the KB's documents answerable to the agent's users.

**Screen context** ([`use-current-screen-context.ts`](../../ui/manager/src/hooks/use-current-screen-context.ts))

- The viewed group was sent as `groupId`, an engine-reserved key that `ClientContextGuard` strips since the 09-26 hardening, so "(group …)" never rendered. It is now `viewedGroupId`.
- `resourceType`/`resourceId`, `conversationId` and `channelId` were sent all along but never read by the template. They are now rendered. On a knowledge base's own page, the operator now knows which KB is meant.
- A test fails if the template ever reads a reserved key again.

**Operator upgrades** ([`operator-revision.ts`](../../ui/manager/src/lib/operator/operator-revision.ts), [`operator-upgrade.tsx`](../../ui/manager/src/components/operator/operator-upgrade.tsx), [`OperatorRevisionCheck.java`](../../src/main/java/ai/labs/eddi/engine/api/OperatorRevisionCheck.java))

- A provisioning revision lives in [`operator-revision.json`](../../ui/manager/src/lib/operator/operator-revision.json), starting at 1; configs from before this are read as 0.
- Activation stamps four fields into the config: `provisionedRevision`, `provisionedEndpoints`, `promptBodyIsDefault` and the Ollama `llmBaseUrl`.
- `operator-revision.test.ts` hashes everything provisioning derives (both scopes' endpoints, preambles and default bodies, plus the gate). It fails when that changes without a revision bump, and prints the new fingerprint.
- **Shown in the Manager:**
  - an *Upgrade* banner on the operator page, listing the tools added and removed;
  - a hint and a dot on the docked drawer;
  - a dashboard line;
  - one `console.warn` per page load.
- **Shown in the server log:** a startup `WARN` from `OperatorRevisionCheck`. It reads the same JSON, which `pom.xml` now copies onto the classpath on every build, `-DskipUi` included.
- **Upgrade is one click.** It is a Reconfigure with every setting carried over, so it runs through every activation check before the old operator is retired. Instructions the admin never edited get the new default. Edited ones are kept unless the admin picks the new default. Legacy configs whose text differs from today's default ask, with *keep* preselected: that text is usually an old default, but it may be an edit, and a click-through must not discard it.
- An operator with a plaintext key (never stored) or an unrecorded model-server address opens the prefilled form instead.
- The form now seeds today's default when the stored text was a default, and offers *Reset to default* whenever the text differs.

**Process** ([`AGENTS.md`](../../AGENTS.md) §4.6, [`ui/manager/AGENTS.md`](../../ui/manager/AGENTS.md))

- New rule: when functionality is added, changed or removed, check the operator's prompt and allow-list alongside the docs. Any change to them is a revision bump.

**Fixed in passing**

- `operator-activation.test.tsx` still expected `claude-sonnet-5` as the fallback model and had been failing since the 09-29 default bump. It now reads the default from the provider table.

### Decisions

- **Upgrade replaces rather than versions the agent in place.** `setup-api` only creates. An in-place new version would need a backend update mode that regenerates the `apicalls` tools from the spec. Replacement reuses the one path that already proves a new operator safe. Cost: the operator chat ends, and old operator conversations leave its history. The confirmation dialog says both.
- **A fingerprint test, not a computed-at-runtime hash.** The backend needs a plain number it can compare. The test keeps that number honest without a build-time code generator.
- **No Sidebar badge.** `Sidebar` is part of the synced design system, and a data hook there would break its preview bundle. The drawer launcher, present on every page of both shells, carries the dot instead.
- **Paused operators are not flagged.** Activating one again always provisions the current revision.

### Next

- Existing deployments will see the upgrade notice on first start of the release carrying this. Nothing is migrated automatically.
