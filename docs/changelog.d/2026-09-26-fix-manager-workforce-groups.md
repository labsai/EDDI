## 🐛 fix(manager): Workforce and group discussions — Stop cancels, streams hand over, configs save what was entered (2026-09-26)

**Repo:** EDDI (`fix/manager-workforce-groups`)

Fix-plan item 22: UI review High 10, the Workforce/group Medium items, G2, G4,
P2, IME and the group search debounce. Manager only, apart from three
backend strings (P2).

### Stop and "+ New" stop the discussion (UI High 10)

- The board's **Stop** only closed this tab's SSE connection: the discussion
  kept running and spending on the server, and the board froze on the last
  frame while still saying new answers would appear. Stop now calls
  `POST /groups/{id}/conversations/{gcId}/cancel` (confirmed first, like the
  Manager's cancel) and closes the stream only once the cancel succeeded. A
  409 means the run already ended on its own — the stream is closed, but the
  state is left to the stored document rather than claimed as CANCELLED.
- Stop pressed before `group_start` has named the conversation is remembered
  (`cancelRequested`) and sent as soon as the id arrives, instead of closing a
  connection to a run nothing could then reach.
- **"+ New"** during a live discussion asks first and stops it, instead of
  leaving it running and letting a second run start beside it.
- New store action `cancelStream`; `abortStream` keeps its meaning (detach,
  used when switching discussions) and is no longer what the board's Stop does.

### Streams that end, and members that "type forever"

- `speaker_complete.outcome` (`TIMEOUT` / `SKIPPED` / `ERROR`, from the
  group-conversation-state backend branch) closes the member's placeholder as
  SKIPPED or ERROR with no content — a failure is never shown as something the
  member said. A completion with no content from an older backend closes as
  SKIPPED too. An unknown outcome value reads as a contribution.
- Against a backend without that field, a PARALLEL member released by the
  batch deadline got no completion at all and its placeholder typed for the
  rest of the discussion. `phase_complete` now closes the phase's open
  placeholders, and every terminal event (`group_complete`, `group_error`,
  `cancelled`, `awaiting_approval`, `human_input_requested`) closes all of them.
- A connection that ends **without a terminal event** (proxy timeout, pod
  restart) is now `interrupted`, not a silent "not streaming": the board shows a
  notice and follows the stored conversation (which polls while it runs); the
  Manager's group page selects it. A network error **after** the first frame is
  treated the same way — it used to mark a still-running discussion FAILED. A
  failure before any frame (the request was refused) is still FAILED.
- The board used to keep the frozen live transcript after a stream settled, so
  follow-ups and later phases never appeared. It now switches to the stored
  document as soon as that has caught up with what the stream delivered
  (`deliveredRowCount`), so the switch never flashes an older transcript.
- A refused stream start now carries the backend's own sentence
  (`streamRefusalMessage`) instead of "400 Bad Request".

### Board and thread

- **Attachment-only sends** are blocked in the composer with a hint — the
  backend requires a question and its 400 left the board on a full-page error
  with no actions. That error screen now has "Start over" (and "View past
  sessions"): it lives in the per-board stream store, which outlives
  navigation, so it used to be a dead end.
- **REJECTED** gets its own composer message on the board ("This
  recommendation was rejected"), as on the Manager page (P2).
- **Version links:** the board, the advisor thread and the history page are
  reached by links without `?version=` and read version 1 — the group's FIRST
  version, with its original name, roster and phases. They now resolve the
  current version (`GET /groupstore/groups/{id}/currentversion`,
  `useResolvedGroupVersion`) and never fetch version 1 while it is in flight.
  The history page learned that version from the enriched descriptor listing —
  up to 201 requests for one number; it is now one.
- **Thread errors:** a thread that could not be opened showed an empty,
  healthy-looking thread with a dead composer; it now says why, with Retry and
  New conversation. A stored conversation that no longer exists (404/410) is
  replaced with a fresh one. Moving from one advisor's thread to another (same
  page, new route param) kept the first advisor's messages and conversation:
  initialisation is now keyed per (board, member) and re-checked after every
  await.
- **IME:** the Enter that confirms a Chinese/Japanese/Korean composition no
  longer sends the question, in the board composer, the Manager's discussion
  input (both textareas) and the thread input (`lib/ime.ts`).

### Group page (P2)

- A rejection is confirmed: the backend ends a rejected run with
  `group_complete` state REJECTED and never sends `hitl_resume`, which was the
  only thing the page waited for.
- "New Discussion" pressed on one group no longer suppresses auto-selection on
  the next group the (still-mounted) page navigates to.

### Overview (G2, G4)

- The round's QUESTION row (stored at phaseIndex 0, phaseName "Question") no
  longer names phase 0 "Question" or marks it started.
- A picked round is reset when the panel moves to another discussion.
- An earlier round no longer borrows whole-discussion records: its stances are
  extracted from its own turns, its cost is unknown (null) rather than the
  total, and it shows its own synthesis and no later verdict.
- The Workforce history viewer passes the group's `style` to the overview.

### Configs that did not save what was entered

- **NEGOTIATION preset:** materializing the phases (which enabling any approval
  point does) stored Arbitration with `inputTemplate: null`, so the moderator
  ran the generic synthesis prompt instead of the arbitration brief.
  `NEGOTIATION_ARBITRATION_TEMPLATE` mirrors `TEMPLATE_ARBITRATION`, and a test
  compares it with the Java text block.
- **Assignment mode:** `normalizeGroupTaskConfig` rebuilt the block from the
  three fields it normalizes, so Workforce settings saved BID back as ROLE. It
  now carries every other field through.
- **Retro:** the backend has no "off" for retro — a null `retroConfig` means the
  default caps. The checkbox is now "Custom retro lesson limits" and says that
  unticking keeps the defaults (remove the RETRO phase to stop it). Saving
  spreads the existing block, so `maxLessonChars` survives.
- **Vote options:** the explicit-options textarea was bound to the cleaned list,
  deleting the newline and trailing space as they were typed — a second option
  or a two-word option could not be entered. It keeps its own draft now, and an
  EXPLICIT ballot with fewer than two options blocks the save with a message
  (the backend's 400).
- The phase, advanced and HITL editors show the backend's error sentence instead
  of "Something went wrong".

### Create flows (validation messages)

- `groupSaveProblems` mirrors `AgentGroupStore`'s hard rejections — DEBATE /
  DEVIL_ADVOCATE preset roles (only for groups without explicit phases),
  unassigned members, HUMAN members' name and principal id, HUMAN members in
  task-force or peer-targeted phases (preset-expanded). The create dialog, the
  group wizard and the Workforce wizard show them on the last step and hold
  Create back. The two wizards create and deploy member agents before saving
  the group, so this runs before the first agent is created. Their `onError`
  shows the backend's sentence.

### Search and paging

- The Groups page search is debounced (300 ms) and keeps the previous results
  while a new filter loads: every keystroke was a fresh enriched listing, one
  descriptor request plus one config read per group.
- Owner-filter pagination (frontend half): the history pages through the
  caller's conversations by `(index, limit)` and offers "Load more" exactly
  while a page comes back full, which is correct once the backend filters by
  owner in the query (the group-conversation-state branch). Against the current
  backend a non-admin can still get a short first page — nothing the client
  can detect. The MSW handler now honours `index`/`limit` as the row offset the
  backend uses.

### Backend strings (P2)

- `RestGroupConversation` close 409 text, `IRestGroupConversation` close 409
  description and the MCP `start_group_discussion` description now name
  REJECTED; the MCP text also names the two waiting states, so a polling client
  does not wait for COMPLETED or FAILED forever.

### Not changed

- `ui/manager/src/lib/api/groups.ts:1622` (the literal NUL byte) is untouched;
  this branch edits other parts of the file byte-safely. Item 17 owns it.
- The group page's own `?version=` default of 1 is left to the version-after-save
  branch (item 19).
- `api-client.ts`'s `extractErrorMessage` still does not parse Quarkus
  `violations[]` bodies (not in this item).

**Files:** [`use-group-discussion-stream.ts`](../../ui/manager/src/hooks/use-group-discussion-stream.ts),
[`workforce-board.tsx`](../../ui/manager/src/pages/workforce/workforce-board.tsx),
[`workforce-thread.tsx`](../../ui/manager/src/pages/workforce/workforce-thread.tsx),
[`use-discussion-digest.ts`](../../ui/manager/src/hooks/use-discussion-digest.ts),
[`group-config.ts`](../../ui/manager/src/lib/group-config.ts),
[`hitl-config.ts`](../../ui/manager/src/lib/hitl-config.ts),
[`group-detail.tsx`](../../ui/manager/src/pages/group-detail.tsx)

```decision-log
| 2026-09-26 | Workforce Stop cancels on the server before closing the stream; a 409 closes it without claiming CANCELLED | Stop only aborted the SSE connection, leaving the run spending | Aborting first and cancelling after (a failed cancel would then leave a running discussion with nothing following it) |
| 2026-09-26 | A stream that ends without a terminal event is "interrupted", and the UI follows the stored conversation | A dropped connection froze the board or showed FAILED for a still-running discussion | Closing placeholders as SKIPPED on interruption (those members may still be answering) |
```
