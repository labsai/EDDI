## 🔒 fix(security): outbound requests, parsers and templates from the 2026-10-17 review (2026-10-17)

**Repo:** EDDI (`fix/security-review-outbound-parsers`)

### What changed and why

One commit per finding of the 2026-10-17 security review (M8–M11, L13–L16). PR #1079 (`UrlValidationUtils`, `requireNotMetadataHop`, `ModelOutputParser`, `ConversationRecallTool`) is not redone here.

- **M9.1 — group artifact JSON schemas could `$ref` the network or the disk.** `ArtifactValidators` now builds its networknt factory with an `AllowSchemaLoader` in front that admits only the bundled meta-schemas (`classpath:draft/…`, `classpath:draft-0…`); an `http(s):`, `file:` or other `$ref` throws before anything connects or is read. Refs are resolved eagerly at save time, so the save fails with an actionable message, and a ref first reached at write time refuses the write. [`ArtifactValidators.java`](../../src/main/java/ai/labs/eddi/configs/groups/ArtifactValidators.java), [group-conversations.md](../group-conversations.md)
