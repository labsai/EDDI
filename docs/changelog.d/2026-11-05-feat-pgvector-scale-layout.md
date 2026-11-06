## ⚡ feat(rag): pgvector v2 layout — HNSW index, indexed jsonb metadata, pooled connections, batched inserts (2026-11-05)

**Repo:** EDDI (`feat/pgvector-scale-layout`)

Slice T2 (pgvector throughput) of the enterprise ingestion plan. `EmbeddingStoreFactory.buildPgVector`
built langchain4j's `PgVectorEmbeddingStore` from host/port/user/password: no vector index (every
retrieval an exact scan), metadata in a `json` column nothing can index (every `removeAll` by document
id and owning source a sequential scan), and a new physical connection for every operation. At ten
million chunks retrieval took seconds and each document replacement scanned the table.

### What changed

- **New `PgVectorSchema`** ([`PgVectorSchema.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/PgVectorSchema.java)) —
  the table layouts and EDDI's own DDL. A table EDDI creates gets the **v2 layout**: `metadata jsonb`,
  an HNSW index on the embedding (`vector_cosine_ops` — langchain4j orders by `<=>`), and b-tree
  expression indexes on `(metadata->>'documentId', metadata->>'sourceKey')` and
  `(metadata->>'sourceKey', metadata->>'runId')`. Index names are unqualified, lower-case and at most 63
  characters (a long table name is shortened with a digest).
- **New `EddiPgVectorEmbeddingStore`** ([`EddiPgVectorEmbeddingStore.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EddiPgVectorEmbeddingStore.java)) —
  a subclass that overrides the protected `getConnection()` to `SET hnsw.ef_search` on every borrowed
  connection, and splits `addAll` into `insertBatchSize`-row batches.
- **New `PgVectorConnectionPools`** ([`PgVectorConnectionPools.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/PgVectorConnectionPools.java)) —
  Agroal pools (already on the classpath through `quarkus-agroal`), one per (host, port, database,
  user, password), keyed on a SHA-256 digest, bounded like the MongoClient cache, `minSize` 0 with a
  two-minute reap so an abandoned pool holds no connection. Closed by `clearCache()` and `@PreDestroy`.
  Max size: new property `eddi.rag.pgvector.pool.max-size` (default 10). The database name is
  URL-encoded into the JDBC URL so it cannot add driver properties.
- **`EmbeddingStoreFactory.buildPgVector`** now parses the new parameters, borrows from the pool,
  creates the `vector` extension only if it is missing (langchain4j ran `CREATE EXTENSION` on every
  connection, which needs CREATE privilege even when it is a no-op), inspects the table, picks the
  layout, builds through langchain4j's `datasourceBuilder()` and then creates the v2 indexes.
- New `storeParameters`: `layout` (`auto`|`v1`|`v2`), `vectorIndex` (`hnsw`|`none`), `hnswM` (16),
  `hnswEfConstruction` (64), `hnswEfSearch` (40), `insertBatchSize` (500). Range-checked; messages name
  the parameter, never the value.
- Docs: [`rag.md`](../rag.md#pgvector-at-scale) (new "pgvector at scale" section, parameter table,
  limits), [`configuration-reference.md`](../configuration-reference.md#knowledge-bases-rag) (pool size).
- Tests: `PgVectorSchemaTest` (parameters, layout resolution, DDL, index names, JDBC URL, batching and
  ef_search against mocked JDBC); `PgVectorScaleLayoutIT` (pgvector/pgvector:pg16) — indexes exist, the
  store's own `removeAll` and search statements use them (plans captured with `auto_explain`,
  `enable_seqscan=off`), a pre-existing json table keeps working untouched, `layout: v1`, pool sharing.

### Design decisions

- **Layout chosen by the table, not by a default on the knowledge base.** `layout: auto` (the default)
  keeps an existing table in the layout its `metadata` column type says it has and creates a missing
  one in v2. A store-parameter default of `v2` would have applied to every existing knowledge base too
  (none carries the parameter), and EDDI would then have built an HNSW index over a production table
  from inside a request — blocking writes for as long as it takes. An explicit layout that contradicts
  an existing table is refused, naming the way out (new table, re-ingest).
- **jsonb + expression indexes, not column-per-key.** Verified in the langchain4j 1.23.0-beta33 jar
  (javap): `JSONFilterMapper` emits `(metadata->>'key')::<type>` for `isEqualTo` and
  `metadata->>'key'` for `isIn`/`isNotIn`; the `::text` cast on a text expression is dropped by the
  parser, so the expression indexes match (the IT proves it on the real statements). Column-per-key
  would also index, but `ColumnsMetadataHandler` silently drops every key without a column — the URL,
  title, source name and loader metadata.
- **EDDI creates the indexes, not langchain4j.** langchain4j's builder offers only IVFFlat, and its
  metadata-index names are `<table>_<key>`, invalid SQL for a schema-qualified table.
- **ef_search per borrow, session-level.** langchain4j runs in autocommit (a `SET LOCAL` would end
  with its own statement) and its only hook is `getConnection()`. Pools are shared across knowledge
  bases, so the value is set on every borrow — one extra round trip per operation.
- HNSW is skipped with a warning above 2000 dimensions (pgvector's limit) and when the server's
  pgvector is older than 0.5.0; the store then works with exact scans, as before.
- **The DDL checks its own inputs (CodeQL `java/sql-injection`).** `PgVectorSchema` no longer relies on
  `EmbeddingStoreFactory.physicalName` alone: `quotedTable` re-checks each part of the (optionally
  schema-qualified) table name against `[A-Za-z_][A-Za-z0-9_$]{0,62}` and emits it as a quoted,
  lower-cased identifier — the same table PostgreSQL resolves langchain4j's unquoted name to — and the
  index names are quoted too. `m`, `ef_construction` and `ef_search` are range-checked ints where they
  meet SQL; the table lookup in `inspect` was already a bind parameter.
- **A store holds a handle, not a pool.** `PgVectorConnectionPools.get` returns a `DataSource` that
  looks the login's pool up on every `getConnection()`, so a size eviction cannot strand a cached store
  or a running ingestion: the next borrow reopens the pool, and each borrow counts as use of it. An
  evicted pool with connections still borrowed (Agroal metrics, `activeCount()`) is retired rather than
  closed under them, and closed on a later lookup once they are returned; `clearCache()` and
  `@PreDestroy` still close every pool. Tested in `PgVectorConnectionPoolsTest` with fake pools.
- **An unqualified table is inspected in `current_schema()`**, where langchain4j's
  `CREATE TABLE IF NOT EXISTS` creates it, not wherever the `search_path` first finds the name — with
  `search_path = myschema, public`, `public.docs` would otherwise decide the layout of the new
  `myschema.docs`.
- **Existing indexes are looked up before their DDL runs** (`to_regclass` on the bound, schema-qualified
  index name): `CREATE INDEX IF NOT EXISTS` takes a `SHARE` lock before it finds the index, so on every
  store build it would queue behind open write transactions. `IF NOT EXISTS` stays for concurrent builds.

### Follow-ups

- A Manager one-click "move to the v2 layout" action (re-embed into a new table) — out of scope here.
- Manager RAG editor fields for the new parameters (they can be set as raw `storeParameters` today).
- `hnsw.iterative_scan` (pgvector 0.8) for filtered queries on shared tables.
