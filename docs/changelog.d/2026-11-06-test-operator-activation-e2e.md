## test(operator): CI activates the Platform Operator against a real backend (2026-11-06)

**Repo:** EDDI (`test/operator-activation-e2e`)

### Why

6.7.0 shipped with Platform Operator activation broken: `POST /administration/agents/setup-api` refuses a
`systemPrompt` over `AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH` (100,000), and the operator prompt had grown to
~110,000 characters, so every fresh activation and every upgrade answered `System prompt is too long (109661
characters, maximum 100000) (HTTP 400)`. Every operator test until now ran on MSW, and none ever sent the real
prompt to the real endpoint. This entry adds the test that would have caught it, and the cap fix it needs to pass
(the separate cap-fix PR, #1290, was closed unmerged).

### What changed

- **New Playwright tier `operator`** (`ui/manager/e2e/operator/operator-activation.operator.spec.ts`,
  `npm run test:e2e:operator`). It activates the operator through the Manager the way an administrator does, then
  checks every stage of the real path: the spec pre-flight (`findMissingEndpoints`, `findUnusableEndpoints`), the
  platform base URL prefilled from `GET /administration/operator/self-url` and recorded as `apiBaseUrl`,
  provisioning through `setup-api` with the real prompt and allow-list, deploy, `verifyGateInstalled` and the gate
  dry run (`operator-gate-verified`), the read canary (no `operator-canary-warning`), the write canary (its
  `canary-result` report must say `pass`), and a chat turn whose tool call must come back with data.
- **A deterministic model.** `ui/manager/e2e/operator/llm-stub.mjs` is a dependency-free OpenAI-compatible server
  (streaming and non-streaming chat completions, `/models`). It answers "list the agents" with a
  `readAgentDescriptors` call, the write canary's prompt with exactly the tool and id it names, and a tool result
  with `OPERATOR_E2E_TOOL_OK` — or `OPERATOR_E2E_TOOL_FAILED` for an `{"error": …}`, a 4xx/5xx or a
  `NOT_EXECUTED` schema refusal. Arguments are filled from each tool's own JSON schema. No paid provider, no key.
- **`ui/manager/docker-compose.integration-operator.yml`**, an overlay on `docker-compose.integration.yml` that adds
  the stub as `llm-stub` (`node:26.13.0-alpine`, the script mounted read-only); `npm run infra:up:operator` /
  `infra:down:operator`. The `ui` project ignores `e2e/operator/`, and `eslint.config.js` exempts it from the
  "import `test` from ./fixtures" rule, as it does the other real-backend tiers. The tier runs with no retries: a
  retry would meet the operator the first attempt activated and report that instead of the real failure.
- **CI job `Operator Activation E2E`** (`e2e-operator` in `ci.yml`), on the image built from the commit like
  `Auth E2E`, whenever `code` changed — so every PR touching the backend or `ui/`. It feeds `E2E Gate` and is a
  `needs` of `docker`, so a commit whose operator cannot be activated is never published. `BuildQualityGatesTest`'s
  "docker needs everything the gate grades" check covers the new job without a change.
- Docs listing the CI jobs: root `AGENTS.md` (Key Files), `CONTRIBUTING.md`, `ui/manager/CONTRIBUTING.md`,
  `ui/manager/AGENTS.md` (Platform Operator), `docs/release-versioning.md` (the `docker` needs list) and the ship-pr
  skill.

### Verified locally

- Against the released `labsai/eddi:6.7.0` image and the Manager it bundles: **fails** with
  `Operator activation failed: System prompt is too long (109661 characters, maximum 100000) (HTTP 400)`.
- Against an image built with the cap raised to 250,000, with this branch's Manager: **passes** (about 18 s).
- Mutation checks against that image, each failing by name: an allow-list entry the spec lacks (`This deployment
  does not expose 1 endpoint(s) …`); `EDDI_SELF_BASE_URL=http://127.0.0.1:9` (the read canary's "could not connect to
  the platform API at http://127.0.0.1:9"); and provisioning without `hitlConfig` (`The approval gate could not be
  verified … hitlConfig is absent`).

### The setup-api prompt cap

- **`AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH` 100,000 → 250,000.** The cap bounds a `systemPrompt` that reaches
  `setup` / `setup-api`, including one an LLM writes through `create_sub_agent` (storage growth, log noise); it is
  not a prompt-cost control. The operator prompt the Manager sends is ~110,000 characters for `read_write`, and
  grows every release; 250,000 gives it room and still bounds an LLM-authored prompt. Raising the cap rather than
  trimming reviewed safety and tool instructions keeps the prompt unchanged (no revision bump for it); shortening
  the prompt is still worth doing, since it is sent on every operator turn.
- **The Manager mirrors the cap** as `SETUP_MAX_SYSTEM_PROMPT_LENGTH` in `ui/manager/src/lib/api/agent-setup.ts`.
  The new backend `SetupPromptCapMirrorTest` reads that file and fails when the two values differ.
- **New `ui/manager/src/lib/operator/__tests__/operator-prompt-size.test.ts`** builds the exact prompt
  `provisionOperator` sends, for both scopes (`read_write` grants every endpoint, so every optional section is in)
  and the longest brand name the validator allows, and fails above 80% of the cap. The headroom is the point: the
  guard should fire on a feature branch, a release before the cap would.

### Fixed on the way

The first green run surfaced three defects in what activation provisions. The tier now asserts each of them.

- **The operator prompt failed its template render on every turn (revision 60 → 61, landing as 63).** The backend renders
  `systemMessage` with Qute, and the forEach entry in the prompt's apicalls section was written
  `forEach {pathToTargetArray (…, required), iterationObjectName …}`. Qute read that as an expression and could not
  parse it. Every operator turn logged `ERROR … Template processing failed for LLM parameter 'systemMessage'` and
  sent the raw prompt, and every deploy reported `TEMPLATE_DOES_NOT_PARSE`. The line now lists the fields in prose.
  With that fixed, the render still failed on the next one: `eddi_email_outbound_total{outcome: sent, …}` reads as
  the namespace `outcome:` (`No namespace resolver found for [outcome]`), which a parse alone does not catch. It is now
  written with the label in parentheses. Both scopes now parse and render. The revision bump is what tells existing
  deployments to upgrade.
- **The compatibility check reported a false `TEMPLATE_DOES_NOT_PARSE`.** `LlmTask` wraps `${vault:…}`-style mentions
  in raw sections before rendering an LLM parameter (`escapeConfigReferenceMentions`), but `AgentCompatibilityLint`
  parsed the raw text, so a prompt that only *mentions* the syntax (the operator's tells the model to store keys as
  `${vault:key-name}`) was reported on every deploy. New `CompatibilityRules.scanLlmTaskTemplates` checks a task's
  `parameters` as they render. The rest of the task is still checked raw, because it renders without that escape.
  `escapeConfigReferenceMentions` is now public for it.
- **Optional query parameters were required tool arguments.** `McpApiToolBuilder` marked every query parameter
  required, defaults included, so `readAgentDescriptors` made the model invent `filter`, `index`, `limit`,
  `ownership` and `space`. A query parameter is now required only when the spec says so. New
  `Request.optionalQueryParams` (null by default, so stored configs are unchanged) lists the others, and
  `ApiCallExecutor.buildRequest` leaves one off the request when its value renders empty instead of sending
  `?limit=`. The approval preview, the request fingerprint and the execution all go through `buildRequest`, so
  request pinning sees the same request that is sent. Side effect: an endpoint that needs a query value its spec does
  not mark required (`duplicateRag`'s `version`) now answers the model with its own 400 instead of being forced.
- **A repeated operationId dropped a tool.** `GET /groupstore/groups/{groupId}/lessons` and `…/outcomes` were both
  `list`. Tools register by name, so assembly logged `Tool name collision: http tool 'list'` and the operator never
  received one of two endpoints it had been granted. The two now have their own operationIds (`listGroupLessons`,
  `listGroupOutcomes`). `McpApiToolBuilder.uniqueToolName` also keeps names unique for any spec: the first operation
  keeps its operationId and a repeat takes its method-and-path slug, so a third-party spec can no longer lose a tool
  this way.

[HTTP Calls → Argument schema](../httpcalls.md#argument-schema-for-llm-tools) and its field table now describe the
requiredness rule, `request.optionalQueryParams` and the renaming of a repeated operationId.

Unit tests: `AgentCompatibilityLintTest` (vault mention not reported, a real error beside one still reported),
`McpApiToolBuilderArgumentSchemaTest` (requiredness, `optionalQueryParams`, repeated operationIds, suffix fallback),
`ApiCallExecutorTest` (an empty optional parameter is not sent, a supplied one and a non-optional empty one are).
Mutation-checked: reverting each of the four fixes fails its test. The E2E checks the same things from outside: one
distinct tool per allow-listed endpoint reached the model (read from the stub's `GET /__e2e/tools`, published on
loopback port 8089), `readAgentDescriptors` has no required arguments, the operator's deployment status carries
no `TEMPLATE_DOES_NOT_PARSE`, and no request reached the model with the prompt unrendered. The stub counts system
prompts that still hold `{#if}` section tags: when its render fails, `LlmTask` only logs the error and sends the raw
text. Restoring the `{outcome: …}` line fails that check with 5 unrendered prompts.

### Next

- Making `Operator Activation E2E` (or `E2E Gate`) a required status check is a repository-settings decision; only
  `Build & Test` and `CodeQL Analysis` are required today.
- The revision bump collided as expected: `main` reached 62 meanwhile, so the merge of `main` into this branch
  (2026-11-14) re-bumped to **63** with the fingerprint of the merged prompt, which still parses and renders in both
  scopes.
- **Follow-up, not in this PR:** the prompt still holds literal braces that Qute evaluates as variables and the
  runtime renders as empty — `{input}`, `{output}`, `{id}`, `{risk}`, `{cause}`, `{context}`, metric label sets such
  as `{reason="caller_timed_out"}` and `{provider,outcome}` (16 in the read_write scope). They do not fail the render,
  so the model simply never sees that text. Escaping the prompt's literal braces at build time, with a test that
  renders it under the runtime engine's rules, is the fix.
