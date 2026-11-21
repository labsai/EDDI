# Microsoft Teams Integration

> **Since**: v6.7.0

A **Teams integration** connects an EDDI agent to Microsoft Teams through an Azure Bot. People chat with the bot in
personal chats, group chats and channel threads, and every message they send starts one agent turn; the answer is
posted back in the same conversation. It is a channel integration with `channelType: "teams"`, so it reuses what
channels already have: the `targets` routing table with trigger keywords, `defaultTargetName`, the Manager's Channels
page and the MCP `create_channel_integration` tool.

EDDI talks to Teams over the Bot Connector REST API directly. No Bot Framework SDK is involved.

## What you need

1. **An Azure Bot resource.** It is mandatory: Teams only delivers to bots registered with the Bot Framework. Create a
   **single-tenant** bot (new multi-tenant bots are no longer offered). Note its **Microsoft App ID** and the tenant id
   of its app registration.
2. **A credential for the app registration.** A certificate is preferred: from 2027-03-01 Microsoft requires
   certificate-based tokens for file access, and a certificate has no secret to leak. A client secret works too.
3. **A public HTTPS address for EDDI.** In the Azure Bot, set *Configuration → Messaging endpoint* to
   `https://<your-eddi>/integrations/teams/messages`. Every Teams integration of a deployment shares this one endpoint;
   the token of each request says which bot it is for. For local development use a tunnel (the Bot Framework Emulator
   is archived and cannot replace a real Azure Bot).
4. **The Teams channel enabled** on the Azure Bot (*Channels → Microsoft Teams*).

## 1. Create the connection

The integration holds no secret. The credential lives in an EDDI [connection](connections.md), which must be:

| Field | Value |
| --- | --- |
| `authType` | `OAUTH2_CLIENT_CREDENTIALS` |
| `binding` | `SERVICE` |
| `oauth.clientId` | the bot's Microsoft App ID, exactly |
| `oauth.tokenUrl` | `https://login.microsoftonline.com/<tenantId>/oauth2/v2.0/token` |
| `oauth.scopes` | `["https://api.botframework.com/.default"]` |
| `oauth.clientAuthMethod` | `private_key_jwt` with the certificate's private key and thumbprint (preferred), or `client_secret_post` / `client_secret_basic` with a vaulted secret |
| `baseUrlAllowlist` | the Bot Connector origin your bot is served from, for example `https://smba.trafficmanager.net` |

The token endpoint must be listed in the deployment's credential endpoint allowlist
(`eddi.connections.credential-endpoint-allowlist`), as for any OAuth connection.

`OAUTH2_JWT_BEARER` is not accepted: it is the RFC 7523 §2.1 grant used by Google service accounts, and Entra issues no
app token for it. The certificate path is client credentials with `private_key_jwt`.

## 2. Create the integration

```json
{
  "name": "teams-helpdesk",
  "channelType": "teams",
  "platformConfig": {
    "appId": "11111111-2222-3333-4444-555555555555",
    "tenantId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "connection": "teams-bot"
  },
  "targets": [{ "name": "default", "type": "AGENT", "targetId": "<agentId>", "triggers": [] }],
  "defaultTargetName": "default"
}
```

| Key | Required | Meaning |
| --- | --- | --- |
| `appId` | yes | The bot's Microsoft App ID (a GUID). |
| `tenantId` | yes | The Entra tenant (GUID) of the bot's single-tenant app registration. Its token endpoint is pinned from it. |
| `connection` | yes | The connection from step 1, by name. |
| `allowedTenantIds` | no | Further tenants whose users may talk to the bot, comma-separated GUIDs. Empty means `tenantId` only — never "every tenant". |
| `conversationId` | no | A channel or group chat (`19:…@thread…`, without `;messageid=`) this integration answers in. Without it, the integration serves the bot's personal chats and every conversation no other integration claims. |
| `hitlApprovalConversationId`, `hitlApproverAadObjectIds` | no | Reserved for approval cards; validated, not used yet. |

Any other key is refused, a pasted secret as much as a `${vault:…}` reference.

One bot can have one channel-less integration and any number of channel-bound ones (one per `conversationId`). A
message in a channel goes to the integration bound to that channel **and** declaring the same app id; anything else
goes to the bot's channel-less integration.

### Who may declare an app id

Whoever owns an app id's integration receives that bot's messages, so the save proves the right to it. The connection
must carry `oauth.clientId` equal to `appId`, the tenant's Entra token endpoint and the Bot Connector scope, **and
saving obtains one access token from that endpoint** with the connection's own secret or certificate. A connection that
names somebody else's client id with an invented secret fails there, because Entra refuses it. A save therefore needs
Entra to be reachable.

The client id, token endpoint and scope are checked again (without a network call) on every inbound activity, because
a ZIP import or a direct store write skips save validation and the connection can be edited later. An activity for an
integration whose connection no longer matches is dropped with a WARN.

This proves the saver can use the app's credential. It does not check who owns the named connection: connections are
not scoped per workspace yet, so anyone who may create channel integrations can name any connection that already holds
the app's credential.

## 3. Install the app in Teams

Download the app package from the channel's page in the Manager (*Download app package*), or call
`GET /channelstore/teams/<integrationId>/app-package?version=<n>` (roles `eddi-admin`, `eddi-editor`). The zip holds a
`manifest.json` (schema 1.29) for the bot with the personal, team and group-chat scopes, and the two icons Teams
requires. Optional query parameters: `developerName`, `websiteUrl`, `privacyUrl`, `termsOfUseUrl` (https only) and
`accentColor`. Upload it in Teams (*Apps → Manage your apps → Upload an app*) or in the Teams admin center.

## How a message is handled

1. **Authentication.** Every request carries a Bot Connector JWT, and it is validated as the Bot Connector
   authentication documentation requires: `RS256` only (`none` and `HS256` are refused before any key is looked up),
   a signature by a key from `https://login.botframework.com/v1/.well-known/openidconfiguration` (refreshed every
   24 hours and once, rate-limited, for an unknown key id), `iss` `https://api.botframework.com`, `aud` the app id of a
   configured integration, `exp` / `nbf` with five minutes of skew, a `serviceurl` claim equal to the activity's
   `serviceUrl`, and the signing key endorsed for the activity's channel (`msteams`). There is no setting to switch
   any check off. A refused request gets 401 (403 for a missing endorsement).
2. **Acknowledgement.** A verified activity is de-duplicated by app id, conversation and activity id (shared across
   nodes in cluster mode, cache `teams-activity-dedup`), answered 200 at once, and processed in the background.
3. **Tenant.** The activity's tenant must be `tenantId` or one of `allowedTenantIds`. A single-tenant bot gets no
   tenant isolation from the platform for this, so the check is the boundary. Anything else is dropped.
4. **Routing.** The bot's own mention (`<at>…</at>`) is removed from the text; mentions of other people stay.
   Targets and trigger keywords work as for Slack, and a conversation keeps its target once chosen.
5. **The turn.** Each (conversation, user, agent) has one EDDI conversation, replaced by a fresh one if it has ended.
   The user is `teams:<tenantId>:<aadObjectId>`: the user's Entra object id, stable across chats. This is a channel
   identity, not an authenticated EDDI login, so a `PER_USER` connection is never resolved for it. Messages without an
   Entra object id (guests without one, other bots) are ignored.
6. **The answer** is posted in the same conversation as a reply to the message, with `textFormat: extendedmarkdown`.
   Text over 80 KB is split. Sends are paced to Teams' per-conversation limits (7 a second, 8 in 2 seconds, 60 in
   30 seconds, 1800 an hour), and 429, 412, 502 and 504 are retried with backoff. The token comes from the connection
   on every send, with the activity's `serviceUrl` as the target, so the connection's `baseUrlAllowlist` decides where
   it may go.

A paused conversation (HITL) answers with a notice; approval cards in Teams are not available yet, so a reviewer
decides in the Manager, over REST or MCP. Group-discussion targets are not supported in Teams yet. Being added to a
conversation posts a short welcome.

The last verified `serviceUrl` and ids of each conversation are stored (`teams_conversation_references`), for messages
sent later. A personal chat's record names its user and is removed with that user's data on a GDPR erasure.

## Settings

| Property | Default | Meaning |
| --- | --- | --- |
| `eddi.teams.max-body-bytes` | `262144` | Largest activity accepted (413 above). |
| `eddi.teams.request-timeout-seconds` | `60` | How long a message waits for the agent before a "still working" notice. |

The endpoint is public at the HTTP layer (`quarkus.http.auth.permission.teams`, POST only). Its bearer token is not an
OIDC token, so a dedicated authentication mechanism keeps the OIDC mechanism from rejecting it first; the endpoint then
validates the token itself.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Save fails with `invalid_client` or similar | Entra refused the connection's secret or certificate, or the client id, tenant or scope is wrong. |
| Save fails naming `oauth.tokenUrl` or the scope | The connection is not pinned to the tenant's endpoint, or lacks `https://api.botframework.com/.default`. |
| Teams shows the bot but nothing answers, logs say `Teams activity refused: AUDIENCE` | No integration declares that app id. |
| Logs say `…from tenant …, which it does not accept` | Add the tenant to `allowedTenantIds`. |
| Logs say the connection is not the app's own | The connection was edited or imported with another client id, token URL or scope. |
| Send fails with `may not be sent to https://…` | Add the `serviceUrl` origin to the connection's `baseUrlAllowlist`. |
