## 🐛 fix(manager): the agent page waits for the current version before showing or deploying one (2026-11-06)

**Repo:** EDDI (`fix/agent-detail-version-load`)

### What changed and why

- **No version before the current one is known.** The agent detail page fell back to version 1 while its version list loaded (`version ?? versions?.[0]?.version ?? 1`), and `useAgent` was enabled on the id alone, so it read v1 straight away. The list needs `currentversion` and then one descriptor read per version, so v1 was on screen first: its deployment status, a Deploy button that deployed v1, and sections that edited it. An edit made in that window built its draft from v1, the page then jumped to the latest version without resetting the draft, and Save wrote the v1-based document as the new version, undoing every change since v1. If the version list failed, the page showed v1 as current with no error. Now `useAgent` runs only with a version above 0, the page uses 0 for "not known yet" (the deployment hooks already skip it), shows the spinner until the list is in, and shows the error panel when the list cannot be read; *Retry* reads the list again.
- **One Limits and one Human-in-the-Loop section after a version switch.** Both carried the key `${id}-${version}` as siblings. On a version change React kept the old Limits card in the DOM beside the new one and logged "two children with the same key". They now carry `limits-` and `hitl-` prefixes, like `continuation-`.
- **Viewers get a read-only page.** A `VIEW` grantee was offered Save, Deploy and Undeploy (header and per environment), Deploy & Chat, Add workflow, and the per-row workflow update and remove buttons; the backend refuses all of them (EDIT). As on the workflow page, `readOnly = !access.canEdit` now hides those controls, keeps the status pill, disables every section through its own `disabled` prop (not the fieldset, which would also disable the headers a viewer needs to open a section), and `editAgent` ignores edits so no draft or Save can arise. Duplicate, Export and Chat stay.

### Tests

New [`agent-detail-load-order.test.tsx`](../../ui/manager/src/pages/__tests__/agent-detail-load-order.test.tsx), with agent1 at v3 (the default mock answers v1, the old fallback, which is why no existing test saw this): no agent read and no Deploy while the version list is held; the error panel and a working Retry when it fails; one section of each kind after switching to v2, with no duplicate-key warning; the viewer page's controls, and that Ctrl+S after typing writes nothing. Each was seen failing on `main` and mutation-checked against its line of the fix.

**Files:** [`use-agents.ts`](../../ui/manager/src/hooks/use-agents.ts), [`agent-detail.tsx`](../../ui/manager/src/pages/agent-detail.tsx), [`agent-manager-gui.md`](../agent-manager-gui.md)
