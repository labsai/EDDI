## The Platform Operator could not be activated or upgraded on 6.7.0 (2026-11-05)

**Repo:** EDDI, branch `fix/operator-prompt-setup-cap`

**What broke:** the Manager provisions the Platform Operator through `POST /administration/agents/setup-api`, which refuses a `systemPrompt` longer than `AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH`. That cap was 100,000 characters. The Operator prompt grew from ~80 KB of source in 6.6.0 to ~136 KB in 6.7.0 (the Operator follow-ups for team budgets, guardrails, model prices, HITL review and oversight, handoff and lessons all landed on release day), and the prompt the Manager sends reached ~110,000 characters. Activation answered "System prompt is too long (… characters, maximum 100000) (HTTP 400)", and so did Upgrade, which provisions a replacement. An Operator provisioned before the upgrade kept working on its old instructions.

**Why nothing caught it:** every Operator test in the Manager runs against MSW, so none of them sent the real prompt to the real endpoint, and the release's live smoke test did not cover the Operator.

**What changed:**
- `AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH` 100,000 → 250,000. The cap exists for prompts an LLM writes through `create_sub_agent` (storage growth, log noise); 250,000 still bounds that.
- `SETUP_MAX_SYSTEM_PROMPT_LENGTH` in `ui/manager/src/lib/api/agent-setup.ts` mirrors it; the new `SetupPromptCapMirrorTest` fails when the two differ.
- New `ui/manager/src/lib/operator/__tests__/operator-prompt-size.test.ts` builds the exact prompt `provisionOperator` sends, for both scopes and the longest brand name the validator allows, and fails above 80% of the cap. The headroom is the point: the prompt grows every release, and the guard should fire a release early.
- Written down where the next person looks: the Platform Operator sections of the root `AGENTS.md` (§4.6 rule 6) and `ui/manager/AGENTS.md`, and a new "Before tagging a stable release" checklist in `docs/release-versioning.md`, whose second step is activating the Operator against the image about to be tagged, fresh and as an upgrade, with a real model key.

**Design decisions:**
- Raise the cap rather than trim the prompt on release day: the cap guards LLM-authored input, not admin-provisioned prompts, and trimming ~10,000 characters of reviewed safety and tool instructions under time pressure is the riskier change. Shortening the prompt is still worth doing: it is sent on every Operator call.
- No operator revision bump: the prompt, allow-list and gate are unchanged.

**Next:** ship as 6.7.1 from a release branch cut at the `6.7.0` tag (`docs/release-versioning.md` → patch release).

```regression-note
| 2026-11-05 | Platform Operator activation and upgrade fail with "System prompt is too long (HTTP 400)" on 6.7.0 | The Operator prompt (~110,000 characters) exceeded `setup-api`'s 100,000-character cap; Operator tests only ran against MSW | Cap raised to 250,000, Manager mirror plus `SetupPromptCapMirrorTest`, `operator-prompt-size.test.ts` holds the prompt to 80% of the cap, pre-tag Operator activation step in `docs/release-versioning.md` | fix/operator-prompt-setup-cap |
```
