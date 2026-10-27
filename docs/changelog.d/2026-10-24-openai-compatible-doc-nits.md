## docs(llm): point self-hosted servers at `openai-compatible` everywhere (2026-10-24)

**Repo:** EDDI (`docs/openai-compatible-doc-nits`)

### Why

Two places written before or alongside the `openai-compatible` provider type (#1062) still
sent self-hosted servers to `type: openai` + `baseUrl`, which inherits OpenAI's capability
answers (JSON mode with tools, vision by model name) instead of the per-endpoint flags that
type exists for.

### What changed

- [`docs/langchain.md`](../langchain.md) — the Jlama note ("for a GPU or a larger model…")
  now points at type `openai-compatible` and its section instead of `openai` + `baseUrl`.
- [`docs/langchain.md`](../langchain.md) — the `classifier` guardrail: the `provider`
  explanation names `openai-compatible` for vLLM / `llama-server`, and the Granite Guardian
  on vLLM example uses `"provider": "openai-compatible"` with `serverName: "vllm"`. Verified
  first that the classifier passes `provider` straight to `ChatModelRegistry`, where the type
  is registered, and that its builder delegates to the OpenAI builder, so `customParameters`,
  `maxTokens` and the rest of the example behave identically.
- Platform Operator system prompt
  ([`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts)) carried the same
  classifier advice; corrected the same way. Operator revision bumped 36 → 37
  ([`operator-revision.json`](../../ui/manager/src/lib/operator/operator-revision.json)).

### Verification

`vitest run src/lib/operator` — 16 files, 484 tests passed (run with Node 24; the machine's
Node 22.14 cannot start the Vitest worker). `DocumentationLinksTest` and
`ChangelogFragmentTest` green.
