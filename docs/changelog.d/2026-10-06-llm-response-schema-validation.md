## ✨ feat(llm): response shape validation and native JSON schema (2026-10-06)

**Repo:** EDDI (`feat/llm-response-schema-validation`, stacked on `feat/llm-output-parsing-never-throws`)

### What changed and why

R4 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../../planning/llm-turn-resilience-plan.md)). Valid JSON of the wrong shape (the field the template reads is missing, has the wrong type, or is `""`) used to render an empty bubble with no signal. This change produces the verdict; the recovery policy that acts on it (`onSchemaMismatch`, retry) is R5.

- **`ResponseShapeValidator`** (`modules/llm/impl`): in-house validator for a JSON-Schema subset (`type` incl. type arrays, `required`, `properties`, `items`, `enum`, `minLength`, optional `additionalProperties: false`). `networknt` is on the classpath but deliberately not used: it evaluates `pattern` (ReDoS from model output) and resolves `$ref` (remote fetch). Unknown keywords are ignored. An unparseable or malformed schema logs one `WARN` per distinct schema and skips validation; it never fails the turn.
- **`nonBlankFields`** (new `LlmConfiguration.Task` field, list of top-level or dotted names that must be non-blank strings). Placed on the task next to `jsonResponseFormat`, not in `responseValidation`, so it is independent of `responseValidation.enabled` and does not touch the class R5 extends.
- **New outcome `schema_mismatch`** (`ModelOutputParser.Kind.SCHEMA_MISMATCH`, parse overload `parse(raw, convert, schema, nonBlankFields, taskId)`). **The parsed object is kept** (not the raw string): templates that worked on a partially valid object keep working, which a plain `INVALID` would break. The reason is recorded on the step under `llm:output:reason:<taskId>` (also now written for `invalid`), counted in `eddi.llm.output{outcome=schema_mismatch}`. Reasons are built from the failed keyword and the schema's own path (`schema: $.htmlResponseText required`, `nonBlank: $.a.b`), never a model value and never a key taken from the reply (`additionalProperties` names the object, not the key). R5 consumes `Kind.SCHEMA_MISMATCH` / `outcome.reason()`.
- **Native schema enforcement** via `JsonResponseFormatPolicy` (new `responseSchema` record component, `of(..., schema)`, `supportsNativeSchema`) and `ResponseSchemaConverter`: for `openai`, `azure-openai`, `mistral` and `gemini` the request carries the `responseSchema` as a `JSON_SCHEMA` format, per request, never on the cached model. The Gemini no-JSON-with-tools rule is unchanged. `gemini-vertex` and all other providers keep schemaless JSON plus the prompt block (the Vertex binding only takes a schema on the model builder). Conversion is all-or-nothing: an inexpressible schema falls back to schemaless JSON.

### Design decisions

- **Strict mode is not sent.** In langchain4j 1.20.2 `strict` is a model-builder flag (`strictJsonSchema`), not part of `ChatRequest`; setting it would bake it into a cached model, which this codebase avoids (the historical Gemini 400). The schema goes out non-strict and EDDI's validation is the actual gate. Documented in `docs/langchain.md`.
- A `responseSchema` written as an *example object* (`{"answer": "string - ..."}`, as in older docs) is not a JSON Schema: it stays prompt-only and is neither validated nor sent natively, so existing agents are unaffected. With neither a JSON-Schema `responseSchema` nor `nonBlankFields` set, behaviour is identical to before apart from the key `llm:output:reason:<taskId>` on `invalid` replies.
- Shape checks run whenever a schema or `nonBlankFields` is configured; there is no master switch because the only effect is an outcome label, a log line and a metric until R5 adds an action.

### Docs

[`docs/langchain.md`](../langchain.md) Structured Output (native schema matrix, shape validation, reason table, outcome table), [`docs/metrics.md`](../metrics.md), Full Metrics dashboard panel description. Manager type `nonBlankFields` in `ui/manager/src/components/editors/llm/types.ts`.

### Follow-ups

- R5: `onSchemaMismatch` action and corrective retry consume `SCHEMA_MISMATCH`.
- Not verified against live provider APIs: the native-schema matrix comes from the langchain4j 1.20.2 bindings, and the native request is checked at the `ChatRequest` level only.
- Manager UI has the type only; no editor control for `nonBlankFields` yet.
