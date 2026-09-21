## 🖥️ feat(manager): ingestion sources panel for knowledge bases (2026-09-18)

**Repo:** EDDI (`feat/manager-ingestion-sources`, stacked on `feat/rag-ingestion-rest`)

### What it adds

A section inside the RAG editor listing a knowledge base's ingestion sources, with add, edit and remove,
and — once the knowledge base is saved — run, preview, purge and recent run history.

### Why a rewrite rather than a port

The stale `labsai/EDDI-Manager#95` (now archived with that repository) targeted standalone
`/ragstore/ingestion-sources` resources. Sources now live on the knowledge base as `sources[]`, so
creating or editing one is an ordinary save of the RAG config, and only the four runtime verbs have
endpoints of their own. The resulting component is considerably smaller than the 846-line original.

### Decisions

- **A new source cannot be run.** It has no id for the endpoints to address until the knowledge base is
  saved; the panel says so instead of offering a button that 404s.
- **Preview is headed "nothing was embedded".** A preview that looked like a run would be worse than none.
- **Purge goes through `AlertDialog`**, since the next run re-embeds everything the source had ingested.
- **Run history polls only while a run is `RUNNING`.** A crawl takes minutes and the start endpoint
  answers 202; an idle screen should not tick forever.

### Contract snapshot

`ui/manager/src/test/mocks/openapi-operations.json` was regenerated from the OpenAPI document Maven
produces. `openapi-contract.test.ts` failed until it was, because the new MSW handlers mocked endpoints the
old snapshot did not know — and the regenerated diff was exactly the four new endpoints, nothing else.

### Tests

16 cases in `resource-detail-rag-sources.test.tsx` (9 when this entry was written, 7 added by later review rounds); 6558 passing across 413 files. `lint`, `typecheck`,
`i18n:check` and `build` all pass, with translations for all 11 locales in the same commit.

---
