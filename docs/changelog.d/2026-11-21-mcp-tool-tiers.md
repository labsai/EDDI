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
  `NullPointerException`; `McpToolFilterTest` is updated to match. The old behaviour failed
  **open**: quarkus-mcp-server 2.0.4 (`ToolManagerImpl.test`, checked with `javap`) logs a
  `RuntimeException` thrown by a `ToolFilter`, skips that filter and admits the tool, for
  `tools/list` and `tools/call` alike. So a filter must never throw.
- **Review fixes**, from a code review of this change:
  - `eachGateNamesItsOwnTool` reads each tool's method body only, up to its closing brace,
    and counts only a `requireAnyRole(…, McpToolTiers.of("…"));` statement. Any other
    `McpToolTiers.of(` in a tool class (in a helper or a comment, or a second lookup) fails it.
  - The parity harness rethrows a failed tier lookup instead of counting it as "admitted",
    which had made a broken map read as "every tool open to every role".
  - The duplicate-entry guard in `McpToolTiers` has its own test.
  - The per-group notes of the old whitelist are carried over into `McpToolTiers`.
  - The javadocs that still described tool gates as `requireRole` are updated.
- **`read_agent_logs`**, whose tier depends on its arguments, is listed under the union of its
  tiers (`OBSERVE`). The method still narrows an unscoped read to `ADMIN_ONLY` itself.
- **`McpRoleTierParityTest`** now measures all 120 tools, `McpVersionTools` included. It
  asserts two things: the measured matrix equals the map, and the map equals the test's own
  hand-written tier record (`tierMapEqualsTheHandWrittenRecord`). Since the gates read the map,
  the first check alone would compare the map with itself. The record lists every tier
  explicitly, AUTHOR included. AUTHOR used to be the fall-through, which would have let a new
  tool filed under AUTHOR in the map pass with no record entry. The record must name exactly
  the map's tools, each once. A third test, `eachGateNamesItsOwnTool`, scans the tool sources
  and requires every tool to have exactly one `McpToolTiers.of(…)` gate, naming itself.
  Mutation-checked, one test failing for each:
  - moving `delete_user_data` to `CONVERSE` in the map;
  - putting `deploy_agent`'s gate back to a `CONVERSE` literal;
  - dropping `deploy_agent` from the record;
  - pointing `deploy_agent`'s gate at `undeploy_agent`.
- **Test messages** in `McpToolFilterTest` and `McpToolFilterCoverageTest` now point at
  `McpToolTiers` rather than `MCP_TOOLS`.
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
