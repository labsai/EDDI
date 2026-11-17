## feat(rag): retrieval benchmark — golden queries, recall@k, MRR, baseline diff (2026-11-05)

**Repo:** EDDI (`feat/rag-retrieval-eval`)

### Why

There was no way to tell whether a RAG change — chunking, embedding model, `minScore` — made
retrieval better or worse. `POST /ragstore/rags/{id}/search` shows one query at a time, and
comparing two configurations meant eyeballing result lists.

### What changed

- **`POST /ragstore/rags/{id}/evaluate?version=N`** (`IRestRagIngestion`, `RestRagIngestion`):
  takes golden queries `[{id?, query, expected[], k?}]`, runs each through the **same**
  `KnowledgeBaseInspector.search` call `/search` uses (no second retriever), and returns per query
  `firstHitRank`, `hit` (hit@k), `reciprocalRank`, `recall` (recall@k), `found`, `missing` and the
  ranked identifiers retrieved, plus `summary` (`hitRate`, `recall`, `mrr`). With a `baseline` (an
  earlier response's `queries`) it adds a `comparison`: per-query and aggregate deltas, regressions
  flagged. EDIT, like `/search`.
- **`RetrievalMetrics`** (`modules/rag/evaluation`): the metric math as a pure class, unit-tested on
  its own.
- **`eddi.rag.evaluation.max-queries`** (default `100`): queries per request; more is a 400 naming
  the setting. Every validation (empty query, no expected documents, `k` outside 1–50, `minScore`
  outside 0–1, duplicate query keys) runs before the first embedding call.
- Docs: [rag.md → Evaluating retrieval](../rag.md#evaluating-retrieval), the endpoint table, and
  [configuration-reference.md](../configuration-reference.md). OpenAPI snapshot for the Manager's
  contract test gains the operation.

### Design decisions

- **Hit = exact, case-sensitive equality** of an expected identifier with the value of one of the
  `matchKeys` in a chunk's metadata. Default keys `source` (a direct ingest's `documentName`),
  `documentId` and `url` (an ingestion source's document), so both ingestion paths work without
  configuration; `matchKeys` overrides them (e.g. `title` for an upload's file name).
- **recall@k is per-document** (distinct expected documents found / expected, averaged), and hit
  rate is returned beside it — they differ whenever a query expects more than one document.
  Chunks of one document count once.
- **Reciprocal rank is cut at k** (0 without a hit in the top k), so `mrr` is MRR@k.
- **A query with nothing expected is refused** (400) at the endpoint; the math class treats it as
  unscorable (null recall, left out of every average) rather than as a perfect or failed answer.
- **Baseline pairing by `id`, else query text**; duplicate keys on either side are a 400. Aggregate
  deltas are over the queries both runs contain; the rest are listed (`onlyInCurrent`,
  `onlyInBaseline`). A query **regressed** if its reciprocal rank *or* recall fell; `kChanged`
  marks a different cut-off.
- **A knowledge base `maxResults` above 50 is clamped** to the search ceiling for the default k,
  while an explicit `k` above 50 is refused — the benchmark never reports a k it did not look at.
- **One failure fails the run** (502 with `queryIndex`), no partial results: a benchmark missing
  queries compares badly with its baseline.

### Not done / next

- No MCP tool (there is no RAG tool family yet; adding one means a new tool class, roles and the
  tool-count docs) and no Manager UI. The Platform Operator is not given the endpoint, matching
  `/search`, which it also does not hold.
