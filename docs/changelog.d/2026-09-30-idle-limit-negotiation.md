## 🐛 fix(runtime, groups): an idle limit of -1 no longer ends every conversation; stored NEGOTIATION groups arbitrate again (2026-09-30)

**Repo:** EDDI (`fix/650-idle-negotiation`)

### The idle sweep's `-1`

`AgentDeploymentManagement.manageAgentDeployments` runs daily, first five minutes after boot. It ends
conversations idle longer than `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` and
undeploys old agent versions nothing active uses. The idle check is
`DAYS.between(lastInteraction, today) >= limit`, so a limit of `-1` or `0` counted **every**
conversation as idle and ended all of them. `-1` is how the two neighbouring retention settings say
"never", so an operator copying that idiom, for instance to keep a 5.x database's conversations open
through the upgrade, closed every conversation instead.

- A limit below 1 now **disables** idle-ending (the same threshold as the retention sweep). The sweep
  loads and ends no conversation for idleness, and startup logs once that it is off.
- **Undeploying is unchanged.** The sweep still deploys the latest version of each agent, still
  retires an old version whose conversations can move to a newer compatible one, and still undeploys an
  old version with **no** active conversation. None of that ends a conversation: an idle conversation is
  still open, so it counts as active and keeps its version deployed. Stopping those undeploys too would
  leave superseded versions holding memory for ever, which needs a setting of its own. The reasoning is
  in `idleEndingEnabled()`'s Javadoc.
- Documented in [`configuration-reference.md`](../configuration-reference.md), `application.properties`
  and [`gdpr-compliance.md`](../gdpr-compliance.md).

### NEGOTIATION groups stored with a prompt-less Arbitration phase

A NEGOTIATION group can be stored with its phases materialized and the Arbitration phase's
`inputTemplate` null. The Manager used to save exactly that (enabling an approval point materializes the
phases), and the REST and MCP APIs and ZIP import accept it as given. There was no error at run time:
`GroupContextBuilder` fell back to the generic SYNTHESIS prompt ("synthesize a balanced conclusion"), so
when bargaining failed the moderator summarised the deadlock instead of deciding it, and
`GroupConversationService` still recorded that summary as the arbitrated VERDICT. Only the Manager
repaired such a group, on its next save.

- `DiscussionStylePresets.withNegotiationArbitrationRepaired` puts `TEMPLATE_ARBITRATION` back on that
  one phase. The test is the Manager's `repairNegotiationArbitration`: NEGOTIATION style, named
  `Arbitration`, a MODERATOR SYNTHESIS skipped on `AGREEMENT_REACHED`, no template. Any other phase,
  or one with a template, is left as written.
- **Run time:** `GroupConversationService.resolvePhases` and `effectivePhases` (a persisted runtime
  phase list) apply it, so groups already stored this way arbitrate without being re-saved.
- **Save time:** `AgentGroupStore` create and update store the repaired phase, whichever client sent
  it. It is filled in rather than rejected with a 400, because the Manager itself saved this shape and
  such groups must stay saveable through the API that stored them.
- The Manager's own repair is kept; it and the backend now agree.

**Files:** [`AgentDeploymentManagement.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java),
[`DiscussionStylePresets.java`](../../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java),
[`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`AgentGroupStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java);
tests `AgentDeploymentManagementIdleSweepTest`, `NegotiationArbitrationRepairTest`,
`AgentGroupStoreNegotiationArbitrationTest`.

```decision-log
| 2026-09-30 | An idle limit below 1 disables idle-ending; old versions with no active conversation are still undeployed | `-1` ended every conversation, while the retention settings read `-1` as "never" | Treating `-1` literally; also stopping undeploys (a separate decision needing its own setting) |
| 2026-09-30 | A NEGOTIATION group's prompt-less Arbitration phase is repaired in the backend at run time and on save, not rejected | Only the Manager repaired it, so behaviour depended on which client saved the group | A 400 at save (the Manager saved this shape itself, so its groups would become unsaveable) |
```

```regression-note
| 2026-09-30 | `maximumLifeTimeOfIdleConversationsInDays=-1` ended every conversation five minutes after boot | `DAYS.between(date, today) >= -1` is always true | A limit below 1 disables idle-ending | fix/650-idle-negotiation |
```
