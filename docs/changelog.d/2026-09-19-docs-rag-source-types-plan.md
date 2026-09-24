## 📝 docs(planning): more knowledge-base source types — sitemap, upload, Drive, SharePoint, mail (2026-09-19)

**Repo:** EDDI (`docs/rag-source-types-plan`)

New plan: [`planning/rag-ingestion-source-types-plan.md`](../../planning/rag-ingestion-source-types-plan.md).
It extends the RAG ingestion stack (#783–#787, #789, #790) from web crawling to further source types:
sitemap-only, file upload, Google Drive, Microsoft OneDrive/SharePoint, and email (Gmail, Microsoft 365,
IMAP), with shorter notes on Confluence, Notion, S3, Git, Slack and help-desk systems.

**Decisions recorded in the plan:**

- A `SourceConnector` interface replaces the pipeline's hard-wired crawler; the web source moves onto it
  with its tests unchanged. Per-source change cursors (Drive changes, Graph delta, Gmail history, IMAP
  CONDSTORE) commit only with a successful run, and delta sources tombstone on explicit deletions instead
  of missed runs.
- Credentials come from the existing connections framework. Scheduled runs use `SERVICE` connections;
  a `PER_USER` grant is spent without its owner present only for a source that owner saved, and by default
  only into a knowledge base whose `audience` is `OWNER` — enforced at retrieval in `RagContextProvider`
  and failing closed without a verified principal. The one exception is an admin-acknowledged Gmail shared
  support mailbox, which must match the linked grant's own address.
- Ingested text is treated as attacker-writable: retrieved context is framed as data, hidden HTML is
  dropped, and mail sources carry sender filters, redaction before embedding, and mandatory retention.
- Least privilege is the documented path: Shared Drive membership, `Sites.Selected`, mailbox-restricted
  `Mail.Read`. No domain-wide delegation. Office formats through Apache POI rather than Tika.
- Order: foundation + sitemap, then upload (brings the converters), SharePoint (no new auth work), Drive
  (adds a JWT-bearer auth type), audience/run-as grants, and mail last.

**Files:** `planning/rag-ingestion-source-types-plan.md` (new), `docs/changelog.md`.

**Next:** nothing is implemented; the plan waits on the ingestion PRs merging. Open questions are in its §13.
