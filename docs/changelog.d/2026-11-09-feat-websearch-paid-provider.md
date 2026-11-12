## 🔍 feat(tools): Brave Search API as the `websearch` paid provider; Google Custom Search deprecated (2026-11-09)

**Repo:** EDDI (`feat/websearch-paid-provider`)

### Why

The built-in `websearch` tool had one paid provider, `google`, which calls Google's Custom
Search JSON API. Google [closed that API to new customers and shuts it down on
2027-01-01](https://developers.google.com/custom-search/v1/overview), so a new deployment could
not get a key. The other provider, `duckduckgo`, calls DuckDuckGo's Instant Answer API. It needs
no key, but it returns an abstract and related topics, not a ranked results page, which is too
thin for research agents. (The docs said it did "HTML scraping"; it never did. Corrected.)

### What changed

- **`WebSearchTool`** gains `eddi.tools.websearch.provider=brave`, which calls the Brave Search
  API web endpoint (`https://api.search.brave.com/res/v1/web/search`) through `OutboundHttp`
  (same `HOPS_ONLY` SSRF policy, 15 s timeout and 2 MB body cap as the other providers). The key,
  `eddi.tools.websearch.brave.api-key`, may be plain or a `${vault:...}` reference resolved by
  `SecretResolver`. A reference the vault cannot resolve fails the search and is never sent. The
  key goes in the `X-Subscription-Token` header, never in the URL. Results come back in the same
  shape as Google's (`N. title / snippet / url`), with Brave's `<strong>` highlighting and
  entities stripped. The query is cut to Brave's 400-character limit.
- **`OutboundHttp.SENSITIVE_HEADERS`** now includes `x-subscription-token`, so a cross-origin
  redirect drops the Brave key. This covers httpcalls too.
- **Startup WARNs** (`WebSearchTool.configurationWarnings`, observed on `StartupEvent`):
  `provider=google` names the 2027-01-01 shutdown and the replacement. A paid provider without
  its key names the missing property and says searches go to DuckDuckGo. An unknown provider
  value is named. `google` itself still works for existing keys.
- **Default price** (`ToolCostTracker.defaultPriceOf`): `websearch` costs $0.005 per call when
  the configured provider is `brave` or `google` (both list $5 per 1,000 requests), and stays at
  $0.001 for `duckduckgo`. A per-agent `toolPricing` still wins. The price follows the
  *configured* provider, so a paid provider that fell back for lack of a key is still charged as
  paid. That errs on the safe side for budgets.
- **Price is configurable.** `eddi.tools.websearch.price-per-call` (dollars per call, empty by
  default) replaces the provider default, so a deployment on a different plan corrects the price
  without a rebuild; an invalid value (negative, non-finite, non-numeric) falls back to the
  provider default with a startup WARN. Per-agent `toolPricing` still wins over both. The
  `google` price rise ($0.001 to $0.005) is called out in
  [Upgrading](../upgrading-from-6.5.md#changes-after-670), with how to keep the old price.
- **Docs:** `langchain.md` (Web Search Tool section rewritten: provider table, Brave setup, terms
  note, Google deprecation; default-price line), `configuration-reference.md`, `docker.md`,
  `developer-quickstart.md`, `security.md` (pricing line), `README.md` feature table.
- **Platform Operator:** a web-search bullet in `system-prompt.ts` (provider property, what
  DuckDuckGo returns, Brave key, Google deprecation, default prices). The operator revision is
  bumped.
- **Tests:** `WebSearchToolProviderTest` (provider selection, the Brave request (header, URL,
  no key in the URI), result shape, the `maxResults` cap, query truncation, non-200, vault
  resolve and refuse, startup warnings before and after the shutdown date) and
  `ToolCostTrackerTest.WebSearchProviderPrice`. HTTP is mocked at `OutboundHttp`.

### Design decisions

- **Brave over Tavily, Exa and SerpAPI.** Prices checked 2026-11-09:
  - Brave *Search*: $5 per 1,000 requests, $5 of free usage a month
    ([brave.com/search/api](https://brave.com/search/api/)). Brave runs its own index and
    authenticates with a header key. Its terms allow use inside the customer's application, but
    forbid storing results beyond "transient storage required for operation" and training on them.
  - Tavily: $0.008 per credit pay-as-you-go, 1 credit per basic search, 2 per advanced search
    ([docs](https://docs.tavily.com/documentation/api-credits)).
  - Exa: $7 per 1,000 for Auto/Fast search, $4 for Lite/Instant ([exa.ai/pricing](https://exa.ai/pricing)).
  - SerpAPI resells scraped Google results, which carries its own terms risk.

  Brave is the cheapest of these with an independent index, and its response maps one-to-one
  onto the shape the tool already returns.
- **The conversation memory keeps tool results.** Whether that counts as "storage" under Brave's
  terms depends on how long a deployment keeps conversations. The docs say so and point at
  Brave's storage-rights plans, rather than EDDI dropping search results from memory.
- **Fallback stays silent at call time but loud at startup.** This matches how `google` already
  behaved without credentials. Failing every search on a missing key would break agents on a
  config typo.

### Next

- `searchNews` still appends " news" to a web search. Brave has a dedicated news endpoint, which
  could back `searchNews` when `provider=brave`.
