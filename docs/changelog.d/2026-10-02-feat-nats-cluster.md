## Horizontal scaling on NATS JetStream — runtime cluster mode (2026-10-02)

**Repo:** EDDI · **Branch:** `feat/nats-cluster`

EDDI now scales horizontally from the one published image: `eddi.messaging.type=nats`
is read at runtime, and any number of replicas run behind a plain round-robin load
balancer, sharing one database and one NATS JetStream cluster. `in-memory` stays the
zero-dependency default and behaves exactly as before — no NATS class is instantiated,
no meter is registered and nothing about NATS is logged.

### What changed

- **Runtime selection** — [`ClusterProducers`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterProducers.java)
  picks the coordinator, lease manager, shared state, event bus and RPC by
  `eddi.messaging.type` (any other value fails the boot). The build-profile
  `NatsConversationCoordinator`, `NatsHealthCheck` and `NatsMetrics` are gone with their
  tests: the coordinator published to JetStream under the per-conversation lock (10/20/30 s
  stacking when NATS was slow), made every turn of a node booted without NATS fail, took
  readiness DOWN on any NATS blip, leaked its connection on a partial start, and its dead
  letters could not be listed, discarded or replayed.
- **One NATS connection** — [`NatsConnectionManager`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java)
  connects asynchronously (boot never waits), fails every call fast while disconnected,
  supports user/password, token, creds and nkey auth and TLS/mTLS, and drains on shutdown
  without leaking a half-open connection.
- **Cluster-wide conversation leases with fencing** — a turn still runs on the node that
  received it, but only while it holds the conversation's lease
  ([`KvLeaseManager`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java),
  KV `create` + heartbeat + server-side TTL, release notifications, presence-based
  takeover, handoff fairness). The lease revision is a fencing token: both conversation
  stores refuse a write carrying an older token than the document has seen
  (`_fence`, [`ConversationFencedException`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationFencedException.java)),
  so a node that lost its lease cannot overwrite the turn that ran after it.
- **Coordinator** — the in-memory coordinator's queue logic moved verbatim into
  [`AbstractQueuedConversationCoordinator`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AbstractQueuedConversationCoordinator.java);
  [`ClusterConversationCoordinator`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ClusterConversationCoordinator.java)
  acquires the lease asynchronously (no NATS I/O under the queue monitor), answers a turn
  that waited past `eddi.cluster.lease.acquire-timeout` with 409 + `Retry-After` (not a dead
  letter — its input was never consumed), and dead-letters failed turns to a JetStream
  stream every node can read, with the turn's input captured for replay. Both coordinators
  now register `eddi.coordinator.total_dead_lettered` and `eddi.coordinator.dead_letters`.

- **Rolling updates without failed turns** — the MongoDB client was closed by a JVM
  shutdown hook, which runs concurrently with Quarkus' shutdown: SIGTERM closed it the moment
  the graceful drain began, so every turn still running failed with "state should be: open"
  and the drain waited out its timeout for turns that could no longer finish (a single node
  was affected exactly the same way). It is now closed by the bean's `@PreDestroy`, after the
  drain. The drain also brackets the coordinator: `beginShutdown` answers the turns still
  waiting for a conversation lease with 409 + `Retry-After` at once, and `completeShutdown`
  releases the leases still held so those conversations move on without waiting out the TTL.
  Found by the live rolling-restart demo.
- **The node RPC and the event bus actually start** — both were `@Typed` to their own class,
  which hid them from `ClusterBootstrap`'s `Instance<ClusterStartable>`, so neither ever
  subscribed: cross-node calls answered "no responders" and no node consumed another's
  events. The unit tests call `startCluster()` themselves and could not see it;
  `ClusterStartableTypingTest` now checks every startable's bean types. Found by the live
  cross-node cancel demo.
- **Replicas can boot together on PostgreSQL** — every PostgreSQL store creates its tables
  with `CREATE TABLE IF NOT EXISTS`, which is not safe against a concurrent copy of itself:
  three replicas starting against an empty database (a Deployment's first install) raced, and
  the loser died on `duplicate key value violates unique constraint "pg_type_typname_nsp_index"`.
  [`PostgresStartupSchemaLock`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresStartupSchemaLock.java)
  holds an advisory lock from the first to the last startup observer, bounded by
  `eddi.datastore.postgres.startup-lock-timeout`. Found by the live PostgreSQL demo.
- **Discarding a dead letter twice answers 404** — a replicated (R3) stream reports an
  already-deleted entry as 10043 ("sequence not found"), which surfaced as a 500. The stream
  itself is provisioned asynchronously after connecting, so its first use can come first:
  a missing stream now reads as empty (a GDPR erasure reported its dead-letter step failed),
  and the first dead letter creates it instead of being lost.

### Design decisions

- Execution stays on the receiving node (SSE, caller identity, live group discussions and
  HITL are unchanged); only mutual exclusion moved to NATS. Routing turns to an owner node
  was rejected — it would have meant serializing caller identities and every response channel.
- In-memory replay keeps its coordinator contract (remove the entry) so its tests stay
  untouched. Every dead letter now carries the turn's input, which is what lets the admin
  replay in the follow-up `feat/nats-cluster-state` rebuild the turn instead of only
  dropping the entry.
- Shared state beyond leases and dead letters (caches, rate limits, nonces, audit
  sequences, cross-node cancel/GDPR/HITL) follows in `feat/nats-cluster-state`; Helm,
  Kubernetes, Compose and the demo in `feat/nats-cluster-deploy`.

## NATS cluster mode, independent review: lease, fence and dead-letter fixes (2026-10-03)

**Repo:** EDDI · **Branch:** `review/p11a` (on `feat/nats-cluster`)

An adversarial review of the runtime-selectable cluster mode (leases, fencing, dead
letters) turned up eight defects. Each is fixed here with a test that fails without the fix.

### What changed

- **A fence is raised when a turn starts, not only when it writes** —
  [`IConversationMemoryStore.raiseFence`](../../src/main/java/ai/labs/eddi/engine/memory/IConversationMemoryStore.java)
  (MongoDB `$max`, PostgreSQL guarded `jsonb_set`; neither touches the revision) runs from
  [`ConversationStepRunner`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java)
  and the HITL resume as soon as the lease is bound. The stores refuse a write whose token is
  older than the stored `_fence`, but the stored value only moved when some write landed: a
  holder whose lease had expired (a paused JVM, a pipeline thread the watchdog had abandoned)
  could still write in the gap between its successor acquiring the lease and the successor's
  first write, persisting a turn that never saw the successor's. Found by pausing a node's
  container past the lease TTL.
- **A turn that lost its lease no longer stamps a state on the conversation** — it was cancelled,
  and the cancel path then moved the conversation to `EXECUTION_INTERRUPTED` by an unfenced state
  write, so a conversation whose successor turn had just committed read as interrupted until its
  next turn. The cancelled turn now leaves the state to whoever holds the lease. Found live by
  pausing a node past the lease TTL.
- **A holder is no longer robbed because its presence record could not be read** —
  [`KvLeaseManager`](../../src/main/java/ai/labs/eddi/engine/cluster/lease/KvLeaseManager.java)
  treated an unreadable presence bucket (a different bucket from the leases, so it can fail
  alone) as "node gone" and, for any lease older than three presence intervals by the
  *holder's own clock*, deleted a live lease by compare-and-set — so any turn longer than 30 s
  could be taken over under a presence blip or a skewed clock. An unreadable lookup is now
  unknown, and a holder with no presence record is taken over only after this node has watched
  the lease revision stay unchanged for three heartbeat intervals (a live holder rewrites it
  every one).
- **A release racing the heartbeat no longer holds the conversation for the whole TTL** — the
  release deleted at the last revision it knew; if the heartbeat had renewed the key between,
  the delete missed and the key stayed until it expired (20 s without a turn on that
  conversation anywhere). The release now re-reads and deletes its own key at the current
  revision.
- **Two deployments on one NATS no longer share the dead-letter stream** — every other
  stream and bucket is named after `eddi.nats.prefix`, the dead-letter stream was not, so a
  second prefix rewrote the first one's stream subjects on every reconnect. The default name
  now follows the prefix (`EDDI_DEAD_LETTERS` stays for the default prefix; an explicit
  `eddi.nats.dead-letter-stream-name` is used as given).
- **Discarding a dead letter by sequence can no longer delete an audit dead letter** — the
  stream also holds audit-ledger entries that could not be stored; `get` already hid them,
  `delete` did not.
- **NATS credentials in a server URL are not logged** — `nats://user:secret@host` is logged as
  `nats://***@host`.
- **The lease wiring of a turn has a unit test** —
  [`ConversationStepRunnerLeaseTest`](../../src/test/java/ai/labs/eddi/engine/internal/ConversationStepRunnerLeaseTest.java)
  covers the fence token on the memory, the raise, the lost-lease cancel and the unfenced paths;
  removing the wiring used to fail nothing below the integration test.

### Design decisions

- The raise costs one small update per clustered turn (none in `in-memory` mode, where the
  default `raiseFence` does nothing and no lease is ever bound).
- It does not bump the revision, so the turn that is about to write on the revision it loaded
  does not conflict with it.

### Fixed after the review

- **A recreated leases bucket no longer locks conversations out.** The fence is the lease's KV
  revision, and a recreated `<prefix>_LEASES` bucket (after NATS lost its data, or to change its
  replica count — which the log line suggested) restarted at 1, below every `_fence` already
  stored: every write to those conversations was refused for good. A new leases bucket is now
  created with its first sequence at its creation time in microseconds
  ([`NatsSharedStateFactory`](../../src/main/java/ai/labs/eddi/engine/cluster/NatsSharedStateFactory.java)),
  so a later bucket starts above everything an earlier one handed out unless that one averaged
  more than a million lease operations a second over its whole life. The replica-count log
  line now says recreating it is safe, and how. `ClusterCoordinatorIT` deletes and recreates
  the bucket against a real NATS server and checks the next fence is higher. A bucket created by
  an earlier build still counts from 1 until it is recreated once.
- **Dead letters kept on a node while NATS was down are forwarded on reconnect** to the shared
  stream, so every node lists them and they survive that node's restart.
- **PR #956 review fixes.** A turn whose lease binding throws is now answered, its lease
  released and the queue drained, instead of wedging the conversation. A dead letter of a turn
  flagged `secretInput` keeps neither the input nor its context (it is marked and cannot be
  replayed). Paging dead letters past a node-local entry no longer repeats the local ring. An
  outbox flush that hits a closing connection puts the event back instead of dropping it, and
  flushed events count in `eddi.cluster.events.published`. Node RPC tolerates one unreadable
  scatter reply and logs a failed subscribe instead of throwing into the connect listener.
  `ClusterCoordinatorIT` picks a free host port instead of a fixed 4319.
- **CodeRabbit reviews stacked PRs.** A new [`.coderabbit.yaml`](../../.coderabbit.yaml) lets auto-review run on PRs into any base branch, not only `main`, because the stacked NATS PRs were being skipped as "base/target branches other than the default branch".

- **A turn stopped by a lost lease is dead-lettered and its caller told (console live re-check).** When a node lost a conversation's lease mid-turn, whether through a partition, a long pause, a NATS blip or an administrator force-releasing it, the turn was cancelled at its next task boundary. The client still got HTTP 200, but the turn was neither stored nor dead-lettered: it never reached a write. Now it is dead-lettered with its input (none for a `secretInput` turn), with `reason: lease-lost` and its fencing token added to the dead letter's turn record, and `failedOn` naming the node. If the reply has not gone out yet, the caller is answered as not processed: 409 with `Retry-After` over REST, an error event on a stream. The turn still stamps no state.

- **The first dead letter of a new cluster reaches the shared stream.** It was seen live while checking the lease-lost fix. The dead-letter stream is created by the first dead letter that needs it, and a publish right after the creation got "no responders" while the replicated stream elected its leader. The entry fell back to the node-local ring, and that ring was forwarded only on a reconnect, so it stayed on one node. Now the publish after a creation retries for up to 2 s, and a dead letter kept locally while NATS is connected is forwarded again after 10 s.

- **A lost KV race is a conflict whichever code NATS answers with (ported from the console branch).** The loser of two concurrent creates of one key can get JetStream error 10164 as well as 10071. `NatsSharedKv` recognised only 10071, so two nodes racing for one lease could report NATS as unreachable. Create, compare-and-set and guarded delete now treat both as a lost race. `ClusterPresence.currentMembers()` reads the member list uncached and throws on a read error, for decisions that must not be taken on a stale or empty list; `members()` keeps answering from its cache.

- **A dead letter is stored once (PR #956 review).** Every attempt to store one dead letter now carries one JetStream `Nats-Msg-Id`: the retries after a stream creation, and a later forward from the node-local ring. The id is derived from the node, conversation, time and error. The dead-letter stream de-duplicates within a 2-minute window, so a publish the server stored but whose acknowledgement was lost is not stored a second time. `ClusterCoordinatorIT` checks it against real NATS.

### Known and not fixed here

- A fenced-out turn's caller still gets the reply it was rendered (HTTP 200), where the design
  says 409: the output is handed to the caller from inside the pipeline, before the write the
  fence then refuses — the same path the pre-existing concurrent-modification refusal takes, and
  changing it means holding every reply until its write commits. The turn is dead-lettered with
  its input and counted in `eddi.cluster.fence.rejected`, which is the signal.
