## Manager UI for agent test suites ("golden conversations") (2026-10-17)

**Repo:** EDDI (`ui/manager`) · **Branch:** `feat/manager-agent-tests`, based on `feat/agent-test-harness` (labsai/EDDI#1065) — merge after it.

The regression harness from #1065 had no Manager UI; suites and runs were reachable only over REST and MCP. This adds it (WS6 phase 6.2, UI part).

**What changed**

- **Test suites are a resource type** (`testsuite`, store `/testsuitestore/testsuites`) in `RESOURCE_TYPES`, so list, create, edit, version history and delete come from the existing Resources pages. The form editor (`components/editors/test-suite-editor.tsx`, registered in `EDITOR_MAP`) covers defaults, tool mocks, cases, turns and every expectation field; the JSON tab stays available. Expectation fields are folded away per turn (progressive disclosure). Half-typed JSON never overwrites the last valid value.
- **Agent detail → "Golden conversations"** (`components/agent-tests/agent-tests-section.tsx`), collapsed by default and outside the draft fieldset: pick a suite, Run (`POST /agenttests/runs`, runs the saved agent version against the suite's current version), run history from `GET /agenttests/runs?agentId=`. The list polls while any run is `RUNNING`.
- **Run result side sheet** (`run-result-sheet.tsx`): per case → repetition → turn pass/fail, each failed expectation with expected and actual side by side, cost and duration, and tool calls that had no mock highlighted. `ABORTED`, `ERROR` and a cost-ceiling stop each get a notice saying the agent was *not judged* (or why the run stopped) and what to do.
- i18n: 129 keys in all 11 locales.
- Mocks: `test/mocks/agent-test-fixtures.ts` + `agentTestHandlers` (passing, failing, aborted, error and ceiling runs; a started run turns `PASSED` after 4 s). The OpenAPI contract test stays green with no new exemptions.

**Decisions**

- `ResourceTypeConfig` gains `duplicable: false` and `jsonSchema: false`: the suite store has no `POST /{id}` duplicate and no `GET /jsonSchema`, so the Manager does not offer or request them rather than shipping a 404 (the snippet/parser drift in the contract test's exemption list is the precedent we avoided repeating).
- **`api-client` keeps a 202 body when it parses.** It used to return `undefined` for every 202, which dropped the run id `POST /agenttests/runs` answers with. 202s with an empty or non-JSON body behave as before.
- `ClusterDrawer` gained an optional `widthClass` so the run sheet can be wider; no other caller changes.
- Operator prompt and allow-list now include agent test suite and run access, so `operator-revision.json` is bumped past main's revision with the new provisioning fingerprint.
- `en.json` and the other locales lose one duplicated block of `decisionSummary*` keys (the file already defined them twice; JSON keeps the last one) as a side effect of re-serialising — no value changes.

**Verified:** `npm run typecheck`, `lint`, `i18n:check`, and the unit suites for resources, editors, lib, hooks, i18n, mock contract and the new components; plus a headless-Chrome walkthrough in MSW mock mode (passing run, failing turn, aborted, error, run-in-progress → passed, suite editor, suite list). Screenshots are in the session notes folder.

**Not done / next:** generate-a-suite-from-a-conversation, an LLM-judge expectation and a deployment gate (all listed as follow-ups in `docs/agent-testing.md`); a Playwright e2e spec for the new screens.
