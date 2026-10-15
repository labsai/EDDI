## 🐛 fix(monitoring): one panel id per panel in the full metrics dashboard (2026-10-13)

**Repo:** EDDI (`fix/metrics-dashboard-panel-id-clash`)

### What changed and why

`main`'s Build & Test went red after #1000 and #951 merged in the same batch: each
added a panel with id 182 to the full metrics dashboard ("Idempotent turn
duplicates by outcome" and "Files refused by upload source"). Grafana keys panel
state, links and repeats on the id, so two panels with one id behave as one.
`MetricsDashboardCoverageTest.panelIdsAreUnique` caught it, and
`DeploymentManifestsTest.splitDashboardPartsAddUpToTheSource` failed with it
because the Kubernetes halves were no longer the source's panels.

The idempotent-turns panel is now id 201, the next free id. The Kubernetes
halves are regenerated with `scripts/sync-monitoring-assets.py`. Nothing
referenced panel 182 by id.

**Files:** [`eddi-full-metrics-dashboard.json`](../monitoring/eddi-full-metrics-dashboard.json),
`k8s/overlays/monitoring/dashboards/eddi-full-metrics-dashboard-{1,2}.json`.
