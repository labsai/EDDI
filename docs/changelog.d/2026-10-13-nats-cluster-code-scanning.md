## fix(cluster): answer the code-scanning findings on the NATS cluster PR (2026-10-13)

**Repo:** EDDI · **Branch:** `feat/nats-cluster` (PR #956)

### What changed and why

- **Log injection (CodeQL).** `NatsClusterRpc.call` (operation and node id),
  `GroupConversationService` (the lease-less group discussion warning) and
  `AgentDeploymentManagement.awaitClusterDeployment` (agent id, twice) now pass the
  caller-supplied value through `LogSanitizer.sanitize`, like the rest of the codebase.
- **Unused lambda parameters.** The two `whenComplete` callbacks in `RestClusterAdmin`
  that only close the SSE sink use unnamed parameters (`(_, _)`). The `StartupEvent`
  parameter of `AgentDeploymentManagement.subscribeToCluster` stays: a CDI observer
  method needs it, and Java does not allow an unnamed method parameter.
- **Cluster demo scripts.** `scripts/cluster-demo/demo.py` and `mock_llm.py` open their
  files in `with` blocks, drop three unused locals (keeping the `start_conv` call whose
  side effect the undeploy scenario needs), comment their deliberate `except: pass`
  blocks (the log export reports a failed save on stderr), and no longer mix value
  returns with fall-through returns. `mock_llm.py` gains
  a `main()` that holds the request log open for the life of the server.

- `RestClusterAdmin` closes the activity stream after its last `busy` or `expired` event through one `closeAfter` helper. A failed send is logged at debug and the sink is still closed, so the callback no longer declares parameters it ignores.

Behaviour is unchanged apart from two diagnostics: a failed final SSE event is logged at debug, and the cluster demo prints a failed node-log export to stderr.
