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

### Known and not fixed here

- A fenced-out turn's caller still gets the reply it was rendered (HTTP 200): the output is
  handed to the caller from inside the pipeline, before the write that the fence then refuses.
  The turn is dead-lettered with its input and counted in `eddi.cluster.fence.rejected`, which
  is the signal. While NATS is unreachable that dead letter stays in the node-local ring.

- The fence is a KV revision. Recreating the `<prefix>_LEASES` bucket (the replica-count log
  line suggests it) restarts the sequence below the `_fence` already stored in conversations;
  every turn on those conversations is then refused as fenced. Until that is solved in the
  token itself, remove `_fence` from the affected documents after recreating the bucket.
