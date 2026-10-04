## Cluster console review: exactly-once replay, atomic drain, viewer privacy, admin SSE slots (2026-10-04)

**Repo:** EDDI · **Branch:** `feat/nats-cluster-console` (review follow-up)

- **The read-only viewer no longer sees who an administrator is** in the activity timeline
  (`an administrator` instead of the principal name), nor the error text of a dead letter (an
  exception message can quote user input or a configuration value); `IRestClusterAdminRoleGateTest`
  pins the exact set of endpoints open to `eddi-viewer`.
- **Replay runs a dead letter at most once.** Two administrators replaying one entry both ran
  its turn (get, say, delete). The entry is now claimed first — a create of `replay.<id>` in the
  new shared `<prefix>_ADMIN` KV bucket (TTL 60 s), which only one caller wins — and read only
  after the claim; the loser gets `IN_PROGRESS`. The entry is deleted before the claim is dropped,
  so a late replay finds it gone. Nothing is lost: a rejected turn keeps the entry, a dead node's
  claim expires.
- **Draining is decided one at a time, cluster-wide.** The last-node guard counted drained nodes
  from presence records up to 5 s old, so two administrators could drain the last two nodes at
  once. A `gate.drain` create in the same bucket serialises drain decisions, and the guard counts
  the `drain.<node>` keys (kept by each drained node, removed on undrain or restart) instead of
  presence; a second decision while one is being applied answers `409 BUSY`.
- **Viewers cannot lock administrators out of the live feed:** at most 12 of the 16 activity
  streams per node go to read-only callers. A stream is closed when the access token it was opened
  with expires (`expired` event); the Manager reopens it with the refreshed token.
- **A clean shutdown is said, not guessed:** a leaving node writes a `left.<node>` marker before it
  removes its presence record; a record that vanishes without one is `LOST` (timing alone misread
  a clean shutdown under scheduler jitter).
- **Found on the live re-check, fixed:** the service tested the injected coordinator with
  `instanceof ClusterConversationCoordinator`, but it is the producer's client proxy — so the
  shared dead-letter count read 0, the per-conversation dead-letter lookup and paging used the
  single-node path, and *Forward local dead letters* reported 0 with a local entry waiting. It is
  resolved by type now. The first dead letter on a fresh cluster fell back to the node-local ring
  (its stream was still electing a leader: "no responders"); the publish now retries for ~2 s, and
  node-local dead letters are forwarded every 30 s while connected, not only on a reconnect. The
  `ADMIN` bucket is provisioned at start: two nodes creating it on first use made the loser of a
  replay race answer `UNAVAILABLE` instead of `IN_PROGRESS`.
- **Recovery actions report what actually happened.** A cache resync while the node is cut off
  from NATS answered `DONE`, though only this node had flushed and the request sat in the outbox.
  It now answers `QUEUED` and says the others get it on reconnect (`requestResyncAll` reports
  whether it was sent). Reconcile and forward-local answer `PARTIAL` when a member did not answer
  (an RPC reply carrying an `error` no longer counts) or, for forward, when entries are still kept
  locally; `details.missing` and `details.remaining` say which. Drain already refused with a `409`
  rather than half-doing it. The console shows the outcome in words and warns, instead of a
  success toast, for anything but `DONE`; audit and activity carry the same outcome.
- **Dead-letter reasons the console does not know** are shown by their code with a generic
  explanation, no longer as "Failed". `lease-lost` has its label, help text, filter option and
  timeline wording.
- **Merged the deploy branch's lease-lost fix** (`4f221dd9d`). The step runner records why it
  stopped a turn in the turn descriptor (`reason`, `fence`, `storedFence`); the coordinator now
  classifies from that first, so a lease-lost turn is stored with `reason: lease-lost` and its
  token in `fence`, shared or node-local, rather than as `failed`. A stream entry that carries
  the reason only in its turn (written by a node without the classifier) is read back the same
  way. The two branches had each added a retry for the first dead letter on a new stream; the
  deploy branch's is kept, as is its 10 s forward after a local fallback.
- **Losing a KV create race is a conflict, not an outage (found live).** The NATS server answers
  the loser of two concurrent creates of one key with error 10164 as well as 10071; only 10071
  was recognised, so in 8 of 15 live drain races the loser answered `409 NATS_UNREACHABLE`
  instead of `BUSY`, and a double replay could answer `UNAVAILABLE` instead of `IN_PROGRESS`.
  `NatsSharedKv` treats both codes as a conflict for create, compare-and-set and guarded delete,
  which every caller of the shared KV (leases included) relies on. After the fix, 20 of 20 races
  answered `BUSY` or ran one after the other. A failed replay claim or drain gate now logs its
  cause.
- **The audit trail shows what a bulk replay or discard did** ("1 of 2 done", a warning when any
  failed) instead of a blank outcome: those entries record counts, not one outcome.
- **Fencing tokens and lease revisions are JSON strings** in the console API, exact beyond 2^53.
- **Cluster drawer focus:** an inline `onClose` re-ran the focus-return effect on every refresh and
  pulled focus out of the dialog; Tab no longer counts disabled controls as the last stop.
