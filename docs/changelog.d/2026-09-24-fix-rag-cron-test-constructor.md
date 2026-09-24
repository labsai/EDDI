## 🐛 fix(test): RestImportServiceRagCronTest passes the source-policy constructor args (2026-09-24)

**Repo:** EDDI (`feat/group-discussion-overview`, a separate commit so it can be cherry-picked to `main` by itself)

### What

`main` stopped compiling its test sources. Two commits landed independently:
`b8814bf8a` (fix(rag): arm ingestion schedules) added `RestImportServiceRagCronTest`, which
builds a `RestImportService` with twelve constructor arguments, and `52c85736b` (fix(backup):
make agent sync work more than once) added three more to the constructor:
`requireHttpsSource`, `allowPrivateSources` and `allowedSources`. `52c85736b` updated every
caller it could see. The new test was not one of them, because the two branches never saw each
other before merging. Each commit was green on its own branch, and together they fail
`testCompile`. That fails every unit test in the module, not just this one.

The test now passes `true, false, Optional.empty()`, the same production defaults its sibling
tests use. It exercises cron arming only, so the source policy has no effect on it.
