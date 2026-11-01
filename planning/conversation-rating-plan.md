# Conversation Ratings — Plan

> **Status: approved for implementation 2026-10-24, after a critical review pass ([§0](#0-review-pass-what-changed-and-why)).**
> Written against `origin/main` @ `5028af0a3`. Code references were read at that commit;
> re-locate by symbol name, line numbers drift. Where §0 and a later section disagree, §0 wins.
>
> **Goal.** People chatting with an agent can rate the conversation (and, optionally, single
> agent replies), and the people who run the agent can see and analyze those ratings in the
> Manager without digging through raw transcripts.

**Contents:** [1. Summary](#1-summary) · [2. What exists](#2-what-already-exists) ·
[3. Agent config](#3-agent-configuration-ratingconfig) · [4. Data model](#4-data-model-and-storage) ·
[5. API](#5-rest-api) · [6. Access and privacy](#6-access-privacy-gdpr) ·
[7. Chat UI](#7-chat-ui) · [8. Manager](#8-manager-feedback-dashboard) ·
[9. MCP, Operator, metrics](#9-mcp-platform-operator-metrics) · [10. Traps](#10-traps-and-edge-cases) ·
[11. Phasing](#11-phased-delivery) · [12. Decisions](#12-decisions-for-the-maintainer)

---

## 0. Review pass: what changed and why

The first draft was re-checked against the code. Each change either removes a risk the draft
introduced or removes scope that bought little. The sections below are already updated.

| # | First draft | Now | Why |
| --- | --- | --- | --- |
| R1 | Copy a `ratingSummary` onto `ConversationDescriptor` for list filters | **Dropped from v1.** Conversation detail fetches the rating from the rating API. | A rating write would race with turn-time descriptor writes; the only gain was a list column. Can come later as a targeted `$set`. |
| R2 | `responseRate` (rated ÷ conversations) in the summary | **Dropped from v1.** | Needs a cross-store count over the descriptor store by agent + date range; no such query exists, and a wrong denominator is worse than none. |
| R3 | Maintainers see a "pseudonymized" user reference via `GdprPseudonymizer` | **Non-admins see no user reference at all;** admins see the userId. | `GdprPseudonymizer` is the *erasure* pseudonym (keyed, written in place of erased ids) — reusing it for display would leak the token that links erased rows. |
| R4 | Comments passed through `SecretValueScrubber` | **Dropped.** Comments are plain text, length-capped, rendered escaped. | That scrubber removes *known vault values from conversation memory*; it does not apply to free text and would imply a guarantee it cannot give. |
| R5 | Unique key `(conversationId, stepIndex, userId)`, `stepIndex` null for conversation ratings | **Key `(conversationId, stepIndex)`, conversation rating stored as `stepIndex = -1`.** | Only the owner may rate, so `userId` is redundant in the key; and PostgreSQL treats NULLs as distinct in a unique index, so a null `stepIndex` would allow duplicate conversation ratings. |
| R6 | Admin list scoped to "agents the caller holds EDIT on" across all agents | **`agentId` is required for non-admins** and checked with `ResourceAccessGuard.requireAccess(agentId, EDIT, …)`; admins (`seesEverything()`) may omit it. | Filtering arbitrary agent sets by space permissions inside an aggregation is complex and easy to get wrong; per-agent is what the UI needs anyway. |
| R7 | `eddi-viewer` read access to summaries | **Dropped;** endpoints enumerate `eddi-admin`, `eddi-editor`. | Viewer is MCP-oriented; keeps one access rule. |
| R8 | Delete the step rating inside `rerunLastConversationStep` | **No hook in the rerun path in v1;** the Chat UI clears its local thumb for a rerun step. Documented limitation. | Keeps the turn hot path untouched; a stale thumb on a rerun reply is a minor analytics skew. |
| R9 | Managed-agent rating endpoint | **Dropped.** Clients rate by `conversationId`, which managed mode also has. | Duplicate surface. |
| R10 | Per-user rate limiter on the PUT endpoints | **Dropped.** | Rows are bounded by the upsert key (≤ steps + 1 per conversation) and comment length; repeated PUTs only overwrite. |
| R11 | Scales `THUMBS`, `FIVE_STAR`, `CSAT` | **`THUMBS`, `FIVE_STAR`.** CSAT % is still reported for five-star (share of 4–5). | `CSAT` differed only in rendering. |
| R12 | CSV export | Kept, **with formula-injection escaping** (cells starting `=`, `+`, `-`, `@`, tab, CR are prefixed with `'`) and a row cap. | Comments are user-controlled text opened in spreadsheets by admins. |
| R13 | Version-regression auto-flag on the dashboard | **Dropped;** the by-version table is shown with counts, low-sample buckets greyed. | Thresholds without a sample-size model mislead. |
| R14 | Rating-config lookup per request | **`ConversationRatingPolicy` mirrors `ConversationReviewPolicy`:** id:version → `ratingConfig`, Caffeine cache (versions are immutable). | Proven pattern, no per-request agent loads. |
| R15 | Rating window measured from "last activity" | From the conversation descriptor's `lastModifiedOn`. | That is the field that exists. |

Decisions D1–D6 (§12) are taken as recommended, with D6 changed per R7.

### 0.1 Second pass: end-to-end experience (added 2026-10-24)

The first two passes produced a dashboard an admin has to remember to open. Feedback nobody
notices is wasted, so the admin side gets a **triage loop** and **push alerts**, and the
user side gets explicit UX rules.

**Admin — triage (PR 1 model + API, PR 3 UI).**
- Each rating carries `reviewStatus` (`NEW` | `REVIEWED`), `reviewedBy`, `reviewedAt`, optional `reviewNote` (≤ 2000 chars). A user re-rating resets it to `NEW`.
- `PATCH /ratings/{id}` (same scoping as §5.2) sets status/note; `POST /ratings/review` marks a filtered set reviewed in bulk.
- `GET /ratings/attention-count?agentId` → number of `NEW` ratings that are **low** (normalized ≤ 0.5) **or carry a comment**, scoped to what the caller may see. Cheap indexed count.
- Manager: a **Feedback sidebar badge** with that count, exactly like the approvals badge in `components/layout/sidebar.tsx` (same polling/query-key approach). The Feedback page opens on an **"Needs attention"** tab (NEW + low-or-commented, newest first); each row has *Mark reviewed*, *add note*, *Open conversation*; bulk select. A dashboard tile "Feedback needing attention".

**Admin — push alerts (PR 5, generalized in [§0.2](#02-third-pass-alert-rules-not-just-ratings)).**
Rating alerts are one *source* of the general alert-rule system in §0.2, not a `ratingConfig.alerts` block.

### 0.2 Third pass: alert rules, not just ratings (added 2026-10-24)

Admins want to hear about more than bad ratings: **errors**, and **conditions on what an agent
returns** (e.g. a field in the agent's JSON output, or a property the turn set). So PR 5 builds
one alerting mechanism with several sources instead of a rating-only feature.

**Why not just the existing pipeline route?** Today a designer *can* extract a field with
`property.json`, match it in `behavior.json` and call a Slack webhook from `httpcalls.json`.
That stays valid, but it is wiring per agent, runs inside the turn (latency, failure coupling),
has no throttling, digest, retry or inbox, and **cannot report errors** — a pipeline that failed
cannot run its own httpcall. Alerts are evaluated outside the pipeline, after the turn.

**Model.**
- **`AlertRule`** (new admin resource, store with Mongo/Postgres twins):
  `{ name, enabled, scope: {agentIds[] | all}, source, condition, delivery[], throttle, digest }`.
  Who may write: admins for `all`/multi-agent scope; editors for rules scoped to agents they hold EDIT on.
- **Sources (v1):**
  | `source` | Fires when | Condition fields |
  | --- | --- | --- |
  | `RATING` | a rating is created or changed | `maxNormalizedScore`, `onComment`, `tags[]` |
  | `CONVERSATION_ERROR` | a turn ends in `ERROR` / `EXECUTION_INTERRUPTED`, a turn deadline is exceeded, or the cost ceiling stops a turn | `errorKinds[]` (allow-listed enum) |
  | `OUTPUT_MATCH` | a turn completes and a value in the turn's result matches | `path`, `op`, `value` (below) |
- **`OUTPUT_MATCH` condition** — a deliberately small, safe matcher, **no expression engine, no template evaluation of runtime data** (AGENTS.md §4.4):
  - `path` addresses `properties.<name>` (conversation properties after the turn), `output.json.<a.b.c>` (the agent's output parsed as JSON when it is JSON), or `actions` (the turn's action list). Dot paths only, max depth 8, no wildcards.
  - `op`: `exists`, `eq`, `neq`, `in`, `gt`, `gte`, `lt`, `lte`, `contains` (string/array). `value` is the rule author's constant.
  - Example: `{ "path": "properties.escalate", "op": "eq", "value": true }`, `{ "path": "output.json.sentiment", "op": "in", "value": ["angry","frustrated"] }`, `{ "path": "actions", "op": "contains", "value": "handoff_requested" }`.
- **`AlertEvent`** (outbox + log): `{ ruleId, source, agentId, agentVersion, conversationId, firedAt, summary, deliveryStatus per target, attempts, acknowledgedBy/At }`. Written **before** delivery, so nothing is lost on a crash; delivery retries with backoff; failures are visible.

**Evaluation.**
- One hook where a turn's outcome is final (the turn runner — locate in `ConversationStepRunner` / `Conversation` where the state is set; one place, not each error site), plus the rating write path. The hook only enqueues; matching and delivery run on a bounded executor off the turn thread. A full queue drops with a counter and a WARN, never blocks a turn.
- Rules per agent cached (Caffeine, invalidated on rule save).

**Delivery.** Slack via a channel integration (`ChannelIntegrationConfiguration` / `ChannelTargetRouter`), webhook via `OutboundHttp` (SSRF policy, body cap, timeout) with an **HMAC signature header** using the existing `HmacSignatures`, and always the **Manager Alerts inbox**.

**Spam control (cluster-safe).** Dedupe key `(ruleId, agentId, conversationId)` within the throttle window; per-rule cap N per window counted from the `AlertEvent` store (shared DB, so it holds across replicas); overflow is recorded but delivered only in the next digest. Digests (`HOURLY`/`DAILY`) run on `ScheduleFireExecutor`.

**Privacy.** Alert payloads never carry a user id or conversation text. They name the rule, the agent and version, the conversation id with a Manager link, and *what* matched (path + op + the rule's own constant), not the runtime value. Exceptions: a rating comment (the user addressed it to the team, disclosed in the card), truncated to 280 chars; and `includeValue: true` on an `OUTPUT_MATCH` rule, honoured only when the agent version has `conversationReview` enabled.

**Manager.** An **Alerts** page: rules editor (source picker, condition builder with a path field and operator dropdown, delivery targets with a "Send test alert" button, throttle/digest), and an events inbox (filter by source/agent/rule, acknowledge, bulk acknowledge, delivery status with retry). Sidebar badge = unacknowledged events, like the approvals badge. The Feedback badge (§0.1) stays separate, since it is about review work, not incidents.

**Also:** Grafana alert-rule examples on the Micrometer metrics in `docs/monitoring` (for ops teams who already run Grafana); MCP tools `list_alert_events` / `acknowledge_alert`, plus operator read access and the matching prompt text.

**Split.** PR 5a: framework + `RATING` + `CONVERSATION_ERROR` sources + Slack/webhook/inbox delivery + Manager Alerts page. PR 5b: `OUTPUT_MATCH` source + condition builder. Then the cross-UI Playwright E2E.

**User — UX rules (PR 2).**
- Never nag: the card appears at most once per conversation unless the user opens it; dismissing it is remembered; never blocks input.
- One tap to rate; comment and tags are optional and appear after the score; immediate "Thanks — your feedback goes to the team running this assistant" state; rating editable later and restored on reload.
- Accessible (keyboard, screen-reader labels, `aria-pressed`), touch-sized targets, dark mode, all shipped locales.
- Failure is visible but soft: optimistic UI, rollback with a toast on error.

**End-to-end proof.** A Playwright test in the `Backend E2E` suite: rate in the Chat UI → the rating appears in the Manager "Needs attention" tab with the sidebar badge count → mark reviewed → badge clears.

---

## 1. Summary

| Question | Recommendation |
| --- | --- |
| What gets rated | A **conversation rating** (one per conversation per user, can be changed) and optionally **per-reply thumbs** on agent outputs. Both are switched on by the agent designer. |
| Scale | Configurable: `THUMBS` (up/down) or `FIVE_STAR` (1–5). Stored as the raw score plus a normalized `0..1` value (thumbs 0/1; stars (s−1)/4) so dashboards can compare agents that use different scales. |
| Extra signal | Optional free-text comment (capped) and optional predefined reason tags ("wrong answer", "too slow", …) from the config. |
| Configurable by | A new `AgentConfiguration.ratingConfig`. **Null means off** — no UI, no endpoint accepts a rating (§3). |
| Where it is stored | Its own store/collection `conversationratings`, **not** in conversation memory (memory is loaded every turn and must stay small; ratings must be aggregatable across conversations). Mongo + Postgres twins selected in `DataStoreProducers`. Nothing is written to the conversation descriptor (R1). |
| Who can rate | Only the conversation owner (`ConversationAccessGuard.requireConversationOwner`). |
| Who can analyze | Admins (all agents) and editors holding EDIT on the requested agent see scores, tags and comments; only admins see user ids. **Opening the transcript** behind a rating keeps the existing rule: owner, admin, or `conversationReview` enabled (`requireConversationRead`). |
| Admin surface | New Manager page **Feedback** (`/manage/feedback`) — KPI tiles, trend, distribution, per-version table, low-rated list with comments, CSV export — plus a Feedback strip on the agent detail page. |
| Not a lifecycle task | Rating happens *between* turns, outside the pipeline — it is a REST resource + store, not an `ILifecycleTask` (AGENTS.md §4.2 "Not everything is a lifecycle task"). |
| Size | Backend M, Chat UI S, Manager M, MCP/Operator S. Four PRs (§11). |

---

## 2. What already exists

| Piece | Where | Relevance |
| --- | --- | --- |
| Nothing rating-related | grep for `rating`/`thumbs`/`feedback` finds no feature | Greenfield. |
| Conversation review opt-in | `AgentConfiguration.ConversationReview`, `ConversationReviewPolicy`, `ConversationAccessGuard.requireConversationRead` | Already decides who may read someone else's transcript. Ratings must **not** become a back door around it (§6). |
| Agent profile for clients | `IRestAgentProfile.AgentProfile(agentId, name, description, reviewNotice)` → Chat UI `fetchAgentProfile` | The natural channel to tell the Chat UI whether/how to show rating controls. |
| Conversation descriptor | `ConversationDescriptor` (agentResource, userId, state, `lastModifiedOn` …) | Read-only here: owner, agent version and rating window. |
| Engine REST | `IRestAgentEngine` at `/agents/{conversationId}/…` | Rating endpoints live beside `endConversation`, `undo`, `rerun`. |
| GDPR | `UserErasureParticipant`, `IGdprParticipant`, `UserDataExport` | The rating store must take part in erasure and export. |
| Dual-DB stores | e.g. `PostgresHitlToolJournalStore`, `PostgresAuditStore` and their Mongo twins | Pattern to copy for `IConversationRatingStore`. |
| Manager conversation pages | `conversations.tsx`, `conversation-detail.tsx`, `conversation-monitoring.tsx`, editor `conversation-review-section.tsx` | Drill-down target, and the model for the new config editor section. |

---

## 3. Agent configuration: `ratingConfig`

Config-driven per Pillar 1 — the designer decides whether and how users are asked.

```json
{
  "ratingConfig": {
    "enabled": true,
    "scale": "FIVE_STAR",
    "conversationRating": {
      "enabled": true,
      "promptText": "How did we do?",
      "askOnEnd": true,
      "askAfterTurns": 0
    },
    "messageRating": { "enabled": true },
    "comment": { "enabled": true, "maxLength": 1000, "askBelowScore": 0.6 },
    "reasonTags": ["Wrong answer", "Didn't understand me", "Too slow", "Helpful", "Friendly"],
    "ratingWindowHours": 168
  }
}
```

| Field | Default when absent | Meaning |
| --- | --- | --- |
| `ratingConfig` | null → feature off | No controls shown, rating endpoints answer 409 `RATING_DISABLED`. |
| `scale` | `THUMBS` | `THUMBS` \| `FIVE_STAR`. |
| `conversationRating.askOnEnd` | `true` | Chat UI shows the rating card when the conversation ends. |
| `conversationRating.askAfterTurns` | `0` (never) | Also offer the card unobtrusively after N agent replies (for conversations that never "end"). |
| `messageRating.enabled` | `false` | Thumbs under each agent reply. Always thumbs, whatever `scale` is. |
| `comment.askBelowScore` | `0` (always offer) | Only expand the comment box for low scores (normalized), reduces friction for happy users. |
| `reasonTags` | `[]` | Closed list; the server rejects tags not in the list of the agent version the conversation ran on. |
| `ratingWindowHours` | `168` | Ratings are accepted until N hours after the conversation descriptor's `lastModifiedOn`. `0` = no limit. |

Validation in the agent store save path (unknown scale, `maxLength` > 4000, > 20 tags, tag length > 60 → 400).
`GET /agents/{agentId}/profile` gains a `rating` object (the client-relevant subset: scale, prompt,
which controls, tags, comment rules) so the Chat UI needs no extra call. Old configs simply lack the field → off.

---

## 4. Data model and storage

```text
ConversationRating
  id                 string
  conversationId     string
  stepIndex          int               -1 = conversation rating; else the agent output's step (R5)
  agentId            string            denormalized: aggregation without a join
  agentVersion       int
  environment        string
  channel            string            "chat-ui" | "manager" | "api" | "mcp" … (from the client, allow-listed)
  userId             string
  scale              THUMBS | FIVE_STAR
  score              int               1..5, or 0/1 for thumbs
  normalizedScore    double            0..1
  tags               string[]
  comment            string | null     length-capped plain text (R4)
  createdAt, updatedAt  instant
```

- **Unique key** `(conversationId, stepIndex)` → `PUT` is an atomic upsert (Mongo `replaceOne(upsert)`, Postgres `INSERT … ON CONFLICT`); re-rating replaces, never duplicates. Handles double-clicks and concurrent tabs without a race. `createdAt` is kept on update.
- **Indexes:** `(agentId, createdAt)`, `(agentId, agentVersion)`, `(userId)` for GDPR, `(conversationId)`.
- **`IConversationRatingStore`** with `upsert`, `delete`, `readByConversation`, `query(filter, page)`, `summarize(filter, groupBy)`, `deleteByUserId`, `readByUserId` (export). Mongo implementation uses an aggregation pipeline; Postgres uses `GROUP BY` with `date_trunc`. Summaries must be computed in the database, never by loading all rows. Wired through `DataStoreProducers` like `IHitlToolJournalStore`.
- **Lifetime:** ratings are kept when a conversation is purged by `ConversationPurgeService` (analytics should not vanish with old transcripts); the transcript link then shows "conversation no longer stored". GDPR erasure deletes them (§6). See decision D3.

---

## 5. REST API

### 5.1 For the person chatting (owner only)

| Method / path | Body / result |
| --- | --- |
| `PUT /agents/{conversationId}/rating` | `{score, tags?, comment?, channel?}` → 200 with the stored rating |
| `GET /agents/{conversationId}/rating` | the caller's conversation rating + per-step ratings (so the UI can re-render state after reload) |
| `DELETE /agents/{conversationId}/rating` | withdraw |
| `PUT /agents/{conversationId}/steps/{stepIndex}/rating` | `{score: 0\|1, tags?, comment?}` |
| `DELETE /agents/{conversationId}/steps/{stepIndex}/rating` | withdraw |

Implemented as a separate resource interface `IRestConversationRating` (like `IRestAgentProfile`, which also lives under `/agents/…`), not by growing `IRestAgentEngine`. Roles: `eddi-admin`, `eddi-editor`, `eddi-user` (as the profile endpoint).

Server-side checks: owner (`requireConversationOwner`), `ratingConfig` enabled on the conversation's agent **version** (`ConversationRatingPolicy`, R14), within `ratingWindowHours`, `stepIndex` exists and holds an agent output, score valid for the scale, tags in the allow-list, comment ≤ `maxLength`. Errors use the existing problem format with stable codes (`RATING_DISABLED`, `RATING_WINDOW_CLOSED`, `INVALID_STEP`, …).

### 5.2 For admins / maintainers

`@Path("/ratings")`, `@RolesAllowed({"eddi-admin","eddi-editor"})` (no role hierarchy in REST — enumerate). Callers for whom `ResourceAccessGuard.seesEverything()` is false must pass `agentId` and hold EDIT on it (`requireAccess(agentId, EDIT, …)`), else 400/403 (R6). `userId` is included in rows only for `seesEverything()` callers (R3).

| Method / path | Purpose |
| --- | --- |
| `GET /ratings?agentId&agentVersion&environment&from&to&minScore&maxScore&kind=conversation\|message&tag&hasComment&index&limit` | Paged list, newest first. Each row carries `canOpenTranscript` (precomputed `requireConversationRead` / `canReview`). |
| `GET /ratings/summary?agentId&from&to&kind&groupBy=none\|day\|week\|agentVersion\|channel` | `{count, avgNormalized, distribution (thumbs: up/down; stars: 1..5), csat (share of 4–5 among five-star), thumbsUpRate, commentCount, topTags[]}` per bucket. No response rate (R2). |
| `GET /ratings/export?…` | CSV (same filter), streamed, capped at 100k rows, formula-injection escaped (R12). |

---

## 6. Access, privacy, GDPR

- **Rating is personal data** (userId + comment). Editors see score, tags and comment with **no** user reference; admins see the userId (R3).
- **No transcript back door.** A rating row links to the conversation only when `requireConversationRead` would admit the caller. Without `conversationReview`, a maintainer sees "user rated 2/5: *'kept looping'*" but not the dialogue. The comment itself is something the user typed *into the rating form*, explicitly addressed to whoever runs the agent — so it is visible to maintainers. The rating card says so ("Your rating and comment are shared with the team running this assistant").
- **Per-reply ratings** include an *excerpt of the rated reply* only for callers who could read the transcript; others see the step index.
- **GDPR:** `ConversationRatingStore` implements `UserErasureParticipant` (`deleteByUserId`) and contributes to `UserDataExport`. Processing restriction (`ProcessingRestrictedException`) blocks new ratings like any other write.
- **Abuse limits:** owner-only + upsert key bounds rows to (#steps + 1) per conversation; comment cap; tag allow-list; `channel` allow-listed (unknown → `other`). No separate rate limiter (R10).
- **Audit ledger:** not needed for the rating itself (it is the user's own action on their own conversation). Exports of ratings by an admin are logged at INFO with the principal, like review reads.

---

## 7. Chat UI (`ui/chat`)

- `fetchAgentProfile` → read `profile.rating`; nothing renders when absent.
- **Per-reply thumbs** in `MessageBubble.tsx` for agent messages (hover/focus on desktop, always visible on touch). Clicking thumbs-down with tags/comment enabled opens a small inline popover. Optimistic update, rollback + toast on error.
- **Conversation rating card** (`RatingCard.tsx`, sibling of `PausedCard`): shown when the conversation ends (`askOnEnd`), after `askAfterTurns`, or via a "Rate this conversation" item in `ChatHeader`. Stars or thumbs by scale; tag chips; comment textarea shown per `askBelowScore`; "Thanks" state; editable afterwards.
- On load/resume call `GET /agents/{id}/rating` to restore state. After a rerun, clear the local thumb for that step (R8).
- New API module `rating-api.ts` (+ demo-mode stub in `demo-api.ts`).
- i18n keys in `i18n.ts` for every shipped locale; a11y: radio-group semantics for stars, `aria-pressed` for thumbs, keyboard reachable.
- Tests: component tests for each scale, optimistic rollback, disabled-when-absent, restore-after-reload.
- The Manager's own chat page (`ui/manager/src/pages/chat.tsx`, `use-chat.ts`) gets the same controls so designers can test the flow.

---

## 8. Manager: Feedback dashboard

### 8.1 New page `/manage/feedback` (sidebar entry "Feedback")

- **Filters bar:** agent picker (reuse `AgentPicker`; required for non-admins, R6), version, environment, date range (7d / 30d / 90d / custom), kind (conversation / reply), tag, "has comment", score range. Filters live in the URL (shareable links).
- **KPI tiles:** ratings count, average (normalized, shown in the agent's scale), CSAT % (or thumbs-up %), comments count — each with delta vs. previous period.
- **Trend chart:** average normalized score + volume per day/week.
- **Distribution:** bar per score (or up/down).
- **By version:** table of avg score and count per `agentVersion` — the main "did my last deploy make it worse?" view. Low-sample rows greyed, no automatic flag (R13).
- **Top tags:** ranked reason tags, click to filter.
- **Ratings table:** score, tags, comment (expand), agent/version, channel, date, user (admins only), "Open conversation" link to `/manage/conversationview/:id` only when `canOpenTranscript`. Default sort "lowest first, newest".
- **Export CSV** button.
- Charts follow the Manager's existing chart components; empty state explains how to enable `ratingConfig`.

### 8.2 Elsewhere in the Manager

- **Agent detail:** a compact Feedback KPI strip linking to `/manage/feedback?agentId=…`.
- **Agent config editor:** `rating-config-section.tsx` beside `conversation-review-section.tsx` — toggles, scale, prompt text, tags editor, comment rules, window.
- **Conversation detail:** shows the conversation rating and per-reply thumbs inline next to the steps (fetched from the rating API). No list column in v1 (R1).
- **Dashboard:** a small "Satisfaction (30d)" tile for admins.
- i18n for all Manager locales (all three gates — `i18n:check`, plural categories, translation-debt baseline); MSW handlers + fixtures; vitest + an MSW e2e for the page.

---

## 9. MCP, Platform Operator, metrics

- **MCP tools:** `list_conversation_ratings` and `summarize_conversation_ratings` (same filters, same access scoping as REST). Check `integrations/claude-plugin/` skills (`SkillToolReferencesTest`).
- **Platform Operator:** add `GET /ratings/summary` and `GET /ratings` to `tool-scopes.ts` (read-only), teach `system-prompt.ts` what ratings are and how to enable `ratingConfig`, bump `operator-revision.json`. Lets an admin ask "how is the support agent rated since Tuesday's deploy?".
- **Micrometer:** `eddi.conversation.ratings` counter tagged `kind`, `scale`, `bucket` (low/neutral/high) and `agentId`; a distribution summary of normalized score per agent. Grafana panel added to `docs/monitoring`.
- **Docs:** new `docs/conversation-ratings.md` (+ `docs/SUMMARY.md`), section in `docs/agent-config-authoring.md` for `ratingConfig`, GDPR page mentions the store, changelog fragment per PR.

---

## 10. Traps and edge cases

| Case | Handling |
| --- | --- |
| **Undo / redo** | Step ratings stay stored; the UI hides ratings for undone steps; summaries count them (they rated what the user saw). |
| **Rerun of a step** | v1: no server hook (R8); the Chat UI clears the local thumb. Known limitation, documented. |
| **Agent version upgraded mid-conversation** (`#1104` moves pinned conversations) | Rating records the version of the **step** for reply ratings and the version at rating time for conversation ratings; config checks use the current conversation version. |
| **Auth disabled / anonymous** | Owner is the conversation's `userId` as today; nothing special. |
| **Conversation purged** | Rating survives (D3); `canOpenTranscript=false`, UI says "no longer stored". |
| **Group conversations, `/v1` API, Slack, Teams** | Out of scope v1. `/v1` and MCP clients can call the REST endpoint directly; Slack reaction → rating is a later item. |
| **Rating while `IN_PROGRESS` / `AWAITING_HUMAN`** | Allowed for earlier steps; rating writes only the rating store, never conversation memory or the descriptor, so it does not go through the coordinator. |
| **Small samples** | Dashboard shows counts next to every average and greys out buckets under N (config, default 10) to avoid over-reading 2 ratings. |

---

## 11. Phased delivery

| PR | Contents | Done when |
| --- | --- | --- |
| **1. Backend** | `ratingConfig` model + validation, profile field, `ConversationRating` model, Mongo + Postgres stores with indexes, `ConversationRatingPolicy`, owner endpoints, admin list/summary/export endpoints with scoping, GDPR erasure/export, metrics, unit tests (JUnit 5 + Mockito) and a `*IT` per store, docs + changelog fragment. | `./mvnw test` green incl. repo-wide guards; ITs green in CI; manual round-trip via REST in Docker. |
| **2. Chat UI** | `rating-api.ts`, thumbs in `MessageBubble`, `RatingCard`, header entry, restore on reload, i18n, tests. | Rating survives reload; disabled agents show nothing. |
| **3. Manager** | Feedback page, agent detail KPI strip, config editor section, conversation-detail inline ratings, dashboard tile, Manager chat controls, i18n, MSW, tests. | Page works in MSW mode and against a live backend with seeded ratings. |
| **4. MCP + Operator** | Two MCP tools, plugin skill check, operator prompt + allow-list + revision bump, docs. | Operator answers a ratings question in a live run. |
| **5a. Alerts framework** | §0.2: `AlertRule` + `AlertEvent` stores, after-turn hook + rating hook, bounded executor, `RATING` and `CONVERSATION_ERROR` sources, Slack/webhook (HMAC-signed)/inbox delivery with retry, cluster-safe throttle + digests on `ScheduleFireExecutor`, Manager Alerts page + badge, MCP/operator, Grafana examples, docs. | A low rating with a comment and a forced turn error each produce exactly one Slack/webhook message and one inbox event; a burst folds into a digest; a turn is never slowed by alerting. |
| **5b. Output match** | `OUTPUT_MATCH` source, safe path/op matcher, condition builder UI, `includeValue` gated on `conversationReview`; then the cross-UI Playwright E2E (§0.1). | `properties.escalate == true` set by an agent fires one alert without exposing conversation text. |
| *Later* | Slack/Teams reaction capture; low-rating triggers (webhook or HITL takeover once WS7 lands); scheduled LLM theme clustering of comments via `ScheduleFireExecutor` with `maxCostPerRun`; group-conversation ratings. | — |

PR 1 blocks the rest; PRs 2 and 3 can run in parallel against PR 1's API; PR 4 after 3 (operator PRs land serially anyway).

---

## 12. Decisions for the maintainer

| # | Decision | Recommendation |
| --- | --- | --- |
| D1 | Default when `ratingConfig` is absent: off, or on with thumbs? | **Off.** Matches `conversationReview`, no surprise UI on existing deployments. |
| D2 | May maintainers without review access read rating **comments**? | **Yes**, with the disclosure line on the rating card; transcripts stay behind `conversationReview`. |
| D3 | Do ratings outlive conversation purge? | **Yes** (analytics), deleted only by GDPR erasure or an explicit admin purge. |
| D4 | Per-reply rating scale: always thumbs, or follow `scale`? | **Always thumbs** — stars per message is too much friction. |
| D5 | Should a rating influence the running agent (e.g. emit an action, set a property)? | **Not in v1.** Rating happens outside a turn; a later "low rating → trigger" feature belongs with triggers/takeover, not the pipeline. |
| D6 | Viewer role (`eddi-viewer`) on admin endpoints? | **No** (R7): admin + editor-with-EDIT only. |
