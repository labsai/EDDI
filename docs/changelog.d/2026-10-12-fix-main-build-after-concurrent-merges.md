## 🐛 fix(test): repair two tests that main's concurrent merges broke (2026-10-12)

**Repo:** EDDI (`fix/main-build-after-concurrent-merges`)

### What changed and why

`Build & Test` on `main` failed with four errors after a batch of PRs merged in
parallel — each was green against the `main` it was opened on, but two of them
broke each other, and with `strict` branch protection off nothing re-ran CI on
the combination. Every open PR's `Build & Test` inherited the failure.

- **`RestImportServiceTemplateConversionTest`** (#992) looks `readResource` up
  reflectively with the five-parameter signature; #997 added a sixth,
  `Set<String> v5LlmUris`, so the lookup threw `NoSuchMethodException`. The test
  now passes an empty set — no document is migrated as 5.x, so only the template
  conversion it is about runs.
- **`RestAgentAdministrationUndeployResilienceTest`** (#990) stubbed
  `IAgentFactory.undeployAgent` to throw the checked `ServiceException`, which
  that method no longer declares; Mockito refuses the stub. It now throws an
  unchecked exception — `RestAgentAdministration` catches any `Exception` there,
  so "a version fails to undeploy" is exercised exactly as before.

Test-only; no production code changes.

**Files:** [`RestImportServiceTemplateConversionTest.java`](../../src/test/java/ai/labs/eddi/backup/impl/RestImportServiceTemplateConversionTest.java),
[`RestAgentAdministrationUndeployResilienceTest.java`](../../src/test/java/ai/labs/eddi/engine/internal/RestAgentAdministrationUndeployResilienceTest.java)

```regression-note
| 2026-10-12 | `Build & Test` red on `main` (4 errors) | #992 and #990 tests written against signatures #997 and another PR changed; PRs merged in parallel with `strict` off, so the combination was never built | Tests updated to the current signatures | fix/main-build-after-concurrent-merges |
```
