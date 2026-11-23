## 🐛 fix(ingestion): fetchable crawl URLs, bounded markdown prefixes, at-block hiding rules (2026-11-06)

**Repo:** EDDI (`fix/ingestion-html-and-crawl`)

### What changed and why

Three ingestion defects from the whole-codebase review.

- **A link with a raw space was never fetched.** jsoup resolves `href="my doc.html"` without encoding the space.
  `CrawlUrls.canonicalize` already encoded it for the document id, but the crawler fetched the raw `abs:href`, and
  `SafeHttpPageFetcher`'s `URI.create` refused it. The page became an inconclusive error on every run, which also
  kept deletion reconciliation from ever running for the source. The new `CrawlUrls.fetchable` strips the fragment
  and encodes through the same tolerant parser (an already valid URL comes back byte-identical); `WebCrawler` uses
  it for links and for sitemap URLs, including for the `MAX_LINK_LENGTH` bound and the frontier cost. A URL that
  still does not parse is skipped instead of queued to fail.
- **Nested blockquotes and lists cost the square of their depth.** A blockquote renders into its own buffer and
  copies every line out behind `> `; a list item nested through another element (`li > div > ul`) is re-indented
  by every enclosing item. Neither budget of the walk counts those prefixes, so a 240 KB mail quoted ninety levels
  deep allocated 340 MB. `HtmlToMarkdownConverter` now caps the stack of such prefixing containers at
  `MAX_PREFIXED_NESTING = 8` per conversion (counted on the per-conversion `Walk`, so the converter stays
  stateless); deeper ones are written in place without their marker. Output up to eight levels is unchanged.
- **A hiding rule inside `@media screen` evaded the mail hidden-content pass.** `HiddenStylesheetRules` skipped
  every at-block, so `<style>@media screen{.x{display:none}}</style>` hid text from every reader and still sent it
  to the model. Blocks that hold on every screen — `@layer`, `@supports`, and `@media` with no query, `all`,
  `screen`, `only screen` or `not <other type>` — are now entered and their rules read like top-level ones.
  Entering continues the same forward scan (the block's closing brace is skipped like a stray one), so nesting
  costs no recursion and the pass stays linear. Viewport and device queries (`(max-width:600px)`,
  `prefers-color-scheme`), `print`, `@container` and the like stay skipped, because responsive mail hides one copy
  there and the other copy everywhere else.

### Design decisions

- The crawl fix encodes the URL to fetch rather than fetching the canonical id: the canonical form lowercases the
  host, drops tracking parameters and folds `index.html`, and the server should get the URL the page linked to.
- The markdown cap bounds stacking instead of rewriting the output model to stream prefixes; eight levels keep
  every real document intact.
- At-blocks are entered in place instead of collected onto a work list and re-read, which is simpler and needs
  no depth cap. A feature query that holds everywhere (`(min-width:0)`) is still skipped; the class Javadoc and
  [`security.md`](../security.md) say so.

**Files:** [`CrawlUrls.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/CrawlUrls.java),
[`WebCrawler.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/crawl/WebCrawler.java),
[`HtmlToMarkdownConverter.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/HtmlToMarkdownConverter.java),
[`HiddenStylesheetRules.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/HiddenStylesheetRules.java),
[`security.md`](../security.md), and their tests.
