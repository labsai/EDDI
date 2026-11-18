## fix(postgres): U+0000 no longer loses data; one lock for lazy schema creation; strict datastore type (2026-11-18)

**Repo:** EDDI (`fix/postgres-nul-schema-and-type`)

### What changed and why

- **U+0000 on PostgreSQL (review E1, live P2).** PostgreSQL rejects the character in `TEXT` and `jsonb`; MongoDB stores it. One NUL in a user message lost the turn, user-memory writes, resources, a whole database-log batch and audit entries. Every PostgreSQL store now wraps its data source with `PostgresSanitizingDataSource` (a proxy at the JDBC boundary), which cleans each string as a statement binds it: U+0000 and unpaired surrogates become U+FFFD, in plain text and in JSON text (escaped `\u0000`, unpaired surrogate escapes), with escaped backslashes honoured. Lookups are cleaned the same way. The pgvector embedding store's connections are wrapped too. Rules and the one-line adoption for a new store: [architecture](../architecture.md#differences-the-postgresql-boundary-hides).

- **One poisoned audit entry no longer takes its batch down (review M2, live: 45 entries undelivered).** `AuditLedgerService.appendIndividually` counted three refusals in a row as "store unavailable", handed every remaining entry to the retry-and-dead-letter path and, after three flushes, dead-lettered unrelated conversations with the poison one. It now classifies the failure (`AuditStoreFailures`: SQLState class 22/23, JSON/BSON errors, MongoDB document-rejecting codes): such an entry is dead-lettered alone with a `reason` and the rest is stored; everything else is an outage and backs off as before. The dead-letter file is written line by line with a lenient UTF-8 encoder (a lone surrogate used to throw `MalformedInputException` and lose the line and everything after it). For PostgreSQL the ledger makes an entry storable (U+0000 and unpaired surrogates to U+FFFD) before it is signed, so the stored row still verifies (`IAuditStore.replacesNulCharacter`). Not changed: `recordUndelivered` is still memory-only.

### Design decisions

- **Replace with U+FFFD rather than remove.** Removal merges neighbours (`a<NUL>b` and `ab` become the same id) and shifts positions; the replacement character keeps both and is visible.
- **One boundary instead of 900 call sites.** `setString`/`setObject` are called some 900 times across the stores; a proxy over the data source covers all of them and every future store. JSON parameters are recognised by the `::jsonb` cast in the SQL or a `PGobject` of type json/jsonb.

**Files:** [`PostgresText.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresText.java), [`PostgresSanitizingDataSource.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresSanitizingDataSource.java), every `Postgres*Store` constructor, [`EddiPgVectorEmbeddingStore.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EddiPgVectorEmbeddingStore.java).
