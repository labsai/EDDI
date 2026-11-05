# End-User Catalog

The catalog is the list of agents and teams an **end user** may pick from. Workforce shows it to a
signed-in `eddi-user` in place of the authoring screens, and any other end-user app can read it the
same way.

It exists because the authoring stores (`/agentstore`, `/groupstore`, `/deploymentstore`) answer
only `eddi-admin` and `eddi-editor`. An app that read them on behalf of an `eddi-user` got 403 on
every page. The catalog answers at the level talking needs (`USE`, the check that also guards
starting a conversation) and tells the caller nothing about how an agent is built.

---

## What it returns

| Endpoint | Roles | Returns |
| --- | --- | --- |
| `GET /catalog/agents` | `eddi-user`, `eddi-editor`, `eddi-admin` | Listed agents the caller may use |
| `GET /catalog/groups` | `eddi-user`, `eddi-editor`, `eddi-admin` | Listed groups (teams) the caller may use |

An **agent** is included when all three hold:

1. **It is deployed.** It must be READY in the `production` environment, the same set
   `GET /v1/models` lists.
2. **The caller may use it.** The check is the `USE` check behind `POST /agents/{agentId}/start`
   (see [Workspaces](workspaces.md#access-levels)). With workspaces off, everyone may use everything.
3. **It is listed.** That means `"listed": true` on the agent configuration, or, with workspaces
   enforced, `published` or `internal` visibility.

A **group** is included when the caller may use it (the same check as running a discussion with
`POST /groups/{groupId}/conversations`) and it is listed by the same rule. Groups are not deployed,
so the first condition does not apply.

An **anonymous** caller never sees more than the published agents, exactly as on `/v1/models`.
They hold no subject, so under enforcement only `published` admits them.

### Response shape

```json
[
  {
    "agentId": "6740a1f3c2b9d84e1f0a7c55",
    "name": "Support Assistant",
    "description": "Answers questions about orders and returns",
    "reviewNotice": null,
    "rating": null,
    "callerLevel": "USE",
    "appearance": {
      "displayName": "Ada from Support",
      "avatar": "https://cdn.example.com/ada.png",
      "accentColor": "#0a7d55",
      "starters": ["Where is my order?", "I want to return an item"]
    },
    "conversationControls": {
      "undo": true, "redo": true, "attachments": true, "rating": true,
      "quickReplies": true, "streaming": true, "rawHtml": false
    }
  }
]
```

The agent fields are the ones `GET /agents/{agentId}/profile` returns: name, description, review
notice, rating controls, and the deployed version's `appearance` and `conversationControls`
([agent presentation](agent-config-authoring.md#agent-presentation-appearance-conversationcontrols)).
`appearance` is `null` when the agent sets none; `conversationControls` is always present, with
every default applied (`rawHtml` is `false` unless the agent enables it). Workforce Home draws its
cards and starter chips from these, so it needs no profile call per agent. `callerLevel` is what
the caller holds on the agent (`USE`, `VIEW`, `EDIT` or `OWN`) and is always at least `USE`.

```json
[
  {
    "groupId": "6740a2b8c2b9d84e1f0a7c90",
    "version": 3,
    "name": "Research pod",
    "description": "Three analysts and a reviewer",
    "style": "PEER_REVIEW",
    "members": [
      { "agentId": "6740a1f3c2b9d84e1f0a7c55", "displayName": "Analyst", "role": null, "memberType": "AGENT", "speakingOrder": 1 }
    ],
    "callerLevel": "USE"
  }
]
```

A group entry carries the current version, the style and the members. That is enough to show a
team and open a thread with one of its members. Phases, tasks and approval settings describe how
the team is built, so they are left out.

Both lists are sorted by name. An entry that cannot be read, such as a broken descriptor, is left
out without failing the rest of the list.

---

## Listing is opt-in

```json
{
  "workflows": ["eddi://ai.labs.workflow/workflowstore/workflows/6740a0e1c2b9d84e1f0a7c11?version=2"],
  "listed": true
}
```

`listed` is a field of the agent configuration and of the group configuration. **It defaults to
`false`**, so upgrading a deployment lists nothing. Without that default, every signed-in user would
be offered the Platform Operator, test agents and internal helpers the moment the catalog shipped.

- **Listing is not a permission.** A listed agent stays invisible to anyone who may not use it, and
  an unlisted agent stays usable by anyone who may (by its id, or through a link).
- **With workspaces enforced**, `published` and `internal` visibility also count as listed. Those
  visibilities already mean "for everyone" or "for everyone signed in", so you do not need to set
  the flag as well. With workspaces off, visibility is not consulted, and only the flag lists
  anything.
- **The Platform Operator** counts as listed only through its own `listed: true`. Its visibility
  does not list it.

Set it in the Manager with the **Listed in the catalog** switch, which sits on an agent's page and
in a group's panel (and in a team's settings in Workforce). You can also put it in the JSON with
`PUT /agentstore/agents/{id}` or `PUT /groupstore/groups/{id}`, which take the full document. Over
MCP only a group's flag can be set, with `update_group`: no MCP tool writes the agent document
(`update_resource` does not take the agent type). An agent change takes effect for the catalog
once the new version is deployed. The flag is part of the configuration document, so ZIP
export/import and [agent sync](agent-sync-guide.md) carry it like any other field.

---

## In Workforce

| Role | What Workforce shows |
| --- | --- |
| `eddi-user` | The catalog: listed agents (opens a chat) and listed teams (opens the board), their discussions and threads. No team builder, team settings or analytics. `/` and `/welcome` go straight to `/workforce` |
| `eddi-editor` | The building dashboard, read from the stores, plus a **What end users see** section that counts the listed entries, or explains how to list one when there are none |
| `eddi-admin` | As an editor, plus a link to the Platform Operator at `/manage/operator`. Workforce does not mount the Operator's drawer |

A deployment that maps roles from a claim other than `realm_access.roles` shows the full Workforce,
because the Manager cannot see the roles and leaves the decision to the backend, as it does
everywhere else.
