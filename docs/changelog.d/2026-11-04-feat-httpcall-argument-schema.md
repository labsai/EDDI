## ✨ feat(httpcalls): validate LLM tool arguments against an argument schema before the approval gate (2026-11-04)

**Repo:** EDDI (`feat/httpcall-argument-schema`)

### What changed and why

Generated OpenAPI tools filled a whole-body template from string parameters, so a malformed call
(missing required field, wrong type, misspelled field, value outside an enum) reached the human
approval card and then failed at the API — or, when the API ignores unknown fields, silently did
nothing after a human had approved it.

- **`ApiCall.argumentSchema`** (new, optional) — JSON Schema of the tool arguments. `null` keeps
  string parameters and today's rendering byte for byte. **`ApiCall.unknownFields`**
  (`reject` | `drop` | `allow`, default `reject` when a schema is set; save-time validated).
- **`McpApiToolBuilder`** generates the schema per operation (new `OpenApiArgumentSchemas`): scalar
  path/query parameters typed, the body schema with every component `$ref` resolved (`allOf` of
  objects merged, recursion and `anyOf`/`oneOf` left untyped, `readOnly` fields omitted).
- **`HttpCallToolsProvider`** types the tool parameters from the schema (via the new
  `ResponseSchemaConverter.toElement`; a parameter with no tool form, including a property-less object
  Gemini would reject, stays a string). With a schema, arguments are normalised (a JSON string for an
  object/array parameter is decoded; `drop` removes undeclared fields) and every non-string value is
  rendered as compact JSON — never a Java `toString` — and substituted as data, as before.
- **`ToolLoopRunner.refuseInvalidArguments`** runs before `ToolApprovalGate.classify`: every executor
  implementing the new SPI `ToolArgumentValidator` is asked; a violating call gets a `NOT_EXECUTED`
  envelope with one line per violation (`$.requestBody.emails: required`,
  `$.requestBody.role: must be one of manager, curator`, `… unknown field (declared fields: …)`), a
  `tool_error` trace step with `error: "argument_schema"`, and `eddi.tool.arguments.refused`. It is
  never paused or executed. The httpcall executor re-checks before sending, which covers resumed calls.
- **`ToolArgumentSchemaValidator`** (`modules/llm/guardrails`) — pure function, uses the
  networknt validator already on the classpath on an effective schema reduced to a safe subset
  (no `$ref` loading, no `pattern`); built so it can become a tool-call guardrail type later.

Docs: [`httpcalls.md`](../httpcalls.md#argument-schema-for-llm-tools),
[`langchain.md`](../langchain.md#argument-schema-refusals).

**Files:** [`ApiCall.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCall.java),
[`ToolArgumentSchemaValidator.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/ToolArgumentSchemaValidator.java),
[`ToolArgumentValidator.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/spi/ToolArgumentValidator.java),
[`OpenApiArgumentSchemas.java`](../../src/main/java/ai/labs/eddi/engine/mcp/OpenApiArgumentSchemas.java),
`McpApiToolBuilder.java`, `HttpCallToolsProvider.java`, `ToolLoopRunner.java`,
`ResponseSchemaConverter.java`, `ApiCallsConfigurationValidator.java`.

### Design decisions

- Rendering moved to the tool-argument boundary (`HttpCallToolsProvider.templateDataFor`, shared by
  the gate-time resolver and the executor) rather than `ApiCallExecutor`: the executor keeps receiving
  strings, so the fingerprint pinned at gate time and the request built at execution agree.
- `drop` is applied when the request is built, not by rewriting the model's call: the transcript and
  the approval card keep the call as the model wrote it.
- Top-level parameters stay all-required as before (an omitted query value would render `?name=`);
  requiredness inside the body follows the spec.

```decision-log
| 2026-11-04 | Tool-argument schema check runs before the approval gate via a `ToolArgumentValidator` executor marker | Malformed generated-tool calls reached human approval, then failed or silently no-op'd at the API | A new map threaded through `ToolContribution`/`ToolSetup` (more plumbing for the same result); a hand-written validator (networknt already ships) |
```
