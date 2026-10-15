## 🐛 fix(manager): the RAG editor invented vault keys and saved empty values (2026-10-12)

**Repo:** EDDI (`fix/rag-editor-defaults`)

### What changed and why

The knowledge-base editor's provider and store parameters misled users and broke configs in three ways:

1. **Invented vault references were saved as real values.** Switching the embedding provider or vector store pre-filled every parameter whose *example* was a `${vault:…}` placeholder with that placeholder. A knowledge base then referenced `${vault:openai-key}`, `${eddivault:gemini-key}`, `${vault:mongo-uri}` and so on, keys nobody had created; the setup wizard names its vault entries uniquely, so they never matched a real one. The Gemini example even used the legacy `eddivault` prefix.
2. **Empty fields were saved as `""`, which defeats the backend's defaults.** The same switch, and the "+ key" chips, created every other parameter with an empty string. `EmbeddingModelFactory` and `EmbeddingStoreFactory` default a parameter only when its key is *absent* (`params.getOrDefault`), so `{"model": ""}` reached the provider as an empty model name and failed at ingest, while the field looked defaulted.
3. **Placeholders mixed real defaults with invented samples.** `nomic-embed-text` (the real default) and `embeddings` (a sample) looked identical. pgvector's `table` showed `embeddings`, but the backend defaults to one table per knowledge base (`eddi_kb_<id>`); a user who copied the hint into several knowledge bases made them share one table. `user` showed `${vault:pg-user}` where the default is `eddi`.

The editor now:

- **never pre-fills anything** on a provider or store switch, and restores only what that provider had earlier in the session;
- **lists every parameter the backend knows**, each with what an empty field means: `Default: …` (copied from the factories), `e.g. …` (a sample), or `Required`. Credential fields say "pick a vault secret" rather than suggesting a key name;
- **never saves an empty value**: clearing a field removes the key. Unknown parameters (from an import, a hand edit, or "Add Parameter") stay on screen, in place and focused, until they have a value;
- **flags `""` values saved by the old editor** ("the default is not used") with a one-click "Use default";
- treats `connectionString` as a credential field (Atlas);
- follows the storage layout from #945 (knowledge bases isolated by id): the default table, collection or index shows as `eddi_kbid_<id>` for a per-id knowledge base (and for a new one, which the server stamps with that layout), and as the name-derived `eddi_kb_<name>` for a 6.5 knowledge base. OpenAI's now-honoured `baseUrl` shows its real default, `https://api.openai.com/v1`.

This is the first PR of [`planning/rag-embedding-ux-plan.md`](../../planning/rag-embedding-ux-plan.md) (P3, P4, P10, P11). The backend still uses `""` when it is sent. Normalising blank to absent server-side, and publishing the parameter catalogue so these hints stop being hand-copied, is WS1a in that plan.

**Files:** [`rag-editor.tsx`](../../ui/manager/src/components/editors/rag-editor.tsx), [`rag-editor.test.tsx`](../../ui/manager/src/components/editors/__tests__/rag-editor.test.tsx), 14 new `ragEditor.*` keys in all 11 locales, [`planning/rag-embedding-ux-plan.md`](../../planning/rag-embedding-ux-plan.md) (new).
