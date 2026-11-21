## ♻️ refactor(mcp): one map holds every MCP tool's role tier, and the whitelist derives from it (2026-11-21)

**Repo:** EDDI (`refactor/mcp-tool-tiers`)

### What changed and why

**New `McpToolTiers`: a map from tool name to role tier, the only place a tool's tier is written.**
Before this change, a tool's tier was a `McpRoles.<TIER>` literal inside each of 120 tool methods, and the
set of tools exposed to clients was a separate literal set in `McpToolFilter`. The two could
drift, and the parity test was the only thing that tied them together. That test also never
measured `get_eddi_version`: it asserted 119 tools while 120 were exposed, and its javadoc
still said 107.

- **Every tool gate** now reads `requireAnyRole(identity, authEnabled, McpToolTiers.of("<tool>"))`.
  The change was mechanical, and each tool keeps its tier. `get_eddi_version` moves from
  `requireRole(…, "eddi-admin")` to the same call (tier `ADMIN_ONLY`, same roles). Its refusal
  text changes from "requires role:" to "requires one of roles:".
- **`McpToolFilter.MCP_TOOLS`** is now `McpToolTiers.TIERS.keySet()`, so a tool is exposed
  exactly when it has a tier. A gate that names a tool missing from the map throws, so it
  fails closed. A null tool name is now hidden (`false`) instead of throwing a
  `NullPointerException`; `McpToolFilterTest` is updated to match.
- **`read_agent_logs`**, whose tier depends on its arguments, is listed under the union of its
  tiers (`OBSERVE`). The method still narrows an unscoped read to `ADMIN_ONLY` itself.
- **`McpRoleTierParityTest`** now measures all 120 tools, `McpVersionTools` included. It
  asserts two things: the measured matrix equals the map, and the map equals the test's own
  hand-written tier record (`tierMapEqualsTheHandWrittenRecord`). Since the gates read the map,
  the first check alone would compare the map with itself. Mutation-checked: moving
  `delete_user_data` to `CONVERSE` in the map fails only the new record check, and putting
  `deploy_agent`'s gate back to a `CONVERSE` literal fails the measured check.
- **`docs/mcp-server.md`**: the Tool Filtering and Authorization sections, and the Role Mapping
  note, now say where tiers are assigned and how to add a tool.

### Design decisions

- No role logic was added to the filter. Hiding tools per role in `tools/list` is the next
  step of the security-gaps proposal. It waits on a spike proving the filter sees the caller's
  identity, and `tools/call` goes through the same filter, so it may only ever filter
  `TOOLS_LIST`.
- `McpToolUtils.requireRole` stays. No tool uses it now, but its own tests do, and removing
  it is not part of this change.

### What's next

Item 3 of the security-gaps proposal: a role-aware `tools/list`, after the identity-in-filter
spike.
