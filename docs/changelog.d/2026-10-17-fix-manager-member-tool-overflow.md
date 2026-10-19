## 🩹 fix(manager): long tool names wrap in a group member's "using <tool>…" line (2026-10-17)

**Repo:** EDDI (`feat/groups-member-tool-call-events`)

Browser-checked the member card during a streamed discussion. A long MCP tool name could not wrap and pushed the line out of the card, so it now breaks. The mock discussion stream emits a `member_tool_call` frame and holds it long enough to see. Files: [`agent-response-card.tsx`](../../ui/manager/src/components/groups/agent-response-card.tsx).
