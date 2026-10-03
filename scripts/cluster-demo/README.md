# Cluster demo

Builds the cluster topology of [Clustering](../../docs/clustering.md) in Docker and runs every
failure scenario that page describes against it, each with a PASS/FAIL verdict and the numbers
behind it.

```
             127.0.0.1:7230  nginx, round robin, no affinity
                   │
   ┌───────────────┼───────────────┐
 eddi-1 :7211   eddi-2 :7212   eddi-3 :7213     one build of this branch, -Xmx768m each
   └───────┬───────┴───────┬───────┘
     nats-1..3 (R3, password)   MongoDB :27310 or PostgreSQL :54410
                   │
          mock LLM on the host :18210 (logs every request)
```

The three EDDI nodes run the **published** `labsai/eddi` image with your locally built
`target/quarkus-app` mounted over `/deployments`, so the demo exercises exactly one build inside
the shipped runtime. Nothing is published beyond `127.0.0.1`.

## Run it

Needs Docker, Python 3.10+ and a build of this checkout. It pulls `labsai/eddi:6.5.0`,
`nats:2.11-alpine`, `natsio/nats-box:0.16.0`, `nginx:1.27-alpine` and `mongo:7.0.14` or
`postgres:16-alpine`; the host needs about 5 GB of free memory.

```bash
./mvnw package -DskipTests -DskipUi=true
export EDDI_APP="$PWD/target/quarkus-app"

# everything, MongoDB; tears the stack down afterwards
python scripts/cluster-demo/demo.py

# the same on PostgreSQL
python scripts/cluster-demo/demo.py --db postgres

# a subset, keeping the stack up between runs
python scripts/cluster-demo/mock_llm.py scripts/cluster-demo/mock-requests.jsonl 18210 &
python scripts/cluster-demo/demo.py --external-mock --keep --only 1
python scripts/cluster-demo/demo.py --reuse --only 2,3

# the before picture: the stock image, which has no cluster mode
python scripts/cluster-demo/demo.py --baseline
```

Each run writes `results-<db>[-s<scenarios>].json` and the three nodes' logs (`logs-<db>…/`)
next to the script and prints one line per scenario: `PASS`, `FAIL`, or `N/A` with the reason
when a scenario cannot be driven through the public API. `--app` defaults to `$EDDI_APP`, then to
this checkout's `target/quarkus-app`.

`/mcp` is routed by client address in `nginx-demo.conf`: an MCP session lives on the node that
opened it (see [Clustering](../../docs/clustering.md#residual-limitations)).

## Scenarios

| # | What it does | Passes when |
|---|---|---|
| 1 | 18 concurrent slow turns and 120 instant turns of one conversation, spread over the three nodes | The mock LLM saw no two calls of the conversation overlap, every call carried the previous call's user message, nothing stored twice or lost |
| 2 | `docker kill` (SIGKILL) of the node running a slow turn, then a turn on another node | The next turn succeeds once the lease expires (≤ `eddi.cluster.lease.ttl`), the killed turn is not stored, the conversation is `READY` |
| 3 | Stop one NATS node, then all three, then start them again | One node down: nothing visible. All down: every node reports `degraded`, readiness stays UP, turns keep working. Afterwards all nodes reconnect |
| 4 | Restart the three nodes one after another (graceful, 70 s) under continuous load through nginx | No request fails other than 409 with `Retry-After`, no accepted turn is lost, HITL crash recovery never touches a live conversation |
| 5 | Cancel a running turn through another node; GDPR-erase a user on node 1 while their turn runs on node 2 | The turn stops on the node that runs it |
| 6 | Undeploy on node 1 | Nodes 2 and 3 stop serving the agent within seconds; a redeploy propagates too |
| 7 | Rotate a vault secret on node 1 | Node 3's next LLM call carries the new key (seen in the mock's `Authorization` hash) |
| 8 | Replay a nonce | `N/A`: signed envelopes are produced and checked inside EDDI only, so no replay can be sent from outside; the scenario lists the shared buckets and names the unit tests that cover it |
| 9 | 15 tool calls across the nodes against a global limit of 10/min | Exactly 10 allowed cluster-wide |
| 10 | A paginated tool response stored on node 1 | Its page 2 is fetched through node 2 |
| 11 | HITL pause on node 1 | Resumed through node 2 |
| 12 | Audit chains of the conversations above, written by three nodes | `/auditstore/verify` reports no break, no sequence-collision warning |
| 13 | A zombie write: the lease is deleted under a running turn and another node takes the conversation | The late write is fenced and dead-lettered; every node lists it; replay on node 3 stores it; discard answers 204 then 404 |
| 14 | `--baseline`: the stock image with three nodes | Shows the overlapping turns cluster mode prevents |

The mock LLM (`mock_llm.py`) answers in OpenAI format and logs each request with its start and
end time, message count, previous user message and a hash of the `Authorization` header. A user
message containing `nap` takes 2 s, `slow` 70 s; `usetool:<name>:<json>` makes it call a tool.
