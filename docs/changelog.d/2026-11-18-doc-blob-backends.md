## ✨ feat(attachments): `BlobBackend` SPI with `gridfs` and `pg-chunks` backends (2026-11-18)

**Repo:** EDDI (`feat/doc-blob-backends`) · PDF documents plan, work package WP-4.1a

### What changed and why

Large documents (up to 512 MB) must never be held in a `byte[]`. This adds the
byte store they will go to: a small SPI and two backends that stream an object
in and out chunk by chunk, proven by one contract suite on both databases. It
is **not wired into the attachment stores yet** — the stores keep every blob
inline until the streaming store API (WP-4.1b) puts a metadata layer over a
backend. Nothing changes for users or existing data.

- **`BlobBackend`** (`engine/attachments/blob`): `put(key, stream, declaredLength, maxBytes)`,
  `get`, `getRange`/`supportsRange`, idempotent `delete`, `stat`, `list(prefix)`.
  Keys are opaque and random (`BlobKeys.random()`, 128 bits hex), limited to letters,
  digits and `-`, so no backend escapes them and none can name a path. Owners,
  grants and quotas are never a backend's business. `BlobLocator(backend, key)`
  names the backend so backends can coexist.
- **A failed `put` leaves nothing.** `LengthCheckedInputStream` reads *past* the
  limit to detect an overrun (stopping at it would store a truncated object) and
  also fails a stream shorter than its declared length. On any failure the backend
  removes what it wrote, on a fresh connection, adding cleanup errors as suppressed
  rather than masking the cause. An existing key is refused and left untouched.
- **`gridfs`** (`datastore/mongo/GridFsBlobBackend`): its own bucket
  `attachment_blobs` (the `attachments` bucket is never touched), key = file `_id`,
  primary reads. Because GridFS writes the files document only on close, `list`
  also reports orphan chunks of a crashed upload and `delete` removes them; it
  merges the two ascending key cursors, so a listing of millions of keys holds
  none of them in memory.
- **`pg-chunks`** (`datastore/postgres/PgChunksBlobBackend`): table
  `attachment_chunks(blob_key, seq, data)`, one row per chunk of
  `eddi.attachments.blob.pg-chunks.chunk-bytes` (4 MiB), each committed on its own
  so a 500 MB upload is not one transaction, and each inserted on a connection
  borrowed for that insert alone, so a slow uploader holds no pooled connection
  while it sends. `put` therefore must not run inside a JTA transaction. Reads fetch one chunk per query on its
  own connection, asking pgjdbc for binary transfer (text mode hex-encodes each
  chunk to twice its size; no test pins that request). GridFS reads likewise cap a read batch at
  about 4 MiB of chunks, equally untested. Range reads take the chunk size from the object's first chunk,
  so changing the setting affects new writes only.
- **Tests:** `BlobBackendContract` (sizes 0, 1, chunk−1, chunk, chunk+1, several
  chunks; overrun past declared length and past `maxBytes`, short stream and
  mid-stream source failure all after durable chunks; ranges; idempotent delete;
  prefix listing, each key once; key alphabet) runs in `GridFsBlobBackendIT`, `PgChunksBlobBackendIT`
  and, against an in-memory double, in the unit run. A 64 MiB round trip runs in a
  child JVM with `-Xmx128m` through the new test helper `testing/ChildJvm`.

### Decisions

- A known `declaredLength` is exact: a shorter stream fails too, not only a longer one.
- No new meters here; the backend meters come with WP-4.1b, which wires the backends.
- No GDPR participant: a backend holds bytes under random keys and nothing that
  identifies a user; erasure goes through the metadata layer (WP-4.1b and later).

**Files:** [`BlobBackend.java`](../../src/main/java/ai/labs/eddi/engine/attachments/blob/BlobBackend.java),
[`GridFsBlobBackend.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/GridFsBlobBackend.java),
[`PgChunksBlobBackend.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PgChunksBlobBackend.java),
[`BlobBackendContract.java`](../../src/test/java/ai/labs/eddi/engine/attachments/blob/BlobBackendContract.java),
[`configuration-reference.md`](../configuration-reference.md)

**Next:** WP-4.1b — the attachment stores become a metadata layer over a backend
(`BlobBackendSelector`, `eddi.attachments.blob.backend`, tombstones, meters).

```decision-log
| 2026-11-18 | Blob backends commit each chunk on its own and clean up a failed put themselves; a crash leaves chunks that list() reports and delete() removes | A 500 MB upload must not be one transaction | One transaction per object (long-held transaction, huge commit); a completion marker column (not in the plan's table, and metadata records the locator only after put returns) |
```
