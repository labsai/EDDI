## 🐛 fix(manager): "Show more" on a group's question could not be undone (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### Why

The question header is pinned above a group discussion's transcript and clamps a long
brief to four lines. Expanding removed the clamp and nothing replaced it: the header is
`shrink-0` inside an `h-full` flex column, so a long brief grew it past the bottom of an
`overflow-hidden` pane and took the transcript, the composer **and its own "Show less"
button** with it. Nothing could be scrolled back to reach the toggle — the scroll container
is the transcript below, not the header — so the only way out was to reload the page.

### What changed

[`discussion-transcript.tsx`](../../ui/manager/src/components/groups/discussion-transcript.tsx):
an expanded question is height-bounded (`max-h-[30vh]`) and scrolls in place, so the header
cannot outgrow the pane and the toggle stays where it was.

**Tests:** four cases in `discussion-transcript.test.tsx` — clamped by default, bounded and
self-scrolling once expanded, collapsible again, and untouched for a short question.

---

## 🐛 fix(manager): "New Discussion" was a no-op on a group with history (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### Why

The handler cleared the selection; the auto-select effect, which lists `selectedConvId` in
its own dependencies, immediately put the newest conversation back. Worse than cosmetic:
attachments are accepted only on a **new** discussion (the backend rejects a continuation
carrying any), so the upload control is not rendered while a conversation is selected —
**a group that had ever held one discussion could never accept a file again**, with no
error to explain it.

### What changed

[`group-detail.tsx`](../../ui/manager/src/pages/group-detail.tsx) records an explicit clear
in a ref the auto-select effect honours. It is set wherever the page deliberately empties
the selection (New Discussion, starting a stream, switching to a resumed stream) and
cleared when a conversation is deliberately selected again — so a plain load still
auto-selects. The Workforce board already avoided this, with a one-shot restore ref.

**Tests:** five cases in `group-detail-selection.test.tsx`, the load-bearing one being that
the attachment control comes back. Mutation-checked: removing the ref check fails four of
them. The MSW group-conversation fixture also gained the `availableActions` field the
backend always serializes — without it the Manager read `[]` and disabled the composer, so
a fixture-backed test could not tell "continue this discussion" from "this discussion is
over".

---

## ✨ feat(manager): the Workforce advisor thread can start over (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### Why

Every other chat surface in the Manager offers one — `chat-panel` and `chat-drawer` ("New
Conversation"), `operator-chat`, and the Workforce board ("New"). The 1:1 advisor thread
did not, and its conversation is pinned in `localStorage` by (board, member): the thread it
resumes on every visit is the one it started the first time. Escaping a derailed thread
meant clearing site data.

### What changed

[`workforce-thread.tsx`](../../ui/manager/src/pages/workforce/workforce-thread.tsx) gains a
"New conversation" control beside the details toggle. The "start fresh" half of the init
effect is now a callback both paths share, so the button and a first visit take the same
route. Registering the thread repoints the stored (board, member) entry at the new
conversation; the old one is left on the server, which is what "New Conversation" means
everywhere else in the Manager.

**Tests:** `workforce-thread-new-conversation.test.tsx` — the control exists, it starts a
conversation and repoints the store, it clears the transcript, the composer stays usable,
and a failed start leaves the thread on the old working conversation rather than a dead id.

---

## 🐛 fix(manager): the log SSE stream no longer opens on every page (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### Why

`main.tsx` imported `session-log-store` for its side effect and that module connected on
load, so **every Manager tab held an open `/administration/logs/stream` SSE connection on
every page**, for the whole lifetime of the tab, whether or not anyone ever opened the Logs
page.

EDDI serves HTTP/1.1, where Chrome allows **six concurrent connections per origin for the
entire profile**, and a live group discussion opens another. Measured with the demo idle,
Chrome sat at exactly six, saturated. The symptom is pages hanging on skeleton loaders
forever, intermittently, while the server answers every request in 0.21 s with zero
variance and no errors — it looks exactly like a dead backend and is not.

Verified two ways: a *fresh* tab opened directly on `/manage/audit`, with no prior
navigation, issued `GET /administration/logs/stream`; and after 48 s on that page
`performance.getEntriesByType('resource')` carried no completed entry for it at all, while
an ordinary request on the same page reported `responseEnd: 547`.

### What changed

The stream is lazy and reference-counted. `connect()` returns an idempotent release (safe
as a React 19 double-invoked effect cleanup), a second consumer reuses the open socket, and
it closes when the last one leaves. `useLogStream` holds it only while mounted and
unfiltered; the filtered path already opened and closed its own, as does the debugger's
live log viewer. The bare import is gone from `main.tsx`.

What was lost is small: the buffer no longer accumulates from app boot. It never needed to
— the store seeds from `getRecentLogs` on open, so arriving at the Logs page still shows
history.

**Tests:** seven lifecycle cases in `session-log-store.test.ts` (including that importing
the module opens nothing, asserted through a new `isStreamOpen()`), three ownership cases
in `use-logs.test.tsx`, and a source-level guard that `main.tsx` never imports the module
again — the runtime tests cannot see that, and `main.tsx` is not importable from a test.

---

## ✨ feat(manager): a rejected discussion reads as a decision (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### What changed

The UI half of the backend's new `REJECTED` state. The transcript badge and the discussion
list carry a neutral "Rejected" rather than a red "Failed"; the composer says the
recommendation was rejected rather than "this discussion has ended"; the Workforce
analytics, history and session views label it too — TypeScript's exhaustive
`Record<GroupConversationState, …>` maps found every one of those reads, which is why the
state was added to the union first.

The SSE hook now honours the `state` the backend puts on `group_complete` instead of
hardcoding `COMPLETED`. `group_complete` is the terminal notification for every outcome,
and a rejection ends the run as `REJECTED` — rendering it as "Completed" for the seconds
before the persisted conversation loads says the opposite of what happened.

**Tests:** `discussion-rejected-state.test.tsx` — the label, the absence of the destructive
badge, the declined synthesis staying visible, and the live-stream state.

---

## ✨ feat(manager): the editor says when a debate synthesis answers with a verdict (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### What changed

`debateVerdictSynthesisPhaseNames` in
[`lib/group-config.ts`](../../ui/manager/src/lib/group-config.ts) mirrors the backend helper
(and through it `GroupContextBuilder.isDebateJudgment`), and the group config panel renders
an informational note naming the phases. Same mirror-and-surface shape as the existing
moderator-less and role-coverage warnings, which exist because the backend only ever wrote
those to its own log.

Styled as a note rather than a warning: for a real debate the verdict path is what was
asked for. The point is that nothing else in the configuration said so.

**Tests:** nine cases on the helper in `group-config.test.ts`, one per condition, plus three
on the panel.

---

## 🐛 fix(manager): a plain Save no longer claims a change is live (2026-09-22)

**Repo:** EDDI (`claude/smc-demo-bugs-tests`)

### Why

The config editor offers two actions: `handleSave`, which cascades resource → workflow →
agent, and `handleSaveAndDeploy`, which cascades and then deploys, polling for up to 30 s.
The first reported "Saved successfully" with nothing to say the running agent was still
serving the previous version.

Measured on an eligibility gate (no model, sub-second, so the effect is unambiguous) with
the ceiling lowered from 150,000 to 50,000 and a case of 85,000: cascade **+ deploy** gave
`gate_fail_over_cap`, cascade **alone** gave `gate_pass` — no change at all. Resource,
workflow and agent versions had advanced to v4/v5 while the deployed version stayed at v3.

### What changed

[`resource-detail.tsx`](../../ui/manager/src/pages/resource-detail.tsx): the cascade path's
toast is now "Saved — not yet live", explains that the running agent still serves the
deployed version, and carries a **Deploy** action that deploys the agent version the
cascade just produced (not the stale one the URL carried). A failed deploy surfaces its
error rather than doing nothing.

**Tests:** `resource-detail-save-not-live.test.tsx` — the wording, the explanation, the
version the action deploys, and the failure path.

```decision-log
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
```

```regression-note
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
```
