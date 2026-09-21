## 📝 docs: add inline documentation for application.properties entries (2026-06-19)

**Repo:** EDDI (`issue-536-inline-docs`)
**What changed:** Added high-level, conceptual inline comments for undocumented properties in `application.properties` to improve configuration clarity and maintainability.

- **File:** `src/main/resources/application.properties` — Added inline comments for 28 previously undocumented properties across runtime, database, caching, HTTP client, NATS, and serialization modules.
- **Rationale:** Standardized documentation tone across the main configuration file, matching the conceptual and functional tone of existing comments while avoiding implementation-specific references that easily drift.

### Follow-up: Address PR #556 review comments (2026-06-30)

**What changed:**
- Fixed comment on line 25: "schema" → "document" — the migration operates on documents, not schemas
- Fixed comment on line 64: `eddi.nats.ack-wait-seconds` is declared but not consumed by runtime code; marked as reserved for future use
