## feat(ratings): conversation ratings — backend (2026-10-24)

**Repo:** EDDI (`feat/conversation-ratings`) — PR 1 of the plan in [`planning/conversation-rating-plan.md`](../../planning/conversation-rating-plan.md)

### What changed and why

People chatting with an agent can now rate the conversation and, if the agent asks for it, single agent replies; the people running the agent can list, summarize, export and triage those ratings. Off unless the agent designer adds `ratingConfig` with `"enabled": true` (checked per agent version the conversation runs on, cached per version in `ConversationRatingPolicy` like `ConversationReviewPolicy`).

- **Config:** `AgentConfiguration.ratingConfig` (scale `THUMBS|FIVE_STAR`, conversation and reply ratings, comment rules, reason tags, rating window), validated on agent create/update/duplicate (`RatingConfigValidator`), and a `rating` subset on `GET /agents/{agentId}/profile` so chat clients need no extra call.
- **Store:** `IConversationRatingStore` with MongoDB ([`ConversationRatingStore`](../../src/main/java/ai/labs/eddi/engine/ratings/mongo/ConversationRatingStore.java)) and PostgreSQL ([`PostgresConversationRatingStore`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationRatingStore.java)) implementations selected in `DataStoreProducers`. Key `(conversationId, stepIndex)`, conversation rating at `-1`; atomic upsert keeping `id`/`createdAt`; summaries aggregated in the database; indexes for agent+time, agent+version, user (GDPR) and the needs-attention count.
- **Owner REST** (`IRestConversationRating`): `PUT|GET|DELETE /agents/{conversationId}/rating` and `PUT|DELETE /agents/{conversationId}/steps/{stepIndex}/rating`, with stable error codes (`RATING_DISABLED`, `RATING_WINDOW_CLOSED`, `INVALID_STEP`, `INVALID_SCORE`, `INVALID_TAG`, `COMMENT_NOT_ALLOWED`, `COMMENT_TOO_LONG`, `NOT_CONVERSATION_OWNER`, `CONVERSATION_NOT_FOUND`).
- **Maintainer REST** (`IRestConversationRatingAdmin`, `eddi-admin`/`eddi-editor`): `GET /ratings`, `/ratings/summary`, `/ratings/export` (CSV, 100k cap, formula-injection escaping), and the triage loop of plan §0.1: `GET /ratings/attention-count`, `PATCH /ratings/{ratingId}`, `POST /ratings/review`.
- **GDPR:** `ConversationRatingGdprParticipant` joins erasure (deletes the user's ratings, clears them as reviewer) and export; processing restriction blocks new ratings.
- **Metrics:** `eddi_conversation_ratings_total`, `eddi_conversation_ratings_normalized`, `eddi_conversation_ratings_withdrawn_total`, charted in a new "Conversation Ratings" row of the Full Metrics Reference dashboard.
- **Docs:** new [`conversation-ratings.md`](../conversation-ratings.md), `ratingConfig` section in [`agent-config-authoring.md`](../agent-config-authoring.md), [`gdpr-compliance.md`](../gdpr-compliance.md), [`metrics.md`](../metrics.md).

### Design decisions

- **Only the owner rates.** `requireConversationOwner` admits administrators too; the endpoint additionally requires `OwnershipValidator.isOwner`, so an admin can read and withdraw but never write a rating in somebody's name.
- **User ids for administrators only** — decided on `isAdmin()`, not `seesEverything()`: without workspace enforcement every editor "sees everything", and the plan's R3 is about admins.
- **Scoping (R6):** a caller who does not see everything must pass `agentId` and hold `EDIT`; `PATCH` checks `EDIT` on the rating's own agent.
- **`canOpenTranscript`** applies the existing conversation-read rule per row (owner, admin, or conversation review) and is false for purged conversations — one descriptor read per distinct conversation on the page (≤ 100).
- **Triage is hidden from the rater:** owner responses strip status, reviewer and note. A re-rating resets triage to `NEW` and clears the note. `PATCH` is conditional on the rating's `updatedAt`, so a maintainer cannot mark reviewed a rating that changed after they read it (409 `RATING_CHANGED`). The reviewer always comes from the principal.
- **`minScore`/`maxScore` filter the normalized score** (0..1), so one filter means the same on every scale. Added `sort=newest|lowest`, `reviewStatus` and `needsAttention` filters.
- Admin endpoints share one `@BeanParam` (`RatingQueryParams`) so the five filtered endpoints cannot drift.

### Deviations from the plan

- A reply rating records the conversation's agent version at rating time: conversation memory stores no version per step (plan §10 assumed one).
- The per-reply excerpt for callers who may read the transcript (§6) is not returned; clients with `canOpenTranscript` open the conversation instead.
- GDPR: implemented as an `IGdprParticipant` (erase + export), which is the SPI for stores; `UserErasureParticipant` is for stopping in-flight work and has nothing to stop here.
- No separate `(conversationId)` index: the unique key's prefix serves it.

### What's next

The MCP rating tools ship with this backend. Next: the Chat UI and the Manager Feedback page (badge on `attention-count`) with the Platform Operator's read access, then push alerts. The store ITs (`MongoConversationRatingStoreIT`, `PostgresConversationRatingStoreIT`) need Docker.
