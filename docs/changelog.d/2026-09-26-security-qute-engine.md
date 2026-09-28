## 🔒 fix(templating): restricted runtime template engine; data is never rendered as a template (2026-09-26)

**Repo:** EDDI (`fix/security-qute-engine`)

### What changed and why

EDDI renders Qute templates parsed at runtime from agent configuration. Two things about that path were hardened.

**1. Data is no longer treated as template text.** A template is what an author wrote; what reaches it at render time is data. This layer converged on the implementation that landed on `main` with #832 (`fix/template-injection`): `fromObjectPath` values are stored as resolved in both `PropertySetterTask` and `PrePostUtils.executePropertyInstructions`, and output and quick replies that arrive through context (caller-supplied, or built by a `postResponse`) carry #832's persisted `IData#isVerbatim()` marker, which `OutputTemplateTask` honours and also sets on every entry it renders. This branch originally carried its own per-turn marker (`isPreRendered`); it was dropped in favour of `isVerbatim`, which is a superset (it survives a HITL resume). This PR's own contribution is layer 2 below plus the MCP variable-name sanitizing; its data-path tests (`PropertySetterTaskDataIsNotTemplateTest`, `PostResponseDataIsNotRenderedTwiceTest`, now asserting `isVerbatim`) are kept as additional coverage of #832's fix.

Generated text that does end up in a template's source is kept literal: the system prompt a model chooses in `create_sub_agent` is stored inside an unparsed block (`TemplateEscaping.unparsedBlock`, also from #832), and `McpApiToolBuilder` reduces OpenAPI parameter names to plain identifiers before copying them into `{...}` placeholders (collisions get distinct suffixes; the query key itself keeps the spec's name).

**2. Runtime templates use EDDI's own, restricted engine.** The Quarkus-injected engine is meant for build-time-validated application templates and exposes more than agent configuration should reach, including the `config:` namespace. `RuntimeTemplateEngineFactory` now builds a separate engine from it through allow-lists:

- namespaces: `vault`, `eddivault`, `connection`, `vars`, `caller`, `uuidUtils`, `json`, `encoder`, `str`, `time`; `config:`, `inject:` and `cdi:` resolve to nothing, and `str:eval` is dropped;
- sections: `if`, `for`/`each`, `let`/`set`, `with`, `when`/`switch` (no `include`, `insert`, `eval`, `fragment`, `cache`, user tags);
- `ReflectionValueResolver` replaced by `PropertyAccessValueResolver`: record components, public no-argument getters and public fields only — no method with arguments, no non-getter methods, no reflection-sensitive types;
- a missing value always renders empty, independent of `quarkus.qute.property-not-found-strategy`;
- per-render bounds: `eddi.templating.max-output-chars` (default 2,000,000; output length and any single evaluated string) and `eddi.templating.max-iterations` (default 100,000; summed over nested loops, checked before a loop starts). `0` disables a bound.

The audit of shipped configs, `docs/agent-configs`, the docs and the tests found only features on these allow-lists in use (`?:`, `{#for}` with `_count`/`_index`, `{#if}`, `.size`, `.raw`, `{|…|}`, EDDI's string methods and `uuidUtils`/`json`/`encoder`, the pass-through references).

### Behaviour change operators should know about

- A property instruction whose `fromObjectPath` value happened to contain template syntax used to have it evaluated; it is now stored literally. Move any intended template into `valueString`.
- A runtime template using `config:`, `inject:`/`cdi:`, `{#include}`/`{#eval}`, or calling a method with arguments on a non-string object no longer resolves (namespaces render empty; unknown sections fail the render like any template error).
- Renders exceeding the new bounds fail like any template error.

### Tests

`RuntimeTemplateEngineFactoryTest` (engine built exactly as production builds it, from a source that carries a real `config:` namespace, `inject:`, `str:eval` and full reflection — each test asserts the feature is live in the source first), `PropertySetterTaskDataIsNotTemplateTest`, `PostResponseDataIsNotRenderedTwiceTest` (real `PrePostUtils` → `OutputGenerationTask` → `OutputTemplateTask` on a real memory), `RestTemplatePreviewTest$RestrictedEngine`, `McpApiToolBuilderVariableNameTest`, `CreateSubAgentToolPromptEscapingTest`, and `RuntimeTemplateEngineIT` against the running application (the only place Quarkus's generated extension resolvers exist). Each security test was mutation-checked against a reverted fix.

**Files:** [`RuntimeTemplateEngineFactory.java`](../../src/main/java/ai/labs/eddi/modules/templating/impl/RuntimeTemplateEngineFactory.java), [`PropertyAccessValueResolver.java`](../../src/main/java/ai/labs/eddi/modules/templating/impl/PropertyAccessValueResolver.java), [`BoundedLoopSectionHelperFactory.java`](../../src/main/java/ai/labs/eddi/modules/templating/impl/BoundedLoopSectionHelperFactory.java), [`TemplatingEngine.java`](../../src/main/java/ai/labs/eddi/modules/templating/impl/TemplatingEngine.java), [`application.properties`](../../src/main/resources/application.properties), [`McpApiToolBuilder.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java); docs: [`security.md`](../security.md#runtime-template-engine), [`output-templating.md`](../output-templating.md), [`properties.md`](../properties.md), [`httpcalls.md`](../httpcalls.md), [`configuration-reference.md`](../configuration-reference.md), [`AGENTS.md`](../../AGENTS.md).

### Known limits / next

- An OpenAPI `servers[0].url` containing braces still reaches the httpcall URL template; with the restricted engine it can no longer read anything, but it is not escaped.
- A model-written sub-agent prompt that itself mentions a `${vault:…}` reference renders with visible `{|`/`|}` markers around it (the known double-wrap limit of `LlmTask.escapeConfigReferenceMentions`); nothing is evaluated.

```decision-log
| 2026-09-26 | Runtime templates render with an EDDI-built Qute engine assembled from the Quarkus engine's resolvers through allow-lists | The injected engine exposes config:, inject:/cdi:, str:eval and unrestricted reflection to agent-authored templates | Keeping the Quarkus engine and scrubbing template text (unbounded syntax surface); hand-writing all extension resolvers (duplicates Quarkus's generated ones) |
| 2026-09-26 | Values read via fromObjectPath are stored verbatim, with no opt-out | They are user/upstream data; no legitimate configuration needs them evaluated, and valueString covers composing text | A config flag to restore rendering (would re-open the path per agent) |
```
