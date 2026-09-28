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
| `eddi.workspaces.legacy-visibility` | `shared` | What happens to resources created before ownership was recorded: `shared` or `admin-only`. **Changeable at runtime** — see [Runtime settings](#runtime-settings). |
| `eddi.workspaces.default-space` | *(empty)* | Empty = new resources land in the creator's personal space. A group name gives a team-first deployment. **Changeable at runtime.** |
| `eddi.workspaces.directory.enabled` | `true` | Record signed-in users so shares can name people — see [Finding people](#finding-people-the-user-directory). |
| `eddi.workspaces.directory.expose-email` | `true` | Whether share suggestions show email addresses. |

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
| `internal` | Everyone who is **signed in** may chat with it (`USE`). The configuration stays private, and anonymous callers are refused. The right setting for an agent meant for a whole organisation. |
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
  "$EDDI/descriptorstore/descriptors/$AGENT_ID/shares?subject=alice&level=USE"
```

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  "$EDDI/descriptorstore/descriptors/$AGENT_ID/shares/visibility?visibility=published"
```

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/descriptorstore/descriptors/{id}/shares` | Owner, space, visibility, grants, and the caller's effective level. |
| `POST` | `/descriptorstore/descriptors/{id}/shares` | Grant `subject` a `level`. The subject is a person (principal, username or verified email, with or without `user:`) or a team (`team:…`). |
| `DELETE` | `/descriptorstore/descriptors/{id}/shares` | Revoke a subject's grant. |
| `PUT` | `/descriptorstore/descriptors/{id}/shares/visibility` | Set `private` / `space` / `internal` / `published`. |
| `PUT` | `/descriptorstore/descriptors/{id}/shares/space` | File a resource you own under another of your spaces — personal work becoming team work. Ownership does not change. |
| `PUT` | `/descriptorstore/descriptors/{id}/shares/owner` | Transfer ownership. **Administrators only.** |
| `POST` | `/descriptorstore/descriptors/{id}/shares/requests` | Ask the owner for access (`level`, optional `message`). See [Notifications](#notifications-and-access-requests). |

Every change except a request takes `dryRun=true`, which answers with the same
`updated` / `skipped` lists and changes nothing. The Manager uses it to show what
a cascading change will reach before it is applied — a share of a group quietly
reaching a dozen agents is the surprise it exists to prevent.

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

**Sharing a lot at once is what team spaces are for.** Rather than sharing
twenty agents with the same five people, move them into a team space (or
create them there) — everyone in the Keycloak group then has edit access, and
people joining the group get it without anyone re-sharing.

### Finding people: the user directory

A grant is stored against a **principal** — the stable id the identity provider
puts in the token. People are not addressed that way, so EDDI records every
user who signs in (principal, display name, username, email, whether the
provider **verified** that email, and teams) and resolves a share against it:

1. an exact principal,
2. else a username, refused if two accounts share it,
3. else an email address — **verified ones only**.

A name that matches nobody is refused with a message saying the person has to
sign in once first, rather than storing a grant that reaches no one — which is
what sharing with an email address used to do. An unverified email is never a
way to find somebody: whoever can set their own address could otherwise collect
shares meant for someone else.

`GET /workspaces/directory?q=` powers the share box's suggestions. The share
dialog labels each grant with the person's name, and flags grants that match no
recorded user (typically ones made before the directory existed) so an owner can
remove them. The directory is covered by GDPR erasure and export. Set
`eddi.workspaces.directory.enabled=false` to take shares exactly as typed.

### Where new resources land

A new resource is filed in the first of:

1. the space named by the request's `X-EDDI-Space` header — the Manager sends
   the workspace currently in view, and says so in the workspace switcher
   ("New items are created in …");
2. the deployment's default space ([runtime setting](#runtime-settings));
3. the creator's personal space.

A header naming a space the caller is not a member of is refused with 403
before anything is created.

### Notifications and access requests

A share tells the recipient: it lands in their notifications (the bell in the
Manager), with a link straight to the resource — or to its chat, when the share
is `USE`. Following a link to something you cannot open shows a **Request
access** panel instead of an error; the request reaches the owner's
notifications, and they grant it in one click.

The request endpoint never reveals whether the resource exists or who owns it —
an id matching nothing is answered exactly like a delivered request. A person
may send 20 requests a day; notifications are kept for 90 days.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/workspaces/notifications?unreadOnly=&limit=` | The caller's notifications, newest first. |
| `GET` | `/workspaces/notifications/count` | `{"unread": n}` — cheap enough to poll. |
| `POST` | `/workspaces/notifications/read` | Mark `{"ids": [...]}` read, or all of them with `{}`. |

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

`ownership=mine` narrows to what the caller owns, and `ownership=shared` to what
somebody else owns and the caller can reach — the Manager's **Shared with me**.
Both combine with `space`. Descriptors carry a read-only `ownerName`, the
owner's display name from the directory, so a row can say who shared it.

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
is provisioned by whoever activates it, so under enforcement it would land in
*that person's* space and every other user would get 403 when they opened the
drawer. Activation therefore sets it to `internal`: everyone signed in may chat
with it, nobody else may read its configuration, and anonymous callers are
refused. If that fails (an older server, say), activation still succeeds and the
share dialog on the agent does the same by hand.

To limit it to one team instead, set it back to `space` and share it at `USE`:
`POST .../shares?subject=team:staff&level=USE&cascade=true`.

The same applies to any agent meant to serve a whole deployment rather than one
person — `internal` is usually the right visibility, `published` only when
anonymous visitors must reach it too.

### Secrets and variables that belong to a space

The vault and the global variables are deployment-wide, so under enforcement
**writing a global variable is administrator-only** (reading stays open to
editors), and an editor could never store their own LLM key at all. Each space
now has a tenant of its own in both, managed by its members without an
administrator — in the Manager's **Workspaces** page, or:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/spacestore/tenant?space=` | The tenant id to use in references. |
| `GET` / `PUT` / `DELETE` | `/spacestore/secrets[/{key}]?space=` | A space's secrets. Values are write-only. |
| `GET` / `PUT` / `DELETE` | `/spacestore/variables[/{key}]?space=` | A space's variables. |

Agents reference them explicitly: `${vault:<tenant>/openai-key}` and
`${vars:<tenant>/model}`, where every response names the tenant. Membership is
what authorizes, and it is checked where a human acts: **deploying an agent that
references a space's tenant requires the deployer to be a member of that
space**, so somebody lent edit access to your agent cannot point it at your
team's key and deploy it. Variables are expanded before the check, so a
reference cannot be smuggled in through one.

### Conversation review

Conversations stay private to the person chatting — the agent's owner cannot
read them. An agent can opt in to review:

```json
"conversationReview": { "enabled": true, "notice": "The support team may read this conversation to improve the agent." }
```

Then the people who maintain the agent (`EDIT` on it) may read its
conversations, and **only read** them. The opt-in is per version: a version
that did not opt in grants nothing, whatever later versions say. Every chat
window — the Manager and the Chat UI — shows the notice before the first
message (without a custom `notice`, a default sentence), from
`GET /agents/{agentId}/profile`, which anybody who may chat with the agent can
read.

`GET /agents/{agentId}/usage` (`VIEW` on the agent) answers how many
conversations an agent has had, how many are active and how many distinct
people started them — without reading any of them.

### Runtime settings

`default-space` and `legacy-visibility` can be changed without a restart by an
administrator, in the Manager's **Workspaces** page or with
`GET` / `PUT /workspaces/settings`. Every setting reports where its value comes
from:

- **`PINNED`** — the property is set in the configuration, and the API refuses
  to change it. Unset the property to hand it over to runtime control.
- **`STORED`** — changed at runtime; kept in the database.
- **`DEFAULT`** — neither.

Changes reach every node within seconds.

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
