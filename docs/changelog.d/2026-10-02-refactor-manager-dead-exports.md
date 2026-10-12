## ♻️ refactor(manager): remove dead exports, make vacuous tests and MSW fixtures real (2026-10-02)

**Repo:** EDDI (`refactor/manager-dead-exports`) — Manager only (`ui/manager`); no behaviour change.

The third of three PRs answering the Manager findings of the 2026-10-02 review (§4.10, "Quality").
Independent of `fix/manager-ui-review` and `fix/manager-streams-and-editors`.

- **Dead exports removed** — nothing in `src/`, `e2e/` or the docs called them (checked across the
  whole repository): the unused hooks `useAuditEntryCount`, `useExportAgent`, `useRawConversation`,
  `useConversationStepCount`, `useAgentJsonSchema`, `useWorkflowJsonSchema`, `useQuotas`,
  `useUpdateResource`, `useSchedule`, `useToolRateLimit`, `useCacheStats`, `useToolHistory`,
  `useSearchMemories`, `useCountMemories`; the API functions and types behind only them
  (`getEntryCount`, `exportAgent`, `exportAndDownloadAgent`, `getRawConversationLog`,
  `ConversationMemorySnapshot`, `getDescriptors`, `listQuotas`, `getAgentJsonSchema`,
  `getWorkflowJsonSchema`, `getToolRateLimit`, `getCacheStats`, `getToolHistory`, `getToolCosts`
  and their types, `getTrigger`, `searchMemories`, `getMemoriesByCategory`, `getMemoryByKey`,
  `upsertMemory`, `countMemories`); `createAuthEventSource` (the SSE parser inventory in
  `sse-utils.ts` now lists only the readers that exist); unused constants (`TOUR_CHAPTER_ORDER`,
  `SLACK_PLATFORM_KEYS`, three `WORKSPACE_*` caps kept as a comment, `RESOURCE_VISIBILITIES`,
  `AUTH_REFERENCE_EXAMPLES`) and the `AddAgentCard` component. Their tests and MSW handlers went
  with them, and the `count` audit query key that only the removed hook used.
- **Vacuous tests made real:** `resource-detail-core` error tests assert the error state (they only
  checked that the back link rendered, which it does either way); `use-save-and-deploy` stubs the
  routes the hook actually calls and asserts the deploy and status reads (its handlers sat on
  `/deploymentstore/…`, which the hook never requests).
- **MSW fixtures backend-shaped:** the generic store handlers answer descriptors with the store's
  real `eddi://` extension, epoch dates and `deleted: false`, and a config GET with the store's
  empty configuration in its Java model's shape — not `{ type, config: {} }`, which no store returns.

The other group-API dead constants (`STANCE_SUMMARY_DEFAULT_MAX_CHARS`, `RESUME_KIND_*`,
`PROPOSAL_*`, `getGroupJsonSchema`), `useStartDiscussion` and `useRecentLogs` are removed in the
two fix PRs, whose files they share.
