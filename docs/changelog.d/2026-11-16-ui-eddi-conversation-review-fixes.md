## @eddi/conversation: CodeQL and review fixes (2026-11-16)

**Repo / branch:** labsai/EDDI, `feat/ui-eddi-conversation` (#1280).

**What changed:**

- `markdown/markdown-plugins.ts`: `looksLikeMath` and `looksLikeCode` no longer backtrack polynomially (CodeQL `js/polynomial-redos`). Math is an `indexOf`/`lastIndexOf` scan; the fence check's indent class excludes `\n`. Same answers on every sample the old regexes were checked against (`markdown-plugins.test.ts`), and adversarial runs of `$` and newlines stay fast.
- `controller/agent-conversation.ts`:
  - a retry or post-approval refresh adds images not yet shown instead of dropping every image;
  - undo/redo applies the restored step's conversation state;
  - `resume()` takes its own generation and ends a running turn before it reads, so an older resume or stream cannot land over it;
  - a turn's post-stream re-read no longer overwrites a newer turn's undo/redo flags, and Stop's late cancel no longer marks a turn sent after it as interrupted (a `turnsSent` counter, the same one #1308 uses);
  - the local (demo) turn drops events once stopped or replaced.
- `controller/state.ts`: snapshot dedupe compares a run of whole `\n\n` segments, so an entry that holds a blank line is recognised in the joined streamed bubble.
- `react/context.tsx`: `ConversationProvider` moves the upload-ownership ref when the conversation changes, also while no composer is mounted.
- `markdown/Markdown.tsx`: same-page links get the `user-content-` prefix rehype-sanitize puts on ids, so GFM footnote links resolve.
- Chat UI `demo-api.ts`: the demo stream honours the turn's abort signal, scripted delays included.
- Manager: the MSW stream mock writes one `data:` line per payload line; the RTL fullstack spec opens the widget on the backend's port; the sanitizer test waits for the lazy HTML parser before its negative checks.

**Design decisions:** Stop still releases the composer at once instead of blocking sends until `/cancel` returns. The cancel's result is applied only if no turn was sent since, which is the design #1308 builds on.
