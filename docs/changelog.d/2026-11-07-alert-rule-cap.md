## 🐛 fix(alerts): cap alert rules per agent; numeric match type selectable on empty values (2026-11-07)

**Repo:** EDDI (`fix/alert-rule-cap`)

### What changed and why

- **Alert rules per agent are capped.** `AlertRuleValidator` bounded the fields of one
  rule, but nothing bounded the number of rules, and every rule watching an agent is
  evaluated on each of its ratings and failed turns. New property
  `eddi.alerts.max-rules-per-agent` (default `50`, at least `1`): a create or update that
  would give an agent more rules listing it — enabled or not — is refused with 409
  `RULE_LIMIT_REACHED`, naming the agent (an every-agent rule: the every-agent count) and the property. The rules over every agent
  have a limit of the same size of their own (`IAlertRuleStore.countAllAgents`, in the
  MongoDB and PostgreSQL stores), so at most twice the limit watch one agent.
- **Only what a save adds is counted.** An agent the stored rule already listed, or an
  every-agent rule that already was one, is not counted again, so after the limit is
  lowered the existing rules stay editable and only new ones are refused. The check
  runs after the access checks, so a caller without `EDIT` learns nothing about another
  agent's rule count, and a test send is never counted.
- **Manager:** the rule editor shows the server's sentence for `RULE_LIMIT_REACHED`,
  as it does for `INVALID_RULE` (no new strings).
- **Manager: Number could not be chosen as an `OUTPUT_MATCH` value type on an empty
  value.** `OutputMatchBuilder` read the type back from the value; an unset number is
  `null`, which reads as Text, so choosing Number snapped back, and clearing a number
  field or typing a lone `-` flipped it to Text mid-edit. The chosen type is now held by
  the builder; a non-null value of another type (a stored rule opened in the editor, or
  the text value a switch to the actions source leaves) still decides it.
- **Platform Operator:** the alert section names the limit and the refusal; operator
  revision bumped.

### Design decisions

- One limit for both pools rather than counting the every-agent rules into each agent's
  total: an every-agent save would otherwise have to check every agent. The worst case
  per agent (twice the limit) is bounded and documented.
- A count before the insert, not a transactional reservation: two saves at the same
  moment can each pass once. Only editors and administrators write rules and evaluation
  runs on the alert worker, so the soft edge is not worth a lock.

**Files:** [`RestAlertRules.java`](../../src/main/java/ai/labs/eddi/engine/alerts/rest/RestAlertRules.java),
[`AlertSettings.java`](../../src/main/java/ai/labs/eddi/engine/alerts/AlertSettings.java),
[`IAlertRuleStore.java`](../../src/main/java/ai/labs/eddi/engine/alerts/IAlertRuleStore.java),
[`output-match-builder.tsx`](../../ui/manager/src/components/alerts/output-match-builder.tsx),
[`alerts.md`](../alerts.md#operating-alerts),
[`configuration-reference.md`](../configuration-reference.md#alerts)
