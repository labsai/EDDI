## Agent Health dashboard panels renumbered after a main merge (2026-11-16)

**Repo:** EDDI · **Branch:** `feat/agent-health`

`main` added a panel with id 449 to [`eddi-full-metrics-dashboard.json`](../monitoring/eddi-full-metrics-dashboard.json), the id this branch's Agent Health row already used, and `MetricsDashboardCoverageTest.panelIdsAreUnique` failed after the merge. The Agent Health row and its two panels move to 450–452, and again to 451–453 when a later `main` gave id 450 to the spend-control panel; the k8s split parts are regenerated with `scripts/sync-monitoring-assets.py`. Nothing links to these ids.
