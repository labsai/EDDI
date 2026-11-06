## 🔧 fix(llm): an `ai.labs.apicalls` step is offered to the LLM as tools, and neither API-call step name is "legacy" (2026-11-06)

**Repo:** EDDI (`fix/httpcalls-step-alias-tools`)

### Why

The API-call step has two names. `eddi://ai.labs.httpcalls` is the task's own id
(`ApiCallsTask.ID`), and `V6RenameMigration` keeps that step type on purpose.
`eddi://ai.labs.apicalls` is an alias that `ApiCallsModule` registers for the same task.
`LlmModule` registers `ai.labs.langchain` for `ai.labs.llm` the same way. Both names run
in the pipeline.

Tool discovery did not treat them alike. `WorkflowTraversal.discoverConfigs` compared the
step type string literally, so an agent whose workflow typed its API-call step
`eddi://ai.labs.apicalls` ran that step but never offered its calls to the LLM as tools.
Nothing logged it. The same literal match hid a `langchain`-typed step from
`TurnBudgetValidator`.

The Manager made it look deliberate: its pipeline view labelled the canonical
`ai.labs.httpcalls` "API Calls (legacy)" and the alias "API Calls", which is the wrong way
round.

### What changed

- `WorkflowTraversal`: there is a new `STEP_TYPE_ALIASES` map (`apicalls` → `httpcalls`,
  `langchain` → `llm`, matching the bootstrap modules' alias registrations) and a
  `canonicalStepType` helper. Discovery compares canonical names on both sides and keys its
  cache by the canonical name, so asking for an alias and asking for its target share one
  traversal. This covers HTTP-call tools, MCP tools, RAG and the turn budget check, since
  they all go through it.
- Manager `extensions.ts`: both names are labelled "API Calls", and the canonical name sorts
  first. The `extensionTypes.httpcallsLegacy` key is removed from all 11 locales.

### Tests

`WorkflowTraversalTest` has a parameterised test that discovers a step typed with either
name when asked for the other, plus the `langchain` → `llm` case, and a test that an alias
never matches a different type. Mutation check: putting the literal comparison back fails
all 3 alias cases.

### Next

The Workforce Active Tools panel (#1294) mirrors the backend's old exact match and leaves
out `apicalls`-typed steps. Once this lands it should list them too.
