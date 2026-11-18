## fix(ui): the chat Markdown repair no longer rewrites correct text (2026-11-18)

**Repo:** EDDI (`fix/chat-markdown-renderer`)

### What changed and why

`formatMarkdownText` (`@eddi/conversation/team`) "repaired" model output with regexes before it was
parsed, and corrupted ordinary prose in the Manager chat, Workforce threads, the Operator chat and
group transcripts: `C# and F#`, `Issue#12`, `www.x.com/p#sec`, `#1 priority`, `#fff` and `| C# |`
became headings or broke a table row, `thanks:-)` turned into `thanks: - )`, `pre-**trained**` into
`pre - **trained**`, `x ** 2 and y ** 2` into bold text and `**bold**word` into literal asterisks. It
also ran, together with a full Markdown parse, on every streamed token.

- **The rewriter is reduced to one repair.** Dropped: the "heading glued to a word" and `#Header`
  rules, `:-`, hyphen-before-bold, `-**`, space-around-`**` insertion and the `,Capital` rule. Each also
  fired on correct text, and valid CommonMark already renders `word**bold**` and `**bold**word`. Kept
  (`repairBoldLine`): whitespace just inside a `**` delimiter (`**bold **x`, `** bold**`) is moved
  outside, because CommonMark does not parse it as emphasis. It works per line and only when every
  `**` on the line has a partner (an unclosed `**` while streaming is left as it is); a span with
  whitespace on both edges is repaired only when it opens the line's content (`** Titel **`), since
  anywhere else it cannot be told from exponent notation. A table row is repaired cell by cell, so
  `| **Name ** |` is fixed and a `|` is never crossed. A pair is repaired only when its opener is at a
  boundary (not after an identifier, `(` or `,`), a closer written with a space before it is not
  followed directly by a letter or digit, and `$…$` math is skipped, so `f(**kwargs, **opts)`,
  `pass **kwargs and **opts` and `$2 **n ** m$` stay as written. A heading marker before the span
  (`## ** Title **`) is allowed.
- **Masking.** Code spans, fences (also unterminated ones), indented code, link destinations,
  autolinks and bare URLs were already masked; link text (`[text](url)`) is now masked with its
  destination.
- **Streaming cost.** The repair and the parse no longer run per token: the new
  `FormattedConversationMarkdown` (`@eddi/conversation/i18next`) memoises the repair by text and
  throttles it to once per 50 ms while the reply streams (the same `useThrottledValue` that `Message`
  uses, now shared from `react/use-throttled-value.ts`); the finished text renders at once and in
  full. The Manager chat (`ChatMessage`), the Operator chat and the Workforce thread use it.
- **Tests.** New table-driven `team/format-markdown-text.table.test.ts` (the ten before/after cases,
  code, tables, links with fragments, hashtags, emoticons, exponents, nested emphasis, mid-stream
  prefixes, the repairs that remain) and `react/use-throttled-value.test.ts`. The Manager's
  `format-markdown-text.test.ts` no longer pins the removed repairs.

### Behaviour change for upgraders

A model reply that glued a heading to a word (`Schluss## Titel`), wrote `#Header` without a space,
or `Speichern,Dann` is shown as written instead of being "fixed". Correct text is never altered.

### Raw HTML (second commit)

`ui/manager/AGENTS.md` said the Manager never renders raw HTML, but the shared `MarkdownContent`
defaulted `enableRawHtml` to on and the Manager chat ignored `conversationControls.rawHtml`.

- `MarkdownContent` now defaults `enableRawHtml` to **off** (the HTML is dropped). `Message` (Chat
  UI widget, Workforce) already passed the agent's setting explicitly and is unchanged.
- `ChatMessage` takes `enableRawHtml`, and `ChatPanel` passes `controls.rawHtml`: HTML in an agent's
  reply is rendered (sanitised, as before) only for an agent with `"rawHtml": true`, and is shown as
  text for every other agent. The Manager's chat drawer, which has no profile, shows it as text.
- A protocol-relative `//host` link in Markdown is shown as its text, not as a link (one line in
  `MarkdownLink`; the sanitizer treats `//host` as a relative URL and let it through).
- `ui/manager/AGENTS.md` and `ui/packages/conversation/README.md` describe the policy.

Upgraders: a Manager chat with an agent that emits HTML but does not set `rawHtml: true` now shows
the tags as text, as the Chat UI widget has since 6.7.0.

### Review follow-up

- **Link guard.** The `//host` check is one helper (`isProtocolRelative`) used by `MarkdownLink` and
  `MarkdownImage`: it drops tabs and newlines, skips leading whitespace and control characters and
  reads `\` as `/`. An image whose source is protocol-relative renders only its alt text (or
  "image"), with no link and no address. Markdown destinations arrive percent-encoded
  (`%5C`, `%20`, `%09`, `%01`), which a browser does not read as `//`; the variants are pinned by
  tests, and the raw-HTML forms (for an agent with `rawHtml: true`), which keep the attribute as
  written, are what the helper stops.
- `useThrottledValue` is no longer exported from the package index; nothing outside the package used it.

### Second review follow-up

- **The transcript follows a reply that grows after its last state change.** `ChatPanel` and the
  Workforce thread scroll when their `deps` change, but the throttled trailing render of a streamed
  reply (and late math, highlighting or images) adds lines afterwards, which stayed below the
  viewport. `useSmartAutoScroll` (`@eddi/app-kit/hooks`) now observes the container and its element
  children with one `ResizeObserver` (a childList-only `MutationObserver` keeps the observed children
  current) and, while the reader is at the bottom, jumps to the new bottom: at most once per animation
  frame, instantly (never smooth), and only when the content actually grew. A reader who scrolled up is
  never moved; nothing runs while the transcript is idle; the observers are released with the
  container. Tests in `ui/manager/src/hooks/__tests__/use-smart-auto-scroll.test.tsx` drive a fake
  observer and animation frame.
- **No placeholder character is forgeable, and none is removed.** `formatMarkdownText` stood U+E000 in
  for protected regions and U+E001 for math spans, and only U+E000 was stripped from the input, so a
  literal U+E001 in a reply (`a` U+E001 ` and $x$ **b **(y`) took the real `$x$` and the span itself was
  deleted. Stripping both (the first attempt) deleted user content instead, such as a U+E001 inside a
  JSON example in a code fence. The placeholders are now chosen per input (`chooseMarks` in
  `team/group-utils.ts`): two Private Use Area characters the input does not contain, U+E000/U+E001
  when it holds none (one regex test, no allocation); an input holding all 6,400 is returned
  unrepaired. A literal mark is ordinary text and comes back as written, and text without `**` is
  returned as it is. Nothing but whitespace next to a repaired `**` changes. Tests in
  `team/format-markdown-text.table.test.ts` cover the review's trigger (mark and span kept, bold
  repaired), a mark without any math span, several spans, forged stash placeholders, a fence holding
  U+E000-U+E003 byte for byte, the all-PUA input, and 400 generated inputs that must lose no
  non-whitespace character; the Manager's `format-markdown-text.test.ts` no longer pins the stripping.
