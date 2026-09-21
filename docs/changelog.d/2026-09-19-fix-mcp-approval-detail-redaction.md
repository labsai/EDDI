## 🔒 fix(hitl): MCP `get_approval_status` detail=full no longer serves raw tool arguments (2026-09-19)

**Repo:** EDDI (`fix/mcp-approval-detail-redaction`)

The REST `GET /agents/{id}/approval-status?detail=full` returns the snapshot through
`ConversationMemoryUtilities.sanitizePendingToolCallsForApprover`, which removes the pending batch's
`argumentsRaw`, `chatTranscriptJson` and `traceSoFar`, masks the request fingerprint and re-redacts the
served arguments and preview. The MCP mirror, `McpHitlTools.getApprovalStatus`, called only
`stripRequestFingerprintsForRead` — one step of that method — so every MCP caller the read gate
admitted received the raw arguments of each gated tool call (a clear-text API key has been observed
there) plus the full serialized LLM transcript. On a deployment without OIDC,
`eddi.mcp.allow-unauthenticated=true` makes that surface reachable from the network.

### What changed

- **`McpHitlTools.getApprovalStatus`** — `detail=full` now serializes
  `sanitizePendingToolCallsForApprover(snapshot)`, the same call the REST surface makes.
- **`ConversationMemoryUtilities.stripRequestFingerprintsForRead` is now `private`.** The divergence
  existed because there were two public projections to choose from; now there is one, and the compiler
  refuses the old call. Its Javadoc and the sanitizer's say both surfaces must use the sanitizer.
- **`McpHitlToolsTest.getApprovalStatus_detailFull_neverServesRawArgumentsOrTranscript`** — builds a
  paused snapshot with a canary in `argumentsRaw`, `chatTranscriptJson` and `traceSoFar`, serializes
  through the real `JsonSerialization` (a mocked serializer hides which fields ride along), and asserts
  the canary is absent while `argumentsRedacted` is still served. It fails on the previous code.
- **`docs/hitl.md`** — the approver read-scope paragraph states the sanitization applies to REST and MCP.

### Decisions

- **Group variant: no change.** `get_group_approval_status?detail=full` (MCP) and its REST twin both
  return the `GroupConversation` unmodified. That document carries no `PendingToolCallBatch`, no
  `argumentsRaw` and no LLM transcript JSON — its `transcript` is the group discussion itself, which is
  the documented content of the full view, and `hitlLastPauseFingerprint` digests task state, not a
  request. Member tool-call pauses inside group turns (`inGroupTurns: INBOX`) are still reserved, so
  nothing gated per call is stored on the group document. Both surfaces already serve the same thing.
- **Readers swept, already safe:** REST `approval-status` (both views — summary builds `pauseDetails`
  from `argumentsRedacted`, re-redacted); `RestConversationStore` raw log (`redactRawPendingToolCallsForRead`,
  names only) and simple log (`convertSimpleConversationMemory`, names only); `ConversationService.readConversation`
  and every say/resume response (`convertSimpleConversationMemorySnapshot`, names only) and so MCP
  `read_conversation`, the OpenAI-compatible bridge, `ConverseWithAgentTool` / `CreateSubAgentTool`
  (tool names only); Slack approval cards (`SlackHitlSupport` reads `argumentsRedacted`, re-redacted);
  GDPR Art. 15 export (conversation outputs only); the HITL audit entry (`argsDigest`, a SHA-256 — not
  the arguments); `RestToolHistory` (step traces, owner-only, not the pending batch);
  `RestTemplatePreview` (`MemoryItemConverter` exposes no HITL fields).
- **The concurrent change landed first, and this branch absorbed it.** `fix/gemini-thought-signatures`
  (#794) added `PendingToolCallBatch.gatingAssistantMessageJson`, which embeds every gated call's raw
  arguments. It dropped that field inside `stripRequestFingerprintsForRead`, with the reasoning that
  the method was "the one method every full-detail read calls — including the MCP approval-status
  tool, which calls nothing else". That premise is exactly what this branch removes, and its own
  sanitizer already nulls the field, so the merge keeps the strip there and drops the duplicate: one
  projection, one place a new raw-argument field has to be listed. The test that asserted the partial
  strip drops the field now asserts that the partial strip is private — the invariant that kept the
  two doors from drifting again.

---
