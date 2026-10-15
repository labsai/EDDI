# Agent Testing — Golden Conversations

An **agent test suite** is a set of scripted conversations with an agent, and for
each turn a statement of what must be true afterwards: which actions fired, which
properties were set, which tools were called, what the output says, what it cost.
EDDI runs the suite against an agent version and records a pass/fail result per
case and turn.

Because EDDI is config-driven and records every turn in conversation memory, the
strongest assertions do not depend on how an LLM words its answer: *"the
`store_city` action fired and `city` is `Vienna`"* holds or it does not. Output
regexes are there too, as the secondary signal.

> **Scope.** This page describes Phase 6.1: suites, runs, tool mocks, REST and MCP.
> Generating a suite from a recorded conversation, an LLM-as-judge expectation, a
> Manager page and a deployment gate are planned follow-ups. Suites are not yet
> included in agent export ZIPs.

## Concepts

| Term | What it is |
|---|---|
| **Suite** | A versioned configuration document (`/testsuitestore/testsuites`), like a rule set or an LLM config |
| **Case** | One scripted conversation: a list of turns, optional context, `repetitions` and `passRate` |
| **Turn** | One user input and its `expect` block |
| **Run** | One execution of a suite against one agent version; a stored record with per-case, per-turn results |
| **Mock** | A substitute response for a tool call — a test never reaches a real endpoint unless the suite says so |

### How a run executes

1. `POST /agenttests/runs` names a suite and an agent (versions optional — the
   current ones by default).
2. The agent version is **deployed to the `test` environment** if it is not already
   (through the normal deploy, so the caller needs deploy rights). Production is
   never touched. If a *newer* version of the agent is also deployed to `test`, the
   run is refused with 409 — new conversations always start on the newest ready
   version, so the run would silently test the wrong one.
3. The run is recorded as `RUNNING` and the call returns **202** with the run id.
4. In the background, every case runs once per repetition, each in its own
   conversation (environment `test`, user id `agent-test:<runId>:<case>:<repetition>`).
   Up to `eddi.agent-tests.max-parallel-cases` conversations run at once.
5. After each turn the stored conversation memory is read and the turn's
   expectations are checked.
6. Every test conversation is ended and **permanently deleted** afterwards, unless
   the run asked for `keepConversations: true`.
7. The run record is updated to `PASSED`, `FAILED` or `ERROR`, and one summary entry
   is written to the [audit ledger](audit-ledger.md) (`taskId: ai.labs.agenttest`).

### What a test conversation does not do

- **It neither reads nor writes long-term user memory.** A test must not change
  what real users have stored, and must not pass or fail because of it.
- **It never executes a tool call unless the suite opts in** — see
  [Tool mocking](#tool-mocking).
- **It never waits for a human.** A HITL pause (`PAUSE_CONVERSATION` or a gated
  tool call) is an observable outcome — assert it with
  `"conversationState": "AWAITING_HUMAN"`. Any later turn of that case fails with
  *"the conversation is AWAITING_HUMAN"*, and the conversation is ended (which
  resolves the pause and is audited as `system:agent-test`). Approval
  notifications go only to channel-started conversations (e.g. Slack), so none is
  sent for a test.
- **Dream consolidation never runs for it** — Dream runs on per-user schedules,
  and a test user has neither a schedule nor any stored memory.

## Suite reference

```json
{
  "name": "Booking agent — core flows",
  "defaults": { "toolMode": "mock", "unmockedTools": "fail", "repetitions": 1, "passRate": 1.0, "turnTimeoutSeconds": 120 },
  "maxCostPerRun": 1.0,
  "toolMocks": [
    { "tool": "getAvailability", "argsSubset": { "city": "Vienna" },
      "response": { "httpCode": 200, "body": { "rooms": 3 } } },
    { "tool": "createBooking", "response": { "httpCode": 403, "body": "forbidden" } }
  ],
  "cases": [ {
    "name": "greeting then availability",
    "context": { "language": "en" },
    "turns": [
      { "input": "hello", "expect": { "actionsContain": ["greet"] } },
      { "input": "rooms in Vienna tomorrow?",
        "expect": {
          "toolCalled": [ { "tool": "getAvailability", "argsSubset": { "city": "Vienna" } } ],
          "toolNotCalled": ["createBooking"],
          "properties": { "city": { "equals": "Vienna" } },
          "outputMatches": "(?i)3 rooms",
          "maxCostUsd": 0.02
        } }
    ]
  } ]
}
```

### Top level

| Field | Default | Meaning |
|---|---|---|
| `name`, `description` | — | For people |
| `defaults` | see below | Per-case defaults |
| `maxCostPerRun` | `1.0` | Dollar ceiling for the whole run, checked before every turn against the summed `audit:cost` of all turns so far (start turns included). Reaching it stops the run; it ends `FAILED` with `costCeilingReached: true` |
| `toolMocks` | none | See [Tool mocking](#tool-mocking) |
| `cases` | — | The scripted conversations |

### `defaults`

| Field | Default | Meaning |
|---|---|---|
| `toolMode` | `mock` | `mock`: tool calls get their mock; `live`: every call runs for real |
| `unmockedTools` | `fail` | With `toolMode: mock`, what a call without a mock does. `fail`: not executed, the case fails. `live`: it runs for real |
| `repetitions` | `1` | How often each case runs (1–20) |
| `passRate` | `1.0` | Fraction of repetitions that must pass, 0–1. `repetitions: 5, passRate: 0.8` means "at least 4 of 5" |
| `turnTimeoutSeconds` | `120` | A turn that takes longer fails |

A case may override `repetitions` and `passRate`. `context` on a case is sent with
every turn; `context` on a turn is applied on top. A string becomes a `string`
context, an object an `object` context, a list an `array` context.

### Expectations (`expect`)

Every present field must hold. Every one that does not is reported, with the
expected and the actual value.

| Field | Holds when |
|---|---|
| `actionsContain` | every listed action fired this turn |
| `actionsExact` | exactly these actions fired, in this order |
| `properties` | for each named conversation property: `{"equals": v}` (value equal; `3`, `3.0` and `"3"` are equal, objects and lists compare structurally), `{"matches": "regex"}`, `{"exists": true}` or `{"absent": true}` |
| `outputContains` | the turn's output text contains the string (case-sensitive) |
| `outputMatches` | the output text matches the regex (`find`, not a full match) |
| `toolCalled` | each listed tool was called this turn; with `argsSubset`, with arguments that contain those key/values |
| `toolNotCalled` | none of the listed tools was called |
| `maxToolCalls` | at most this many tool calls |
| `maxCostUsd` | the turn's `audit:cost` was at most this |
| `conversationState` | the state after the turn: `READY`, `AWAITING_HUMAN`, `ENDED`, `ERROR`, `IN_PROGRESS`, `EXECUTION_INTERRUPTED` |

Tool expectations see every call of the turn — LLM tool calls of any source,
rule-triggered httpcalls and rule-triggered MCP calls — mocked, refused or live.

**Regexes are bounded.** A pattern may be at most 500 characters (checked when the
suite is saved) and one match may take at most 250 ms; a match that runs out of
time fails its expectation with `<regex timed out>`. A suite therefore cannot hang
the runner with a catastrophically backtracking pattern.

### Limits

Checked when a suite is saved (400 naming the field): at most 200 cases, 50 turns
per case, 20 repetitions, 500 mocks, and 2,000 turns per run in total (cases ×
repetitions × turns). Invalid regexes, unknown modes and states are rejected too.

## Tool mocking

A test must never reach a production endpoint by accident, so tool calls of a test
conversation are **fail-closed**:

- a call that has a mock gets the mock's response and is never executed;
- a call without a mock, with `unmockedTools: fail` (the default), is **not
  executed** and answered with
  `{"status":"NOT_EXECUTED","reason":"no mock defined in test mode"}` — and the case
  fails, with an `unmockedTool` failure on the turn and the tool named in
  `unmockedToolCalls`;
- only `toolMode: live` or `unmockedTools: live` lets a call run for real — an
  explicit opt-in per suite.

Mocks apply at the three places a pipeline reaches the outside world:

| Path | How the mock is applied |
|---|---|
| LLM tool calls (http, MCP, A2A, built-in, dynamic, recall) | Before the executor runs. An httpcall tool's mock reaches the model in the httpcall result shape, `{"httpCode": …, "body": …}`; any other tool gets `body` (a string as-is, anything else as JSON) |
| Rule-triggered httpcalls (`httpcalls` step) | The mock **is** the HTTP response: `saveResponse`, retries and `postResponse` property instructions run on the mocked `{httpCode, body}` exactly as on a real one. An unmocked call is answered `501` with the NOT_EXECUTED body. Fire-and-forget calls are not dispatched |
| Rule-triggered MCP calls (`mcpcalls` step) | The mock's `body` is the tool result. With nothing allowed to run live, the MCP server is not contacted at all — not even to list its tools |

Matching: `tool` is the name the agent calls — an httpcall's `name`, an MCP tool
name, a built-in tool name. `argsSubset` narrows a mock to calls whose JSON
arguments contain those key/values; the first matching mock wins. Rule-triggered
calls have no model-chosen arguments, so only mocks *without* `argsSubset` match
them.

Exceptions:

- **Tools that write long-term user memory never run live** in a test, whatever the
  suite says; in a live suite they are answered NOT_EXECUTED without failing the
  case.
- `discover_tools` (LAZY tool loading) is not an outside call and always runs.
- Mocked calls cost $0.
- Listing an MCP server's tools to build an LLM's tool set still happens in a test
  (it reads a schema, it does not call a tool).

## Results

A run record (`GET /agenttests/runs/{runId}`):

```json
{
  "id": "…", "suiteId": "…", "suiteVersion": 3, "agentId": "…", "agentVersion": 7,
  "status": "FAILED", "startedBy": "alice", "durationMs": 4120, "totalCostUsd": 0.0132,
  "maxCostPerRun": 1.0, "costCeilingReached": false, "models": ["gpt-4o-mini"],
  "casesTotal": 1, "casesPassed": 0, "casesFailed": 1,
  "cases": [ {
    "name": "greeting then availability", "repetitions": 1, "passRate": 1.0, "passedRepetitions": 0, "passed": false,
    "attempts": [ {
      "repetition": 1, "passed": false, "costUsd": 0.0132, "finalState": "READY", "unmockedToolCalls": [],
      "turns": [ {
        "index": 2, "input": "rooms in Vienna tomorrow?", "passed": false,
        "actions": ["ask_date"], "output": "For which date?", "toolCalls": [], "conversationState": "READY",
        "failures": [ { "expectation": "toolCalled", "expected": {"tool": "getAvailability", "argsSubset": {"city": "Vienna"}}, "actual": [] } ]
      } ]
    } ]
  } ]
}
```

`status` is `PASSED` when every case met its pass rate, `FAILED` otherwise (or when
the cost ceiling stopped the run), `ERROR` when the run could not execute. The
newest `eddi.agent-tests.max-runs-per-agent` runs per agent are kept.

## REST API

All endpoints take `eddi-admin` or `eddi-editor` — the same roles as deploying an
agent, because a run deploys to `test`.

| Method & path | Purpose |
|---|---|
| `POST /testsuitestore/testsuites` | Create a suite (201, `Location` carries id and version) |
| `GET /testsuitestore/testsuites/{id}?version=N` | Read a suite |
| `PUT /testsuitestore/testsuites/{id}?version=N` | Update (creates version N+1) |
| `DELETE /testsuitestore/testsuites/{id}?version=N` | Delete |
| `GET /testsuitestore/testsuites/descriptors` | List suites |
| `POST /agenttests/runs` | Start a run — body `{"suiteId", "agentId", "suiteVersion"?, "agentVersion"?, "keepConversations"?}`; 202 with the `RUNNING` record |
| `GET /agenttests/runs/{runId}` | Read a run |
| `GET /agenttests/runs?agentId=…&index=0&limit=20` | An agent's runs, newest first |

Suite URIs follow the v6 scheme: `eddi://ai.labs.testsuite/testsuitestore/testsuites/{id}?version=N`.

Errors: 400 (invalid suite, missing ids, empty suite), 404 (suite or agent not
found), 409 (the version could not be deployed to `test`, or a newer version is
deployed there), 429 (more than `eddi.agent-tests.max-concurrent-runs` runs at once).

### CI recipe

```bash
#!/usr/bin/env bash
# Fail the pipeline unless the agent's suite passes.
set -euo pipefail
EDDI=https://eddi.example.com
AUTH="Authorization: Bearer $API_TOKEN"

RUN_ID=$(curl -sf -X POST "$EDDI/agenttests/runs" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"suiteId\":\"$SUITE_ID\",\"agentId\":\"$AGENT_ID\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

while :; do
  STATUS=$(curl -sf "$EDDI/agenttests/runs/$RUN_ID" -H "$AUTH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  [ "$STATUS" != "RUNNING" ] && break
  sleep 5
done

echo "Agent test run $RUN_ID: $STATUS"
[ "$STATUS" = "PASSED" ]
```

## MCP tools

`run_agent_tests`, `get_agent_test_run` and `list_agent_test_runs`, at the same tier
as `deploy_agent` — see [MCP Server](mcp-server.md#agent-test-tools-3).

## LLM nondeterminism

A run uses the live model. Deterministic assertions — actions, properties, tool
calls — are the primary signal; output regexes are secondary. Where an LLM decides
the path, express the tolerance you accept with `repetitions` and `passRate`
("at least 4 of 5 runs must pass"), and keep `maxCostPerRun` in proportion.

## Evidence (EU AI Act)

Every run is stored with the suite version, the agent version, the models the
turns reported, per-turn results and cost, and leaves one summary entry in the
tamper-evident audit ledger. Together they document *that* an agent version was
tested against a defined set of behaviours, *what* the result was and *who* ran it
— the kind of testing and accuracy evidence Art. 9 (risk management) and Art. 15
(accuracy, robustness) ask a provider of a high-risk system to keep. A suite
complements, it does not replace, the provider's own risk assessment.

## Configuration

| Property | Default | |
|---|---|---|
| `eddi.agent-tests.max-parallel-cases` | `4` | Conversations of one run at once |
| `eddi.agent-tests.max-concurrent-runs` | `2` | Runs at once per node |
| `eddi.agent-tests.max-runs-per-agent` | `50` | Run records kept per agent |

Metrics: `eddi_agent_tests_runs_total{status}`, `eddi_agent_tests_turns_total`,
`eddi_agent_tests_run_duration_seconds` — see [Metrics](metrics.md#agent-test-metrics).

## Known limitations

- A run in progress lives on the node that started it; a run on a node that
  restarts mid-run stays `RUNNING`.
- Test conversations count against the tenant's conversation quota and appear in
  conversation metrics while they run.
- A rule-triggered httpcall is still *built* before its mock answers it, so one whose
  headers need `${caller:token}` fails in a test (there is no caller) even when mocked.
