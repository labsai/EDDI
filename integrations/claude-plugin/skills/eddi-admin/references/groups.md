# Groups of agents

A group is a stored configuration: member agents (or nested groups), a moderator, a
discussion style and its phases. Running a discussion is the `eddi` skill; this is how to
set groups up. Full reference: `read_docs("group-conversations")`.

## Choose the shape

- `describe_discussion_styles` — what each style does and when to use it: `ROUND_TABLE`,
  `PEER_REVIEW`, `DEVIL_ADVOCATE`, `DELPHI`, `DEBATE`, `TASK_FORCE`, `NEGOTIATION`, or
  `CUSTOM` phases. Match it to the question the user wants answered, not to the number of
  agents.
- `list_group_templates` — packaged groups (research pod, editorial team, ops task force,
  decision board, negotiation table), each with the roles it needs.

## Create

- **From a template** (preferred when one fits): `create_group_from_template` with a
  `roleAssignments` JSON object mapping each role to an agent id (for a HUMAN role, the
  person's principal id).
- **By hand**: `create_group`. Every member must be a **deployed** agent; a member that is
  not deployed is skipped at run time. Nested groups use `memberTypes` `GROUP`.
- Inspect and change: `list_groups`, `read_group`, `update_group` (full JSON replacement —
  read first), `delete_group` (ask first).

Collaboration features are opt-in by configuration: shared artifacts and the task list
exist only when their config is set, so tools for them are not offered otherwise. Human
approval of phases and human members are configured on the group as well; see
`read_docs("hitl")`.

## Try it

Run a short discussion on a real question with `start_group_discussion`, poll
`read_group_conversation`, and read the transcript for: members that said nothing useful
(wrong agent for the role), a synthesis that ignores dissent, and the cost. Use
`followup_with_member` to probe a weak contribution before changing the member's agent.

## Standing teams

A team keeps a backlog that outlives any one discussion; scheduled cadences pull tasks
into task-force runs. `add_team_task` (higher priority runs earlier), `list_team_backlog`
for status, assignee and verification outcome.

## Clean up

`close_group_conversation` ends a discussion and removes agents created only for it;
`delete_group_conversation` also deletes its transcript and shared artifacts. Both are
final — ask first.
