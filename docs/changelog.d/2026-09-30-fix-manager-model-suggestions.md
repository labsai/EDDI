## 🐛 fix(manager): model suggestions that vendors retired, renamed or never had (2026-09-30)

**Repo:** EDDI (`fix/manager-model-suggestions`)

### Why

While building the model catalog for eddi.technology, every id the Manager suggests was checked
against the vendors' own documentation (2026-09-30). Several could not load: an agent created with one
saves and deploys cleanly, then fails on its first message.

### What changed

- **`ui/manager/src/lib/model-suggestions.ts`**
  - Gemini: dropped the bare `gemini-3.1-pro` (Gemini API and Vertex lists). Gemini 3.1 Pro exists only as
    `gemini-3.1-pro-preview` / `-customtools`, which stay.
  - OpenAI: added `gpt-6.1-sol` (2026-09-29), which supersedes `gpt-6-sol`; the older id is still served
    and stays.
  - Ollama: `phi4:mini` → `phi4-mini` (the `phi4` library only has 14b tags).
  - Hugging Face: `THUDM/GLM-5.1` → `zai-org/GLM-5.1`.
  - Mistral: removed `devstral-latest`, `devstral-small-latest` (Devstral retired by 2026-07-31) and the
    `magistral-*-latest` aliases (their targets were retired 2026-07-31; reasoning is now
    `reasoning_effort` on Small and Medium). `mistral-small-4` → the documented `mistral-small-2603`.
    Added the documented dated ids `mistral-large-2512`, `mistral-medium-3-5`, `ministral-14b-2512` and
    `codestral-2508` beside the `-latest` aliases.
  - Bedrock: Llama 4 Maverick and Scout now suggest the cross-region profiles
    `us.meta.llama4-…-instruct-v1:0`. Bedrock has no in-region on-demand support for Llama 4, so the
    bare id fails.
  - Oracle GenAI: `cohere.command-latest` and `cohere.command-plus-latest` never existed on OCI, and
    Command R / R+ are retired there. Replaced with `cohere.command-a-03-2025`, `-reasoning` and
    `-vision`. The Llama 4 entry used the Hugging Face repo name; it is now OCI's
    `meta.llama-4-maverick-17b-128e-instruct-fp8`. The unverified Scout entry was dropped.
- **`ui/manager/src/lib/api/agent-setup.ts`**: the Oracle GenAI default model `cohere.command-r-plus-v2`
  → `cohere.command-a-03-2025`.
- **`docs/langchain.md`**: the Oracle GenAI example and provider line name Command A.
- **Platform Operator revision 1 → 2** (`operator-revision.json`): the Operator's system prompt carries a model catalogue built from these suggestions, so existing Operators are told to upgrade and stop recommending the retired ids.
- **`pom.xml`: Jackson overrides 2.22.2 → 2.22.3** (core, databind, dataformat-csv, dataformat-xml).
  CVE-2026-91776 and CVE-2026-91777 in jackson-databind 2.22.2 were published on 2026-09-30 and
  turned `Trivy Filesystem Scan` red on every PR. Dependabot's #913 carries the same bump inside a
  larger group whose build is failing, so the security fix is taken here on its own.
- **Test**: `model-suggestions.test.ts` lists the retired ids with the reason for each and fails if any
  provider suggests one or defaults to one.

### Not changed

- `claude-haiku-4-5` stays: it is active, though Anthropic only guarantees it until 2026-10-15, so check
  the deprecation page after that date.
- `deepseek-v4-flash` stays in the vision-token list: the alias still routes to V4.1-Flash.
- The legacy rule-based reference agent under `docs/agent-configs/rule-based-reference/` still offers
  older model ids as quick replies; it is a historical sample and was left as it is.
- The `openai/gpt-oss-*` ids under Oracle GenAI were not verified against OCI's naming and were left.
