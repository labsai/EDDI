## 🛠️ fix(manager): readable Active Tools list in the Workforce agent panel (2026-11-06)

**Repo:** EDDI (`fix/workforce-active-tools-display`)

### Why

The Workforce chat's Agent Details panel listed an LLM task's `tools` as raw
`eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=1` URIs, one badge each. Three
problems: the badges overflowed the 320 px panel and gave it a horizontal scrollbar; an agent
with sixteen API-call tool sets produced sixteen near-identical badges; and nothing on them said
which tool set was which.

### What changed

- `ui/manager/src/lib/agent-tool-refs.ts` (new) — `parseToolResourceRefs` turns the `tools` list
  into `{ id, version, slug }` references, dropping blanks and duplicates. The slug comes from
  the store segment, with the `ai.labs.*` extension as fallback.
- `ui/manager/src/components/workforce/active-tools-list.tsx` (new) — the Active Tools section:
  - tool resources resolved to their **descriptor name** (one cached `getDescriptor` read each,
    falling back to a short id if it cannot be read), with an API/MCP icon, the version, the full
    URI in the tooltip, and a link to the resource's editor;
  - the first four shown, the rest behind "Show N more";
  - built-in tools as compact chips, or one "All built-in tools" chip when
    `enableBuiltInTools` is on without a whitelist (previously not shown at all);
  - workflow HTTP / MCP / memory switches as their own chips;
  - a total count in the header.
- `agent-details-panel.tsx` — uses the new component; capabilities are no longer repeated in the
  tool list (they have their own card). The panel clips horizontal overflow and the model badge
  truncates instead of pushing the header out.
- 8 new `Workforce.agentEditor.tools*` keys in all 11 locales.

### Tests

`active-tools-list.test.tsx`: URI parsing (slug, dedupe, unknown store), name resolution and
link, collapse/expand, id fallback on a 404 descriptor, built-in and switch grouping, empty
state.
