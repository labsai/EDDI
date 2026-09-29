## ✨ feat(workspaces): sharing people can actually use — directory, notifications, space secrets, review (2026-09-28)

**Repo:** EDDI (`feat/workspaces-sharing-ux`)

### Why

A hands-on run of workspaces with three users (alice, bob, carol) on a build of
`main` showed that the access model was sound and the experience around it was
not. Sharing with an email address stored a grant that reached nobody and still
answered 200. There was no way to find what had been shared with you, and the
recipient was never told. A 403 showed as "Something went wrong" with a Retry
that could never work. Creating anything in a team needed an operator property,
and changing that property needed a restart. An editor could neither store their
own LLM key nor keep another team from overwriting a global variable. The
Operator was unusable for anyone but the person who activated it. This branch
fixes all of that; [`docs/workspaces.md`](../workspaces.md) describes the result.

### What changed

**Backend**
- **User directory** (`engine/security/spaces/directory/`). Signed-in users are
  recorded asynchronously, on Mongo and on Postgres. A share now resolves in this
  order: exact principal, then username, then **verified** email. An unknown or
  ambiguous name is refused with a message that says why. Grants and owners
  carry display names, and `GET /workspaces/directory` provides autocomplete.
  The directory takes part in GDPR erasure and export through a new
  `IGdprParticipant` hook.
- **Listings** take `ownership=mine|shared`. This is backed by a new
  `IResourceFilter.NotMatching` filter: `$not` on Mongo, and
  `COALESCE(...) !~` on Postgres, where a missing field counts as not
  matching.
- **Create in a space.** A request can name its space in the `X-EDDI-Space`
  header. `SpaceHeaderFilter` refuses a space the caller is not a member of
  before anything is written.
- **Runtime settings.** `default-space` and `legacy-visibility` live in
  `workspace_settings` and can be changed with `GET/PUT /workspaces/settings`.
  A value set in the properties is pinned and wins; each setting reports
  whether it is PINNED, STORED or DEFAULT.
- **Sharing changes.**
  - Every change accepts `dryRun`.
  - `PUT .../shares/space` moves a resource into another of your spaces.
  - A new visibility, `internal`, gives USE to anyone signed in.
  - A share now notifies the recipient.
  - `POST .../shares/requests` asks the owner for access. It is limited to 20
    per day, and it never reveals whether the resource exists.
- **Notifications** (`workspace_notifications`) are kept for 90 days.
- **Space secrets and variables** (`/spacestore/*`). Each space has a hashed
  tenant. Members manage it; references read `${vault:<tenant>/k}` and
  `${vars:<tenant>/k}`. Deploying an agent that references a tenant requires
  membership of that space (`SpaceReferenceGuard`). Under enforcement, writing
  a global variable is admin-only.
- **Conversation review.** `AgentConfiguration.conversationReview` is an opt-in
  per version. Once opted in, the agent's EDIT holders may **read** its
  conversations, and nothing more. `/agents/{id}/profile` (USE) exposes the
  notice, and `/agents/{id}/usage` (VIEW) returns counts only.
- **Keycloak**: the compose realm's `eddi-frontend` web origins are now
  `http://localhost:${EDDI_PORT:7070}` / `https://localhost:${EDDI_HTTPS_PORT:7443}`,
  and `docker-compose.auth.yml` passes both ports to Keycloak, which substitutes
  them on import. Before this, running on any port other than 7070 broke
  browser login with a bare 401.

**Manager / Chat UI**
- The workspace switcher gains ownership filters and a "Created in" hint.
- The share dialog gains:
  - a dry-run preview;
  - directory autocomplete;
  - a warning on grants that reach nobody;
  - `internal` visibility;
  - move to team;
  - a chat link.
- A notification bell, and a request-access panel on detail pages.
- A `/manage/workspaces` page for settings, space secrets and variables.
- A conversation-review editor section and a chat notice, in both chat UIs.
- Operator activation now sets `internal` visibility.
- 101 new i18n keys, in all 11 locales.

### Fixes found on the way
- Ownership transfer from the Manager sent `user:bob` as the new owner id,
  which matched nobody. It now sends the principal, and the server also strips
  the prefix.
- The standalone Chat UI (`/chat`) has no sign-in, so on an OIDC deployment
  it reaches only agents anonymous callers may reach — published ones. Chat
  links for anything else now open the Manager's chat
  (`/manage/chat?agentId=…`), which signs people in; the share dialog offers
  the public `/chat` address only once an agent is published. Found on the
  live instance, where a `/chat` link to an "everyone signed in" agent hung on
  "Starting conversation…" behind a 401.
- The share preview rendered unnamed workflows and configs as empty bullets:
  the server sends `""`, and `name ?? id` only falls back on null.
- A default team applied to everyone, so someone outside it had new work
  filed where that team could read and edit it; with the setting now
  changeable at runtime, turning it on for one team moved everybody's new
  work there. It now applies to the team's members only.
- `requestAccess` answered 202, and `ApiClient` discards the body of a 202, so
  "you already have access" showed as "sent". It now answers 200 with the
  outcome.

### Hardening from a critical review, and the merge with main's security batch

An independent review of the branch found these problems; each is fixed here and has a test.

- **A secret could be read by assembling a reference from pieces (high).** Variables
  resolve before vault references, and every check saw only the pieces. Two variables
  holding `"${vau"` and `"lt:t.finance…/openai}"` joined into another team's key at
  runtime, past the deploy-time membership check, the grant check and the API-call
  reference guard, and a variable could be edited after deploy. `GlobalVariableResolver`
  now refuses any `${scheme:…}` reference that is not present whole in the value as
  written or in one substituted variable (`AssembledReferenceException`), and the deploy
  scan resolves in place so such an agent is refused at deploy. The false sentence in
  `docs/workspaces.md` that claimed variables were covered is replaced.
- **Space variables holding secret references.** The merge brought main's rule that
  only an admin may store a variable whose value is a vault or connection reference.
  `/spacestore/variables` now applies it too.
- **Conversation review showed too much.** Reviewers could read the raw memory
  document, including the person's long-term properties from other conversations. The
  raw read is owner-only again; reviewers get the simple log without properties or the
  detailed view.
- **GDPR missed personal-space secrets and variables.** A new participant erases them
  and exports them (secrets by name, never by value). Team spaces are untouched.
- **Access requests revealed that a resource existed.** A repeated request answered
  `ALREADY_REQUESTED`, where an unknown id answered `SENT`. It now answers `SENT`, and
  every attempt counts toward the 20-a-day limit, not only the delivered ones.
- **Legacy visibility failed open.** It fell back to `shared` when the settings store
  was unreadable at startup, even if an admin had set `admin-only`. It now fails closed
  until the store can be read.
- **Tenant hashes were 32 bits.** Two principals that slug alike collided after about
  2^32 attempts, and a collision means membership of the other tenant. They are now
  64 bits.
- **Smaller fixes:**
  - directory search no longer matches unverified or hidden email addresses;
  - `/workspaces` and the notification endpoints are open to every signed-in role;
  - space variables written by tenant id through `/variablestore` are never
    exportable;
  - the review-policy cache expires after write;
  - `X-EDDI-Space` is sent only on POSTs that create resources, so a chat turn can no
    longer be refused because of it.

From CodeRabbit's review of the PR:

- The deploy-time space check now follows `${connection:…}` into the connection
  document, the way the grant check already did.
- A review setting that cannot be read is no longer cached as "review off". Reading
  a conversation is denied, and the chat shows the notice.
- A settings update reads the store directly, so a failed read aborts the update and
  can no longer erase a pinned field's stored value.
- A GDPR participant whose export fails is named in the bundle and makes it
  incomplete.
- In the notification bell, a failure to mark a request read after a successful
  grant no longer reports the grant as failed.
- A move into a space is refused unless the target is a well-formed space id
  (`user:<principal>` or `team:<group>`). Administrators skip the membership
  check, so a malformed id used to file the whole cascade under a space nobody
  could hold.
- The deploy-time space check fails closed. A workflow, or the config of a
  scanned extension (LLM, HTTP calls, MCP, RAG), that cannot be read refuses
  the deployment instead of counting as "names no tenant".
- The same applies to a connection that cannot be read, and to a store failure
  while reading the agent itself. A missing agent still answers 404.
- The Chat UI's review notice is cleared and refetched when the chat's target
  changes, and a slower answer for the previous target cannot overwrite it. The
  input stays closed until the profile lookup has answered, so nobody can type
  before being told the conversation may be read.

Deliberately not changed, and documented instead:

- A sub-agent that inherits a team's key cannot be deployed for a non-member. The
  guard is right: the chatting user owns the sub-agent and could repoint it.
- Notification retention is applied when a new notification arrives, not by a sweep.
- Still open, as follow-ups:
  - exact principal or username taking precedence over a verified email that looks
    alike;
  - `internal` snippets being exported with USE;
  - admin-created non-space tenants being reachable from agents;
  - the Mongo usage count scanning every distinct user.

The merge with `main` (PRs 859 to 865) was resolved by hand in:

- `RestGlobalVariableStore`: both restrictions kept.
- `GdprDeletionResult` / `UserDataExport` / `GdprComplianceService` / `McpGdprTools`:
  `connectionGrants` and the participant maps both kept. The duplicate constructor
  introduced by the auto-merge was removed.
- `RestConversationStore`: main's strict delete and EDIT gates, plus the review reads.
- `ResourceSharingService`: main's `SharingChangedEvent`, never fired on a dry run,
  plus moves and notifications.
- `use-agents.ts`, the compose file and the 11 locales.

```decision-log
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed (64-bit) per-space tenants, authorized by membership and checked again at deploy; a reference assembled from variable pieces is refused at resolution | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
```
