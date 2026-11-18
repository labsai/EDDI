## fix(openapi): published configuration schemas are closed where the REST boundary is strict (2026-11-18)

**Repo:** EDDI (`fix/openapi-strict-config-schemas`)

### What changed and why

`StrictConfigurationBodyInterceptor` answers 400 to an undeclared key in any configuration body,
nested objects included, but every generated OpenAPI schema left `additionalProperties` open. A
client validating against the published contract could therefore build a payload the server
refuses. CodeRabbit raised it on labsai/EDDI#1461 for the new `documents` block; no configuration
type closed its schema, so this fixes all of them at once.

- **`StrictConfigurationSchemaFilter`** (`configs/rest`) is a build-stage `@OpenApiFilter`. SmallRye
  passes it the Jandex index. It takes as roots the JAX-RS entity parameters whose class passes
  `StrictConfigurationBodyInterceptor.isConfigurationModel`, the same predicate the interceptor
  uses. It then walks each Java type and its schema together, following the `$ref` each property
  actually emits. Nested schemas are never matched by simple name, because SmallRye suffixes
  collisions (`HitlConfig`, `HitlConfig1`). Strict schemas get `additionalProperties: false`. The
  current build closes 159 component schemas reached from 24 roots.
- **Left open:** maps (their value types are still walked and closed), `Object`/`JsonNode`
  fields, `@JsonIgnoreProperties(ignoreUnknown = true)` types (`PromptSnippet`, `BrandUpdate`,
  `ModelPricing`), `@JsonAnySetter` types, polymorphic bases such as `OutputItem` (the schema
  lists only base fields), custom-deserialized types, and any type that *writes* a key its schema
  omits. That last case matters because the component is also the response schema.
- Bodies outside `configs.*.model` (`AgentTriggerConfiguration`, `ScheduleConfiguration`,
  `AlertRule`, …) are not strict at the boundary and stay open.
- `ui/manager/src/test/mocks/openapi-operations.json` was regenerated with no diff. It records only
  method and path.

### Design decisions

- **Legacy input names are accepted but not advertised.** `@JsonAlias` names
  (`retryHttpCallInstruction`, `packages`, `pipelineSteps`, `apiCalls`, `ruleGroups`, …) and keys
  ignored by name still parse on the server, but the closed schema lists only the canonical names.
  This makes the contract narrower than the server, never wider, and only a wider one produces the
  400 this fix exists to prevent. Requiring them would have left exactly the main bodies open:
  `AgentConfiguration`, `WorkflowConfiguration`, `RuleSetConfiguration`, `ApiCallsConfiguration`
  and `LlmConfiguration.Task`. A client that validates and still sends a legacy key will now be
  rejected client-side. Three shipped JSON fixtures use one: two v5 test fixtures and the
  reference agent's `retryHttpCallInstruction`, which `agent-config-authoring.md` already flags.
- One schema per class: a component reached from a strict body is closed even where a lenient
  endpoint also uses it, again the narrower direction.

### Not done

- `GET /<store>/<resource>/jsonSchema`, which the parser's 400 message points clients at, is a
  separate published contract and was not checked here.
