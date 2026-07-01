# Group Conversations

> Multi-agent structured discussions with moderator synthesis.

## Overview

Group Conversations enable multiple agents to discuss a question. Each agent participates through its normal pipeline — agents are group-unaware by default. A `GroupConversationService` orchestrates the discussion through configurable phases.

## Discussion Styles

| Style | Flow | Best For |
|---|---|---|
| `ROUND_TABLE` | Opinion × N → Synthesis | Brainstorming, open-ended exploration |
| `PEER_REVIEW` | Opinion → Critique → Revision → Synthesis | Code review, document review |
| `DEVIL_ADVOCATE` | Opinion → Challenge → Defense → Synthesis | Risk assessment, stress-testing |
| `DELPHI` | Anonymous rounds → convergence → Synthesis | Forecasting, reducing groupthink |
| `DEBATE` | Pro → Con → Rebuttals → Judge | Trade-off analysis, comparisons |
| `TASK_FORCE` | Plan → Execute → Verify → Synthesis | Structured task decomposition, parallel execution |
| `CUSTOM` | Define your own phases | Any workflow |

## Quick Start (MCP)

```
# 1. Discover available styles
describe_discussion_styles

# 2. Create a group
create_group(
  name="Architecture Review",
  memberAgentIds="expert-1,expert-2,expert-3",
  memberDisplayNames="Backend Expert,Frontend Expert,DevOps Expert",
  moderatorAgentId="moderator-agent",
  style="PEER_REVIEW"
)

# 3. Run a discussion
discuss_with_group(groupId="<id>", question="Should we use microservices?")
```

## Quick Start (REST)

```bash
# Create group config
curl -X POST /groupstore/groups \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Architecture Panel",
    "members": [
      {"agentId": "expert-1", "displayName": "Backend Expert", "speakingOrder": 1},
      {"agentId": "expert-2", "displayName": "Frontend Expert", "speakingOrder": 2}
    ],
    "moderatorAgentId": "moderator-agent",
    "style": "ROUND_TABLE",
    "maxRounds": 2
  }'

# Start discussion
curl -X POST /groups/<groupId>/conversations \
  -H "Content-Type: application/json" \
  -d '{"input": "What is the best architecture for our new service?"}'
```

## Member Roles

Some styles require specific roles:

| Role | Used By | Purpose |
|---|---|---|
| `DEVIL_ADVOCATE` | DEVIL_ADVOCATE style | Argues against consensus |
| `PRO` | DEBATE style | Argues in favor |
| `CON` | DEBATE style | Argues against |

```
create_group(
  name="Debate Panel",
  memberAgentIds="agent-a,agent-b",
  memberRoles="PRO,CON",
  moderatorAgentId="judge-agent",
  style="DEBATE"
)
```

## Nested Groups (Group-of-Groups)

Members can be other groups. The sub-group runs its own discussion and its synthesized answer becomes the member's response.

```
# Create sub-groups
create_group(name="Team A", memberAgentIds="a1,a2", style="PEER_REVIEW")  → g1
create_group(name="Team B", memberAgentIds="a3,a4", style="DEBATE")       → g2

# Create meta-group with GROUP members
create_group(
  name="Tournament",
  memberAgentIds="g1,g2",
  memberTypes="GROUP,GROUP",
  moderatorAgentId="judge-agent",
  style="ROUND_TABLE"
)
```

Depth tracking prevents infinite recursion (`eddi.groups.max-depth`, default: 3).

## Custom Phases

For full control, define phases directly:

```json
{
  "name": "Custom Panel",
  "style": "CUSTOM",
  "phases": [
    {
      "name": "Independent Opinions",
      "type": "OPINION",
      "participants": "ALL",
      "turnOrder": "PARALLEL",
      "contextScope": "NONE"
    },
    {
      "name": "Peer Critique",
      "type": "CRITIQUE",
      "participants": "ALL",
      "targetEachPeer": true,
      "contextScope": "FULL"
    },
    {
      "name": "Final Synthesis",
      "type": "SYNTHESIS",
      "participants": "MODERATOR",
      "contextScope": "FULL"
    }
  ]
}
```

### Phase Types

| Type | Purpose |
|---|---|
| `OPINION` | Share perspective on the question |
| `CRITIQUE` | Review another member's response |
| `REVISION` | Revise own response based on feedback |
| `CHALLENGE` | Argue against consensus (devil's advocate) |
| `DEFENSE` | Defend position against challenges |
| `ARGUE` | Present argument for a side (debate) |
| `REBUTTAL` | Counter opposing arguments |
| `PLAN` | Decompose the question into sub-tasks |
| `EXECUTE` | Work on assigned sub-task |
| `VERIFY` | Review and validate another member's work |
| `SYNTHESIS` | Moderator produces balanced conclusion |

### Context Scopes

| Scope | What the agent sees |
|---|---|
| `NONE` | Only the question (independent) |
| `FULL` | All previous transcript entries |
| `LAST_PHASE` | Only the previous phase's entries |
| `ANONYMOUS` | Previous entries with speaker names removed |
| `OWN_FEEDBACK` | Only feedback addressed to this agent |
| `TASK_ONLY` | Only this agent's assigned task from the plan |
| `TASK_WITH_DEPS` | Assigned task plus outputs from dependency tasks |

### TASK_FORCE Configuration

The TASK_FORCE style uses a 4-phase pipeline: **Plan → Execute → Verify → Synthesize**.

1. **PLAN** — The moderator decomposes the goal into actionable tasks and assigns each to an agent
2. **EXECUTE** — Agents execute their assigned tasks in parallel (each sees only `TASK_ONLY` or `TASK_WITH_DEPS` context)
3. **VERIFY** — The moderator reviews each task result against the original goal
4. **SYNTHESIS** — The moderator combines all verified results into a coherent final deliverable

#### Pre-Configured Tasks

Pass a `tasks` array to skip the PLAN phase entirely — useful for deterministic, repeatable workflows:

```json
{
  "name": "Documentation Team",
  "style": "TASK_FORCE",
  "moderatorAgentId": "moderator-id",
  "members": [
    {"agentId": "researcher-id", "displayName": "Researcher"},
    {"agentId": "writer-id", "displayName": "Writer"}
  ],
  "tasks": [
    {
      "subject": "Research topic",
      "description": "Research the key trends and data points.",
      "assignToRole": "Researcher",
      "priority": 0
    },
    {
      "subject": "Write article",
      "description": "Using the research findings, write a 500-word article.",
      "assignToRole": "Writer",
      "dependsOn": ["Research topic"],
      "priority": 1
    }
  ]
}
```

When `tasks` is provided, the system posts `[System] "Pre-configured task plan: N tasks"` instead of invoking the moderator's LLM.

#### Task Dependencies

Use `dependsOn` to create sequential execution chains. Each entry references a task `subject`:

- Tasks with no dependencies execute in **parallel**
- Tasks with dependencies wait for their predecessors to complete
- Dependent tasks receive their predecessor's output via the `TASK_WITH_DEPS` context scope
- **Cycle detection** prevents circular dependency chains (fails fast at planning time)

#### Task Statuses

| Status | Meaning |
|---|---|
| `PENDING` | Waiting for dependencies or execution |
| `ASSIGNED` | Assigned to an agent, waiting to start |
| `IN_PROGRESS` | Currently being executed by an agent |
| `COMPLETED` | Agent produced output |
| `VERIFIED` | Moderator verified the result |
| `FAILED` | Agent or verification failed |

### Dynamic Agents

During TASK_FORCE (or any group) discussions, agents with the appropriate LLM tools can **create, recruit, and delegate to new agents at runtime**:

| Tool | Purpose |
|---|---|
| `CreateSubAgentTool` | Create a new ephemeral agent with a specific system prompt |
| `ConverseWithAgentTool` | Delegate a sub-task to an existing deployed agent |
| `FindAgentsByCapabilityTool` | Discover agents by capability keywords |
| `TeardownAgentTool` | Clean up dynamically created agents |

#### DynamicAgentConfig

Guardrails for dynamic agent creation are configured per-group via `AgentGroupConfiguration.dynamicAgents`:

```json
{
  "dynamicAgents": {
    "enabled": true,
    "allowCreation": true,
    "allowRecruitment": true,
    "allowDelegation": true,
    "maxCreatedAgentsPerDiscussion": 5,
    "maxRecruitedAgentsPerDiscussion": 10,
    "maxDelegationsPerTask": 3,
    "lifecyclePolicy": "ephemeral",
    "inheritParentModel": true,
    "allowedProviders": ["anthropic", "openai"],
    "allowedModels": {
      "anthropic": ["claude-sonnet-4-6"],
      "openai": ["gpt-4o"]
    }
  }
}
```

| Setting | Default | Purpose |
|---|---|---|
| `enabled` | `false` | Master switch for dynamic agent capabilities |
| `allowCreation` | `false` | Allow creating new agents (vs. only recruiting existing) |
| `allowRecruitment` | `false` | Allow recruiting already-deployed agents into the discussion |
| `allowDelegation` | `true` | Allow delegating sub-tasks to other agents |
| `maxCreatedAgentsPerDiscussion` | `5` | Cap on new agents created per discussion |
| `maxRecruitedAgentsPerDiscussion` | `10` | Cap on recruited agents per discussion |
| `maxDelegationsPerTask` | `3` | Cap on delegations per task |
| `lifecyclePolicy` | `EPHEMERAL` | `EPHEMERAL`, `KEEP_DEPLOYED`, `UNDEPLOY_ONLY`, or `AGENT_DECIDES` |
| `inheritParentModel` | `true` | Created agents inherit the parent agent's model |
| `allowedProviders` | `null` (any) | Whitelist of LLM providers |
| `allowedModels` | `null` (any) | Per-provider model whitelist |

Dynamic agents are tracked in `GroupConversation.dynamicMembers`, `createdAgentIds`, and `retainedAgentIds`.

### Tenant Quota Enforcement

If tenant quotas are enabled, `QuotaExceededException` is propagated regardless of the group's `onAgentFailure` policy — quota violations always abort the discussion to prevent runaway resource consumption.

## Protocol Configuration

```json
{
  "protocol": {
    "agentTimeoutSeconds": 60,
    "onAgentFailure": "SKIP",
    "maxRetries": 2,
    "onMemberUnavailable": "SKIP"
  }
}
```

| Setting | Options | Default |
|---|---|---|
| `agentTimeoutSeconds` | Any positive integer | 60 |
| `onAgentFailure` | `SKIP`, `RETRY`, `ABORT` | `SKIP` |
| `maxRetries` | 0+ | 2 |
| `onMemberUnavailable` | `SKIP`, `FAIL` | `SKIP` |

## REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/groupstore/groups` | Create group config |
| `GET` | `/groupstore/groups` | List group configs |
| `GET` | `/groupstore/groups/{id}` | Read group config |
| `PUT` | `/groupstore/groups/{id}` | Update group config |
| `DELETE` | `/groupstore/groups/{id}` | Delete group config |
| `GET` | `/groupstore/groups/styles` | List discussion styles |
| `POST` | `/groups/{groupId}/conversations` | Start discussion |
| `GET` | `/groups/{groupId}/conversations/{id}` | Read transcript |
| `GET` | `/groups/{groupId}/conversations` | List conversations |
| `DELETE` | `/groups/{groupId}/conversations/{id}` | Delete + cascade |

## MCP Tools

| Tool | Description |
|---|---|
| `describe_discussion_styles` | Rich descriptions of all styles |
| `list_groups` | List group configs |
| `read_group` | Read group config |
| `create_group` | Create group (name, members, style, roles, types) |
| `update_group` | Update group config JSON |
| `delete_group` | Delete group config |
| `discuss_with_group` | Start discussion, return transcript |
| `read_group_conversation` | Read conversation transcript |
| `list_group_conversations`  | List past discussions for a group, with state and timestamps                                                                         |
| `start_group_discussion`    | Start a discussion asynchronously (returns immediately). Poll with `read_group_conversation`                                         |
| `delete_group_conversation` | Delete a group conversation and cascade-delete all member conversations                                                              |

## Slack Integration

Group discussions integrate natively with Slack. See [slack-integration.md](slack-integration.md) for full setup instructions.

### UX Pattern: Header + Thread

All discussion styles use the same rendering pattern in Slack:

1. **Start Banner** — posted in the user's thread with style name, agent count, and question
2. **Agent Headers** — each agent's first contribution is a channel-level message with a short preview
3. **Full Content** — the complete response is posted as a thread reply under the agent's header
4. **Peer Feedback** — feedback threads under the target agent's header message
5. **Revisions** — revised contributions thread under the agent's own header
6. **Synthesis** — moderator's synthesis gets its own channel-level header + thread

### Discussion Styles in Slack

| Style | Phase Flow in Slack |
|-------|-------------------|
| **ROUND_TABLE** | Each agent posts → Moderator synthesizes |
| **PEER_REVIEW** | Agents post → Critiques thread under targets → Revisions thread under own → Synthesis |
| **DEVIL_ADVOCATE** | Agent posts → Challenger threads challenges → Agent threads defense → Synthesis |
| **DEBATE** | PRO agent posts → CON agent posts → Rebuttals thread under opponents → Judge synthesizes |
| **DELPHI** | Round 1 agents post → Round 2 agents post (convergence) → Synthesis |
| **TASK_FORCE** | Moderator posts plan → Agents post task results → Verifiers thread under targets → Synthesis |

### Trigger Keywords

Configure trigger keywords in `ChannelIntegrationConfiguration` to route to specific groups:

```
@EDDI panel: Should we adopt microservices?     → GROUP target "panel"
@EDDI debate: REST vs GraphQL                   → GROUP target "debate"
@EDDI peer: Review this architecture             → GROUP target "peer"
```

### Follow-up Conversations

After a discussion, users can reply in any agent's thread to ask follow-up questions. The system injects the agent's discussion context (contribution + peer feedback received) into the prompt for a contextual response.

## Configuration

```properties
# application.properties
eddi.groups.max-depth=3    # Max recursion depth for nested groups
```

