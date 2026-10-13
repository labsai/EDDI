## Manager: conversations, monitoring, user data, GDPR and chat UX fixes (2026-10-04)

Repo: EDDI (`ui/manager`), branch `fix/manager-conversations-userdata-chat`. Fixes from the Manager UX review.

**Destructive actions**
- User Memory: the row trash button now opens an `AlertDialog` naming the key before the permanent delete, and only the row being deleted is disabled (previously one pending delete disabled every row). "Delete All" shows the user id and entry count, and deletes the id it was opened for, not the debounced input.
- GDPR erasure requires typing the user id back (same pattern as the vault reset). An erasure whose counters are all zero, which is what a mistyped id produces, shows a neutral "No data found for this user id" instead of a green "Erasure Complete".
- Monitoring: the purge of ended conversations is now its own bordered section whose title, description and confirmation say it applies to all agents (the backend purge has no agent scope; there is no count or dry-run endpoint, so none is shown). The days field can be emptied while typing and is validated on Purge.

**Conversations list and detail**
- Filters, search, agent, version, page and page size live in the URL (`q`, `state`, `agent`, `version`, `page`, `size`); the detail page's Back link returns to that exact list (passed via router state, validated to a `/manage/conversations` path). Monitoring keeps `agent` and `version` in the URL.
- A later page that comes back empty shows "No more results" with a way back instead of the "deploy an agent" empty state; deleting the last row of a later page steps back automatically. The backend still reports no total, so Next stays enabled on a full page.
- Card delete button is no longer nested inside the card link (stretched-link overlay on the id); the detail delete button has an accessible name. Agent names resolve for up to 1000 agents through one memoised map instead of the first 50 and a per-row regroup.
- Detail page polls every 3 s while `IN_PROGRESS`, the transcript search is labelled and announces a match count, and the Markdown export carries per-step timestamps.

**User data**
- One user id is shared by the Memories, Properties and Conversations tabs and kept in `?user=`; tabs have ids and accessible names on mobile (the panel's `aria-labelledby` pointed at nothing).
- The create-binding dialog uses the shared `AccessibleDialog`, an `AgentPicker` for the agent, and `htmlFor` labels.

**Chat**
- The transcript is a polite `role="log"`; Enter no longer sends mid-IME-composition in the chat panel, the drawer and the secret field; the secret-mode toggles are named and expose `aria-pressed`; the agent selector is keyboard-operable (search box, arrows, Home/End, Enter, Escape returning focus).

**Deliberately skipped**
- "New chat opens with a user message before the welcome": a mock artefact. The MSW handler's `conv1` fixture is the conversation the panel reopens (most recent READY one) and it carries a user turn; the logic that builds messages is correct, and the handler already returns welcome-only for freshly started conversations.
- Conversation state "shown raw": every list/detail/monitoring surface in this stream already localises it.
- No export or count preview for GDPR erasure: the existing export endpoint is the full bundle, and the memory count covers one category only.
