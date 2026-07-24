# EDDI Manager Project Handoff

## Current Status (v6.0.0 Feature Work)

### Completed Phases
- Triggers UI/UX Refactoring: Replaced text inputs with dynamic `AgentPicker` combobox using `useDebounce` and backing API calls. Solved 404 proxy issues inside Vite server.
- Triggers Table Refactoring: Enabled the list filter to filter agent deployments not just by `agentId` but also by resolved `name` leveraging `useAgentDescriptors` and `Map`. 
- Bugfix: Fixed TypeScript build error in `src/pages/triggers.tsx` where `AgentDescriptor` incorrectly accessed an undefined `id` property instead of relying on `parseResourceUri(a.resource).id`.
- Codebase Audit Hardening: Fixed cascade save data corruption issues, standardized URI schemes across resource types, resolved version staleness bugs in the save lifecycle, added secrets API authentication headers, and implemented comprehensive test coverage for all fixes.
- Global Variables Admin Page: New `/manage/variables` route for managing deployment-wide `eddivar` configuration values. API layer, TanStack Query hooks, table UI with search/filter, create/edit/delete dialogs, key validation, export flag, clipboard copy for syntax hints, sidebar nav entry, MSW handlers, i18n (11 locales), 17 unit tests.
- Bugfix: Group CRUD version param — `getGroup()` truthy check omitted required `?version=` param, wizard success link navigated without version, detail page defaulted version to `undefined`. All three caused 400 Bad Request with no backend logs.
- **Agentic Improvements UI** (branch: `feat/capability-registry-ui`): Full UI parity with `feature/agentic-improvements` backend branch. Includes:
  - **LLM Editor**: Behavioral counterweights (level/placement/custom), identity masking (toggle + rules + validation), tool response limits (strategy dropdown + summarizer model + per-tool limits), info banners and contextual warnings.
  - **Agent Config**: Session management (auto-snapshot + trigger chips + max checkpoints), forking (disabled + "coming soon"), cryptographic identity (versioned keys + legacy v0 fallback + expiration), security toggles (disabled with AlertDialog guard).
  - **Rules Editor**: `deploymentContext` and `capabilityMatch` condition types with auto-populated default configs.
  - **Code quality**: Variable shadowing fix, orphaned config cleanup, comprehensive MSW mock data, 12 new tests.
- **Channel Integration Management UI** (branch: `feat/channel-integrations-v2`): Full Slack channel integration management with backend model parity. Includes:
  - **Pages**: `/manage/channels` (listing with card/table views, search, create wizard) and `/manage/channels/:id` (detail editor with inline target management).
  - **Architecture**: Config-driven `ChannelIntegrationConfiguration` model, multi-target routing (Agent/Group types) with trigger keywords, observer mode with configurable guardrails (cooldown, cost, daily limits), vault-enforced credentials via `SecretKeyPicker`.
  - **Components**: `ChannelCard` (interactive card with Slack branding), `CreateChannelDialog` (3-step wizard), `ChannelDetailPage` (full config editor with target cards, observe config, webhook URL, required scopes).
  - **Infrastructure**: API layer (`channels.ts`), TanStack Query hooks, Vite proxy for `/channelstore` + `/integrations`, sidebar entry, i18n (11 locales), MSW handlers (full CRUD + realistic multi-target mock data).
  - **Fixes**: Version bump on save (prevents 409 on double-save), pre-existing `resources.test.tsx` failure fixed (missing MSW `currentversion`/`descriptors` handlers), aria-labels on all remove buttons.
  - **Tests**: 36 new tests (8 API unit, 11 channels page, 17 channel detail page).
- **Code Review Hardening** (branch: `fix/code-review-issues`): Comprehensive code audit and bug fixes. Includes:
  - **SSE Auth**: Replaced native `EventSource` (which cannot send Authorization headers) with `fetch`-based SSE streaming (`sse-utils.ts`). Updated all 6 callers: `logs.ts`, `coordinator.ts`, `live-log-viewer`, `session-log-store`, `use-logs`, `use-coordinator`.
  - **Debugger Error Handling**: Added `isError` handling to `prompt-viewer`, `pipeline-trace`, and `memory-inspector` — they now display error indicators (AlertTriangle) instead of silently showing empty state.
  - **Type Alignment**: Fixed `ViewState` type to match backend enum (`UNSEEN|SEEN` not `UNSEEN|READ|DONE`). Added missing `EXECUTION_INTERRUPTED` to `ConversationState`.
  - **Cost Dashboard**: Fixed API contract mismatches in `ToolCostSummary` and `RestToolHistory` types.
  - **Code Quality**: parseFloat fix (was parseInt), error toast in channel creation, mock data fidelity, constant deduplication.
  - **Tests**: 33 new tests across 3 new test files.
- **Parser Editor** (branch: `feat/parser-editor`): Full parser configuration editor with dual-mode support. Includes:
  - **ParserEditor component**: Form editor for `ai.labs.parser` config — collapsible sections for Configuration (3 toggles), Dictionaries (6 built-in + regular dict CRUD with URI picker), Corrections (3 types with Levenshtein distance), Normalizers (4 types with punctuation config).
  - **Inline workflow editing**: Parser steps in the workflow pipeline show a "Configure" button that opens a dialog for editing parser config without navigating away. Dialog has Escape key handling, body scroll lock, auto-focus, and smooth animations.
  - **Standalone resource support**: Parser registered in `RESOURCE_TYPES` (`parserstore/parsers`), `EDITOR_MAP`, `EXTENSION_TO_SLUG`, and `EXTENSION_TO_RESOURCE_SLUG` — browsable at `/manage/resources/parser/:id`.
  - **Architecture**: Types/constants extracted to `parser-editor-types.ts` (satisfies `react-refresh/only-export-components`). All derived state wrapped in `useMemo`/`useCallback`.
  - **UX polish**: `focus-visible` rings on all interactive elements, URI-based stable React keys, smooth ChevronDown rotation, destructive hover on delete buttons.
  - **i18n**: Full `parserEditor.*` namespace across all 11 locales.
  - **Tests**: 49 new tests (35 parser editor unit, 9 resource detail integration, 5 workflow inline integration). Coverage: 98.82% lines, 89.58% branches.

- **Test Coverage Fixes** (branch: `feat/test-coverage`): Comprehensive testing improvements reaching >70% overall function coverage. Includes:
  - **Interaction Tests**: Expanded test suites for `LogsPage`, `OutputEditor`, `ChannelDetailPage`, and `Onboarding` flow.
  - **Mocks Improvements**: Handled edge cases with environment variables (like `navigator.clipboard` access) and async store state mutations.
  - **Coverage milestone**: Achieved 70.1% total function coverage (from 69.3%).

- **Quotas 404 Fix** (branches: `fix/quotas-404-bootstrap` on EDDI-Manager, `fix/quota-store-bootstrap` on EDDI): Fixed quotas page returning 404 on fresh Mongo/Postgres instances. Includes:
  - **Backend**: Added `@ConfigProperty` injection and default quota bootstrapping to `MongoTenantQuotaStore` (constructor) and `PostgresTenantQuotaStore` (lazy in `ensureSchema()`). Added package-private test constructors. Refactored Postgres `getQuota()` to use connection-reusing `getQuotaInternal()`.
  - **Frontend API**: `getQuota()` and `getUsage()` now catch 404 → return local defaults (no auto-write). Non-404 errors still propagate.
  - **Frontend UI**: Loading skeleton, enforcement-off banner, quota field icons, dimmed fields when disabled, `data-testid` attributes throughout.
  - **Tests**: 30 new frontend tests (19 page + 11 hooks), 4 new backend bootstrap tests. All 860 frontend tests pass, all 112 backend tenancy tests pass.

- **Group Task Orchestration** (branch: `feat/group-task-orchestration`): Full UI support for EDDI's new TASK_FORCE discussion style with live task tracking. Includes:
  - **Types & API**: Added `TASK_FORCE` style, `TaskItem`, `TaskDefinition`, `DynamicAgentConfig`, `SharedTaskList` types. Added `task_plan_created` and `task_verified` SSE event types. Updated `GroupConversation` with `taskList`, `dynamicMembers`, `createdAgentIds`, `retainedAgentIds`. Added `PLAN`, `EXECUTE`, `VERIFY` phase types. Fixed SSE parser default event type safety.
  - **SSE Hook**: Updated `use-group-discussion-stream.ts` with task lifecycle tracking — `taskPlan`, `tasksInProgress`, `tasksCompleted`, `taskVerifications` state fields. Fixed `GroupStartPayload` field name (`groupConversationId`). Speaker events during EXECUTE phase automatically infer task progress. Protected shared initialState from reference sharing bugs.
  - **TaskBoard Component** (NEW): Kanban-style live task board with 4 columns (Pending → Active → Done → Verified). Animated task cards with priority badges (P0–P3), agent avatars, verification feedback. Responsive mobile layout. Progress bar with streaming indicator. CSS pulse animation (moved from inline `<style>` to `index.css` with CSS custom properties). ARIA attributes for accessibility (role=progressbar, region labels). Section heading with icon.
  - **Transcript Integration**: TASK_FORCE theme colors (orange accent), task board mounted during TASK_FORCE discussions (both live SSE and completed conversations loaded from API via `MemoizedApiTaskBoard`). PLAN/TASK_RESULT/VERIFICATION entry types with distinct visual treatments. Fixed flash on stream completion — board persists until API data loads. Fixed `tasksCompleted` inflation (excludes VERIFIED tasks).
  - **Config Panel**: Pre-configured tasks section and dynamic agents configuration display.
  - **Phase Header**: PLAN 📋, EXECUTE ⚡, VERIFY ✅ icons.
  - **Wizard**: TASK_FORCE style option with orange accent in style picker.
  - **i18n**: 27 new keys across `groups.*` and `taskBoard.*` namespaces (incl. `emptyState`, `unassigned`), propagated to all 11 locales.
  - **Code Review**: 10 fixes — TaskBoard flash, ARIA accessibility, inline style → CSS, memoized API path, completed count fix, SSE parser safety, state reset protection, missing i18n keys, CSS custom properties.
  - **Tests**: 62 tests across 4 files — 22 TaskBoard (100% stmt/line/func, 98.24% branch), 17 SSE hook (6 new task-specific), 8 transcript, 15 i18n. Build succeeds.

- **Boardroom App v1** (branch: `feat/advisory-board-wizard`): Standalone advisory board experience within the EDDI Manager. Includes:
  - **Layout**: Responsive shell with collapsible sidebar, mobile bottom tabs, topbar with breadcrumbs. Separate from Manager chrome.
  - **Dashboard**: Board cards with quick actions (settings, history, duplicate, delete), grid/list view toggle (localStorage persisted), welcome hero with template shortcuts for empty state.
  - **Creation Wizard**: 4-step wizard (Name → Team → Style → Review). 3 templates (advisory-board, code-review, pro-con). Team builder with agent search. 5 discussion styles (ROUND_ROBIN, DEBATE, PEER_REVIEW, BRAINSTORM, ARGUMENT). Confetti on success.
  - **Group Chat**: SSE streaming via `useGroupDiscussionStream`, phase-aware transcript with collapsible sections, synthesis footer, file upload support.
  - **1:1 Threads**: Per-agent follow-up conversations from group chat context. File attachments (drag-and-drop + paperclip). localStorage thread registry.
  - **Board Settings**: Team member management (add/remove/reorder), discussion style config, max rounds, danger zone (delete). Form validation, dirty state tracking, `beforeunload` guard.
  - **Conversation History**: Split-view (list + viewer). Search/filter, keyboard arrow navigation, paginated loading, delete with confirmation. Conversation viewer shows full transcript with phase separators.
  - **UX Enhancements**: Copy response (clipboard), pin/bookmark (localStorage), export conversation as markdown, keyboard shortcuts (N/? + dialog), grid/list toggle.
  - **Quality**: 3 full code review rounds. RTL-safe (zero `px-`/`pl-`/`pr-`/`ml-`/`mr-`/`left-`/`right-` violations). Focus traps on all dialogs. `prefers-reduced-motion` for all animations. ARIA landmarks/labels throughout. All `t()` calls have fallbacks.
  - **i18n**: 100+ new keys across `boardroom.*` namespace, propagated to all 11 locales.
  - **Commits**: 8 commits on `feat/advisory-board-wizard` from `4226d6c5` to `13a538ba`.

- **Debug Panel Overhaul + Workforce Rename + Settings Gap Closure** (branch: `test/debug-workforce-coverage`): Comprehensive debug panel redesign, boardroom→workforce rename, and missing configuration coverage. Includes:
  - **Debug Panel Rewrite**: Pipeline waterfall with expandable task detail, cost dashboard with stat cards + per-turn table, memory inspector with key-value search, role-labeled prompt viewer, polished log viewer.
  - **Boardroom → Workforce Rename**: Renamed across 65+ files — directories (`src/pages/workforce/`, `src/components/workforce/`), component prefixes (`Workforce*`), i18n namespaces, route paths, git-tracked directory renames.
  - **CodeRabbit Review Fixes**: Resolved all 10+ critical/major findings — TS type safety (TS2345, TS2532, TS2339), cost dashboard error handling, memory inspector React keys, workforce board panel mutual exclusivity, i18n mojibake repair.
  - **Workforce Settings Config Gaps**: Added 4 new collapsible sections to `workforce-settings.tsx` completing backend parity:
    - **Protocol & Resilience**: Agent timeout, failure policy (Skip/Retry/Abort), max retries, member unavailable policy, max turns.
    - **Human Oversight (HITL)**: Approval granularity (Phase/Task), timeout with ISO-8601 duration, timeout policy (Wait/Auto-approve/Auto-reject/Abort), rejection policy (Fail/Retry).
    - **Dynamic Agents**: Toggle-gated creation/recruitment/delegation with per-discussion limits, lifecycle policy (Ephemeral/Keep/Undeploy/Agent Decides), model inheritance.
    - **Task Definitions**: TASK_FORCE-only pre-configured task list with subject, description, role assignment, priority.
  - **UX Polish**: Chevron disclosure affordance on collapsible sections, hover feedback, module-level defaults for stable React hook refs.
  - **i18n**: 57 new `Workforce.settings.*` keys propagated to all 11 locales.
  - **Tests**: 119 unit tests across debug panel + workforce components, audit test fix (camelCase assertion).
  - **Commits**: 6 commits on `test/debug-workforce-coverage` from `e05391bf` to `feeb5aef`.

### Boardroom v2 Design Decisions

**Decisions confirmed by user:**
| Decision | Answer |
|---|---|
| Design | Dark mode default, EDDI gold/black CI (`--color-primary: #f59e0b`) |
| 1:1 Chat | Streaming, markdown, HITL, file upload, history (no undo/debug) |
| Agent discovery | Show all deployed agents |
| System prompt | Full LLM config editor (Monaco) |
| API keys | BYOK — manage within Boardroom UI, global + per-agent |
| File upload | Both group and individual chats |
| Language switcher | Standalone component in sidebar |

**Phases:**
1. Design system alignment (gold/black CI, replace hardcoded slate/indigo)
2. Sidebar with individual agents section
3. Premium 1:1 agent chat (markdown, streaming, HITL, files)
4. Group chat UX upgrade (markdown rendering, gold theme)
5. Full LLM config editor per agent
6. BYOK API key management
7. Language switcher
8. File upload for group chat

**Key files to reference:**
- Design tokens: `src/index.css` (L1-60)
- Manager chat UX: `src/components/chat/chat-panel.tsx`, `chat-message.tsx`, `chat-input.tsx`
- Agent API: `src/lib/api/agents.ts`, `src/hooks/use-agents.ts`
- LLM types: `src/components/editors/llm/types.ts` (systemMessage in `tasks[0].parameters`)
- Secrets API: `src/lib/api/secrets.ts`, `src/hooks/use-secrets.ts`
- Language selector pattern: `src/components/layout/top-bar.tsx` (L188-202)
- Boardroom files: `src/pages/boardroom/`, `src/components/boardroom/`, `src/styles/advisory.css`

### Test Counts
- 277 test files passing (EDDI-Manager)
- 4004 Tests passing (`npm run test`)
- 112 Backend tenancy tests passing (`mvn test`)

### Last Commit Focus
- Frontend: `feat: fix live logs — REST seeding, SSE resilience, better empty states` on `fix/group-chat-defensive-rendering` (`e5303c5e`)
  - REST API seeding on first SSE connect (session-log-store.ts), BearerEventSource \r\n handling + 45s inactivity timeout, `seeded` state exposed through use-logs hook, 3-state empty view (loading/no-activity/connecting), 3 new i18n keys across 11 locales, 3 new tests
- Backend: `feat: add SSE heartbeat to log stream endpoint` on `feat/v6.2.0-prep` (`cd0551925`)
  - 15s heartbeat comment events in RestLogAdmin.streamLogs(), AtomicLong lastEventTime tracking, 3 new Mockito tests (idle heartbeat, recent-event suppression, closed-sink stop)
