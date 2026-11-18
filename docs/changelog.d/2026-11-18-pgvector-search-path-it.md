## 🧪 test(rag): pgvector table and index resolution under a non-default search_path, against a real server (2026-11-18)

**Repo:** EDDI (`test/1313-pgvector-search-path-it`). Closes #1313.

### What changed and why

`PgVectorSchema.inspect()` and `indexExists()` look up an unqualified table or index in `current_schema()`, the schema where langchain4j's `CREATE TABLE IF NOT EXISTS` creates it, not at the first match on the `search_path` (#1306). Until now only unit tests with mocked JDBC covered that. `PgVectorScaleLayoutIT` (Testcontainers, `pgvector/pgvector:pg16`) now covers it against PostgreSQL, through the real `EmbeddingStoreFactory` build. The test logs in as a second, non-superuser role with `search_path = myschema, public` and `log_statement = ddl`:

- **An unqualified `docs`, with an old-layout `public.docs` and no `myschema.docs`.** The build creates `myschema.docs` as v2 (jsonb, HNSW and both metadata indexes) and writes its rows there. `public.docs` keeps its `json` column, its single index and its rows.
- **An explicit `layout: v2` on such a table is not refused** because of the `public` table. `inspect` reports the unqualified name as absent and `public.docs_v2` as `json`.
- **A knowledge base pointing at `public.docs`** reads the rows written before the upgrade, writes its own rows there, keeps v1 and gets no new index.
- **v2 tables of one name in both schemas** (`public.shared`, then unqualified `shared`). Each table gets its own three indexes, even though `public.shared`'s indexes sit on the `search_path` under the same names. `indexExists` finds them for both names.
- **A second build creates no index.** After the cache is cleared and every store is built again, the server log shows no `CREATE INDEX` from the scoped role. The log window is bounded by two marker statements and is read only after the closing marker appears, so a missing statement means it was never sent, not that it has not been logged yet. Each first build must show its three `CREATE INDEX` statements, so the check cannot pass on an empty log.

`indexes()` in the test now filters on the schema as well as the table name.

**Mutation check.** Five mutations of `PgVectorSchema` were each caught by the new tests:
- both lookups resolve through the `search_path`;
- only `inspect` does;
- only `indexExists` does;
- `indexExists` drops the schema from a qualified index name;
- `indexExists` always returns false. The rebuild check catches this one: it logs 3 `CREATE INDEX` statements where it expects 0.

No production bug was found, so `PgVectorSchema` is unchanged.
