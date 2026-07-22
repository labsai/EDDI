# Follow-ups after the EDDI feature-parity branch

Items that are **not** part of `feat/chat-ui-eddi-feature-parity` and should be
picked up separately. Written to be actionable without the conversation that
produced them.

> **2026-07-22:** the branch absorbed the reachable parts of the two other open
> PRs — #27 (model-cascade hint) and #28 (multimodal attachments), both of which
> can now be closed as superseded. Three attachment gaps found while comparing
> them are fixed here (`forwardableInline`, the orphaned-blob leak on chip
> removal, and the collapsed error taxonomy); items 3 and 4 below are what that
> comparison surfaced and did **not** fix.

---

## 1. EDDI backend: `input:initial` returns secret input in clear

**Repo to change:** `EDDI` (Java/Quarkus). **Not** the chat UI — the widget cannot
fix this properly on its own.

### What happens

When a chat client marks a turn as secret (the widget sends a `secretInput`
context entry and renders the bubble as `●●●●●●●●`), the backend stores the
**raw** message and returns it to any client that reads the conversation.

Verified by reading the source:

- `src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java:330-343`
  — `storeUserInputInMemory` does:
  ```java
  initialData = new Data<>(INPUT_INITIAL.key(), message);   // RAW, unconditionally
  initialData.setPublic(true);
  lifecycleData.add(initialData);

  String displayValue = isSecretInput ? SECRET_INPUT_PLACEHOLDER : message;
  currentStep.addConversationOutputString(INPUT.key(), displayValue);
  ```
  The mask is applied **only** to the separate `conversationOutput["input"]`
  entry. `input:initial` keeps the plaintext.

- `src/main/java/ai/labs/eddi/engine/memory/MemoryKeys.java:32` —
  `INPUT_INITIAL = MemoryKey.of("input:initial")`.

- `src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java`
  (~186-207) — the wire projection explicitly admits `input:initial`:
  ```java
  if (returnDetailed || key.equals(INPUT_INITIAL.key()) || key.startsWith(ACTIONS.key())
      || key.startsWith(OUTPUT_PREFIX) || key.startsWith(QUICK_REPLIES_PREFIX)) {
  ```
  so it lands in `SimpleConversationStep.conversationStep[]` on **every**
  `GET /agents/{conversationId}`, including with `returnDetailed=false`. The
  masked `"input"` output key does *not* start with `input:initial` and is
  filtered out — i.e. the masked copy is dropped and the plaintext is kept.

Reported by a reviewer and **not** independently verified by me: the only
existing scrubber is `PropertySetterTask` (~:438-443), and it runs only when the
agent is configured to auto-vault the value. Confirm before relying on it.

### Why it matters

Any client that rebuilds a transcript from `conversationSteps` — which is the
only correct way to do it, since `SimpleConversationStep` has no `input`/`output`
fields — will render the user's password in clear. It is also returned to any
caller authorised to read the conversation, so it is a storage/exposure issue,
not only a rendering one.

### What the chat UI does today (and why it is not enough)

`src/api/snapshot.ts` takes a `secretTexts: ReadonlySet<string>` and masks any
`input:initial` whose text matches. `ChatWidget` populates that set from turns
sent with secret mode on **in the current session** (`secretTextsRef`).

That is session-scoped by construction. After a page reload the widget no longer
knows which past turns were secret, so a rebuild of an older conversation still
shows them. This cannot be fixed client-side: nothing on the wire distinguishes a
secret turn from an ordinary one.

### Suggested fix (backend), in preference order

1. **Persist the secret flag per step and honour it in the projection.** Record
   that the turn was secret alongside the step, and have
   `ConversationMemoryUtilities` emit `SECRET_INPUT_PLACEHOLDER` for
   `input:initial` on those steps when `returnDetailed=false`. Keeps the
   plaintext available to the lifecycle (so `PropertySetterTask` can still vault
   it) while keeping it off the **non-detailed** wire.

   Note the trust boundary this draws: `returnDetailed=true` bypasses the
   key-prefix allowlist entirely, so a detailed read would still return the
   plaintext. That is defensible only if detailed reads are treated as an
   operator-level capability. If any end-user-reachable client can request
   `returnDetailed=true`, this option is not sufficient on its own and option 2
   (scrub at rest) is the one to take. Whichever is chosen, the acceptance check
   below must be run against **both** `returnDetailed=false` and `=true`.
2. **Scrub at rest.** After the vaulting step, overwrite the stored
   `input:initial` with the placeholder for secret turns, so it is not merely
   filtered but not retained.
3. **Drop `input:initial` from the non-detailed projection entirely.** Simplest,
   but breaks transcript rebuild for every client — only take this if option 1
   is not viable, and pair it with a supported replacement.

### Acceptance check

A conversation with one secret turn, read via
`GET /agents/{conversationId}?returnDetailed=false`, must not contain the
plaintext anywhere in the response body. Add a backend test asserting exactly
that; the chat UI's `src/api/snapshot.test.ts` covers only the client-side mask.

Add the same assertion for `returnDetailed=true` if detailed reads are reachable
by anything other than an operator — see the trust-boundary note under option 1.

---

## 2. Optional: ship undo/redo disabled by default

**Repo to change:** `eddi-chat-ui`. Small, reversible, and only worth doing if you
want a cautious rollout.

### Why

Across the review rounds, undo/redo was the single most defect-dense surface —
it produced the transcript-wipe CRITICAL and several of the high-severity race
findings. It is also the least load-bearing feature in the widget: hiding it
costs two buttons and no core functionality.

### The mechanism already exists

`ChatConfig` has `enableUndo` / `enableRedo`
(`src/types.ts`), defaulted in `defaultConfig` (`src/store/chat-store.tsx`), and
the widget already honours them:

```tsx
{state.config.enableUndo !== false && ( ... undo button ... )}
{state.config.enableRedo !== false && ( ... redo button ... )}
```

Query params `hideUndo=true` / `hideRedo=true` currently turn them **off**
(`parseConfigFromQuery` in `src/components/ChatWidget.tsx`).

### The change

1. In `src/store/chat-store.tsx`, flip the defaults:
   ```ts
   enableUndo: false,
   enableRedo: false,
   ```
2. In `parseConfigFromQuery` (`src/components/ChatWidget.tsx`), add opt-**in**
   params alongside the existing hide-params:
   ```ts
   if (params.get("showUndo") === "true") cfg.enableUndo = true;
   if (params.get("showRedo") === "true") cfg.enableRedo = true;
   ```
   Keep `hideUndo`/`hideRedo` working so existing embeds do not break.
3. Update the query-param list in `AGENTS.md` §3.
4. Existing tests in `src/components/ChatHeader.test.tsx` and
   `ChatWidget.test.tsx` assume the buttons render; the undo regression tests in
   `ChatWidget.test.tsx` ("undo must never wipe the transcript") drive
   `undo-btn` directly and will need `<ChatProvider config={{ enableUndo: true }}>`.

### Do not do this if

You want undo/redo generally available. The defects behind this suggestion are
fixed; this is belt-and-braces for a first production rollout, not a
correctness requirement.

---

## 3. Attachment markers do not survive undo/redo

**Repo to change:** `eddi-chat-ui`. Real, and neither open PR solved it correctly.

### What happens

The widget renders a sent attachment by baking a display line into the message
content (`ChatWidget.tsx`, `attachmentLine`). `ChatMessage` has no `attachments`
field. Undo and redo both re-read the conversation and dispatch
`REPLACE_MESSAGES` over the **whole** transcript, and `stepsToMessages`
(`src/api/snapshot.ts`) rebuilds each user bubble from `input:initial` — the raw
text the client POSTed, which never carried the `📎` line.

So one undo strips the attachment indication from **every** prior turn, not just
the undone one. The user's own record of what they sent silently changes.

### Why PR #28's fix was not taken

It carried attachments forward by **position** within the filtered list of user
messages. That aligns on undo (the list gets shorter) and silently fails on redo
(the list gets longer — the restored turn reads past the end and gets nothing).
It shipped a test for the undo direction only. Position is also the wrong key
here: `WITHDRAW_LAST_USER_MESSAGE` removes user bubbles client-side that the
server never recorded, so client and server user-message counts can diverge and
attachments would be stapled onto the wrong bubble.

### Two viable fixes

1. **Client-side, keyed on message id** — add `attachments?: { fileName: string }[]`
   to `ChatMessage` and carry it forward in `REPLACE_MESSAGES` by id, not index.
   Cheap; still client-only, so it does not survive a page reload.
2. **Server-side** — re-read undo/redo with `returnDetailed=true`, the only mode
   that emits `context:attachment_N` step data
   (`ConversationMemoryUtilities.java` ~195-196 filters it out otherwise), and
   map it back. Authoritative and reload-proof, but inflates every undo response
   with the full pipeline dump.

Option 1 unless reload-fidelity matters.

---

## 4. Optional: image thumbnails for attachments

**Repo to change:** `eddi-chat-ui`. Genuine UX gap; deliberately deferred.

A screenshot currently attaches as the text `📎 shot.png` in both the composer
chip and the sent bubble. PR #28 rendered real thumbnails, but its approach does
not port: it kept the raw `File` in component state, whereas this branch's store
holds only `AttachmentResult` and the `File` is discarded once the upload
resolves.

Doing it here means a `Map<storageRef, objectUrl>` held in a ref, with revocation
driven from `REMOVE_ATTACHMENT` / `CLEAR_ATTACHMENTS` / `CLEAR_MESSAGES`. Two
traps worth knowing before starting:

- **Do not revoke from the reducer.** PR #28 called `URL.revokeObjectURL` inside
  `CLEAR_MESSAGES` and `REPLACE_MESSAGES`. React double-invokes reducers under
  StrictMode and replays pending actions from the last committed state, so those
  revocations fire on discarded renders and kill URLs a retained render still
  uses. This branch's reducer is pure throughout — keep it that way and drive
  revocation from an effect instead.
- **Revoke the difference, not the whole set.** An effect cleanup keyed on the
  URL set runs on *every* change to that set, so revoking everything it captured
  would kill previews that are still on screen — remove one of five chips and
  the other four go blank. Diff previous against current and revoke only what
  left the set; revoke the whole set only on unmount.
- **Do not hand a preview URL to the sent message and then forget it.** PR #28
  did exactly that, deliberately, and never revoked it — an unbounded leak
  proportional to every image sent in the session.

The durable alternative (render from the download endpoint) is not a drop-in
either: downloads are owner/grant-checked and this branch sends auth as an
`Authorization: Bearer` header, which an `<img src>` cannot carry. It would need
fetch → blob → object URL.
