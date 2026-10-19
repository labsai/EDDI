## ✨ feat(manager): see ingestion while and after it runs — progress, run details, schedule, contents, test retrieval (2026-10-15)

**Repo:** EDDI (`feat/rag-ingestion-ux`)

### What changed and why

The knowledge-base editor showed a spinner during a run, "N ingested · M unchanged" after it, a bare
cron string for the schedule, and nothing at all about what the knowledge base held. It now uses the
endpoints added in the backend entry of the same date.

- **Live progress** (`ingestion-run-history.tsx` → `RunProgress`): documents looked at against what the
  last complete run saw (or the file count, for a Files source), every counter as it moves, the trigger,
  and elapsed time against the source's time budget. The run history polls every 2 s while a run is in
  flight (was 3 s).
- **Run history** opens each run to all it recorded — skipped, failed, removed, chunks, duration,
  estimated cost and how it ended, the stop reason said in words. A completed run that lost documents
  gets an amber icon instead of the green tick, and its summary says how many failed.
- **Errors say what the server said.** Run, Preview and Purge failures append the server's message to
  the generic line ("…A run may already be in flight." was shown for a disabled source and for a
  database fault alike).
- **Schedule** (`ingestion-schedule.tsx`): a preset picker (hand only, hourly, daily, weekly, monthly —
  UTC) or a custom cron checked against the server's rules while it is typed, with its next fire times
  in local time. Once saved, `ScheduleStatus` shows the next run, the last fire, recent fires with their
  errors, and says in words when a saved cron produced no schedule, when fires are failing, when the
  scheduler disabled it and when it was dead-lettered. The card header shows the preset's name with the
  raw cron on hover.
- **Ingested documents** (`ingestion-source-contents.tsx`): the source's documents, filterable and
  paged, each Live, Missed by the last N runs or Removed; opening one shows its chunks. Collapsed by
  default — each opened document is an embedding call on the server, so chunks are fetched only on
  demand, never retried, and kept for five minutes.
- **Test Retrieval** (`retrieval-tester.tsx`): a new editor section that searches the whole knowledge
  base or one source exactly as an agent would and shows each chunk with its score and origin.
- After a run ends the document list and schedule refetch (`useRefreshAfterRun`), keyed on the newest
  run's id and status rather than a timer.
- The legacy **Document Ingestion** section now says that pasted documents cannot be listed, updated
  or removed later, and points to a Files source.
- **Schedules page:** an ingestion schedule links to the knowledge base it is changed on.

### Design decisions

- **No `count` interpolation** in the new strings, so none of them needs per-locale plural categories;
  the numbers read naturally with neutral wording ("Missed by the last {{runs}} run(s)").
- **The source card observes the files query only while a run is in flight**, for the progress total.
  Observing it always refetched the list every time a card was opened — caught by the existing
  "reloads the file list" test.
- The run id `POST …/run` now returns is not used: `ApiClient` discards every 202 body, and the
  `RUNNING` row in the history identifies the run.

### Tests

`resource-detail-rag-insight.test.tsx` (9 cases: progress estimate and bar, run details and stop
reason, server error text, schedule status / missing schedule, live cron check, documents → filter →
chunks, retrieval request and result, retrieval failure), one case in `schedules-regressions.test.tsx`;
the cron assertion in `resource-detail-rag-sources.test.tsx` now checks the preset label and the raw
cron in its title. 87 new keys in all 11 locales; `npm run i18n:check`, `lint` and `typecheck` pass.
The five new operations are added to `openapi-operations.json` (refreshed from a Maven-built spec), which the MSW contract test checks every handler against. Checked in the browser against the mock API.
