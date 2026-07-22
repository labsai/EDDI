# Follow-ups after the EDDI feature-parity branch

Two items that are **not** part of `feat/chat-ui-eddi-feature-parity` and should be
picked up separately. Written to be actionable without the conversation that
produced them.

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
   it) while never putting it on the wire.
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
