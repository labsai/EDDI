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

> **Scope.** This page describes suites, runs, tool mocks, REST, MCP and the
> Manager: suites are edited under **Resources → Test Suites**, and an agent's
> detail page has a **Golden conversations** section that starts a run, cancels
> one in progress and shows its results. Generating a suite from a recorded
> conversation, an LLM-as-judge expectation and a deployment gate are planned
> follow-ups. Suites are not yet included in agent export ZIPs.

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
3. The run is recorded as `RUNNING`, with the node executing it (`ownerNode`), and
   the call returns **202** with the run id. A node runs at most
   `eddi.agent-tests.max-concurrent-runs` at once; that is checked *before* the
   deploy of step 2, so a request refused with 429 has deployed nothing.
4. In the background, every case runs once per repetition, each in its own
   conversation (environment `test`, user id `agent-test:<runId>:<case>:<repetition>`).
   Up to `eddi.agent-tests.max-parallel-cases` conversations run at once.
5. After each turn the stored conversation memory is read and the turn's
   expectations are checked.
6. Every test conversation is ended and **permanently deleted** afterwards, unless
   the run asked for `keepConversations: true`.
7. The run record is updated to `PASSED`, `FAILED`, `ERROR` or `CANCELLED`, and one
   summary entry is written to the [audit ledger](audit-ledger.md)
   (`taskId: ai.labs.agenttest`). While it runs, the record's `heartbeatAt` is
   refreshed every 30 seconds (see [Known limitations](#known-limitations)).

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

The time limit only sees work that reads the text, and a counted repetition of
something that can match empty reads nothing: `(?:(?:){99999}){99999}` spins
ten billion times against any output. So counted minimums (`{n}`, `{n,}`,
`{n,m}`) applied to a group, an anchor or a back-reference may force at most
1,000 iterations in total — nested counts multiply, and the counts across the
pattern add up. Counts on a single character, class or escape (`\d{4}`,
`[a-z]{2,8}`) are not limited, since each iteration reads the text. The
comments flag `(?x)` is not supported. Such a pattern is rejected when the suite
is saved, and one saved before the check existed fails its expectation with
`<regex timed out>` without being run.

### Limits

Checked when a suite is saved (400 naming the field): at most 200 cases, 50 turns
per case, 20 repetitions, 500 mocks, and 2,000 turns per run in total (cases ×
repetitions × turns). Invalid regexes, unknown modes and states are rejected too.
Also: `maxCostPerRun` between 0 and 100 (USD), at most 20,000 characters per turn
`input`, and `turnTimeoutSeconds` between 1 and 900.

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
| LLM tool calls of an httpcall tool | The mock **is** the HTTP response, exactly as for a rule-triggered httpcall below: no request is built or sent, and `saveResponse`, retries and `postResponse` property instructions run on the mocked `{httpCode, body}` — so a property an agent sets from a tool's response is set in a test too. The model gets the executor's result, `{"httpCode": …, "body": …}` |
| LLM tool calls of every other source (MCP, A2A, built-in, dynamic, recall) | Before the executor runs: the tool gets `body` (a string as-is, anything else as JSON) |
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
- A tool call made on the start turn (`CONVERSATION_START`) has no turn result of
  its own; an unmocked one still fails the attempt and is listed in the attempt's
  `unmockedToolCalls`.
- Mocked calls cost $0, and are never served from or stored in the tool-result cache.
- A call is recorded once, however often it is retried — a retried live call is one
  tool call too — so a retry does not count against `maxToolCalls` again. An LLM
  tool call that goes through its executor (an httpcall tool's mock, or any call
  the suite runs live) is recorded only once the loop's own gates — tool budget,
  tenant quota, turn deadline — have let it through; one they refuse was never
  made and is not counted.
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
the cost ceiling stopped the run), `ERROR` when the run could not execute,
`ABORTED` when the node running it stopped before it finished, and `CANCELLED`
when it was [cancelled](#cancelling-a-run). `error` gives the reason for the last
three. The newest `eddi.agent-tests.max-runs-per-agent` runs per agent are kept.

### Cancelling a run

`POST /agenttests/runs/{runId}/cancel` (MCP: `cancel_agent_test_run`; Manager: the
**Cancel run** button on a running run) stops a run that is `RUNNING` on the node
that receives the call. It takes the rights starting one takes: EDIT on the agent.
The stop is cooperative: no further conversation or turn starts, a turn waiting for
the engine is abandoned, and every test conversation is ended and deleted (unless
the run keeps them). The call returns **202** with the record as it is; it reads
`CANCELLED` moments later, with the cases that finished and the reason
(`Cancelled by <principal> …`). The cases that did not run to the end are failed.
409 means the run has already finished, or executes on another node — the message
names it.

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
| `POST /agenttests/runs/{runId}/cancel` | Cancel a `RUNNING` run — see [Cancelling a run](#cancelling-a-run); 202 |
| `GET /agenttests/runs/{runId}` | Read a run |
| `GET /agenttests/runs?agentId=…&index=0&limit=20` | An agent's runs, newest first |

Suite URIs follow the v6 scheme: `eddi://ai.labs.testsuite/testsuitestore/testsuites/{id}?version=N`.

Errors: 400 (invalid suite, missing ids, empty suite), 404 (suite, agent or run not
found), 409 (the version could not be deployed to `test`, a newer version is
deployed there, the deployment runs in cluster mode; on cancel: the run has
finished or executes on another node), 429 (more than `eddi.agent-tests.max-concurrent-runs` runs at once).

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
  [ "$STATUS" != "RUNNING" ] && break   # PASSED, FAILED, ERROR, ABORTED or CANCELLED
  sleep 5
done

echo "Agent test run $RUN_ID: $STATUS"
[ "$STATUS" = "PASSED" ]
```

## MCP tools

`run_agent_tests`, `get_agent_test_run`, `list_agent_test_runs` and
`cancel_agent_test_run`, at the same tier as `deploy_agent` — see
[MCP Server](mcp-server.md#agent-test-tools-4).

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

- **Not supported in cluster mode** (`eddi.messaging.type=nats`): starting a run
  is refused with 409. A run and its tool mocks live in the JVM that started it,
  and cluster mode may route a test conversation's turn to another node, where
  every tool call would be refused. Run suites against a single-node deployment.
  If a call is ever refused because the node holds no session for the
  conversation, its result says so —
  `"reason":"no agent-test session on this node (…)"` — rather than *no mock
  defined in test mode*, so the two causes cannot be confused.
- A run executes on the node that accepted it, and can be cancelled only there. If
  that node stops mid-run, the record is marked **`ABORTED`** (with the reason in
  `error`) and the run's test conversations are deleted. Start the run again.
  Every node sharing the database checks, every minute, whether the `heartbeatAt`
  of each `RUNNING` run it does not execute itself has *changed* since it last
  looked; a run whose heartbeat has stood still for three minutes (90 seconds when
  the owner carries this node's id — a restart of this process, or a second
  process on the same host) is closed. Only equality of the owner's timestamp is
  compared, on the observer's own clock, so clock skew between nodes does not
  matter, and a live run is never closed while its heartbeat moves. The close is a
  compare-and-swap on the heartbeat that stood still: a heartbeat arriving in
  between wins. A run recorded before the owner and heartbeat fields existed is
  closed once it has been seen unchanged for three minutes.
- The flip side: an owner that cannot write its heartbeat for three minutes (a
  database outage on its side) loses the run. Another node closes it, the owner's
  next heartbeat finds the record changed, and the owner stops the run without
  writing anything back — the record stays `ABORTED`, and no result is audited.
- A turn that times out, or is abandoned by a cancel, may still be running in the
  engine after its conversation was ended and deleted. When it completes (or after
  another turn timeout), the conversation is deleted again, in case the turn wrote
  it back.
- Test conversations count against the tenant's conversation quota and, while a
  run is in progress, appear in conversation listings and conversation metrics —
  and in what is computed from them — like any other conversation of the agent (environment `test`, user id
  `agent-test:…`). They are deleted when the run ends unless it keeps them.
- User ids starting with `agent-test:` (in any case) are reserved: starting a
  conversation with one through REST, MCP or a group conversation is refused with
  403, for an admin and with authorization off too — whether it is requested or is
  the caller's own identity — a schedule with one is refused when it is saved over
  REST (400), and any schedule with one, however it was stored, is not fired. A
  signed-in principal with such a name is refused at authentication. A run's mocks
  and its record of tool calls are keyed on those ids, so an outside conversation
  must not join them.
- A mocked httpcall — rule-triggered or an LLM tool — is never *built*: its
  `${vault:}`, `${connection:}` and `${caller:}` references are not resolved, so a
  mocked call whose headers need `${caller:token}` works in a test although there
  is no caller.
