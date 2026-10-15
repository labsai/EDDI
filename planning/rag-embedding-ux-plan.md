# RAG embedding UX — plan

**Status:** proposal, 2026-10-07. Nothing here is implemented yet.

**Goal:** a fresh EDDI should be able to create, fill and query a knowledge base
with no external service and no API key. Choosing a hosted embedding model
should be a deliberate, checked step, never a silent default. The Platform
Operator should be able to do the same setup an admin does, behind its existing
approval gate.

## 1. What is wrong today

| # | Problem | Where | Effect |
|---|---|---|---|
| P1 | No in-process embedding model. All 8 providers are external services; Ollama is a separate server | `EmbeddingModelFactory.java:148-157`; `docs/rag.md:916` lists ONNX as a future enhancement | RAG cannot work with EDDI alone |
| P2 | A new knowledge base is silently OpenAI | `RagConfiguration.java:50` (`embeddingProvider = "openai"`); `rag-editor.tsx:735,790` (`?? "openai"`) | It fails at the first ingest, not at save, and the error points at the run, not the setting |
| P3 | **Example vault keys are saved as real values.** Switching provider or store fills every `${vault:…}` *placeholder* in as the parameter's *value* | `rag-editor.tsx:800-805` (embedding), `:869-874` (store) | The KB references `${vault:openai-key}`, `${eddivault:gemini-key}` and so on — keys nobody created. The setup wizard names vault entries uniquely (`AgentSetupService` `vaultKeyReuse`), so these guesses never match a real key. This is the "non-existing gemini vault key" |
| P4 | The Gemini hint uses the legacy `${eddivault:` prefix | `rag-editor.tsx:94` | Inconsistent with the canonical `${vault:` everywhere else |
| P5 | Nothing checks a credential until ingestion | no test endpoint on `IRestRagStore` | Errors show up only as failed runs |
| P6 | Changing the provider or model on a knowledge base that already holds data leaves old vectors in place | ingestion skips pages whose content hash is unchanged (to verify: whether the embedding model is part of that state) | Retrieval mixes vectors from incompatible models and quietly returns nonsense; the operator itself had to say "purge and re-run" |
| P7 | The vector store's `dimension` (pgvector placeholder `1536`) is unrelated to the chosen model | `rag-editor.tsx:117` | A dimension mismatch fails late |
| P8 | The operator cannot create or edit a knowledge base | `tool-scopes.ts:150-183` (reads only), `system-prompt.ts:288` | The admin has to leave the operator to finish setup |
| P9 | Embedding parameters are a raw key/value list | `KeyValueEditor` in `rag-editor.tsx` | Users type model names and dimensions from memory; there is no model list and no dimension shown |
| P12 | **The in-memory store deletes its data, and nothing refills it.** `in-memory` stores live in the general store cache (`EmbeddingStoreFactory.java:87-89`): `expireAfterAccess(30 min)`, plus `invalidateAll()` on ANY vault-secret or global-variable change (`:163`). For every other store type, eviction drops a client; for `in-memory` it drops **the data**. Ingestion then skips unchanged pages by content hash, so a scheduled run does not refill an emptied store. `docs/rag.md:881-893` documents this as a limitation | `EmbeddingStoreFactory`; ingestion state | A KB answers for 30 minutes, then silently returns nothing. Rotating an unrelated secret wipes every in-memory KB. Users are told to "Purge state + Run now", or to keep it alive with a query every 30 minutes. With "zero-config RAG" (WS1) defaulting to `in-memory`, this becomes the default experience, so **it blocks WS1** |
| P10 | **An empty field is saved as `""`, and `""` defeats the backend default.** The factories default only an ABSENT key (`params.getOrDefault(...)` in `EmbeddingModelFactory` and `EmbeddingStoreFactory`; only the integer parsers treat blank as missing). The Manager creates every hinted key with `""` on provider or store switch (`rag-editor.tsx:803,872`) and on "add" (`:247`) | `rag-editor.tsx`; both factories | `model=""`, `baseUrl=""` and the like reach the provider and fail at ingest. A field that *looks* defaulted is actually blank |
| P11 | **Placeholders mix real defaults with invented examples, and look the same.** `model` hints match the Java defaults. Others do not: pgvector `table` shows `embeddings` but defaults to a per-KB table from the KB id; `user` shows `${vault:pg-user}` but defaults to `eddi`; every `collectionName` shows a sample name but defaults per KB | the `*_PARAM_HINTS` constants vs the factories | A user who copies the hint gets different behaviour from one who leaves it empty. Copying pgvector `table=embeddings` into several KBs makes them **share one table**. The hint lists are hand-copied from Java and drift |

## 2. Decisions to make first

| ID | Decision | Recommendation |
|---|---|---|
| D1 | In-process engine: ONNX (onnxruntime plus a model packaged in the dependency) or Jlama (already a dependency) | **ONNX**, confirmed by the spike in WS0. Jlama needs the Vector API flag and a HuggingFace download on first use, which is wrong for a default |
| D2 | Default model | **Multilingual is required** (decided 2026-10-07: users worldwide, including CJK, Thai, Hindi and Arabic). Candidate: `multilingual-e5-small` (int8 ONNX, ~120 MB, ~100 languages, 384 dimensions). e5 needs `query:` / `passage:` prefixes, so use the existing `InputTypedEmbeddingModel` role split. Verify in WS0 whether langchain4j publishes it as a packaged artifact; if not, load it as a file through `OnnxEmbeddingModel(modelPath, tokenizerPath, PoolingMode.MEAN)`. Larger optional models (`multilingual-e5-base`, `bge-m3`) are offered as on-demand downloads, never bundled |
| D2b | Bundle or download? | **Hybrid:** ship the default model in the **Docker image** (a separate image layer, not in the jar), and **download it on demand, pinned and checksum-verified**, everywhere else. Details in WS1 "Model delivery" |
| D3 | Backend default for `embeddingProvider` | **Keep `"openai"` in Java.** Stored KBs without the field rely on it. Change only what *new* KBs get, in the Manager, the operator prompt and the setup paths |
| D4 | Operator ingestion (`POST …/ingest`, a source's `run`) | **Not in this plan.** Reverses `planning/operator-write-scope-plan.md` §5; needs its own amendment |
| D5 | Can the operator see vault key *names* (never values)? | Needed for it to stop guessing keys. Check `operator-write-scope-plan.md` before adding any `/secretstore` read; that plan excludes secretstore writes only |

## 3. Workstreams

### WS0 — Spike (about 1 hour; throwaway branch, not merged)

Measure on ONNX (D2 candidates) and on Jlama:
- how much the jar and Docker image grow;
- embedding throughput on about 1,000 chunks of 512 characters;
- startup time and heap use;
- a basic retrieval check on a small multilingual fixture: English, German, Chinese, Japanese, Thai, Hindi and Arabic, including cross-language query and document pairs;
- whether `multilingual-e5-small` exists as a packaged langchain4j artifact, or must be loaded as a file; and its licence;
- how much the image grows when the model sits in its own layer, and the first-use download time on the jar path.

Output: the numbers, written into this file. They settle D1 and D2.

**Results (2026-10-12, local branch `spike/embedded-embeddings`, not pushed).** These settle D1 = ONNX and D2 = `multilingual-e5-small` int8.

- **Jlama cannot load it at all.** Jlama 0.8.4's BERT tokenizer is WordPiece-only; e5 uses a SentencePiece Unigram tokenizer. Jlama is out as the default engine.
- **ONNX works.**
  - **Retrieval:** 21 passages in 7 languages (EN, DE, ZH, JA, TH, HI, AR), 15 queries, 8 of them cross-language: top-1 **15/15**.
  - **Load:** about 6 s to build the model on an idle host.
  - **Speed:** about 0.16 CPU-s per 512-character passage with one ORT intra-op thread, so about 6 passages/s per free core. These are lower bounds measured on a loaded host; re-measure on quiet Linux before quoting them.
  - **Memory:** heap about 5 MB, because the model is native. The process grows about 0.5 GB native, and peaks around 1.5 GB at 20 parallel callers.
- **No packaged multilingual artifact exists** on Maven Central. Load the files with `new OnnxEmbeddingModel(modelPath, tokenizerPath, PoolingMode.MEAN)`. That fits D2b, delivery by file.
- **Size:**
  - The dependencies add **+114 MB** to `quarkus-app/lib`. Most of it is onnxruntime 1.20.0 at 93 MB, of which 61 MB is a Windows debug-symbol file. langchain4j pins that version; onnxruntime 1.31 is 51 MB.
  - **To decide in WS1:** strip the debug file in the image build, or override the onnxruntime version after checking compatibility.
  - Model files: 135 MB (`model_quantized.onnx` + `tokenizer.json`).
- **Pinned files:** `Xenova/multilingual-e5-small` @ `761b726dd34fb83930e26aab4e9ac3899aa1fa78`.

  | File | Bytes | SHA-256 |
  |---|---|---|
  | `onnx/model_quantized.onnx` | 118,308,185 | `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193` |
  | `tokenizer.json` | 17,082,730 | `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39` |

  The model and onnxruntime are MIT; DJL tokenizers are Apache-2.0. The Xenova conversion declares no licence of its own, so ship the upstream intfloat MIT notice.
- **Requirements found:**
  1. **`query: ` / `passage: ` prefixes** must be added on the query and ingest paths, configurable per model.
  2. **Scores are compressed** (unrelated passages 0.70–0.80, hits 0.75–0.93), so the current `minScore` default of 0.6 retrieves almost everything. Ship a per-model default threshold, and show it in the editor.
  3. **Thread control:** `OnnxEmbeddingModel` exposes no ORT session options, and the defaults oversubscribe the CPU, costing 1.5–4× the CPU. Write a small adapter that builds the ORT session with bounded intra-op threads (a deployment setting).
  4. **Writable directories:** onnxruntime unpacks natives to `java.io.tmpdir`, and DJL to `~/.djl.ai` / `DJL_CACHE_DIR`. Both must be writable in the image, or point `-Donnxruntime.native.path` at pre-extracted natives. Set `-Dai.djl.offline=true`; tested, the tokenizer then works fully offline.
  5. **Image check pending:** `libstdc++.so.6` must exist in `ubi10/openjdk-25-runtime` (`rpm -q libstdc++`), because both natives link it. glibc is fine: 2.39 against the 2.34 needed.
  6. **Chunk size:** text over 510 tokens is split mid-word for non-English text. Cap chunks at about 500 tokens for this model.

### WS0b — Backend: a built-in store that keeps its data (fixes P12; prerequisite for WS1)

- **Separate data from clients.** Remove `in-memory` stores from the evicting Caffeine cache. Hold them in a registry keyed by KB id, with no TTL and no secret/variable invalidation: an in-memory store holds no credential to rotate. Drop an entry only when its KB is deleted, or when its store or embedding configuration changes (which needs a re-embed anyway, see P6). The memory bound is the configured `maxSegmentsPerRun` per source. Add a deployment-wide cap on in-memory segments with a clear error when it is reached, never silent eviction.
- **Store epoch in the ingestion state.** Each store instance gets an id. Every source's ingestion state records the store epoch it wrote into. If the epoch differs (the store was recreated: restart, config change), the next run re-embeds everything instead of trusting content hashes. This also covers restarts until persistence lands. It is the same mechanism as the P6 embedding fingerprint, so build them together.
- **Durable built-in store, so it survives a restart (decide scope in WS0).** A new store type `builtin` (label "Built-in (EDDI database)") that needs no external service:
  - on PostgreSQL deployments, use pgvector in EDDI's own database when the extension is available;
  - otherwise, an in-process store persisted to EDDI's own datastore (snapshot plus incremental segments in MongoDB/PostgreSQL), loaded lazily on first use after a restart.
  
  Together with the `embedded` provider (WS1), this makes zero-config RAG actually durable. `in-memory` stays available, honestly labelled "test only: lost on restart".
- **"Is anything there?"** Expose the segment count per KB (read endpoint), and show it in the Manager next to the run history. Make "the store is empty but sources reported success" a visible warning. The operator gets the count too, so it stops guessing.
- Docs: rewrite the `docs/rag.md` in-memory caveat (L881-893) to match.

### WS1a — Backend: one source of truth for parameters (fixes P10, P11; serves P9)

- **Blank means absent, server-side.** Normalise embedding and store parameters once, before either factory reads them: drop entries whose value is blank. Do this in the shared entry path the factories use, not by editing each `getOrDefault`. That way every client (Manager, operator, ZIP import, MCP, hand-written JSON) gets the documented default. Unit test: for every provider and store, `{"model": ""}` (or the key's equivalent) builds exactly what `{}` builds. Mutation-check it.
- **A parameter catalogue endpoint:** `GET /ragstore/rags/providers` (read-only). For each embedding provider and each store type it returns its parameters, each as `{ key, required, secret, default | defaultDescription, example, description }`.
  - `defaultDescription` covers computed defaults such as "per-KB table derived from the KB id".
  - Embedding models also list `{ name, dimension }` where known.
- **The Java factories read their default values from the same constants the catalogue publishes.** The catalogue cannot drift from what runs, and a test pins that every key the factory reads appears in the catalogue.
- The operator gets this endpoint as a read, so it stops guessing parameter names and defaults.

### WS1 — Backend: built-in embedding provider

- New provider `embedded` in `EmbeddingModelFactory`, `RagConfiguration.SUPPORTED_EMBEDDING_PROVIDERS` and `SUPPORTED_PROVIDERS_HINT`.
- `embeddingParameters.model` selects from the bundled models; a missing value uses the default model. An unknown value fails on save in `RagConfiguration.validate()`, not at ingest.
- **Model delivery (D2b).** One model directory, `eddi.rag.embedded.model-dir`:
  - **Docker image:** the default model is pre-seeded in its own image layer. That covers the common case with no network access, and the layer stays cached across EDDI upgrades whose model does not change.
  - **Jar, dev mode, and the optional larger models:** downloaded on first use into the directory.
    - Each download is pinned by **source revision and SHA-256** in a manifest compiled into EDDI. A mismatch is a hard failure, never a "use anyway".
    - The source is a configurable base URL (`eddi.rag.embedded.mirror-url`; default the pinned HuggingFace revision), so enterprises can point at an internal mirror.
    - The download uses the outbound HTTP client and is fully logged.
    - Write to a temporary file, then rename atomically. Hold a file lock so concurrent replicas sharing a volume download once.
  - **Air-gapped:** `eddi.rag.embedded.download=false`. A missing model then fails with "copy model X to <dir>", and the docs list the files and checksums.
  - **Visible state:** the model's status per KB (present / downloading n% / missing / checksum failed) is exposed via the test endpoint and the parameter catalogue. The Manager shows it, and ingestion waits for a download in progress rather than failing.
  - Licences: record each model's licence in the manifest and docs (`multilingual-e5-small` is MIT, to be verified in WS0) and in the NOTICE / SBOM where the image bundles it.
- Model instances are cached per model and shared across KBs, never built per turn (AGENTS.md §4.7).
- Report the model's dimension, so the Manager and the pgvector store can use it (feeds WS3 and P7).
- **Embedding fingerprint (P6).** Record provider, model and dimension with the ingestion state of every source. Verify the current behaviour first.
  - On a mismatch, the next run re-embeds everything rather than skipping unchanged hashes.
  - Retrieval against a store whose fingerprint differs from the configuration fails with a message naming the fix, not with silently wrong results.
- **Test endpoint (P5):** `POST /ragstore/rags/test-embedding`, with the RagConfiguration in the body.
  - It resolves secrets exactly as ingestion does (`VaultGrantChecker` and `SecretResolver`) and embeds one short string.
  - It returns `{ ok, provider, model, dimension, latencyMs }` or a specific error: missing vault key, no grant, auth refused by the provider, host unreachable.
  - The request goes through the same `SafeHttpClient` / provider client path as ingestion. It adds no new outbound capability.
  - It needs the same role as editing a KB.
- Tests (unit, Mockito), plus one IT that ingests and retrieves with `embedded` plus `in-memory` and no external service.
- Docs: `docs/rag.md` gets a "Zero-config RAG" quick start (move ONNX out of Future Enhancements), the provider table, the fingerprint and re-embed behaviour, and the test endpoint.

### WS2 — Manager: credentials that cannot be invented (fixes P3, P4)

- **Never write a placeholder as a value.** On provider or store switch, prefill only non-secret keys, and leave them empty so the placeholder shows. Remove the `isVaultPlaceholder(h.placeholder) ? h.placeholder : ""` branch in both places.
- Fix the Gemini hint to `${vault:…}`, and make the hint text say "pick a secret" rather than show a fake name.
- Sensitive keys already render `SecretKeyPicker`. Make it the only path:
  - it lists real vault keys;
  - a value naming a key that does not exist shows an inline error with a "Create secret" action;
  - "Save" warns, but does not block, while any reference is unresolved, because a key can be created after the KB.
- **Reuse a key the user already has.** When the provider matches one already used by an LLM configuration, offer that configuration's vault key first ("Used by agent *X*'s Gemini model"). This uses only the Manager's own reads, with no backend change. `${connection:…}` stays refused for embedding providers, as `docs/connections.md` Limitations explains.
- Clean up already-broken data. When a stored KB references a vault key that does not exist, the editor shows a banner. It does not rewrite the value silently.

### WS3a — Manager: honest fields (fixes P10, P11 on screen)

- **Build the fields from the WS1a catalogue**, not from `EMBEDDING_PARAM_HINTS` / `STORE_PARAM_HINTS`, and delete those constants.
- **Every parameter that has a default gets an explicit Default / Custom switch, and the default is always on screen:**
  - **Default** (the initial state): the field is read-only and shows the effective value, e.g. `text-embedding-3-small`, or for a computed default, "one table per knowledge base (`kb_6789abcd`)". The key is **absent** from the saved JSON.
  - **Custom**: the input becomes editable, prefilled with the default as a starting point, not empty. The default stays visible beneath it ("Default: text-embedding-3-small"), so you always know what you are overriding. The key is saved with the typed value.
  - Switching back to Default removes the key. An empty Custom value is not saveable; that state is what Default is for.
  - The section header counts overrides ("2 custom"), so a reviewer sees at a glance what deviates.
  - One shared component (`DefaultableField`) does this for embedding, store and retrieval settings, and is reusable by the other editors that have the same problem.
- Parameters with no default (`apiKey`, Vertex `project`) have no switch. They are plain required fields.
- Examples appear only as helper text prefixed "e.g.", never as placeholder text that looks like a value.
- **Required fields with no default** (`apiKey`, Vertex `project`) are marked required and block "Test connection" until they are set. A missing one is shown before save, not at ingest.
- **Never write `""`.** Clearing a field removes the key. Provider or store switch adds no keys at all: every catalogue parameter is rendered as a field regardless, so there is nothing to pre-create.
- Unknown keys (from an import or hand-written JSON) still show, in the "Advanced" key/value list, unchanged.
- Existing KBs that already hold `""` values: shown as "empty (default applies)" once WS1a lands, and removed on the next save.

### WS3 — Manager: editor flow (fixes P2, P7, P9)

- **New KB defaults to `embedded` + `in-memory`.** The provider list puts "Built-in (no API key, runs inside EDDI)" first. The other providers follow, grouped as Hosted and Self-hosted (Ollama).
- **Per-provider forms instead of raw key/value:**
  - a model dropdown listing known models with their dimensions, plus "Custom…" for anything else;
  - a `SecretKeyPicker` for the API key;
  - a base URL / endpoint field only where the provider has one.
  
  The key/value editor stays under "Advanced" for extra parameters.
- **"Test connection" button** calls the WS1 endpoint and shows dimension and latency, or the specific error.
- **The store dimension follows the model.** pgvector `dimension` is prefilled from the chosen model, and a disagreement is flagged before save.
- **Changing provider or model on a KB with ingested content** opens a confirm dialog: "Existing vectors are incompatible; all sources will be re-embedded on the next run (≈ N segments)." The count comes from run history where available.
- **In-memory store with scheduled sources:** a hint that every restart re-embeds everything, which costs money with a hosted model and nothing with `embedded`.
- **Run history:** make a failed run's `error` string prominent in the source panel, with a "Test connection" shortcut. To verify: how much of it shows today.
- i18n: every new string in all 11 locales, in the same commit.
- Tests: editor tests for the switch-without-placeholder behaviour (mutation-check it: put the old branch back and the test must fail), the default provider for a new KB, the dimension coupling, and the reuse suggestion. Update MSW handlers for the test endpoint with real backend shapes.

### WS4 — Operator: create and edit knowledge bases (fixes P8)

- **Depends on credential-safe partial edits** (JSON Patch, branch `feat/operator-credential-safe-edits`). A full-document PUT of a KB that holds a `${vault:…}` reference is refused by `ConfigReferenceGuard`, because echoing a reference the operator read back counts as conversation data. This already makes the operator unable to edit any LLM config with an API key. So KB *edits* go through `PATCH /ragstore/rags/{id}`, which never repeats a credential. The guard then refuses any patch that introduces a reference, which is most of the `rag-guard` below for free.
- Add `POST /ragstore/rags`, `PATCH /ragstore/rags/{id}` (preferred over PUT), `POST /ragstore/rags/{id}` (duplicate) and the WS1 test endpoint (a POST, so it is gated like a write, because it spends a provider call) to `WRITE_ENDPOINTS`.
- **`rag-guard` (a hard refusal, like `gate-guard.ts`), applied to every ragstore write.** It refuses a body that:
  - introduces a `${vault:…}` / `${vars:…}` reference not present in the stored previous version. On create, any reference not chosen by the user in this conversation counts. A new KB using `embedded` + `in-memory` needs no reference at all;
  - holds a literal credential in a sensitive key, using the same `isSensitiveKey` rule as the editor;
  - changes a host-bearing parameter (`baseUrl`, `endpoint`, `serverUrl`, `host`, `connectionString`) away from its stored value;
  - cannot be read in full to prove the above, which fails closed.
- **Escalation flags** (attention, not control): new or changed `sources[]` URLs, a provider or model change on a KB with ingested data (re-embed cost), and a switch away from `embedded`.
- **Prompt:**
  - the authoring section prefers `embedded` for new KBs;
  - **never name a vault key it has not read**: ask the user, or (if D5 allows) list key names;
  - run the test endpoint after a change;
  - explain the re-embed consequence.
  
  The read-only line drops out by itself through `grantsKnowledgeBaseAuthoring`.
- Bump `operator-revision.json`. Add tests for the guard (every refusal path, mutation-checked) and for the prompt sections. Update `ui/manager/AGENTS.md` → Platform Operator, which says what is granted and why.

### WS5 — Docs, changelog, cleanup

- `docs/rag.md` (WS1), `docs/security.md` if the test endpoint needs a line, and the operator section of the Manager docs.
- One changelog fragment per PR under `docs/changelog.d/`.
- Close this plan with what landed, and what is deferred: D4, the Jlama provider, more bundled models.

## 4. Delivery

| PR | Contents | Depends on |
|---|---|---|
| 0 | Spike numbers into this file (no code merged) | — |
| 1 | **Quick fixes in the Manager:** WS2 (no placeholder written as a value) plus the minimal part of WS3a (never write `""`; clearing removes the key; an empty field's ghost text reads "Default: …" or "e.g. …" from a corrected hint list). Small and independent; ship first | — |
| 1b | **WS1a backend:** blank-means-absent normalisation and the parameter catalogue endpoint | — (parallel to PR 1) |
| 1c | **WS0b part 1:** in-memory stores out of the evicting cache, plus the store epoch. Small; ship early, it fixes a live data-loss bug | — |
| 2 | WS1 backend: `embedded` provider, fingerprint, test endpoint, docs | PR 0, PR 1c |
| 2b | WS0b part 2: the durable `builtin` store and the segment-count endpoint | PR 1c |
| 3 | WS3a + WS3 editor flow, driven by the catalogue | PR 1b, PR 2 |
| 4 | WS4 operator | PR 2 (test endpoint, `embedded`), PR 3 not required |

PR 1 fixes the most confusing symptom today and touches nothing else.

## 5. Risks

- **Jar and image size, and native code.** Measured in WS0. onnxruntime is JNI, which pushes `planning/native-image-migration.md` further away. Record that in its decision log.
- **Quality expectations.** Small local models retrieve worse than hosted ones. The editor and docs say so plainly: "good to start; switch to a hosted model for quality".
- **Back-compat.** No stored KB changes meaning. The Java default stays `openai`. The fingerprint check treats a KB with no recorded fingerprint as "unknown" and does not force a re-embed, but logs a WARN.
- **Operator guard bypass.** Indirection through `${vars:…}` that expands to a vault reference has to be resolved before the guard compares references. `VaultGrantChecker` already had this bug once (see its Javadoc).
