## 🐛 fix(manager): operator teardown addressed a stale agent version (2026-10-03)

**Repo:** EDDI (`fix/operator-superseded-stale-version`)

### Symptom

Reconfiguring the Platform Operator left the previous operator deployed, with the
banner "The new operator agent (…) is live, but the one it replaced (…) could not
be removed (eddi://ai.labs.agent/agentstore/agents/…?version=2)".

### Cause

The operator config records the agent's version once, at activation, and never
refreshes it. Any in-place edit of the operator afterwards, such as a model or
prompt change through Studio or the resource editors, repoints the agent and
writes version N+1. Every teardown then addressed the recorded version:

- A permanent delete is refused with a 409 unless it names the live version (the
  backend's guard against a stale tab erasing a resource that moved on, in
  `RestVersionInfo.requireCurrentVersion`). The 409's body is only the current
  URI, and the Manager quoted it as the reason.
- An undeploy of the recorded version left a newer deployed version running.

This affected three teardown paths: retiring the superseded operator on
reconfigure, **Delete operator** (`resetOperator`), and the write probe's
emergency teardown of an operator with a proven gate breach
(`tearDownBreachedOperator`). The kill switch (`deactivateOperator`) had the
same undeploy gap. The backend behaved as designed; the bug was the Manager's.

### What changed

- `retireOperatorAgent` in [`operator.ts`](../../ui/manager/src/lib/api/operator.ts)
  is now the single teardown path. It reads `/currentversion`, undeploys that
  version and every earlier one (ending their conversations), then deletes
  permanently at the live version. It retries once on 409, in case the agent
  moved between the lookup and the delete, and treats an agent that is already
  gone (404) as retired. If the lookup fails it falls back to the recorded
  version, so it is never worse than before.
- `deactivateOperator` undeploys every version up to the live one.
- The undeploy never targets a version lower than the recorded one.
- A 409 on retirement is reported as a version conflict instead of a bare URI.

### Design decisions

- **Reactivate still deploys the recorded version, and the status panel still
  reads it.** The recorded version is the one whose approval gate activation
  verified. Re-enabling an operator at an edited version nobody verified is a
  separate decision, so it is left for a follow-up.
- Deleting at the live version destroys nothing extra: a permanent delete is
  ID-scoped and drops every version anyway.

**Files:** [`operator.ts`](../../ui/manager/src/lib/api/operator.ts),
[`use-operator.ts`](../../ui/manager/src/hooks/use-operator.ts),
[`write-canary.ts`](../../ui/manager/src/lib/operator/write-canary.ts), plus tests in
[`operator.test.ts`](../../ui/manager/src/lib/api/__tests__/operator.test.ts) and
[`use-operator-supersede.test.tsx`](../../ui/manager/src/hooks/__tests__/use-operator-supersede.test.tsx).
The new tests fail with the version lookup reverted (5 failures).
