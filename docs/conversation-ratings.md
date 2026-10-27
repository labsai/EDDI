# Conversation Ratings

People chatting with an agent can rate the conversation — and, if the agent asks
for it, single agent replies. The people who run the agent see and triage those
ratings without reading raw transcripts.

Ratings are **off unless the agent's designer switches them on** in the agent
configuration (`ratingConfig`). They live in a store of their own
(`conversationratings` on MongoDB, `conversation_ratings` on PostgreSQL), never in
conversation memory: memory is loaded on every turn and must stay small, and
ratings must be aggregated across conversations.

> **Status.** This page describes the backend (REST API, storage, metrics, GDPR)
> and the MCP rating tools. Rating controls in the Chat UI, the Manager's Feedback
> page and push alerts follow in later releases.

## Switching ratings on

Add `ratingConfig` to the agent configuration. The full reference is in
[Agent Config Authoring](agent-config-authoring.md#conversation-ratings-ratingconfig);
the short version:

```json
{
  "ratingConfig": {
    "enabled": true,
    "scale": "FIVE_STAR",
    "conversationRating": { "enabled": true, "promptText": "How did we do?", "askOnEnd": true, "askAfterTurns": 0 },
    "messageRating": { "enabled": true },
    "comment": { "enabled": true, "maxLength": 1000, "askBelowScore": 0.6 },
    "reasonTags": ["Wrong answer", "Didn't understand me", "Too slow", "Helpful", "Friendly"],
    "ratingWindowHours": 168
  }
}
```

The setting is read from the **agent version the conversation runs on**, so turning
ratings on in a new version does not open old conversations to ratings. Chat
clients learn how to render the controls from `GET /agents/{agentId}/profile`,
whose `rating` object carries the client-relevant subset (scale, prompt, which
controls, tags, comment rules, window) and is `null` when ratings are off.

## Scales and scores

| Scale | Score | Normalized (0..1) |
| --- | --- | --- |
| `THUMBS` | `0` (down) or `1` (up) | 0 or 1 |
| `FIVE_STAR` | `1` … `5` | (score − 1) / 4 |

A reply rating is **always thumbs**, whatever `scale` says. Every rating is stored
with its raw score and its normalized score, so dashboards can compare agents that
use different scales.

## Rating a conversation (the person chatting)

Roles: `eddi-admin`, `eddi-editor`, `eddi-user` — and the caller must be the
conversation's **owner**. An administrator can read and withdraw ratings on any
conversation but cannot rate one on somebody's behalf (that would forge it).

| Method | Path | Body / result |
| --- | --- | --- |
| `PUT` | `/agents/{conversationId}/rating` | `{score, tags?, comment?, channel?}` → the stored rating |
| `GET` | `/agents/{conversationId}/rating` | `{conversation, steps[], controls}` — restores the client's state after a reload; `controls` are the rating controls of the version *this conversation* runs on (null when it takes no ratings), which a resuming client shows instead of the profile's |
| `DELETE` | `/agents/{conversationId}/rating` | 204, withdrawn (also when there was none) |
| `PUT` | `/agents/{conversationId}/steps/{stepIndex}/rating` | `{score: 0\|1, tags?, comment?, channel?}` |
| `DELETE` | `/agents/{conversationId}/steps/{stepIndex}/rating` | 204 |

```bash
curl -X PUT https://eddi.example.com/agents/$CONVERSATION_ID/rating \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"score": 2, "tags": ["Too slow"], "comment": "kept looping", "channel": "chat-ui"}'
```

A repeated `PUT` **replaces** the rating — there is one per conversation and one
per reply, keyed by `(conversationId, stepIndex)` with the conversation rating at
`stepIndex = -1`. The write is an atomic upsert, so double clicks and two tabs
never produce two rows; the original `createdAt` is kept. `channel` is one of
`chat-ui`, `manager`, `api`, `mcp`, `slack`, `teams`; anything else is stored as
`other`, absent as `api`.

The responses never include the maintainers' triage (status, reviewer, note).

### What the server checks

| Check | Error (`{"error": "<code>", "message": …}`) |
| --- | --- |
| Caller owns the conversation | 403 `NOT_CONVERSATION_OWNER` (or the guard's own 403/404) |
| The conversation exists and is not deleted | 404 `CONVERSATION_NOT_FOUND` |
| Processing is not restricted for the user (GDPR Art. 18) | 403 `processing_restricted` |
| `ratingConfig` enabled on the conversation's agent version, and the kind (conversation / reply) enabled | 409 `RATING_DISABLED` |
| Within `ratingWindowHours` of the conversation's last activity (`lastModifiedOn`) | 409 `RATING_WINDOW_CLOSED` |
| `stepIndex` exists and holds an agent output | 400 `INVALID_STEP` |
| Score valid for the scale | 400 `INVALID_SCORE` |
| Every tag in `reasonTags` | 400 `INVALID_TAG` |
| Comment allowed (`comment.enabled`) | 400 `COMMENT_NOT_ALLOWED` |
| Comment ≤ `comment.maxLength` (at most 4000) | 400 `COMMENT_TOO_LONG` |

Comments are plain text: trimmed, control characters other than line breaks and
tabs dropped, and rendered escaped by every client. Withdrawing is always allowed
— it is never blocked by the window, a disabled config or a restriction.

A rating writes only the rating store, never conversation memory or the
descriptor, so it does not go through the conversation coordinator and may be
given while a turn is running.

## Analyzing and triaging (the people running the agent)

Roles: `eddi-admin`, `eddi-editor` (enumerated — REST has no role hierarchy, and
`eddi-viewer` is deliberately excluded).

**Scoping.** An administrator sees every agent's ratings. Anyone else must pass
`agentId` and hold `EDIT` on that agent, else 400 `AGENT_ID_REQUIRED` / 403. Only
administrators see the `userId` of the person who rated; editors see score, tags
and comment with no user reference. (Without workspace enforcement every editor
"holds" every agent, as everywhere else in EDDI.)

**No transcript back door.** Each row says `canOpenTranscript`: true only when the
conversation still exists and the existing conversation-read rule admits the
caller — owner, administrator, or a maintainer of an agent version with
[conversation review](workspaces.md) enabled. Without review, a maintainer sees
"rated 2/5: *kept looping*" but not the dialogue. The comment is something the
user wrote *to the team running the agent*, so it is visible to them.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/ratings` | Paged list (`index`, `limit` ≤ 100), newest first or `sort=lowest` |
| `GET` | `/ratings/summary` | Aggregates per bucket, `groupBy=none\|day\|week\|agentVersion\|channel` |
| `GET` | `/ratings/export` | CSV, same filters, at most 100,000 rows |
| `GET` | `/ratings/attention-count?agentId=` | `{count}` of ratings needing attention |
| `PATCH` | `/ratings/{ratingId}` | `{reviewStatus?, reviewNote?}` — triage one rating |
| `POST` | `/ratings/review` | Mark every `NEW` rating matching the filter reviewed |

**Filters** (all endpoints except `PATCH`): `agentId`, `agentVersion`,
`environment`, `from`, `to` (ISO-8601 instant, or a date read as UTC; `to` is
exclusive and a date there includes the whole day), `minScore` / `maxScore`
(normalized, 0..1), `kind=conversation|message`, `tag`, `hasComment`,
`reviewStatus=NEW|REVIEWED`, `needsAttention=true`.

**Summary** buckets carry `count`, `avgNormalized`, `distribution` (thumbs up/down,
stars 1–5), `csat` (share of five-star ratings scored 4–5), `thumbsUpRate`,
`commentCount` and `topTags`. Everything is computed in the database. Day and week
keys are UTC (`2026-10-24`, ISO week `2026-W43`).

**Export.** Streamed CSV; the `userId` column appears for administrators only;
`X-Ratings-Truncated: true` when the 100,000-row cap cut it short. Every text cell
is quoted, and cells a spreadsheet would read as a formula (starting with `=`, `+`,
`-`, `@`, tab or carriage return) are prefixed with an apostrophe. Each export is
logged at INFO with the caller.

### Triage

Each rating carries `reviewStatus` (`NEW` | `REVIEWED`), `reviewedBy`, `reviewedAt`
and an optional internal `reviewNote` (≤ 2000 characters, never shown to the person
who rated).

- **Needs attention** = `NEW` and either low (normalized ≤ 0.5) or carrying a
  comment. `GET /ratings/attention-count` counts them — backed by a dedicated index,
  so polling it for a badge is cheap.
- `PATCH /ratings/{ratingId}` sets the status and/or note; a field left out stays.
  The reviewer is **always the caller**, never taken from the body. The update only
  applies if the person has not re-rated since the maintainer read it, else 409
  `RATING_CHANGED` (also `RATING_NOT_FOUND`, `NOTE_TOO_LONG`).
- `POST /ratings/review` marks every `NEW` rating matching the filter reviewed —
  for example `?agentId=…&needsAttention=true`. Logged at INFO.
- **A re-rating resets the triage**: status back to `NEW`, reviewer, time and note
  cleared — the note was about the old rating.

## Lifetime and privacy

- Ratings **outlive conversation purges** (analytics); the transcript link then
  reports `canOpenTranscript: false`. Only GDPR erasure or the person withdrawing a
  rating removes it.
- **GDPR:** the rating store joins erasure and export as a GDPR participant
  (`conversationRatings`) — see [GDPR Compliance](gdpr-compliance.md). Erasure deletes
  the user's ratings and forgets them as the reviewer of ratings they triaged; the
  export lists their ratings without the reviewer's identity. Processing
  restriction blocks new ratings.
- Group conversations, the `/v1` API and channel reactions (Slack, Teams) do not
  capture ratings yet; `/v1` and MCP clients can call the REST endpoints directly.

## Known limitations

- **Rerun.** Rerunning a step does not delete that step's reply rating on the
  server; the chat client clears its local thumb. A stale thumb on a rerun reply is
  a minor analytics skew.
- **Undo.** Ratings of undone steps stay stored and count in summaries (they rated
  what the person saw).
- **Reply version.** A reply rating records the agent version of the conversation
  at rating time — conversation memory does not keep a version per step.

## Metrics

`eddi_conversation_ratings_total`, `eddi_conversation_ratings_normalized` and
`eddi_conversation_ratings_withdrawn_total` — see
[Metrics](metrics.md#conversation-rating-metrics). The Full Metrics Reference
dashboard has a "Conversation Ratings" row.
