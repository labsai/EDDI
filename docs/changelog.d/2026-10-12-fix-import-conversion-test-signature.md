## 🐛 fix(test): import conversion test reflected on a stale readResource signature (2026-10-12)

**Repo:** EDDI (`fix/import-conversion-test-signature`)

`main` went red after #997 merged. #992 added `RestImportServiceTemplateConversionTest`, which reaches the private
`RestImportService.readResource` by reflection with five parameters. #997, merged afterwards, gave that method a sixth
(`Set<String> v5LlmUris`), so both tests failed with `NoSuchMethodException`. Each PR passed on its own; only the
combination broke, and every PR that merged `main` inherited the failure.

The test now asks for the six-parameter method and passes an empty mutable set, which `readResource` may record into.

**Files:** [`RestImportServiceTemplateConversionTest.java`](../../src/test/java/ai/labs/eddi/backup/impl/RestImportServiceTemplateConversionTest.java)
