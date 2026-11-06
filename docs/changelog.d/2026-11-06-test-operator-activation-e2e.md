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

### Found on the way, not fixed here

- Every operator turn logs `ERROR … LlmTask … Template processing failed for LLM parameter 'systemMessage' … invalid
  virtual method in {required),(iterationObjectName}`, and the deploy-time compatibility check reports
  `TEMPLATE_DOES_NOT_PARSE … empty expression found {vault:...}`. The operator prompt contains literal `{…}` text
  that Qute tries to parse. The turn still runs (the test passes), but the error fires on every call.
- The generated `readAgentDescriptors` tool marks all five optional query parameters (`filter`, `index`, `limit`,
  `ownership`, `space`) **required**, defaults included, so a model must invent values for each.
- `setup-api` logs `Tool name collision: http tool 'list' clashes with …` for the operator's toolset: one generated
  tool is dropped.

### Next

- Making `Operator Activation E2E` (or `E2E Gate`) a required status check is a repository-settings decision; only
  `Build & Test` and `CodeQL Analysis` are required today.
- Until the cap fix merges, this job fails on `main`, by design: `main` still cannot activate the operator.
