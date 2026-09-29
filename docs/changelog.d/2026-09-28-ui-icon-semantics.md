## 🎨 fix(ui): one icon per meaning across the Manager and the Chat UI (2026-09-28)

**Repo:** EDDI (`fix/ui-icon-semantics`)

A review of every icon in both UIs — 208 distinct lucide icons in the Manager, and the Chat UI's emoji and Unicode glyphs — against what each one stands for where it is used. It started from two sidebar entries (Active Conversations and Coordinator) sharing the Activity pulse, and found that pattern repeated, plus three places where an icon lookup was silently falling back.

### Defects, not taste

- **Resource-type icons came from six tables that disagreed.** The resource card knew six of the ten types and fell back to `GitBranch`, so every RAG, MCP, snippet and parser card on a list page was drawn as a rule set. The Resources grid's own map lacked snippets and parsers. The pipeline drew parser, behavior, workflow, dictionary and RAG as the same page glyph. The detail page aliased RAG to the dictionary's book. All of them now resolve through one table, [`resource-type-icons.ts`](../../ui/manager/src/lib/resource-type-icons.ts); `ResourceTypeConfig.icon` (a string key into those maps) is gone.
- **The chat activity panel never showed a step's icon or colour.** SSE `task_start`/`task_complete` carry the task's `getType()` (`langchain`, `behavior_rules`, `httpCalls`, …), and the row looked that up as an `eddi://` extension id. It never matched, so every step rendered as the unknown type in grey. The new `extensionTypeForTask` in [`extensions.ts`](../../ui/manager/src/lib/api/extensions.ts) maps the backend's runtime types (read from the task classes, not from the mocks, which use `ai.labs.*` ids) to extension types.
- **The Audit trail keyed its badges on `behavior`, `httpcalls` and `propertysetter`**, which the backend never sends, so those entries all showed the default `#`, and MCP and RAG had no entry. Badges now resolve through the same function; the colours stay on the page, and the icons come from the shared table.

### Meaning collisions resolved

| Where | Was | Now | Why |
|---|---|---|---|
| Active Conversations | `Activity` | `Radio` | Shared its icon with Coordinator. `Radio` already means "live" on the Logs page |
| Coordinator | `Activity` | `Network` | The queue / message bus |
| Approvals, `AWAITING_HUMAN`, every HITL badge | `HandMetal` (🤘) | `Hand` | A raised palm: waiting on a human. The Chat UI's paused card uses the same |
| Caller-supplied credential | `Hand` | `UserRoundKey` | Frees the hand for HITL |
| Groups | `Boxes` in the nav, `Users` on cards and detail | `Users` everywhere | One concept, one icon |
| User Data | `Users` | `UserRoundSearch` | Would have collided with Groups |
| Workforce mode | `Users` | `Briefcase` | The mode switcher sits in the same sidebar as Groups |
| Standing-team workspace | `Boxes` | `SquareKanban` | It is the team's work board |
| Sync | `RefreshCw` | `ArrowRightLeft` | `RefreshCw` is the Refresh button in 42 files. The Sync page's own header already used this icon |
| Capabilities | `Layers` | `Blocks` | `Layers` stays with model cascade |
| GDPR / Privacy | `ShieldAlert` | `ShieldUser` | `ShieldAlert` is the generic "dangerous request" warning |
| Language picker | `Globe` | `Languages` | `Globe` is API calls |
| User memories | `Brain` | `NotebookPen` | `Brain` is the LLM |
| Properties | `Settings` / `Database` | `Tags` | A gear read as "settings" |
| Dictionary | `BookOpen` | `BookA` | `BookOpen` is the Documentation link |
| MCP calls | `Plug` | `ServerCog` | `Plug` is Connections |
| Agent status `NOT_FOUND` | `Square` | `CircleDashed` | The square is the Stop button |
| Wizard steps | Identity `Brain`, Model `Settings2`, Review `Rocket`, Template `Sparkles` | `IdCard`, `Brain`, `ClipboardCheck`, `LayoutTemplate` | Each step says what it is. `Rocket` stays on the Deploy action |
| Studio / debugger "Pipeline" | `GitBranch` | `Workflow` | `GitBranch` is Rules |

Near-duplicate variants were unified where they meant the same thing: `CheckCircle`→`CheckCircle2`, `Users2`/`UsersRound`→`Users`, `User2`→`User`, `Edit3`→`Pencil`, `Cog`→`Settings`.

### Chat UI: emoji replaced by lucide-react

The widget used 📎 ⏳ 🔒 🔓 👁 🧠 💬 ⏸ next to ◑ ■ ↓ ➤ ▶ ↩ ↪ ↻. Emoji render in colour and differently on every OS, so they ignored the widget's theme colours. The "hidden" eye, 👁‍🗨, is an eye in a speech bubble rather than a crossed-out eye. Send was ➤ in one input and ▶ in the other, and "New conversation" used ↻, which reads as retry. These are now `lucide-react` icons at `size="1em"`, so they follow each control's existing responsive font-size and `currentColor`. The eye shows the action (open eye = reveal), matching its `aria-label`. New conversation is `MessageSquarePlus`, the same as in the Manager. Cost: +9.6 kB raw, **+1.6 kB gzipped**. The lockfile gained only the lucide entries: npm's own rewrite also reshuffled unrelated `peer` flags and line endings, so it was restored and edited by hand, and `npm ci` accepts it.

### Deliberately unchanged

- **Discussion-style and group-template emoji** (🗣️ 😈 ⚖️ …) stay. They are illustrations that tell seven styles apart at a glance, not UI controls, and they appear inside prose strings.
- **`Sparkles`** stays on the Platform Operator and on "AI / auto" affordances. That is the convention, and nothing in the sidebar shares it.
- **The 📎 and ⚠️ inside chat message text** stay. They are message content, and the withdraw-and-restore logic reads them.
- **Deprecated lucide aliases** (`AlertTriangle`, `Loader2`, …) were not mass-renamed. They still resolve in 0.577, and renaming ~100 files would change no pixels and only conflict with open branches.

### Guards

- [`sidebar-icons.test.tsx`](../../ui/manager/src/components/layout/__tests__/sidebar-icons.test.tsx): no two sidebar destinations may share an icon.
- [`resource-type-icons.test.ts`](../../ui/manager/src/lib/__tests__/resource-type-icons.test.ts): the icons in the shared table are distinct, every `RESOURCE_TYPES` slug is covered, and `"constructor"` does not resolve.
- [`chat-activity-icons.test.tsx`](../../ui/manager/src/components/chat/__tests__/chat-activity-icons.test.tsx) and the new `extensionTypeForTask` cases: real backend task types resolve to their icon and colour.

Each guard was mutation-checked: it fails with the old icon or the old lookup put back, and passes on the fix.

**Verification:** Manager — typecheck, lint (0 warnings), **6,875 tests / 429 files**, i18n check. Chat UI — typecheck, **278 tests**, production build. Checked in a browser against MSW mock data: all 26 sidebar entries report distinct icons in the live DOM (collapsed and expanded), the Resources grid shows ten distinct type icons, and the Chat UI composer, secret mode, header and empty state render in the theme colour. The icons that only appear mid-conversation (undo/redo, stop, paused card, thinking indicator) were covered by the component tests only, not checked visually, because that needs a backend.
