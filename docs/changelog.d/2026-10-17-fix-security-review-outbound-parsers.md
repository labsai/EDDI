## 🔒 fix(security): outbound requests, parsers and templates from the 2026-10-17 review (2026-10-17)

**Repo:** EDDI (`fix/security-review-outbound-parsers`)

### What changed and why

One commit per finding of the 2026-10-17 security review (M8–M11, L13–L16). PR #1079 (`UrlValidationUtils`, `requireNotMetadataHop`, `ModelOutputParser`, `ConversationRecallTool`) is not redone here.

- **M9.1 — group artifact JSON schemas could `$ref` the network or the disk.** `ArtifactValidators` now builds its networknt factory with an `AllowSchemaLoader` in front that admits only the bundled meta-schemas (`classpath:draft/…`, `classpath:draft-0…`); an `http(s):`, `file:` or other `$ref` throws before anything connects or is read. Refs are resolved eagerly at save time, so the save fails with an actionable message, and a ref first reached at write time refuses the write. [`ArtifactValidators.java`](../../src/main/java/ai/labs/eddi/configs/groups/ArtifactValidators.java), [group-conversations.md](../group-conversations.md)
- **M9.2 — pgvector host injection.** pgjdbc splices server names into its JDBC URL unencoded, so `host = "db/eddi?sslfactory=…&x="` injected driver properties and `a.com,10.0.0.5` dialled a host the metadata guard never saw. `EmbeddingStoreFactory.requirePlainHost` accepts one hostname (RFC 1123 labels, `_` allowed for Docker service names) or IP literal, nothing else, for pgvector and Qdrant. [`EmbeddingStoreFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java), [rag.md](../rag.md)
