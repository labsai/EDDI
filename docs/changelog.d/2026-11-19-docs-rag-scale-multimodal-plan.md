## 📝 docs(planning): large-scale and multimodal knowledge-base ingestion (2026-11-19)

**Repo:** EDDI (`docs/rag-scale-multimodal-plan`)

### What changed and why

A plan only; no code changes. [`planning/rag-large-scale-multimodal-ingestion-plan.md`](../../planning/rag-large-scale-multimodal-ingestion-plan.md) follows a live test that ingested a 68-file Google Shared Drive with `gemini-embedding-2`. 49 files failed on the Gemini free tier's 100-requests-per-minute limit, because a 429 was counted as a document failure after two quick retries. The 3 images and 2 videos in the drive were skipped as unsupported.

The plan covers three problems, built in this order:

1. **Throttling.** A small fix first (T1-min): honour the provider's `retryDelay`, and stop a run as `THROTTLED` without failing documents. A shared cooldown is kept per project, model and endpoint.
2. **Huge drives** (millions of files, terabytes):
   - a durable per-document work table, on both datastores, drained in short slices under the existing schedule lease;
   - Drive's change feed, run alongside the backfill;
   - folder scoping by path, with exclusions and a picker;
   - Drive quota counted in units, and dollar budgets;
   - vector-store sizing, which refuses unindexable dimensions and the in-memory store on large sources.
3. **Images, audio, video and PDF pages:**
   - native media vectors plus an embedded transcript, caption or OCR text for every media segment by default;
   - one result per segment at retrieval;
   - a text-only fallback for models without native support.

An independent review the same day replaced the draft's checkpointed long run with the work table, shrank the first throttle fix, and corrected the quota models; the plan records it.

```decision-log
| 2026-11-19 | Media in knowledge bases defaults to BOTH: the native media embedding and the embedded companion text (transcript, caption or OCR) for every image, audio, video and PDF-page segment; text only when the model lacks native support | Product owner: everything in a drive must be searchable by what it shows and what is said; Gemini's video embedding ignores the audio track, so a transcript is needed anyway | Text-first with native vectors opt-in (the reviewer's recommendation); native vectors only |
| 2026-11-19 | Large-scale ingestion runs on one durable per-document work table drained in short slices under the existing schedule lease, not on long runs with checkpoints and a retry set | Millions of files; a crash should lose a slice, not a run; throttles, budgets and transient errors all become a `notBefore` | Long runs with checkpoints, deferral sets and heartbeat reaping; NATS JetStream (optional in EDDI); Vertex AI RAG Engine (GCP-only managed store) |
```

### What's next

T1-min (throttle fix) first, then folder scoping, then the work table. Unverified points are listed in the plan, among them whether `embedContent` honours video offsets and how batch calls count against the quota.
