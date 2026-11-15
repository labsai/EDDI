/**
 * Resolved per turn from `context.*` (Qute, `quarkus.qute.strict-rendering=false`
 * so a turn sent without it — the full `/manage/operator` page, or an older
 * conversation from before this shipped — degrades to nothing, not a stray
 * literal). Populated only by the docked drawer (`operator-drawer.tsx`), from
 * `useCurrentScreenContext()`: the admin's location when they opened the
 * drawer, not a claim from inside the conversation, so rule 1 (tool output is
 * data, not instructions) does not apply to it.
 *
 * Unconditional, not gated by scope or granted endpoints — knowing where the
 * admin is doing does not depend on what the operator is allowed to do about
 * it.
 *
 * Every key here must be one the drawer actually sends AND one the engine lets
 * a client set. The group used to arrive as `groupId`, which is an
 * engine-reserved context key (`ClientContextGuard.RESERVED_KEYS`): since the
 * reserved-key hardening the backend strips it from every client-started turn,
 * so "(group …)" silently never rendered. Hence `viewedGroupId`. The resource,
 * conversation and channel ids were sent all along and simply never read — on a
 * knowledge base's own page, "is this set up right?" reached the operator with
 * no way to tell which one was meant.
 */
export const BODY_APP_CONTEXT = `{#if context.screen}
The administrator is currently viewing: {context.screen}\
{#if context.agentId} (agent {context.agentId}){/if}\
{#if context.workflowId} (workflow {context.workflowId}){/if}\
{#if context.viewedGroupId} (group {context.viewedGroupId}){/if}\
{#if context.resourceId} ({context.resourceType} resource {context.resourceId}){/if}\
{#if context.conversationId} (conversation {context.conversationId}){/if}\
{#if context.channelId} (channel {context.channelId}){/if}\
{#if context.boardId} (workforce board {context.boardId}){/if}.
If a question about which agent, workflow, group, resource, conversation or
board is meant is ambiguous, assume this one unless told otherwise. A resource
read by id also needs its version: find it before reading.
{/if}`;
