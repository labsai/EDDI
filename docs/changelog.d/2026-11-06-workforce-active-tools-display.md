## 🛠️ fix(manager): Active Tools in the Workforce agent panel shows what the LLM really gets (2026-11-06)

**Repo:** EDDI (`fix/workforce-active-tools-display`)

### Why

The Workforce chat's Agent Details panel listed an LLM task's `tools` as raw
`eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=1` URIs, one badge each. The badges
overflowed the 320 px panel (horizontal scrollbar), an agent with sixteen tool sets produced a
wall of near-identical badges, and nothing said which tool set was which.

Worse, it was the wrong list. The backend never resolves `task.tools`
(`LlmConfiguration.Task#tools`: "a non-empty list only switches the task into agent mode").
HTTP and MCP tools are discovered from the `eddi://ai.labs.httpcalls` / `eddi://ai.labs.mcpcalls`
steps of **every** workflow of the agent (`HttpCallToolsProvider`, `McpToolsProvider`,
`WorkflowTraversal.discoverConfigs`), gated by `enableHttpCallTools` / `enableMcpCallTools`,
which default to **on**. The panel showed the HTTP/MCP chips only when those flags were
explicitly `true`, and showed a built-in whitelist even when `enableBuiltInTools` was off, in
which case the backend ignores it.

### What changed

- `ui/manager/src/lib/agent-tool-refs.ts` (new): `collectWorkflowToolRefs` reads tool sets from
  the agent's workflows the way the backend does. It matches the step type exactly (a step typed
  with the `eddi://ai.labs.apicalls` alias is not offered as a tool by the backend, so it is not
  listed), respects the two switches with default-on semantics, and deduplicates.
- `ui/manager/src/components/workforce/active-tools-list.tsx` (new): the Active Tools section.
  - Each tool set is shown by its **descriptor name** (one cached `getDescriptor` read, short-id
    fallback), with an HTTP/MCP icon, its version, the full URI in the tooltip and a link to its
    editor.
  - The first four are shown; the rest sit behind "Show N more".
  - Built-in tools appear only when `enableBuiltInTools` is on: the whitelist as chips, or one
    "All built-in tools" chip when there is no whitelist.
  - There is a Memory chip and a total count.
  - A loading state shows while the workflows load.
- `agent-details-panel.tsx`:
  - It fetches every workflow of the agent, sharing the existing query key so the first workflow
    comes from cache.
  - It uses the new component and no longer repeats capabilities in the tool list.
  - It clips horizontal overflow, and the model badge truncates.
- Six new `Workforce.agentEditor.tools*` keys in all 11 locales.

### Tests

`active-tools-list.test.tsx` has 9 tests:

- collection across workflows, with dedupe;
- the `apicalls` alias is ignored;
- the per-category switches;
- `task.tools` is not listed;
- name resolution, link and collapse;
- the id fallback on a 404 descriptor;
- built-ins gated by `enableBuiltInTools`;
- the loading and empty states.

Mutation check: when the HTTP switch is flipped from default-on to default-off, 2 tests fail.

### Follow-ups found on the way (not in this PR)

- Pipeline labels: `extensions.ts` labels the canonical `eddi://ai.labs.httpcalls` step type
  "API Calls (legacy)" and the alias "API Calls". It is the other way round: `ApiCallsTask.ID`
  is `ai.labs.httpcalls`, and `V6RenameMigration` deliberately keeps that step type.
- Backend: a workflow step typed `eddi://ai.labs.apicalls` runs, but its calls are never offered
  to the LLM as tools, because `WorkflowTraversal` matches the type string exactly.
  `docs/architecture.md` shows that alias in an example.
