## 📝 docs(planning): security-gaps proposal — canary guardrail, role-filtered MCP tools, token exchange, step-up approvals (2026-11-21)

**Repo:** EDDI (`docs/security-gaps-plan`)

### What changed and why

**New [`planning/security-gaps-plan.md`](../../planning/security-gaps-plan.md), revision 2, a proposal. Nothing is built.**
It comes from comparing EDDI with the security patterns in Quarkus Insights #267/#268,
and was revised after an adversarial review. It covers four items:

- a `canary` guardrail that detects a leaked system prompt;
- a `tools/list` filtered by MCP role, with a single `McpToolTiers` source;
- an RFC 8693 token-exchange connection type;
- step-up authentication when approving a tool call.

It also explains why agent sandboxing is out of scope.

**Fact-checked against `main` @ `a2acb28e86` the same day.** The "(verified)" claims were
re-checked in source and in the jars. Corrections are marked *Fact-check* in the plan:

- the system-prompt cap is opt-in, and the rolling summary is appended after it;
- only one of the three `AUDIT_COMPILED_PROMPT` sites stores the assembled prompt;
- model-backed guardrails forward no system message, which answers an open question;
- an unknown `toolCallGuardrails` type is refused, not skipped;
- `disclosure` is enforced at save time only for tool-call guardrails;
- there are three `isOAuth()` call sites, not four;
- `${caller:token}` authors are a configurable role set;
- `/v1` has a subject under `http-policy=authenticated`;
- `StepUpAuthenticationPolicy` is internal to Quarkus;
- the parity test's tool count was wrong, and a tier map would make it circular.

**A second adversarial review then found what the fact-check missed.** Its corrections are
marked *Second review* in the plan:

- In quarkus-mcp-server 2.0.4 a `ToolFilter` that throws fails **open**: the tool is
  listed and callable. So the role filter must catch everything and return `false`.
- There is no server-side `tools/list` cache. `ttlMs` and `cacheScope` are response hints,
  and a per-caller list needs `cacheScope: PRIVATE`.
- The agent and tool-loop path builds its own system message (`LlmTask.java:822`), so the
  marker has to go inside `composeSystemMessage` or on both paths.

The design gaps it found are listed in a new "Second review" section, undecided:

- `call`-scope canaries are disarmed after a pause;
- one leaked marker poisons every conversation under `agent` scope;
- a more specific approval rule drops step-up for that call;
- step-up must follow per-call decisions;
- changing a connection to token exchange re-points existing editor-authored references.
- `acr` is any-of in the plan but all-of in Quarkus;
- an earned-autonomy promotion can delete the rule that carries `requiredAuthLevel`;
- a leaked marker, key rotation, the exchange cache key and the step-up replay window are
  listed as unverified suspicions.

### Design decisions

- The canary marker goes at the tail of the final system message, after the cap and the
  rolling summary. It is kept in the pause transcript. Its only blocking action is `block`;
  the INPUT entry is `warn`.
- The role filter applies only to `TOOLS_LIST`. In quarkus-mcp-server, `tools/call`
  goes through the same filter, so filtering there would turn `FORBIDDEN` into
  "not found". The filter fails closed.
- Token exchange sends the subject token only to the validating tenant's discovery
  `token_endpoint`, and checks that on every resolve. It fails closed on HITL resume and
  on scheduled calls, and on `/v1` under the default `permit` policy (allowing it under
  `authenticated` is left open). Exchanged tokens are cached in memory only, which is a
  §12 carve-out still to be agreed.
- Step-up merges `maxAge` by minimum, and `acr` by a rule that depends on an undecided any-of or
  all-of choice (Quarkus' check is all-of), into a separately persisted field. Only approvals
  need step-up; rejections never do. It covers `toolApprovals.rules` only. Which rules and
  calls the merge covers is an open gap.

### What's next

Item 1 of the suggested order, `McpToolTiers`, is built on `refactor/mcp-tool-tiers`
(not yet merged), and item 3, the role-aware `tools/list`, is in progress stacked on it.
The others wait on the gates and open gaps the plan lists.
