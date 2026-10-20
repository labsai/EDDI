## 🐛 fix(manager): a readable "Model & LLM Engine" card on the Workforce agent panel (2026-10-20)

**Repo:** EDDI (`feat/upgrade-conversation-generation`)

### What changed and why

The Workforce agent details panel listed an LLM task's `tools` as raw resource URIs (`eddi://ai.labs.apicalls/apicallstore/apicalls/…?version=1`). For an agent with many HTTP-call tools, the card filled with a column of near-identical, horizontally overflowing chips that named nothing.

- **Grouped and named.** Tools are grouped by kind: API Calls, MCP Calls, other resources, Built-in Tools, and "Also enabled" for the workflow HTTP/MCP call and memory switches. Each group has its icon, tint (the `ResourceTypeBadge` colours) and count, and the total shows beside the heading. Each tool resource is named from its own descriptor (`GET /descriptorstore/descriptors/{id}?version=N`, the pattern the parser editor uses: one cached read per reference, not a paginated store listing a resource can fall outside of). While a name is loading, a skeleton chip shows instead of an id that flickers. A missing descriptor falls back to a short id (`6ad75f…a7b2`), never the raw URI, and the full URI stays in the tooltip.
- **Linked.** A tool resource opens its editor at the referenced version (`/manage/resources/{type}/{id}?version=N`).
- **Once each, and contained.** A repeated URI is listed once. Chips truncate instead of overflowing. Beyond eight tools the rest collapse behind `common.showMore` / `common.showLess`, and the toggle appears only when there is something to show.
- **Persona "Show more" only when it means something.** The persona preview offered "Expand Persona" under every prompt, even one that fitted in its three lines. The clamped text is now measured (re-measured on resize), and the toggle appears only when it is actually cut off. The `common.showMore` / `common.showLess` fallbacks are aligned with every other caller.
- **Header.** "Model & LLM Engine" no longer wraps, and the model pill truncates (full name in the tooltip) instead of breaking a long model id across two lines.
- **Translated.** The feature labels, previously hardcoded English ("HTTP Calls", "MCP Tools", "Memory"), and the group names are now `Workforce.agentEditor.toolFeatures.*` / `toolGroups.*` in all 11 locales. Capability skills are no longer repeated in this list: the Capabilities card directly below shows them.
- The list-building lives in `ui/manager/src/components/workforce/active-tools.ts` (pure; `__tests__/active-tools.test.ts`). The panel keeps the descriptor queries and the rendering.

### Verification

Typecheck, lint, `i18n:check`, and the workforce and i18n Vitest suites (51 files, 555 tests). Checked in the browser against the mock API, with a temporary override of six API-call tools (one duplicate, one with a very long name, one slow descriptor, one missing descriptor), one MCP tool and three built-ins:
- 11 tools in three groups;
- correct editor links, including the MCP version;
- a skeleton for the slow descriptor and a short id for the missing one;
- collapsing to 8 with "Show more (3)", then expanding to 11;
- no horizontal overflow;
- no persona toggle for an 89-character prompt; for a 904-character one, the toggle expands it from 66 px to 445 px.
