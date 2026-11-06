## 🐛 fix(conversations): the chat panel resumes only the caller's own conversation (2026-11-06)

**Repo:** EDDI (`fix/conversations-mine-flag`)

### What changed and why

The Manager's chat panel opens an agent by resuming the newest `READY` or `AWAITING_HUMAN` conversation
the listing returns. An administrator's listing holds **every user's** conversations, so an admin who
opened an agent an end user had talked to more recently landed in that user's conversation, and the
next message ran in it as that user: their tools, property writes and long-term memory. The admin
passes the per-conversation ownership check, so nothing stopped it. The operator's "restore my last
investigation" had the same shape: a fresh tab restored another admin's newest operator conversation,
pending approval card included, and its History list showed and opened other admins' investigations.

The Manager cannot filter this by itself: a conversation's owner is the principal name the backend
resolved, which the Manager does not reliably know. So the listing gained a flag:

- **`GET /conversationstore/conversations?mine=true`** lists only the caller's own conversations, for
  every caller: an administrator, and an agent's reviewer (who otherwise also sees that agent's
  conversations). The owner is pushed into the descriptor query as an exact match on `userId`, so a
  pre-v5.1.6 conversation that records no owner on its descriptor is left out, and the per-row check
  enforces the same, as it stays the authority over the query. The "search matches nothing, list
  everything" fallback is decided within the same set. Without a named caller (authorization disabled)
  there is no owner and the flag changes nothing. The default is `false`: existing clients are unchanged.
- **Manager:** the chat panel's resume and its history list (they share one cache entry, so they must
  ask the same question), the operator's restore and its History list all ask for `mine`. Browsing
  everybody's conversations stays on the Conversations page.
- **Operator polling:** the 1.5 s poll while a resumed turn settles slept through a helper that added an
  abort listener per call and never removed it, about 200 on one signal over the five-minute ceiling.
  It is now `lib/abortable-sleep.ts`, which removes its listener when the timer fires.
- **Operator History** sorts with `conversationRecency`, whose comment already claimed the list shared
  it; the list carried its own copy of the expression.

**Files:** [`IRestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/IRestConversationStore.java),
[`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java),
[`conversations.ts`](../../ui/manager/src/lib/api/conversations.ts),
[`use-chat.ts`](../../ui/manager/src/hooks/use-chat.ts),
[`use-operator-chat.ts`](../../ui/manager/src/hooks/use-operator-chat.ts),
[`use-conversations.ts`](../../ui/manager/src/hooks/use-conversations.ts),
[`operator-history.tsx`](../../ui/manager/src/components/operator/operator-history.tsx),
[`abortable-sleep.ts`](../../ui/manager/src/lib/abortable-sleep.ts),
[`rest-api-reference.md`](../rest-api-reference.md#the-conversation-store)

### Design decisions

- A server-side flag, not a client filter: only the backend knows the principal name it stamps as owner.
- Exact owner match, no "or unowned" alternative: a legacy conversation without a recorded owner is not
  "mine" to resume. Same semantics as the open "search my conversations" endpoint (#1307).
- The old eight-argument Java signature stays as a `default` overload (`mine=false`), so the MCP tool and
  the existing tests are unchanged.

```decision-log
| 2026-11-06 | Conversation listing takes `mine=true` (exact owner, any caller incl. admins); the Manager's chat resume, chat history and operator restore/History use it | An admin's chat panel resumed an end user's newest READY conversation and sent the next message into it | Filtering in the Manager (it does not know the owner principal); a separate endpoint (the listing already has every other filter) |
```

```regression-note
| 2026-11-06 | An admin opening an agent's chat resumed another user's conversation and sent messages into it as that user | The chat panel picked the newest resumable conversation from a listing that, for an admin, holds every user's | `mine=true` on the listing, used by the chat panel and the operator | fix/conversations-mine-flag |
```
