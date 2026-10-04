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
- **Fencing tokens and lease revisions are JSON strings** in the console API, exact beyond 2^53.
- **Cluster drawer focus:** an inline `onClose` re-ran the focus-return effect on every refresh and
  pulled focus out of the dialog; Tab no longer counts disabled controls as the last stop.
