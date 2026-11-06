# User Events: the per-user event stream

`GET /me/events` is a server-sent event stream that tells a signed-in user's open tabs about things that concern them, as they happen: an approval waiting for them, a team discussion that ended, a long job that finished, a new notification. It is what the end-user app's notifications are built on, so a user does not have to keep a page open and refresh it to find out.

It is a **hint channel**. An event says *that* something happened, with ids, never *what*: the client fetches the details through the endpoints it would use anyway, and those check access again.

## What arrives

The first frame of every stream is `ready`:

```text
event: ready
data: {"v":1,"heartbeatSeconds":20}
```

Then one named event per thing that concerns the caller. The SSE event name is the event's `kind`, and `data` is one JSON object.

| `kind` | When | Who receives it |
|---|---|---|
| `approval.pending` | A 1:1 turn or tool call paused for approval (`surface: "regular"`, `conversationId`, `agentId`), or a group discussion paused at a phase or task gate (`surface: "group"`, `groupId`, `groupConversationId`, `state`: `PHASE` or `TASK`) | The conversation's owner and every `eddi-admin` and `eddi-approver` — exactly who may decide it ([Who May Decide](hitl.md#who-may-decide)). For a 1:1 pause the owner is told only while they may still `USE` the agent, as resuming requires |
| `approval.decided` | A pending approval of either surface was decided, cancelled or timed out; `state` is the outcome (`APPROVED`, `REJECTED`, `ABORTED`, …, or `MIXED` for a partly approved tool batch) | The owner, and admins and approvers so their inboxes drop it |
| `team.started` | A group discussion started, or a continuation round of it (`state` is its state) | The discussion's owner and its `HUMAN` members |
| `team.waiting_for_human` | A group discussion paused for a `HUMAN` member's turn or a facilitator escalation; `yourTurn` is `true` on the copy of the person it waits for | The owner, the `HUMAN` members and the person it waits for |
| `team.ended` | A group discussion reached a terminal state; `state` is `COMPLETED`, `FAILED`, `REJECTED`, `CANCELLED` or `CLOSED` | The owner and the `HUMAN` members |
| `job.finished` | The last turn of a [self-continuation](long-jobs.md) job ran — it asked for no successor; `state` is `done`, or why it stopped (`limit`, `cost_ceiling`, …) | The conversation's owner |
| `job.run_completed` | A [scheduled](scheduling.md) run on the conversation completed (`jobKind: "schedule"`, `scheduleId`) | The conversation's owner |
| `job.failed` | A scheduled run or a continuation turn failed (`jobKind`, `scheduleId`); a failed fire is retried, so this can arrive once per attempt | The conversation's owner |
| `notification.created` | A [workspace notification](workspaces.md) landed in the inbox (`notificationId`, `notificationType`: `SHARED_WITH_YOU`, `ACCESS_REQUESTED`, `UPDATE_AVAILABLE`) | The recipient; a deployment notice (`UPDATE_AVAILABLE`) goes to every administrator |

Every event also carries `id` (unique per event) and `ts` (epoch milliseconds). A field that does not apply is absent.

```text
event: approval.pending
data: {"id":"5b1f…","kind":"approval.pending","ts":1793961600000,"surface":"regular","conversationId":"6720…","agentId":"6650…"}
```

**Never content.** No message text, no title, no prompt, no pause reason a designer wrote, no tool argument, no member's display name. A client that wants to show "Refund approval for Acme" reads the pending approvals (`GET /agents/pending-approvals`, `GET /groups/pending-approvals`), the conversation, or the notification inbox — all access-checked. That keeps the stream harmless if an event ever reached the wrong tab.

A comment line (`: keepalive`) arrives every `eddi.user-events.heartbeat-interval` (20 s). A client ignores it; it keeps proxies from cutting an idle stream, and it is how the server notices a closed tab.

## Who receives what

Scoping is decided **on the server, for every connection, at fan-out**:

- Where the event is raised, its audience is read from the record that is authoritative for it — the conversation descriptor's owner (the same field every ownership check reads), the discussion's owner and roster, the notification's recipient.
- When a stream opens, the caller's principal, `eddi-admin` / `eddi-approver` roles and team subjects are captured from **that request's authenticated identity**. Nothing the client sends — no parameter, no header — selects what it receives.
- Each event is delivered to a connection only if the connection's principal is in the audience, or its role is one the audience names. For a 1:1 pause the owner additionally passes a fresh `ResourceAccessGuard` `USE` check on the agent (with workspaces enforced); a check that cannot answer delivers nothing.
- A stream is closed when its access token expires and after `eddi.user-events.max-connection-age` (1 h), so the roles it captured are never older than that. The client reconnects with a fresh token.

**Authorization on:** an anonymous caller gets `401`. Every role (`eddi-user` included) may open a stream; what arrives is scoped as above.

**Authorization off** (`authorization.enabled=false`, development only — `AuthStartupGuard` refuses it in production): every caller is treated as the owner and an administrator, as on every other endpoint, so all connections share one anonymous bucket and receive every event. The per-user cap then applies to that one bucket, i.e. to the whole node.

## Clients

**One stream per tab.** A tab opens one stream and fans the events out to whatever on the page wants them; components never open their own. The Manager and Workforce use the framework-free client in `@eddi/core/user-events`:

```ts
import { createUserEventStream } from "@eddi/core/user-events";

const stream = createUserEventStream({ http }); // the app's HttpClient: base URL and token
const off = stream.on("approval.pending", (event) => queryClient.invalidateQueries({ queryKey: ["approvals"] }));
stream.onStatus((status) => { if (status === "open") refetchEverythingShown(); });
stream.start();
```

It reads the stream with `fetch` (an `EventSource` cannot send a bearer token), reconnects with exponential backoff and jitter, stops on `401`/`403`/`404` (after one token refresh on `401`), and backs off on `429`. The embeddable Chat UI widget does not use it.

**There is no replay.** Events are hints, not a log: the server keeps no per-user history and ignores `Last-Event-ID`. A client that (re)connects refetches what it shows — the `ready` frame is the signal — and from then on applies events as they come. An event missed while disconnected is therefore never lost in effect: the refetch shows its outcome.

## Limits

| Property | Default | What it bounds |
|---|---|---|
| `eddi.user-events.max-connections-per-user` | `8` | Streams one principal may hold on one node; the next gets `429` with `Retry-After: 30` |
| `eddi.user-events.max-connections` | `10000` | Streams on one node, all users together |
| `eddi.user-events.buffer-size` | `64` | Frames written to a stream but not yet acknowledged; a client further behind is closed as a slow consumer and reconnects |
| `eddi.user-events.dispatch-queue-size` | `10000` | Events waiting for fan-out on one node; past it an event is dropped and counted |
| `eddi.user-events.heartbeat-interval` | `20s` | Keep-alive interval, and how soon a closed tab's slot is freed |
| `eddi.user-events.max-connection-age` | `1h` | Longest a stream stays open before the client must reconnect |
| `eddi.user-events.enabled` | `true` | `false` answers `404` and raises no events |

The caps are per node: behind a load balancer a user's tabs spread over pods, and each pod counts its own.

## In a cluster

Events raised on one pod reach a user whose tab is connected to another. With `eddi.messaging.type=nats` each event travels over the cluster event bus that already carries cache invalidations (the JetStream stream `<prefix>_EVENTS`, subject `eddi.<prefix>.evt.user.event`), together with its audience. The raising pod delivers to its own streams at once; every other pod scopes the event against its own connections with the same rules. The audience names principals only as SHA-256 hashes (as the other user-keyed cluster events do), so no user id is written into the stream in clear; the stream keeps events for `eddi.cluster.events.max-age` (1 h).

A pod that was disconnected from NATS briefly receives the events it missed when it reconnects (late, which is harmless for a hint); one disconnected longer than the retention misses them, and its clients' next refetch covers that. With `in-memory` messaging (single node) only local delivery happens.

## What raises events

| Event | Raised by |
|---|---|
| `approval.pending` (1:1) | `HitlPauseCommittedEvent`, fired after a pause is durable — the trigger the [email notifier](hitl.md#email-notifications) uses |
| `approval.decided` | `HitlDecisionRecorded`, fired with the `hitl.approval` audit entry. With the audit ledger switched off (`eddi.audit.enabled=false`) no decided event is sent |
| `approval.pending` (group), `team.*` | `GroupConversationService` at the start and the end of every discussion leg (the end reads the state the leg left behind), and the cancel of a paused discussion |
| `job.finished` | The commit of a continuation turn that arms no successor |
| `job.run_completed`, `job.failed` | `ScheduleFireExecutor`, after a scheduled conversation fire or a continuation fire |
| `notification.created` | `WorkspaceNotifications`, after the notification is stored |

Every source is best effort: it never throws and never blocks on a client. The thing that happened is already durable; the event only says so.

## Monitoring

`eddi_user_events_connections` (gauge), `eddi_user_events_delivered_total{kind}`, `eddi_user_events_dropped_total{reason}` and `eddi_user_events_rejected_total{reason}` — see [Metrics](metrics.md#user-event-stream-metrics). The **User event stream** row of the Full Metrics Reference dashboard charts them.

## Not here yet

- **Web push.** Notifications to a closed browser need web push; its payloads will follow the same rule (no content), with VAPID keys in the vault.
- **Replay.** `Last-Event-ID` is not supported (see above).

## See also

- [Human-in-the-Loop](hitl.md) — pauses, decisions and who may decide
- [Group Conversations](group-conversations.md) — discussions, human members, approval gates
- [Long Jobs](long-jobs.md) and [Scheduling](scheduling.md)
- [Clustering](clustering.md) — the event bus
