## feat(integration): F9 — the cross-feature fixes, MCP tools and Platform Operator for the ten feature PRs (2026-11-03)

**Repo:** EDDI (`feat/operator-mcp-integration`)

The combined follow-up to cost accounting, team handoff, the SERVICE judge, human tie-breaks, human task executors,
tool-call guardrails, edit-on-approve, approval analytics and earned autonomy, approval card context and team
budgets. Those ten PRs deliberately left out what every one of them would have conflicted on — new MCP tools and
the Platform Operator — and wrote down what each needed in `planning/operator-followups/<feature>.md`. This entry is
what was built from those notes, after checking each against the merged code.

### Cross-feature fixes

- **A handoff into a team that cannot pay is refused.** `TeamBudgetHandoffAdmission` is the first
  `HandoffAdmission` bean: the *receiving* team's `TeamBudgetService.decide` (spent, tenant spent, or unreadable
  — fail closed) refuses the handoff with the budget's own sentence, before anything is filed. New refusal surface
  `handoff` on `eddi_team_budget_refusals_total`. See [Team Budgets](../team-budgets.md#what-happens-at-the-cap)
  and [Cross-Team Handoff](../team-handoff.md).
- **A follow-up respects the team ceiling and is charged.** `GroupConversationService.resolveEffectiveCostCeiling`
  ignored `teamBudgetState.ceilingUsd`, so a follow-up on a discussion that had already reached its team's ceiling
  ran one more paid turn; and a follow-up starts no leg, so its spend reached the team only if the discussion was
  ever continued. The ceiling now includes the team's, and `GroupLifecycleOps.followUpWithMember` charges the turn
  as soon as its cost is harvested, as a phase turn does.
- **The SERVICE judge prices from the catalog.** `JudgeCallPricing` was the seam left for the price resolver; it now
  passes the judge's `llmProvider/llmModel` to `ModelPriceResolver`, so a judge without prices of its own is priced
  from the [price catalog](../model-pricing.md) instead of $0. Explicit prices still win.
- **Dashboard.** The HITL row of the Full Metrics Reference had two panels at the same position after the merge
  (approval-context renders and decision latency); the former moved below. The k8s copies are regenerated.
- **Schema version.** `handoffState` and `teamBudgetState` ride `GroupConversation` schema v5 with the tie-break
  fields — recorded in the version comment as decided, valid while the ten ship in one release.
- `GroupConversationService.executeTaskExecutionPhase` keeps its unused `speakers` parameter (the reflection seam
  every phase delegator shares); the reason is now in its Javadoc.

### MCP tools (96 → 108)

Thin wrappers over the in-process REST beans, so every access rule of the REST resource — existence first, VIEW or
EDIT on the group, per-agent oversight scoping, the caller's principal as the recorded actor — applies to the MCP
caller unchanged. Each class checks its tier itself, because an in-process call never passes the HTTP layer's
`@RolesAllowed`; `McpRoleTierParityTest` measures the tiers and compares them with the REST annotations (now also
method-level ones). New tiers in `McpRoles`: `OVERSIGHT` (admin, editor, approver) and `BUDGET_READ` (admin, editor,
viewer). `McpToolUtils.responseJson` turns a resource's `Response` into the tool result, keeping its sentence or code.

| Class | Tools | Tier |
| --- | --- | --- |
| `McpPricingTools` | `read_model_prices(provider?, model?)` | Author (read only — prices are a budget lever) |
| `McpTeamHandoffTools` | `list_team_handoffs`, `accept_team_handoff`, `decline_team_handoff` | Author, then VIEW / EDIT on the group |
| `McpTeamBudgetTools` | `read_team_budget(groupId, historyMonths?)`, `list_team_budgets` | Budget read |
| | `adjust_team_budget`, `transfer_team_budget` | Admin |
| `McpOversightTools` | `summarize_approvals`, `list_autonomy_recommendations`, `get_autonomy_state`, `count_pending_audit_samples` | Oversight |

Not built: a tool to file a handoff or delete a backlog task, any oversight write (never), the per-decision and
sample listings (they carry users' redacted tool arguments), `preview_approval_context` (no render-preview service
method exists) and a separate `get_hitl_decisions` (`summarize_approvals` answers it). The `eddi-admin` Claude Code
skill teaches the new tools (`SKILL.md`, `references/groups.md`, `references/destructive-ops.md`).

**Found on the way:** `RestTeamHandoffs` caught the guard's `io.quarkus.security.ForbiddenException` in its generic
`catch (Exception)` and answered **500** to a caller without access to the group, over REST and MCP alike; its test
threw the JAX-RS `ForbiddenException`, which the guard never throws. It now rethrows the Quarkus exception (403), and
the test uses the real type. `RestGroupWorkspace` on `main` has the same pattern and is left for its own fix.

### Platform Operator (revision 52 → 53)

- **Allow-list** (`tool-scopes.ts`), reads only, each with the exclusions written next to it: the price catalog,
  model resolution and unpriced sightings, and one tenant's usage (`PUT …/model-prices`, the reference list and every
  quota write stay out — a price is a budget lever); a team's handoff intake inbox (accept, decline and backlog
  delete stay out — an intake decision is a person's); a team's budget, its history and the cross-team listing (the
  cap change and the transfer stay out); the approval analytics summary, the earned-autonomy states, one category's
  evidence and the sample count (the sample list, the per-decision rows and every oversight write stay out). Four
  new predicates gate the matching prompt sections, so a deployment without one of the reads is not told about it.
- **Prompt** (`system-prompt.ts`): sections on cost and prices (resolution order, unpriced models and the strict
  policy, where cost shows), cross-team handoff (two-sided consent, REVIEW vs AUTO, why group handoffs are gated on
  the receiving side, every refusal), team budgets (window, who pays, every refusal, the admin-only calls to give)
  and approval oversight (tiers, `sampledAudit`, `autonomyPolicy`, the 409s); and quick-reference entries for the
  SERVICE judge, human tie-breaks, humans in task forces, tool-call guardrails, edit-on-approve with the decision
  audit, and approval card context, plus their documentation pages. The operator is told it never answers a
  tie-break or a human task, never decides an intake item, and never changes a price, a cap or an oversight state.
- **Gate guard** (`gate-guard.ts`): an LLM-config write that sets `toolCallGuardrails` — in a PUT body at any depth,
  or a patch path, `from` or value — is refused in the approval UI with the new reason `carries-guardrails` (string
  `operator.approval.blockedGuardrailCarrying`, all 11 locales), mirroring the backend's `SelfUngatingGuard`. The
  agent-store oversight fields the approval-analytics follow-up asked for need no rule: the operator holds no
  agent-store write.

The ten `planning/operator-followups/*.md` notes stay: planning documents are kept in this repository after they
are done, and these record why each read was granted or left out.
