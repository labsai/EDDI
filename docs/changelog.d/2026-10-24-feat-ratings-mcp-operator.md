## feat(ratings): MCP rating tools and Platform Operator read access (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 4 of [`planning/conversation-rating-plan.md`](../../planning/conversation-rating-plan.md)

### What changed and why

Ratings can now be read and triaged from an MCP client, and the Platform Operator can answer questions about them.

- **MCP** — [`McpRatingTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpRatingTools.java): `list_conversation_ratings` (every `/ratings` filter incl. `needsAttention`, `reviewStatus`, `conversationId`; `sort`, `index`, `limit`), `summarize_conversation_ratings` (`groupBy`), `count_ratings_needing_attention` and `review_conversation_rating` (REVIEWED / NEW and a note). Registered in `McpToolFilter`; 89 → 93 tools, 292 arguments of which 128 are required.
- **Operator** — [`tool-scopes.ts`](../../ui/manager/src/lib/operator/tool-scopes.ts) allow-lists `GET /ratings`, `GET /ratings/summary` and `GET /ratings/attention-count`; [`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts) gains a ratings section (what a rating holds, `ratingConfig` and its defaults, needs-attention, `AGENT_ID_REQUIRED`, how to answer "how is agent X rated since version N"), gated on a new `grantsRatingReads` predicate. Operator revision 39 (37 when written; main had meanwhile claimed 37 too, so the merge made it 38, and clarifying the date-only `to` in the prompt made it 39).
- **Docs** — [MCP Server](../mcp-server.md#rating-tools-4) (section, count, Author tier row), [Conversation Ratings](../conversation-ratings.md#mcp-tools-and-the-platform-operator), and a "Feedback from users" section in the `eddi-admin` Claude Code skill.

### Design decisions

- **Access is the REST rule, run for the MCP caller.** The tools inject the in-process `IRestConversationRatingAdmin` bean — the pattern `McpAdminTools` uses with `IRestAgentAdministration` — not the loopback REST client, which sends no credentials (401 with authorization on, no scoping with it off). That bean's `ResourceAccessGuard` and `ConversationAccessGuard` read the request's `SecurityIdentity`, i.e. the MCP caller: admin sees everything, an editor names an agent it holds `EDIT` on, `userId` for admins only, `canOpenTranscript` as on REST. Nothing is re-implemented, so MCP cannot drift from the Feedback page.
- **Tier `McpRoles.AUTHOR`** (admin, editor) — equal to the REST `@RolesAllowed`, pinned by `McpRoleTierParityTest` (`IRestConversationRatingAdmin` added to the mirrors). `eddi-viewer` stays out.
- **Reviewer = the caller's plain principal** (`callerActor`), as on REST — not the HITL tools' `mcp:<principal>` form — so one person is one reviewer whichever surface they triage from.
- **Errors keep the REST codes** as `errorCode` (`AGENT_ID_REQUIRED`, `RATING_CHANGED`, `INVALID_PARAMETER`, …). A guard refusal (`io.quarkus.security.ForbiddenException`) is mapped to `FORBIDDEN` explicitly: left to `toolFailure` it is not "caller-facing" and would surface as `INTERNAL`.
- **No writes for the operator.** `PATCH /ratings/{ratingId}` and `POST /ratings/review` record the caller as the person who looked at the feedback, and a bulk review empties the queue in one call — that judgement stays with a human on the Feedback page. `GET /ratings/export` is a `text/csv` download of up to 100,000 rows (userIds included for an admin) that no chat answer needs. Both are documented as deliberate exclusions in the allow-list.
- MCP export and bulk review are left out too; both stay REST and Manager only.

### Tests

- `McpRatingToolsTest` (13) wires the real `RestConversationRatingAdmin` with mocked guards and store: role tier, `AGENT_ID_REQUIRED`, missing `EDIT`, userId redaction, admin cross-agent listing, grouping and the attention filter, reviewer from identity, `NOT_PERMITTED` for an agentless rating, `RATING_CHANGED` / `RATING_NOT_FOUND`, bad requests, non-leaking internal errors. Mutation-checked: 8 of 8 mutants killed by a test failure (role tier widened, role gate dropped, guard refusal not mapped to FORBIDDEN, agentId / needsAttention / conversationId not forwarded, REST code lost, empty review accepted).
- `McpRoleTierParityTest`, `McpToolContractTest`, `McpToolFilterCoverageTest` and `SkillToolReferencesTest` updated (`ratingConfig`, `canOpenTranscript` exempted as config/result fields).
- Manager: operator `tool-scopes` and `system-prompt` tests for the grant, the excluded writes/export and the gated section.
