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
| `SYNTHESIS` | Moderator produces balanced conclusion |

### Context Scopes

| Scope | What the agent sees |
|---|---|
| `NONE` | Only the question (independent) |
| `FULL` | All previous transcript entries |
| `LAST_PHASE` | Only the previous phase's entries |
| `ANONYMOUS` | Previous entries with speaker names removed |
| `OWN_FEEDBACK` | Only feedback addressed to this agent |

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
| `list_group_conversations` | List past discussions |

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

