## feat(operator): a cost budget for the operator prompt, prompt caching, and detail moved to the docs (2026-11-18)

**Repo:** EDDI (`feat/operator-prompt-budget`)

### Why

The Platform Operator's system prompt is resent on every round of its tool loop (up to 100 per
turn), next to the generated tool definitions for its ~105 allow-listed endpoints. Measured on the
rendered `read_write` prompt: 142,220 characters of prompt plus roughly 134,000 of tool definitions
(estimated from the OpenAPI spec the same way `McpApiToolBuilder` inlines body schemas) — about
70k tokens per round, uncached on Anthropic because setup-api never asked for caching.
`system-prompt.ts` grew from 42 KB to 169 KB between 2026-10-13 and 2026-11-17, a paragraph per
feature, and the only guard was the 200,000-character acceptance budget under setup-api's cap.

The operator already reads this deployment's `docs/` pages on demand, but a docs read returns the
whole page and stays in context for the rest of the turn; `langchain.md` alone is 257 KB. So detail
moves only to small dedicated pages, and the prompt now says which pages are too big to read
casually.

### What changed

- **Prompt caching** — `CreateApiAgentRequest.promptCaching` (setup-api, optional, placed before
  `dryRun`, which stays last). True writes `cacheSystemMessages` and `cacheTools` on the generated
  LLM task for `anthropic`; every other provider is left alone (the OpenAI family caches a repeated
  prefix by itself), and a value already set is kept. The Manager sends it when it activates or
  upgrades the operator (`provisionOperator`). An older backend ignores the field (the setup request is not parsed strictly). Documented beside `dryRun` in `docs/architecture.md`. Tests in
  `AgentSetupApiDryRunTest` (mutation-checked: removing the call fails them) and `operator.test.ts`.
- **Prompt trimmed toward small pages** (`system-prompt.ts`): `read_write` 142,220 → 117,122
  characters, `read_only` 121,915 → 96,817. Condensed to the boundary, the common symptom and a
  page pointer: the signing-key block of the role section (10 KB → new
  [`agent-signing`](../agent-signing.md) page), the per-user event stream (→ `user-events`),
  approved-result actions (→ `group-approved-result-actions`), tool-call guardrails, edit-on-approve,
  approval card context, SERVICE judge and human decisions (→ their dedicated pages), the email
  channel's field reference (→ `email-integration`), self-continuation (→ `long-jobs`), the tenant
  cost-reservation detail (→ `tenant-quotas`) and the agent-operations tool limits
  (→ `agent-ops-tools`). Plan binding and the input/output guardrails stay, condensed or whole: their
  only page is `hitl` or `langchain`. The docs map now warns that the seven largest pages are read
  whole and points at the dedicated ones first.
- **New page** [`docs/agent-signing.md`](../agent-signing.md) (in `SUMMARY.md` under Cryptography):
  generating and revoking keys, turning signing on, signed audit entries and their verify codes,
  what `requirePeerVerification` changes, group records, and a troubleshooting table — the text the
  prompt carried, now readable by admins too.
- **Cost budget** — `operator-prompt-size.test.ts` adds `COST_BUDGET_CHARS` = 125,000, held for both
  scopes, and asserts it stays well below the acceptance budget. The rule for what belongs in the
  prompt is in `ui/manager/AGENTS.md` (Platform Operator) and in root `AGENTS.md` §4.6 item 6.
- `system-prompt.test.ts`: three assertions now match whitespace-normalised text (they pinned line
  wraps), and a new test pins, for every topic whose detail moved, the boundary or symptom that
  stayed and its page pointer.
- Operator revision 81 → 82.

### Not measured

Token counts are characters ÷ 4; rounds per turn and how often each topic is asked are unknown, and
no test grades answer quality. Not done: a heading-scoped docs read (`?section=`) on
`/administration/docs/{name}`, which would let the large pages serve as references; trimming the
tool definitions (`PUT`/`POST /llmstore` are about 19,000 characters each).

### Next

Eleven open PRs edit `system-prompt.ts` (net about +13,000 characters together, measured from their
diffs on 2026-11-18). Each also bumps `operator-revision.json`, so each must merge `main` before it
lands and then meets the 125,000-character budget in its own CI: expect several to move detail to a
docs page first.
