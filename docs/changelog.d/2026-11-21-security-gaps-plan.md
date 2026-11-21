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

### Design decisions

- The canary marker is applied **after** `capSystemPrompt` and is scrubbed from
  `AUDIT_COMPILED_PROMPT`. It is kept in the pause transcript. Its only action is `block`.
- The role filter applies only to `TOOLS_LIST`. In quarkus-mcp-server, `tools/call`
  goes through the same filter, so filtering there would turn `FORBIDDEN` into
  "not found".
- Token exchange sends the subject token only to the validating tenant's discovery
  `token_endpoint`, and checks that on every resolve. It fails closed on HITL resume,
  scheduled and `/v1` calls. Exchanged tokens are cached in memory only, which is a
  §12 carve-out still to be agreed.
- Step-up merges `acr` by intersection and `maxAge` by minimum, across every matched
  rule, into a separately persisted field. Only approvals need step-up; rejections
  never do.

### What's next

Item 1 of the suggested order, `McpToolTiers`, has no gate. The others wait on the
gates the plan lists.
