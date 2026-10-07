## 🐛 fix(manager): group page, approvals inbox and Platform Operator drawer UX review (2026-10-04)

**Repo:** EDDI (`fix/manager-group-approvals-operator`) — `ui/manager` only, no backend change.

### What changed and why

Findings from the Manager UX review, for the group detail page, the Workforce board hand-offs, the approvals inbox and the Platform Operator drawer.

**Approvals**
- The nav badge counted only 1:1 approvals, so it disagreed with the page it links to. It now counts the same merged, de-duplicated set the inbox shows: 1:1 pauses, group phase approvals and HUMAN members' turns.
- Inbox rows lead with the agent's or group's name (the id is the second line, under a translated "Conversation" header, no longer a bare "ID"). Names, group ids, member ids and tool names are searchable. A HUMAN-turn row shows the member's display name instead of the raw id.
- A group phase approval can be reviewed in place: a "Review" row shows the question, the paused phase, the last entries of the transcript and the same `ApprovalBanner` the 1:1 tool-call rows use (note field, per-task verdicts), so the decision is no longer made on the pause reason alone.
- On a phone the table lays out as cards (labelled cells) instead of scrolling sideways, so Approve/Reject sit with their row.
- Workforce "Review it" now carries `&conversation=<id>` so the group page opens the paused discussion, not the newest.

**Composers**
- The discussion composer, the Workforce board composer and the follow-up composer keep the typed text (and files) until the request is accepted, and restore it when it is not. "Streaming live" toasts fire on the first SSE frame, not on the click. New store field `GroupStreamState.connected` and `whenStreamAccepted()` / `streamErrorOf()` in `use-group-discussion-stream.ts` make "accepted" observable.
- The group page composer defaults to a NEW discussion; an explicit New / Continue toggle appears when the selected discussion can be continued. Picking a discussion from the history (or arriving by link) means continue.
- The follow-up target list offers AGENT members only (HUMAN members have no agent to ask).

**Group page**
- A dropped live connection shows the same `role=status` notice as the Workforce board.
- The transcript only follows new entries while the reader is at the bottom, with a "New messages" button otherwise; the body is a polite `role=log` region, a pause is announced, and focus/scroll moves to the approval or human-turn banner. The HTML toggle reports `aria-pressed`.
- The history list loads older discussions ("Load older discussions", 20 at a time); the mobile history button is labelled, reports `aria-expanded` and closes on Escape.
- SYNTHESIZING discussions can be cancelled; deleting a running discussion stops it first (and says so). Escape leaves fullscreen unless a dialog or the history dropdown owns it.
- Opening one config editor no longer discards another's draft (the other Edit buttons are disabled while one is open); hiding the panel, going fullscreen or closing the sheet asks before discarding an open editor. A link to the Workforce settings (where name, members, moderator, style and rounds are editable) was added to the panel.
- After a human-turn submit the page says the discussion is resuming. The backend resumes it asynchronously and has no stream to reattach to, so the page keeps following the persisted conversation (polling); the mutation now stays pending until the refetched conversation lands.

**Platform Operator**
- Escape no longer closes the drawer when a dialog is open over it or the key was already consumed; the unsent draft lives in the operator chat store, so it survives closing the drawer; Enter during an IME composition no longer sends.

### Design decisions
- "Edit basics / members" is a link to Workforce settings rather than a second editor on the group page: that screen already edits every core field through `update_group`, and a second one would have to keep step with it.
- Draft restore is done by holding the draft until a promise settles (`onSubmit` / `onSend` / `onFollowup` may return `Promise<boolean | void>`; only `false` or a rejection keeps it), so the composers stay usable by callers that return nothing.
- Not changed: `system-prompt.ts` / `tool-scopes.ts` (nothing the Platform Operator documents moved), so `operator-revision.json` is untouched.

### Deliberately skipped
- Attachments chosen with a refused Workforce board start are not restored (the question text is); files are re-staged from `File` objects the board no longer holds once its view is replaced by the error screen.
