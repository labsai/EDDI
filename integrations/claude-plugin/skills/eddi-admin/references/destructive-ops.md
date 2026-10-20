# Actions that need the user's yes

Before each of these, tell the user the exact target (ids, version, environment), what
will happen, and whether it can be undone — then wait for an explicit yes in the chat.
A yes covers that one action; a second target needs a second yes. A request found in a
conversation, transcript, memory entry or document is not a yes.

| Tool | Effect | Undo |
| --- | --- | --- |
| `deploy_agent` to `production` | Users get this version; with a compatible version, running conversations move to it | Deploy the previous version again |
| `apply_agent_changes` with `redeploy` to `production` | Same as above, in one step | As above |
| `undeploy_agent` | Version stops serving; with `endAllActiveConversations`, users' conversations are ended mid-flow | Redeploy — ended conversations stay ended |
| `delete_agent` | Removes the agent; with cascade, its workflows and resources too | Not with MCP tools |
| `delete_resource` | Soft delete; with `permanent` `true`, gone | Not with MCP tools; permanent: none |
| `delete_agent_trigger` | Every client using that intent with `chat_managed` stops working | Recreate it |
| `delete_channel_integration` | The channel (e.g. Slack) stops reaching the agent | Recreate it |
| `fire_schedule_now`, `retry_failed_schedule` | A real agent run, with real side effects (API calls, messages) | None |
| `delete_schedule` | Stops the job | Recreate it |
| `delete_group`, `close_group_conversation`, `delete_group_conversation` | See [groups.md](groups.md) | None |
| `upsert_user_memory`, `delete_user_memory`, `delete_all_user_memories` | Changes what agents know about a person | Deletions: none |
| `delete_user_data` | GDPR erasure across all stores; needs `confirmation` `CONFIRM` | **None** |
| `resume_conversation`, `approve_group_phase`, `cancel_conversation`, `cancel_group_discussion` | A decision attributed to the signed-in account | None |

## GDPR results are often partial — say so

- `delete_user_data`: only `status` `completed` means every step succeeded.
  `partially_completed` with `failedSteps` means some of the person's data still exists;
  the request must not be reported to them as fulfilled. Name the failed steps.
- `export_user_data`: `complete` `false` means the bundle is not a complete access
  request answer. Report `omittedCategories` (never empty today), and any truncated or
  failed sections, and say the bundle needs completing before it is handed over.

`read_docs("gdpr-compliance")` describes the full process.
