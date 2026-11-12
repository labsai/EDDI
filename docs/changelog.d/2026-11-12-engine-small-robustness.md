## 🐛 fix(engine): dead-letter payload JSON, logSizeLimit parsing, unreadable Postgres deployment rows (2026-11-12)

**Repo:** EDDI (`fix/engine-small-robustness`)

### What changed and why

Three small robustness fixes, independent of each other.

- **Dead-letter payload is built with Jackson.** The node-local dead-letter ring
  (the in-memory coordinator, and the cluster coordinator's fallback while NATS is
  unavailable) built each entry's `payload` with `String.format`, escaping only
  double quotes. A failure message with a backslash or a newline gave invalid JSON,
  and one carrying `\"` could close the string early and add fields of its own. The
  payload is now an `ObjectNode` written by a plain `ObjectMapper`, as
  `JetStreamDeadLetterStore` writes its messages; the field names and types
  (`conversationId`, `error`, `timestamp` as a number) are unchanged. The error text
  of a ring entry (its `error` field and the payload) now goes through
  `SecretRedactionFilter.redact` first, because an exception message routinely
  carries a resolved URL or a provider error body and the entry is served by the
  dead-letter REST endpoints. The JetStream path (`ClusterConversationCoordinator` →
  `JetStreamDeadLetterStore.append`) still stores the raw message; it is not
  changed here.
- **`logSizeLimit` no longer fails the turn on a bad value.** `LlmTask` parsed the
  templated parameter with a bare `Integer.parseInt`, so `"ten"`, `"3.5"` or an
  out-of-range number threw `NumberFormatException` out of the task on every turn.
  The value is now trimmed; blank falls back silently, and a non-integer or a number
  below `-1` falls back with one WARN naming the agent, the conversation and the
  (sanitised) value. The fallback is unchanged: the task's
  `conversationHistoryLimit`, else `-1`. `-1` (unlimited) and `0` keep their
  meaning. [`langchain.md`](../langchain.md) now says so.
- **One bad row no longer breaks reading every Postgres deployment.**
  `PostgresDeploymentStorage` mapped rows with `Enum.valueOf`, so a single row with
  an unknown `deployment_status`, or a v5 environment name (`unrestricted`,
  `restricted`), threw `IllegalArgumentException` out of `readDeploymentInfos` —
  the read the startup redeploy depends on. The environment is now read with
  `Environment.fromString`, the lenient reader `MongoDeploymentStorage` uses through
  Jackson (v5 names map to `production`; an unknown name falls back to `production`
  with a WARN). A row whose status is unknown or null is skipped with a WARN naming
  the agent id, version and value; the point read returns no row for it. A status
  is never guessed, since reading it as `deployed` or `undeployed` would deploy or
  drop an agent nobody asked for. Note that the MongoDB twin still fails the whole
  read on an unknown status (Jackson raises `InvalidFormatException`, surfaced as
  `ResourceStoreException`); that is not changed here.

### Design decisions

- No new redactor: the dead-letter error text uses the same `SecretRedactionFilter`
  rules the logs and the snapshot redaction use.
- For the Postgres environment column the twin's behaviour is matched, not
  "skip": that is the documented read-back contract of `Environment.fromString`.

**Files:**
[`AbstractQueuedConversationCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AbstractQueuedConversationCoordinator.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`PostgresDeploymentStorage.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresDeploymentStorage.java),
[`langchain.md`](../langchain.md); tests in
[`InMemoryConversationCoordinatorTest.java`](../../src/test/java/ai/labs/eddi/engine/runtime/internal/InMemoryConversationCoordinatorTest.java),
[`LlmTaskTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/LlmTaskTest.java),
[`PostgresDeploymentStorageUnitTest.java`](../../src/test/java/ai/labs/eddi/datastore/postgres/PostgresDeploymentStorageUnitTest.java).
