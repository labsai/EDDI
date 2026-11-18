## fix(tools): built-in tools stop presenting errors and nothing as facts (2026-11-18)

**Repo:** EDDI (`fix/tool-arguments-and-builtin-tool-answers`)

### What changed and why

Review finding P3. Several built-in tools returned a wrong or empty answer that reads like a fact.

- **`WebScraperTool`**: relative links resolve against the fetched URL after redirects (`extractLinks`
  used to answer "No links found." on most sites; the Markdown converter and selector/metadata parsing
  get the same base). The body is decoded with the declared charset (`Content-Type`, BOM, then
  `<meta charset>`; UTF-8 otherwise) and a non-text content type is refused with a tool error.
- **`WebSearchTool`**: a provider answer that cannot be parsed is a failed search
  (`ToolFailureException`), not "No results found". An empty DuckDuckGo Instant Answer now says "no
  instant answer", and the `searchWeb` description says what that provider is. The default provider
  and the Google Custom Search provider are unchanged.
- **`DateTimeTool`**: an offset or zone in the input (`...Z`, `+02:00`, `[Europe/Vienna]`) is honoured;
  a local time inside a DST gap is reported as an error instead of being moved forward.
- **`CalculatorTool`**: division/modulo by zero and non-finite results are `Error:` answers, `round()`
  no longer saturates at the `long` range, and results below 1e-4 print in scientific notation instead
  of `0`.
- **Tool result cache**: a result starting `Error:` is not stored (`ToolExecutionService`);
  `@NeverCached` (new, method level, read by `ToolCacheService.isConversationBound`) keeps
  `getCurrentDateTime` out of the cache. The explicit failure signal already existed
  (`ToolFailureException`), so none was added.

### Upgrade note

Tool answers change text only where they were wrong: `10 / 0` is `Error: Division by zero` rather than
`Infinity`, an unreadable search answer is an error rather than "No results found".

### Decision

Calculator values keep ten decimals for ordinary magnitudes (existing results are unchanged); only
magnitudes below 1e-4 switch to ten significant digits. Rejected: switching everything to
`BigDecimal`, which would change every printed result.
