## test(operator): CI activates the Platform Operator against a real backend (2026-11-06)

**Repo:** EDDI (`test/operator-activation-e2e`)

### Why

6.7.0 shipped with Platform Operator activation broken: `POST /administration/agents/setup-api` refuses a
`systemPrompt` over `AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH` (100,000), and the operator prompt had grown to
~110,000 characters, so every fresh activation and every upgrade answered `System prompt is too long (109661
characters, maximum 100000) (HTTP 400)`. The cap fix is its own PR (`fix/operator-setup-and-default-favicon`). This
entry is the test that would have caught it: every operator test until now ran on MSW, and none ever sent the real
prompt to the real endpoint.

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
- Against an image built from the cap-fix branch, with this branch's Manager: **passes** (about 18 s).
- Mutation checks against that image, each failing by name: an allow-list entry the spec lacks (`This deployment
  does not expose 1 endpoint(s) …`); `EDDI_SELF_BASE_URL=http://127.0.0.1:9` (the read canary's "could not connect to
  the platform API at http://127.0.0.1:9"); and provisioning without `hitlConfig` (`The approval gate could not be
  verified … hitlConfig is absent`).

### Fixed on the way

The first green run surfaced three defects in what activation provisions. The tier now asserts each of them.

- **The operator prompt failed its template render on every turn (revision 60 → 61).** The backend renders
  `systemMessage` with Qute, and the forEach entry in the prompt's apicalls section was written
  `forEach {pathToTargetArray (…, required), iterationObjectName …}`. Qute read that as an expression and could not
  parse it. Every operator turn logged `ERROR … Template processing failed for LLM parameter 'systemMessage'` and
  sent the raw prompt, and every deploy reported `TEMPLATE_DOES_NOT_PARSE`. The line now lists the fields in prose.
  The real parser accepts every other `{` in both scopes: checked by running the built prompt through it. The revision
  bump is what tells existing deployments to upgrade.
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

Unit tests: `AgentCompatibilityLintTest` (vault mention not reported, a real error beside one still reported),
`McpApiToolBuilderArgumentSchemaTest` (requiredness, `optionalQueryParams`, repeated operationIds, suffix fallback),
`ApiCallExecutorTest` (an empty optional parameter is not sent, a supplied one and a non-optional empty one are).
Mutation-checked: reverting each of the four fixes fails its test. The E2E checks the same things from outside: one
distinct tool per allow-listed endpoint reached the model (read from the stub's `GET /__e2e/tools`, published on
loopback port 8089), `readAgentDescriptors` has no required arguments, and the operator's deployment status carries
no `TEMPLATE_DOES_NOT_PARSE`.

### Next

- Making `Operator Activation E2E` (or `E2E Gate`) a required status check is a repository-settings decision; only
  `Build & Test` and `CodeQL Analysis` are required today.
- Until the cap fix merges, this job fails on `main`, by design: `main` still cannot activate the operator.
- The revision bump (61) collides with any other open PR that bumps the operator revision; whichever merges
  second re-bumps.
