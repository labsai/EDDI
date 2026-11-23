## ✨ feat(org): carry the org across the runtime and coordinator hand-offs (organizations Phase 1, package 5a) (2026-11-23)

**Repo:** EDDI (`feat/org-5-background-binding`, stacked on `feat/org-4-outside-resources`)

### What changed and why

Plan §6.3. An org binding is a `ScopedValue`, so work handed to another thread loses it. This
package carries it through the two chokepoints that most work passes through. It edits the
implementations, not the call sites. In single mode every binding is `default`, so behaviour does
not change.

- **`BaseRuntime.submitCallable`** wraps each submission with `OrgPropagation`. The work and
  its completion callbacks then run with the submitter's binding, on the worker pool, the turn
  pool and the nested pool alike.
  - All eight callers submit bounded work: a turn, a deployment, one API call, a parse, a HITL
    resume, a cleanup job. None of them starts a long-lived task, so nothing outlives the
    request that bound it.
  - A worker thread keeps no binding after the task.
- **The conversation coordinators capture at enqueue.** `submitInOrder` wraps each task in
  `OrgCapturedTask` with the enqueuer's binding.
  - A queued turn is submitted later, from the thread that finished the turn before it or that
    received the cluster lease. That thread is not the requester's. The coordinator therefore
    submits under the captured binding, and the runtime carries that binding on.
  - The runtime still receives the original task, and the coordinators read the marker
    interfaces (`IDescribedTask`, `ILeaseAwareTask`, `IDiscardableTask`) from it. So dead-letter
    descriptions, lease binding and discard hooks work as before.
  - A task captured for one org, submitted from a thread bound to another, is refused
    (`OrgBindingException`). The queue then dead-letters it and moves on.
- **`OrgPropagation.capture()`** returns the current binding as a value, to restore around work
  that is chosen later. The coordinator uses it; streaming callbacks will too.

**Package 5 is split.**
- 5a, this package: the hand-off chokepoints.
- 5b, next: the jobs, startup and deploy paths, and moving the `@Scheduled` jobs off the
  `@OrgBound` classes.
- Later packages: the after-return work (`AsyncResponse`, streaming, SSE, LLM callbacks) and
  the NATS consumers, which need the `Eddi-Org` header of Phase 1 task 6.

**Not in this package:** the 66 classes that hand work off with raw async APIs outside the
chokepoints. `OrgHandOffGuardTest` pins them, and the later packages work through them.

### Tests

- `BaseRuntimeTest`: the work and its callback see the submitter's binding on the worker thread.
  The next task on the same thread sees none.
- `InMemoryConversationCoordinatorTest`:
  - a turn enqueued while bound is submitted under that binding, although the previous turn
    finished on an unbound thread;
  - the runtime and the discard hook get the original task.
- `ClusterConversationCoordinatorTest`: a turn waiting for its lease keeps its enqueuer's binding,
  although the lease arrives on another thread, and the lease reaches the original task.
- `OrgPropagationTest`:
  - `capture()` restores later work, and refuses another org;
  - two capture layers: unbound outside org, platform outside org, and org outside unbound work
    all hold, and two different orgs are refused.
- **`OrgHandOffGuardTest`** (new). A ratchet over raw async APIs. A new class that uses one must
  propagate. A class on the pinned list that starts propagating, or stops handing off, must
  leave the list.
- **Mutation checks:** removing any of these fails a test:
  - the runtime wrap;
  - the enqueue capture;
  - either coordinator's submit-under;
  - any of the three marker reads from the original task.
