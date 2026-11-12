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
  below `-1` falls back with one WARN naming the agent, the conversation, the kind
  of problem and the value's length — not the value itself, which is the rendered
  template and can carry conversation data. The fallback is unchanged: the task's
  `conversationHistoryLimit`, else `-1`. `-1` (unlimited) and `0` keep their
  meaning. [`langchain.md`](../langchain.md) now says so.
- **One bad row no longer breaks reading every Postgres deployment.**
  `PostgresDeploymentStorage` mapped rows with `Enum.valueOf`, so a single row with
  an unknown `deployment_status`, or a v5 environment name (`unrestricted`,
  `restricted`), threw `IllegalArgumentException` out of `readDeploymentInfos` —
  the read the startup redeploy depends on. A row whose status is unknown or null,
  or whose environment is not exactly `production` or `test`, is now skipped with
  a WARN naming the agent id, version and (sanitised) value; the point read returns
  no row for it. Neither value is guessed: reading a status as `deployed` or
  `undeployed` would deploy or drop an agent nobody asked for, and the environment
  is part of the table's primary key, which every later write uses. Note that the
  MongoDB twin still fails the whole read on an unknown status (Jackson raises
  `InvalidFormatException`, surfaced as `ResourceStoreException`), and reads the
  environment leniently through `Environment.fromString`; neither is changed here.

### Design decisions

- No new redactor: the dead-letter error text uses the same `SecretRedactionFilter`
  rules the logs and the snapshot redaction use.
- The Postgres environment column is not read through `Environment.fromString`,
  which maps the v5 names and any unknown name to `production`. A row stored as
  `unrestricted` would then load as `production`: the startup and periodic sweep
  would redeploy it, while the undeploy write and the cleanup delete, keyed on
  `production`, would never reach the stored row — so it could not be undeployed.
  Skipping keeps the in-memory key equal to the stored one. On MongoDB,
  `V6RenameMigration` rewrites v5 deployment rows in place (resolving collisions);
  it does not run on PostgreSQL because EDDI 5 never did. This table was added on
  2026-03-11 and the environment enum renamed on 2026-03-21, both before 6.0.0-RC1,
  so only a database written by a pre-release development build can hold v5 names.
  Before this change such a row threw; now it is skipped and named in the log. A
  Postgres migration for it is a possible follow-up, not part of this fix.

**Files:**
[`AbstractQueuedConversationCoordinator.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AbstractQueuedConversationCoordinator.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`PostgresDeploymentStorage.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresDeploymentStorage.java),
[`langchain.md`](../langchain.md); tests in
[`InMemoryConversationCoordinatorTest.java`](../../src/test/java/ai/labs/eddi/engine/runtime/internal/InMemoryConversationCoordinatorTest.java),
[`LlmTaskTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/LlmTaskTest.java),
[`PostgresDeploymentStorageUnitTest.java`](../../src/test/java/ai/labs/eddi/datastore/postgres/PostgresDeploymentStorageUnitTest.java).
