## ✨ feat(manager): group and Workforce wizards and operator re-enable go through the vault grant flow, one question per key (2026-10-06)

**Repo:** EDDI (`feat/vault-grant-wizards`, stacked on `feat/vault-grant-ux`), `ui/manager`

### Why

Under `eddi.vault.grant-enforcement=enforce` an agent that uses a restricted vault key is refused at deploy until it is on the key's grant, and a brand-new agent never is. The agent wizard already created with `deploy: false` and deployed through the grant dialog; three other Manager paths still created or deployed in one step and so hit a failed first deploy: the group wizard (members and moderator), the Workforce wizard (advisors), and re-enabling a deactivated Platform Operator (`reactivateOperator` called a plain `deployAgent`).

### What changed

- **Batch entry point** `runDeployManyWithGrants` / `useDeployWithGrants().deployMany` in [`use-deploy-with-grants.ts`](../../ui/manager/src/hooks/use-deploy-with-grants.ts): preflights every target in parallel, merges the grant issues by `(tenantId, keyName)` (the raw reference when it is not a plain vault reference), opens **one** dialog listing each key once with every agent that needs it, then deploys each target with the waited deploy. The tail (deploy, one second chance on an unexpected `VAULT_GRANT_MISSING`, outcome mapping) is now shared by the single and the batch flow (`deployAndSettle`). Returns a per-target outcome; a transport error on one target is that target's `failed`, not a throw that hides the others.
- **Dialog** ([`grant-required-dialog.tsx`](../../ui/manager/src/components/secrets/grant-required-dialog.tsx)): `GrantRequest.agents` / `GrantIssue.agentIds` make it show several agents per key (own title, key line, question and non-admin text). One confirmation appends every listed agent to every key it needs; the copy-for-an-admin request lists all the calls. A one-agent request renders exactly as before.
- **Group wizard:** members and moderator are created with `deploy: false`, then deployed together through the batch flow before the group is saved. The per-card "create agent" buttons deploy through the single flow. A member that is created but not live keeps a `pendingDeploy` marker, so the next Create deploys it instead of treating it as done.
- **Workforce wizard:** advisors are created with `deploy: false` and deployed as one batch before the group is saved; advisors a previous attempt created but could not deploy are remembered (`pendingDeployRef`) and deployed on Try Again.
- **`reactivateOperator`** goes through `runDeployWithGrants` like activation: a cancelled grant or a failed deploy is an error and the operator stays off; the config is written only after a READY deploy.
- New `resolveSetupVersion` (agent-setup.ts) and `describeUndeployed` (deploy-outcome.ts); 8 new i18n keys in all 11 locales.

### Design decisions

- **Cancel deploys nothing, for the whole batch** (including members that needed no grant) and the wizard does not save the group around members that are not live. It says the agents were created, that Create again retries the grant and deploy, and that they can be deployed from their agent pages. The wizards stop rather than report success.
- **One confirmation, an append per (key, agent).** The backend's atomic append (compare-and-set) is per agent; replacing a key's whole list with one PUT from here would drop an agent an administrator added a moment ago. "Allow every agent" is still a single PUT per key. Dry runs run first, as for one agent.
- **Targets are deployed one after another**, not in parallel: a second-chance dialog would replace the first, and a waited deploy is not something to fan out.
- **Platform Operator prompt and allow-list unchanged** (no endpoint or behavior the operator describes changed), so `operator-revision.json` is not bumped.

**Tests:** `use-deploy-many-with-grants.test.tsx` (merge per key, one dialog for N agents, an append per agent only after confirm, cancel deploys nothing, second chance per agent, failed target isolated), `group-wizard-grants.test.tsx`, `workforce-wizard-grants.test.tsx` (created undeployed, no deploy before the grant, cancel then retry), and reactivation cases in `use-operator-grants.test.tsx` / `operator.test.ts`. Each was mutation-checked by reverting the behavior it guards.
