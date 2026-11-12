## ✨ feat(tools): configurable Custom Search endpoint for the web search tool (2026-11-11)

**Repo:** EDDI (`feat/websearch-google-base-url`)

### What changed and why

The `google` provider of the web search tool sent every request to `https://www.googleapis.com/customsearch/v1`,
written into `WebSearchTool`. Google's overview page says existing Custom Search JSON API customers have until
January 1, 2027 to move to another solution. The new optional property `eddi.tools.websearch.google.base-url`
(`EDDI_TOOLS_WEBSEARCH_GOOGLE_BASE_URL`) sets the endpoint; empty or unset keeps Google's, and the request is then
the same as before. Surrounding spaces and trailing slashes are dropped. The request still goes through
`OutboundHttp` with `SsrfPolicy.HOPS_ONLY`, since the URL comes from configuration.

**Files:** [`WebSearchTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/WebSearchTool.java),
[`WebSearchToolTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/tools/impl/WebSearchToolTest.java),
[`configuration-reference.md`](../configuration-reference.md), [`langchain.md`](../langchain.md)
