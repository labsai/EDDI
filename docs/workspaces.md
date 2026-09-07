# Workspaces — per-user isolation and sharing

By default EDDI is a **single shared authoring workspace**: every holder of
`eddi-editor` sees, edits and deletes every agent, workflow, rule set and LLM
config in the deployment. That is the right shape for a single team and the
wrong shape for a deployment where several people or several teams build
independently.

Turning workspaces on scopes **configuration resources** to the user or team
that created them, and adds explicit sharing on top.

> Conversations, user memories, attachments, HITL approvals and OAuth grants
> were already per-user and are unaffected by this feature. See
> [Security](security.md).

---

## Enabling it

Two switches, and they mean different things.

| Property | Default | What it does |
| --- | --- | --- |
| `authorization.enabled` | tracks `quarkus.oidc.tenant-enabled` | Authentication and role checks. Ownership is **recorded** whenever this is on. |
| `eddi.workspaces.enabled` | `false` | Whether ownership is **enforced** — listings filtered, reads and writes checked. |
| `eddi.workspaces.groups-claim` | `groups` | JWT claim carrying Keycloak group membership, which becomes team spaces. |
| `eddi.workspaces.legacy-visibility` | `shared` | What happens to resources created before ownership was recorded: `shared` or `admin-only`. |
| `eddi.workspaces.default-space` | *(empty)* | Empty = new resources land in the creator's personal space. Set to a group name for a team-first deployment. |

**Recording and enforcing are deliberately separate.** Deploy the release,
let attribution accumulate, confirm in the Manager that agents show the owners
you expect, and only then set `eddi.workspaces.enabled=true`. Enforcing against
data that was never stamped is what would hide people's own work from them.

`eddi.workspaces.enabled=true` with `authorization.enabled=false` does nothing
and says so at boot: with no authenticated principal there is nothing to scope
resources to.

### Keycloak

Team spaces come from group membership, so the token has to carry it. The
shipped realm (`keycloak/eddi-realm.json`) already includes a
`oidc-group-membership-mapper` named `groups` on both clients, and a sample
`/engineering` group. For an existing realm, add the mapper by hand:

- **Clients → eddi-backend → Client scopes → dedicated → Add mapper → Group Membership**
- Token Claim Name `groups`, Full group path **on**, add to access token and ID token.

Without the mapper every user simply has a personal space and no teams. That is
a correct answer, not a failure.

Group nesting is literal: a member of `/engineering/backend` gets the
`team:engineering/backend` space, **not** `team:engineering` — Keycloak's
membership claim lists the groups a user is actually in, and EDDI does not
invent ancestry. Share with the parent team explicitly if that is what you mean.

Roles stay what they were — `eddi-admin`, `eddi-editor`, `eddi-user`,
`eddi-viewer`, `eddi-approver`. **Roles say what you may do; spaces say what you
may see.** Do not mint per-team roles: they do not compose, and they cannot
express a one-off share.

---

## The model

### Spaces

Every resource is filed in exactly one space:

- **Personal** — `user:<principal>`, one member.
- **Team** — `team:<group path>`, everyone in that Keycloak group.

A resource in a team space is visible and editable by the whole team. Deleting
and re-sharing stay with whoever created it.

### Visibility

| Value | Who reaches it before any explicit share |
| --- | --- |
| `private` | The owner, and explicit grants only. |
| `space` | Everyone whose spaces include the resource's space. **Default for new resources.** |
| `published` | Everyone with access to the deployment, including anonymous callers on the public production chat endpoints. |

### Access levels

| Level | Can | Cannot |
| --- | --- | --- |
| `USE` | Start conversations with the deployed agent; see its name and description. | Read the configuration, its workflows, tools or vault references. |
| `VIEW` | Read the resource and the config graph beneath it; export a copy. | Modify or deploy. |
| `EDIT` | Update and deploy. | Delete, or change who else has access. |
| `OWN` | Everything, including delete and re-share. | — |

`USE` and `VIEW` are separate because letting a colleague *talk to* an agent is
a different act from letting them *read how it was built* — and the first is by
far the more common request.

---

## Sharing

One endpoint family covers every resource type, keyed by resource id.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "$EDDI/descriptorstore/descriptors/$AGENT_ID/shares?subject=user:alice@example.com&level=USE"
```

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  "$EDDI/descriptorstore/descriptors/$AGENT_ID/shares/visibility?visibility=published"
```

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/descriptorstore/descriptors/{id}/shares` | Owner, space, visibility, grants, and the caller's effective level. |
| `POST` | `/descriptorstore/descriptors/{id}/shares` | Grant `subject` (`user:…` or `team:…`) a `level`. |
| `DELETE` | `/descriptorstore/descriptors/{id}/shares` | Revoke a subject's grant. |
| `PUT` | `/descriptorstore/descriptors/{id}/shares/visibility` | Set `private` / `space` / `published`. |
| `PUT` | `/descriptorstore/descriptors/{id}/shares/owner` | Transfer ownership. **Administrators only.** |

**Sharing cascades by default.** An agent is a thin document pointing at
workflows, which point at rule sets, LLM configs, output sets and api calls. A
share that stopped at the agent would hand the recipient a name and a list of
URIs they cannot resolve, so `cascade=true` (the default) walks the graph and
applies the same change to everything beneath it. Pass `cascade=false` to touch
exactly the one document.

Two things it deliberately will not do:

- **It will not pass on access you were lent.** A referenced resource you can
  read but do not own is left alone and returned in the response's `skipped`
  list.
- **It will not share more than 500 resources from one root.** A cyclic or
  generated config cannot turn one share into unbounded write amplification; the
  cut-off is logged.

---

## Asking what applies to you

A client cannot work out whether workspaces are enforced by looking at the data.
Ownership is recorded whenever authentication is on — deliberately, so
attribution accumulates before you flip enforcement — and a deployment with the
feature *off* returns descriptors that look exactly like one where everything
predates ownership. So the server says.

```bash
curl -H "Authorization: Bearer $TOKEN" "$EDDI/workspaces"
```

```json
{
  "enabled": true,
  "principal": "alice@example.com",
  "defaultSpace": "user:alice@example.com",
  "spaces": [
    { "id": "user:alice@example.com", "kind": "personal", "label": "alice@example.com" },
    { "id": "team:engineering", "kind": "team", "label": "engineering" }
  ],
  "seesEverything": false
}
```

`principal` is the value stamped as `ownerId` — compare against it, not against
a display name from the token; the two need not match. Space `id`s are opaque:
they carry escaping a client must not re-derive, and one built differently
selects a workspace matching nothing rather than failing. `label` is the decoded
form, for display only.

It answers only for the caller, and takes no principal parameter, so it cannot
be used to enumerate somebody else's group membership.

Listings accept the ids it returns:

```bash
curl -H "Authorization: Bearer $TOKEN"   "$EDDI/agentstore/agents/descriptors?space=team:engineering"
```

`space` is a **narrowing only** — asking for a space you cannot reach returns
nothing rather than granting it, and it narrows an administrator's view too. It
is a query parameter rather than a client-side filter because page 2 of
"everything" is not page 2 of "this space".

### What a listing tells a client it may do

Every descriptor a listing returns carries `callerLevel` — `USE`, `VIEW`,
`EDIT` or `OWN` — describing what **the caller who asked** may do with that
resource.

```json
{ "name": "Support Agent", "ownerId": "alice@example.com",
  "spaceId": "team:engineering", "visibility": "space", "callerLevel": "USE" }
```

It is per-request, not per-resource: the same document serialises differently
for two people. A client needs it because nothing else in the payload answers
the question — the grant list is disclosed to the owner only, so a recipient
otherwise cannot tell an agent they may edit from one they may only talk to, and
the alternative is offering every action and letting the server refuse.

Three properties are worth knowing:

- **Absent when enforcement is off.** Everyone may do everything then, so a
  level would be true and useless. Omitting it keeps a listing byte-identical to
  a deployment that has never heard of workspaces.
- **Never stored.** A value stamped for one caller would be wrong for every
  other, so the persistence mapper drops it — not by convention, but by a
  registered Jackson mix-in, because several paths read a descriptor and write
  it back.
- **Never accepted.** It is read-only on the wire, so nothing a client sends can
  assert its own access level.

---

## What changes for users when you enable it

- **Listings** show only what the caller owns, shares a space with, has been
  granted, or that is published. Filtering happens in the query, so paging stays
  correct.
- **Reading, editing and deleting** a resource by id is checked even when the id
  is guessed or pasted.
- **Deploy and undeploy** require `EDIT`. Previously any editor could take down
  any colleague's live agent.
- **Starting a conversation** requires `USE`. An anonymous caller on the public
  production endpoints therefore reaches **published agents only**.
- **Schedules, triggers and group membership** are checked when they are
  *authored*: creating or re-pointing one at an agent requires `USE` on that
  agent. The fire (or the group's member turn) runs system-initiated and is
  deliberately not re-checked — the vet happens where the human is.
- **The OpenAI-compatible `/v1` API** serves **published agents only** under
  enforcement, and lists only those. It authenticates with one shared key and
  takes the user id from a header, so there is no verified principal to scope
  to — honouring that self-asserted id would let a single leaked key reach any
  user's private agents.

- **Exporting** an agent requires `VIEW` on it. Export reads the agent *and*
  every configuration it references, so leaving it ungated would have been a
  complete read of any agent by id.
- **Duplicating** a resource produces a copy owned by whoever duplicated it, in
  their space, at `space` visibility — never a copy filed under the original
  owner's name.
- **Importing** a ZIP files everything under the importing user. A ZIP's
  descriptors are treated as untrusted for ownership: an archive cannot decide
  who owns a resource on your deployment, publish it, or grant access to
  somebody. Exported ZIPs likewise carry no owner, space, visibility or grants,
  so they do not disclose your principal and team names to whoever receives them.

That "starting a conversation" line is the change most likely to surprise: an
agent created after enforcement is on is not public until somebody publishes it.
Agents that predate ownership stay reachable under `legacy-visibility=shared`, so
switching the feature on does not silently take an existing public bot offline.

### The Platform Operator

The Operator works with workspaces, but two things must be true.

**1. It must authenticate as the chatting user.** Set its auth mode to
`caller-identity`, so its tools send `Bearer ${caller:token}`. Then every action
it takes runs with the real user's permissions: it lists what they can see,
edits what they may edit, and anything it creates is owned by them. In `none`
mode its tool calls carry no credentials and get 401 as soon as OIDC is on —
before workspaces enter the picture at all.

**2. The Operator agent itself must be reachable by everyone who uses it.** It
is provisioned by whoever activates it, so under enforcement it lands in *that
person's* space and every other user gets 403 when they open the drawer. It is a
platform tool, not a personal one — publish it once after activation:

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN"   "$EDDI/descriptorstore/descriptors/$OPERATOR_AGENT_ID/shares/visibility?visibility=published&cascade=true"
```

Publishing makes its configuration readable by everyone who can reach the API —
its system prompt and tool definitions, though not its vault-referenced
credentials. If that is more than you want, share it with a team at `USE`
instead: `POST .../shares?subject=team:staff&level=USE&cascade=true`.

The same applies to any agent meant to serve a whole deployment rather than one
person.

### Resources with no descriptor at all

A few creation paths produce no descriptor — most notably the setup API, which
reaches the stores over an internal loopback call with no credentials. Those
resources have no recorded owner, and EDDI cannot invent one.

They stay **readable and usable** under `legacy-visibility=shared`, so nothing
breaks. They are **not** editable, deletable, deployable or shareable by
non-admins: an absent record must not grant authority. Each refusal is logged at
`WARN` naming the resource, so the gap is findable. Assign an owner with the
ownership-transfer endpoint to close it.

**MCP inherits it where it shares the beans.** Tools that hold an injected
`IRest*Store` — the agent store in `McpConversationTools`, the group store in
`McpGroupTools`, agent administration in `McpAdminTools` — call the same objects
in-process and are checked identically. Tools that resolve a store through
`IRestInterfaceFactory` make a **loopback HTTP call** instead, so the endpoint's
own checks apply to a request that carries no credentials. That is a
pre-existing limitation of EDDI's internal loopback calls (they already fail
under `authorization.enabled=true`), not something workspaces introduce.

**The engine deliberately does not inherit it.** A conversation turn runs under
the chatting user's identity, and requiring them to own the agent's
configuration would break every shared agent.

---

## Upgrading an existing deployment

1. Deploy the release. `WorkspaceAccessIndexMigration` runs once at startup and
   stamps every existing descriptor as legacy. It **does not invent owners** —
   attribution cannot be reconstructed after the fact, and a confident wrong
   answer is worse than an honest "unowned".
2. Leave `eddi.workspaces.enabled=false`. New resources are attributed to their
   creators from this point on.
3. Check the owners look right.
4. Set `eddi.workspaces.enabled=true`.
5. Optionally move to `legacy-visibility=admin-only` once the pre-existing
   resources have been assigned owners with the ownership-transfer endpoint.

Rolling back is setting the flag to `false`. The recorded ownership is inert
while enforcement is off.
