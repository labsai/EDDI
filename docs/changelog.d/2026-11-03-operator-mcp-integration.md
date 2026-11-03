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
