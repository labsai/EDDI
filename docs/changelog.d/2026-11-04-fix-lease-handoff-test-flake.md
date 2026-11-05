## 🐛 fix(cluster): a finished turn could take back the lease it had just handed on (2026-11-04)

**Repo:** EDDI (`fix/lease-handoff-test-flake`)

### What changed and why

`ClusterConversationCoordinatorTest.leaseIsHandedToTheNextQueuedTurn` failed intermittently in CI
(`one acquire for the whole queue ==> expected: <1> but was: <2>`). It was not the test: the local
lease handoff added in #1240 had a real race.

`releaseOrHandOver` parks the lease in `handoffs`, calls `submitNext` (whose `execute` takes it for the
next queued turn), and in its `finally` removes "its" parking with `handoffs.remove(conversationId, lease)`.
But a handed-over lease is the *same* `LeaseHandle` object for every turn in the chain. Once `submitNext`
has handed it on, turn N+1 can run to its end and park that same lease for turn N+2 before turn N's thread
reaches its `finally` — which then matched N+1's parking, released the lease, and left turn N+2 to acquire
a fresh one. In production that is the extra NATS round trips and the window for another node to take the
conversation that the handoff exists to avoid.

Each parking is now its own `Handoff` object (a private class with identity `equals`, deliberately not a
record), and the `finally` removes only the parking this call made.

Reproduced before the fix with the test repeated 1000 times: 13 failures. After it: 0 failures in 3000.

**Files:** [`ClusterConversationCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ClusterConversationCoordinator.java)

```regression-note
| 2026-11-04 | `leaseIsHandedToTheNextQueuedTurn` flaky: a queued turn acquired a second lease | `releaseOrHandOver`'s cleanup matched the lease, which every turn of a handoff chain parks again, so a finished turn removed and released the next turn's parking | Park a per-call `Handoff` token compared by identity | fix/lease-handoff-test-flake |
```
