## ✨ feat(llm): lazy loading for HTTP, MCP and A2A tools (`lazySources`) (2026-11-04)

**Repo:** EDDI (`feat/llm-lazy-external-tools`)

### What changed and why

An operator agent whose tools are a 60–70 operation API sent every tool description on every model call: `toolLoadingStrategy: LAZY` only ever hid the built-in tools, because `AgentOrchestrator.buildToolSetup` built `discover_tools` from the built-ins and merged the http/mcp/a2a sources afterwards, in full.

- **`lazySources`** (`LlmConfiguration.Task`): any of `builtin` (default, the old behaviour), `http`, `mcp`, `a2a`. **`lazyRetention`**: `turn` (default) or `conversation`. Both are defaults, not switches: a stored config is unchanged.
- **`LazyToolCatalog`** (new, stateless): with an external source listed, the `discover_tools` name is reserved in assembly phase 1 (so no remote tool can take it) and completed after phase 2 into a *grouped* meta-tool — its description lists the groups with a one-line summary each; it takes `group` and/or `keywords`; results are ranked and capped by `maxToolsInContext`. The lazily-managed specs travel in `ToolSetup.builtInSpecs`, the component LAZY always activated from, so the live loop, resume and refusal share one list. An empty catalog removes the meta-tool again.
- **Groups**: `ToolGroup` on the tool SPI (`ToolContribution.toolGroups`, carried through `ToolSourceRegistry`). An http group is one apicalls configuration (resource id), summarised by the new optional **`ApiCallsConfiguration.description`**, which `McpApiToolBuilder` now fills from the OpenAPI tag (`"<tag>: <first line of the tag description>"`). An MCP group is one server (`name`, else host — never the URL).
- **Safety parity**: a call to a lazily-managed tool that is not loaded is answered `NOT_EXECUTED` ("call discover_tools first") *before* the approval gate — never executed, never shown to an approver (trace `tool_not_activated`, metric `eddi.llm.lazy_tools.refused`). Loaded tools go through the unchanged per-call pipeline. This also applies to the existing built-in-only LAZY mode, where a hidden built-in called directly used to run. Resume executes approved calls by name regardless of what is loaded.
- **Retention**: the loaded names are stored on the conversation step (`llm:lazyTools:activated:<taskId>`, not a client-visible key) when `discover_tools` activates, and re-applied at the start of every loop run (cascade steps, resume) and, with `conversation`, from the latest earlier step (capped at `maxToolsInContext`). Metric `eddi.llm.lazy_tools.activated`.

### Measured

Synthetic 66-tool HTTP agent, six apicalls groups of eleven, OpenAPI-generated shape (`LazyExternalToolsTest`, GPT-4o tokenizer, tool specs only): eager 66 specs / 40,626 chars / ~9,438 tokens per request; lazy first request 1 spec / 1,024 chars / ~258 tokens; after loading one group 12 specs / 7,795 chars / ~1,831 tokens. See [Lazy tool loading](../langchain.md#lazy-tool-loading).

### Docs

`docs/langchain.md` (table rows + new "Lazy tool loading" section), `docs/httpcalls.md` (`description`). The Platform Operator's prompt and allow-list do not cover tool loading, so they are unchanged.
