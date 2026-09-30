## ✨ feat(manager, chat): compatible saves, deployment impact and version markers in the UIs (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following-ui`) — the Manager and Chat UI half of agent version
following; the backend ships in `feat/agent-version-following`. Against a backend without it, the
`compatible` parameter is ignored (every save stays breaking, as today) and the impact preview
hides itself when its request fails.

- **Manager — saving**: a "Compatible with the previous version" checkbox, unticked by default and
  reset after every save, on each save path that writes an agent version and has room for a choice:
  the post-save cascade dialog, the resource editor in cascade mode, the Studio editor panel, the
  workflow editor's Save & Test, and the Workforce agent editor sheet. Ticked on top of a version
  that predates generations, it says that conversations already on that version stay on it.
  The tick also resets when the page's agent context changes underneath it: the resource editor
  and the workflow editor stay mounted when only their query string changes, so a tick given for
  one agent could otherwise have written another agent's version as compatible. The workflow
  editor now also takes the agent version its next Save & Test replaces from the new context.
  `updateAgent` sends `compatible=true` only for an explicit `true`. Silent inline saves (agent
  section toggles, adding or removing workflows on the agent page) stay breaking.
- **Manager — deploying**: the agent page's Environments card shows, per environment, what the
  displayed version does to the conversations on the other deployed versions (`FOLLOW` / `STAY`
  with the reason), from `GET /administration/{env}/deploymentimpact/{agentId}`. The undeploy
  dialog notes that conversations a compatible deployed version can continue are moved rather than
  ended. A badge next to the version shows its compatibility generation.
- **Manager — conversations**: the conversation view and the debugger's memory inspector show the
  version each step ran on (`agent:version`), an "Agent moved from vN to vM" notice on the step
  after a move (`agent:switch`), and "Ended: the agent version was retired" for such an end.
  16 new keys in all 11 locales. The OpenAPI contract test exempts the new endpoint until the next
  snapshot refresh.
- **Chat UI**: a message refused because the conversation ended (410) re-reads it; when it ended as
  `agent-version-retired`, the footer says "This assistant was updated. Start a new conversation to
  continue." next to the existing new-conversation button.
- **Not built** (tracked in the plan): a compatible/breaking marker on every entry of the version
  list, and a keep/end choice inside the impact preview.
