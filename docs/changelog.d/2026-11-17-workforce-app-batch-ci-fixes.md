## The combined frontend batch's CI failures, fixed (2026-11-17)

**Repo:** EDDI · **Branch:** `feat/workforce-app` (PR #1285, the batch of #1285 #1287 #1307 #1308 #1310 #1315 #1317 #1331 #1333 plus `main`)

Each PR was green on its own; merged together, these broke because one PR moved a file or added a meter that another PR, or `main`, still referred to by its old place.

**What changed**

- **Full Metrics Reference dashboard** (`docs/monitoring/eddi-full-metrics-dashboard.json`, regenerated k8s copies). The User event stream row reused panel id 450, which the Team Budgets row's "Spend-control changes" panel already had. The row and its panels now use 454–457. A new panel, 458 "Cross-node relay (cluster mode)", charts `eddi_cluster_events_transient_published_total` and `eddi_cluster_events_transient_dropped_total`, the core-NATS relay of user events. The meters had been registered without a panel, which `MetricsDashboardCoverageTest` rejects.
- **`SetupPromptCapMirrorTest`** now reads `SETUP_MAX_SYSTEM_PROMPT_LENGTH` from `ui/packages/app-kit/src/lib/api/agent-setup.ts`, where the app-kit move put it. The Javadoc of `AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH` names the new path too.
- **Chat UI test**: the test "ignores tool_result … the status stays" was written for the pre-merge widget. Under the merged controller (`@eddi/conversation`, `state.ts` `activeTool`), a `tool_result` clears the tool line and returns to "Thinking…", and its neighbour "clears the tool status when its tool_result arrives" already pins that. The test now checks the same thing, and still checks that nothing of the payload reaches the page.
- **Backend E2E**: `brand-leak.fullstack.spec.ts` read `RESOURCE_TYPES` through `new URL("@eddi/app-kit/lib/api/resources", import.meta.url)`. The mechanical `@/` → `@eddi/app-kit/` rewrite had turned that module specifier into a file path that does not exist. It now reads `ui/packages/app-kit/src/lib/api/resources.ts`.
- **Operator Activation E2E** (`ci.yml`): the job still ran `ui/manager/scripts/install-playwright-chromium.mjs`. The script now lives in `ui/scripts/`, so the job runs `../scripts/install-playwright-chromium.mjs`, as the other E2E jobs do.
- **CodeQL**: two new alerts, both in moved files, are fixed.
  - `js/polynomial-redos` in `property-value-utils.ts`: `DECIMAL_NUMBER` is now `^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$`. Fraction digits can follow only a literal `.`, so no run of digits can be split between two quantifiers. It accepts the same inputs as before.
  - `js/incomplete-sanitization` in `ui/packages/core/src/boundaries.test.ts`: the `exports` pattern target replaces every `*` (`split("*").join(…)`), which is also Node's rule.

**Not changed**: `MongoClusterE2EIT.streamLeaderFailoverUnderLoad` failed with `LOST=118`. That is a failure mode of stream-leader failover that predates this branch, not a result of the transient-event subscription. The same test failed with `LOST` on branches without that subscription (runs 43730561735 and 43740262094). In this run the conversation's stored fence (a `KV_EDDI_LEASES` revision) ended up above every revision the bucket handed out after a leader step-down, so every later write was refused as fenced, while the client had still been answered 200. That needs its own fix in the lease and fencing code.
