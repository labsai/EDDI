## 🧪 test(hitl): raise branch coverage of the oversight classes (2026-11-03)

**Repo:** EDDI (`feat/approval-analytics-autonomy`)

### What changed and why

CI's `Integration Tests` job failed the JaCoCo gate (branches covered ratio 0.79, minimum 0.80) because the approval
analytics, sampled audit and earned-autonomy classes landed with poorly covered branches. This adds plain JUnit 5 and
Mockito tests (no container, no Docker) for those classes under `src/test/java/ai/labs/eddi/engine/hitl/oversight/`
and `src/test/java/ai/labs/eddi/configs/hitl/model/`: the autonomy service (reads, evaluation, decision and sample
triggers, lift, dismiss, suspend, apply and revert), the suspended-gate overlay and its service, the promotion patch
builder, analytics grouping and sample joins, the sampled-audit recorder and its sampling key, the write guard, the
GDPR participant, the config lookup, the maintenance schedule and service, the rebuild service, the REST resources, the
metrics and the config model.

No production code changed. The oversight classes' own tests now leave 118 of 1,320 branches uncovered, almost all in
the three database stores that only an integration test can reach.
