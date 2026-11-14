## 🐛 fix(health): a rating deferred during a drain's mark is not removed with it (2026-11-14)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Review finding on PR #1389. `HealthOutcomeEvaluator.drainDeferredRatings` stored a deferred conversation's mark and then
removed the conversation from the deferred set. A new rating deferred in between was lost with it. This happens when a
pass judges the session and clears its due mark while the drain is storing, and the rating arrives with the hand-off
queue full: `deferRating` coalesced it into the entry the drain then removed.

The deferred set is now a map from conversation to a generation that each deferral increments. The drain removes an
entry only while it still holds the generation read before marking (`remove(key, value)`), so a later deferral stays
for the next drain. New test: `HealthOutcomeEvaluatorTest.drainKeepsARatingDeferredDuringTheMark`.

## 🐛 fix(health): the outcome feed's direct retries rotate across passes (2026-11-14)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Review finding on PR #1389. The direct retries of failed feed conversations always started at the oldest capture. If
that retry used up the replay's share of the pass every time, a later failure behind the watermark was never reached,
by the retries, the replay or the forward feed, until it was given up at `MAX_RETRY`. Each pass now starts its retries
after the conversation the previous pass retried last (`lastRetried`, per node), wrapping around. New test:
`HealthOutcomeEvaluatorTest.retriesRotateAcrossPasses`.

## 🐛 fix(backup): a ZIP upgrade refuses invalid agent settings before it creates a connection (2026-11-14)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Review finding on PR #1389. `UpgradeExecutor` checked the settings it adopts before its own first write, but a ZIP
upgrade (`RestImportService.executeUpgradeFromZip`) imports the archive's connections before it calls the executor. A
refusal there could only roll them back on a best-effort basis, and a failed delete left a connection behind. The new
`UpgradeExecutor.checkAdoptedSettings` builds the same preview and runs the same checks, and the ZIP path calls it
before `importConnections`. So "nothing is written" now holds for that path too. A sync imports no connections and
keeps relying on the executor's own check. Tests: `RestImportServiceConnectionsTest.upgradeRefusedSettingsCreateNoConnection`
and `UpgradeExecutorSyncTest.checkAdoptedSettingsBeforeAnyWrite`.
