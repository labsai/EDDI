## fix(tools): built-in tools stop presenting errors and nothing as facts (2026-11-18)

**Repo:** EDDI (`fix/tool-arguments-and-builtin-tool-answers`)

### What changed and why

Review finding P3. Several built-in tools returned a wrong or empty answer that reads like a fact.

- **`WebScraperTool`**: relative links resolve against the fetched URL after redirects (`extractLinks`
  used to answer "No links found." on most sites; the Markdown converter and selector/metadata parsing
  get the same base; a `<base href>` wins, as Jsoup applies it). The body is decoded in the WHATWG
  order: a byte order mark (UTF-8 or UTF-16, stripped from the text), then the `Content-Type` charset,
  then a `<meta charset>` or `<meta http-equiv="content-type">` in the first 1024 bytes — a real
  attribute, not `charset=` text inside another one, and a `<meta>` naming UTF-16 reads as UTF-8 —
  UTF-8 otherwise. A body truncated at the size cap drops only the character the cut split, in any
  charset. A non-text content type is refused with a tool error, except `application/octet-stream`
  whose body starts like markup.
- **`WebSearchTool`**: a provider answer that cannot be parsed is a failed search
  (`ToolFailureException`), not "No results found". An empty DuckDuckGo Instant Answer now says "no
  instant answer", and the `searchWeb` description says what that provider is. The default provider
  and the Google Custom Search provider are unchanged.
- **`DateTimeTool`**: an offset or zone in the input (`...Z`, `+02:00`, `[Europe/Vienna]`) is honoured;
  a local time inside a DST gap is reported as an error instead of being moved forward. `addTime` works
  on `ZonedDateTime`: hours, minutes and seconds move the instant (01:30 + 1 h on the spring-forward
  night is 03:30 CEST, not an error; the repeated autumn hour is counted), years to days move the wall
  clock and keep the start's offset in a repeated hour (a result in a skipped hour is reported), and an
  input carrying the later offset of an ambiguous hour keeps it.
- **`CalculatorTool`**: division/modulo by zero and non-finite results are `Error:` answers, `round()`
  no longer saturates at the `long` range (and `round(0.49999999999999994)` is `0`), and results below
  1e-4 print in scientific notation instead of `0`.
- **Tool result cache**: a result starting `Error:` is not stored (`ToolExecutionService`);
  `@NeverCached` (new, method level, read by `ToolCacheService.isConversationBound`) keeps
  `getCurrentDateTime` and `listTimezones` (it shows the current local times and takes no arguments)
  out of the cache. The explicit failure signal already existed (`ToolFailureException`), so none was
  added.

### Upgrade note

Tool answers change text only where they were wrong: `10 / 0` is `Error: Division by zero` rather than
`Infinity`, an unreadable search answer is an error rather than "No results found".

### Decision

Calculator values keep ten decimals for ordinary magnitudes (existing results are unchanged); only
magnitudes below 1e-4 switch to ten significant digits. Rejected: switching everything to
`BigDecimal`, which would change every printed result.
