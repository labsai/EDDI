import type { AlertRule } from "@/lib/api/alerts";

/** What the page refuses before the backend would (AlertRuleValidator mirrors). */
export function ruleProblems(rule: AlertRule): string[] {
  const problems: string[] = [];
  if (!rule.name.trim()) problems.push("name");
  if (!rule.scope.allAgents && rule.scope.agentIds.length === 0) problems.push("scope");
  if (rule.source === "RATING") {
    const c = rule.condition;
    const any = c.maxNormalizedScore != null || c.onComment === true || (c.tags?.length ?? 0) > 0;
    if (!any) problems.push("condition");
  } else if (!rule.condition.errorKinds?.length) {
    problems.push("condition");
  }
  rule.delivery.forEach((target, i) => {
    if (target.type === "SLACK" && !/^[CGD][A-Z0-9]{2,30}$/.test(target.channelId ?? "")) problems.push(`delivery${i}`);
    if (target.type === "WEBHOOK" && (!/^https?:\/\//.test(target.url ?? "") || !/^\$\{vault:[^}]+\}$/.test((target.signingSecret ?? "").trim()))) {
      problems.push(`delivery${i}`);
    }
  });
  return problems;
}

