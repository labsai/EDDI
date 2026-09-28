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
- `requestAccess` answered 202, and `ApiClient` discards the body of a 202, so
  "you already have access" showed as "sent". It now answers 200 with the
  outcome.

```decision-log
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed per-space tenants, authorized by membership and checked again at deploy | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
```
