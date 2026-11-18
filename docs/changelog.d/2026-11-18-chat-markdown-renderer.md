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
  anywhere else it cannot be told from exponent notation. Table rows are never touched.
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
