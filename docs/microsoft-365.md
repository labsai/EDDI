# Microsoft 365 Setup (Entra ID)

> **Since**: v6.7.0

Every EDDI feature that talks to Microsoft 365 authenticates as an **app registration in Microsoft Entra ID**,
through an EDDI [connection](connections.md). This page is the one place that explains how to set those up. The
feature pages ([Teams](teams-integration.md), [Email](email-integration.md)) link here instead of repeating it.

Three rules run through every section:

1. **One app registration per feature.** A Teams bot, a mailbox and a SharePoint knowledge base each get their own
   app. A leaked bot credential then cannot read mail, and a tenant admin can consent to each one separately. Sharing
   one app between features works (a connection is only a token endpoint, a client and scopes), but it is not
   recommended.
2. **A certificate, not a client secret.** Use `clientAuthMethod: private_key_jwt` with a certificate credential.
   Entra holds only the public half, so there is no shared secret to leak. Some Microsoft features accept only
   certificates, federated credentials or managed identities: from 2027-03-01, Teams bot file download and upload
   need an app-only token that must come from one of those three
   ([Send and receive files using bots](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/bots-filesv4)).
   A client secret is the last resort.
3. **Least privilege, then a negative test.** Grant the narrowest permission that works, then prove the boundary by
   doing something the app must **not** be able to do and checking that it fails.

## Create an app registration with a certificate

1. **Entra admin center → App registrations → New registration.** Name it after the feature (`eddi-teams-bot`,
   `eddi-mail-invoices`). For *Supported account types*, choose *Accounts in this organizational directory only*
   unless the feature must serve other tenants (only Teams can; see its page).
2. Note the **Application (client) ID** and the **Directory (tenant) ID**.
3. Create a certificate. A self-signed one is fine; Entra checks the key, not the issuer:

   ```bash
   openssl req -x509 -newkey rsa:3072 -sha256 -days 730 -nodes \
     -keyout eddi-app.key -out eddi-app.crt -subj "/CN=eddi-teams-bot"
   openssl x509 -in eddi-app.crt -noout -fingerprint -sha256
   ```

4. **Certificates & secrets → Certificates → Upload certificate**: upload `eddi-app.crt` (the public half only).
5. Store the private key (`eddi-app.key`) in the EDDI [secrets vault](secrets-vault.md), then delete the local copy.
6. Allow the token endpoint for client credentials in the deployment's connection settings:
   `eddi.connections.credential-endpoint-allowlist` must contain `https://login.microsoftonline.com`
   ([Two allowlists](connections.md#two-allowlists-and-why-they-are-separate)).
7. Create the connection. The fields every Microsoft connection shares:

   ```json
   {
     "name": "teams-bot",
     "authType": "OAUTH2_CLIENT_CREDENTIALS",
     "binding": "SERVICE",
     "oauth": {
       "tokenUrl": "https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token",
       "clientId": "<application-id>",
       "clientAuthMethod": "private_key_jwt",
       "privateKey": "${vault:eddi-teams-bot-key}",
       "certificateThumbprint": "<the SHA-256 fingerprint>",
       "certificateThumbprintHeader": "x5t#S256",
       "scopes": ["<the feature's scope>"]
     },
     "baseUrlAllowlist": ["<the feature's API origin>"]
   }
   ```

   The scope and allowlist differ per feature, below. Rotate by uploading a second certificate, switching the
   connection to it, then deleting the old one in Entra.

**Client secret instead** (last resort): *Certificates & secrets → Client secrets → New client secret*, vault the value,
and set `clientSecret: "${vault:…}"` instead of `privateKey` / `certificateThumbprint`. Record its expiry: Entra secrets
expire, and nothing in EDDI warns you before they do.

## Feature by feature

| Feature | Status | Permissions (least privilege first) | Connection scope | `baseUrlAllowlist` |
| --- | --- | --- | --- | --- |
| [Teams bot](#teams-bot) | available | **None on Graph.** An Azure Bot resource only | `https://api.botframework.com/.default` | `https://smba.trafficmanager.net` |
| [Mailbox over IMAP/SMTP](#mailbox-over-imap-and-smtp) | available | `IMAP.AccessAsApp` (+ `SMTP.SendAsApp`), scoped with `Add-MailboxPermission` | `https://outlook.office365.com/.default` | `imaps://outlook.office365.com` (+ `smtp://smtp.office365.com:587`) |
| [Mailbox over Graph](#mailbox-over-graph) | planned | **No Entra `Mail.*` consent.** Exchange roles `Application Mail.ReadWrite` + `Application Mail.Send`, scoped to one mailbox | `https://graph.microsoft.com/.default` | `https://graph.microsoft.com` |
| [SharePoint knowledge base](#sharepoint-and-onedrive) | planned | `Sites.Selected` per site, or `Lists.SelectedOperations.Selected` per library | `https://graph.microsoft.com/.default` | `https://graph.microsoft.com` |
| [Personal OneDrive](#sharepoint-and-onedrive) | planned | Delegated `Files.Read` (+ `Files.ReadWrite` for tools) and `offline_access`, or delegated `Files.SelectedOperations.Selected` | per user (`PER_USER` connection) | `https://graph.microsoft.com` |

"Planned" rows describe the intended setup for features of the Microsoft 365 plan that are not released yet.

### Teams bot

The bot needs **no Graph permission at all** for messaging: Teams talks to it through the Bot Connector, and the
connection asks only for a Bot Connector token.

1. Create the app registration with a certificate, as above.
2. Create an **Azure Bot** resource for that app id (*Type of App: Single Tenant*; new multi-tenant bots cannot be
   created), and enable its **Microsoft Teams** channel.
3. Create the connection with scope `https://api.botframework.com/.default` and `baseUrlAllowlist`
   `["https://smba.trafficmanager.net"]`. The Teams integration requires exactly this token endpoint, client id and
   scope, and obtains one token when you save it, so a wrong credential fails at save time.
4. Continue on the [Teams integration page](teams-integration.md).

**Negative test:** with the bot's token, a Graph call (`GET https://graph.microsoft.com/v1.0/users`) must fail. A Bot
Connector token is not a Graph token, and the app has no Graph permission to get one.

### Mailbox over IMAP and SMTP

What the [email integration](email-integration.md) uses today.

1. Create the app registration (certificate preferred).
2. **API permissions → Office 365 Exchange Online → Application permissions → `IMAP.AccessAsApp`** (and
   `SMTP.SendAsApp` for conversation mode), then grant admin consent. These let the app use the protocols; they open
   no mailbox by themselves.
3. In Exchange Online PowerShell, register the service principal and grant **one mailbox**:

   ```powershell
   New-ServicePrincipal -AppId <application-id> -ObjectId <enterprise-application-object-id>
   Add-MailboxPermission -Identity "invoices@contoso.com" -User <service-principal-object-id> -AccessRights FullAccess
   ```

   Exchange's RBAC for Applications does not cover `IMAP.AccessAsApp`, so the IMAP path keeps
   `Add-MailboxPermission`.
4. Connection scope `https://outlook.office365.com/.default`; allowlist `imaps://outlook.office365.com` (and the SMTP
   origin in conversation mode).

**Negative test:** point a test integration at an unrelated mailbox. The IMAP login must fail.

### Mailbox over Graph

Planned. Application `Mail.ReadWrite` / `Mail.Send` consented in Entra covers **every mailbox in the tenant**, and
Exchange's RBAC for Applications is additive to Entra consent. Scoping therefore only works if the app has **no Entra
consent for any `Mail.*` permission** and gets Exchange roles with a scope instead:

```powershell
New-ServicePrincipal -AppId <application-id> -ObjectId <enterprise-application-object-id> -DisplayName "eddi-mail-invoices"
New-ManagementScope -Name "eddi-invoices-mailbox" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'invoices@contoso.com'"
New-ManagementRoleAssignment -App <application-id> -Role "Application Mail.ReadWrite" -CustomResourceScope "eddi-invoices-mailbox"
New-ManagementRoleAssignment -App <application-id> -Role "Application Mail.Send" -CustomResourceScope "eddi-invoices-mailbox"
Test-ServicePrincipalAuthorization -Identity <application-id> -Resource "invoices@contoso.com"
Test-ServicePrincipalAuthorization -Identity <application-id> -Resource "ceo@contoso.com"
```

The first `Test-ServicePrincipalAuthorization` must show the two roles as in scope, the second must not. Role changes
take 30 minutes to 2 hours to apply; this cmdlet bypasses that cache, so use it rather than waiting. A scope can also
be an administrative unit; group-based scopes match direct members only.

**Negative test:** read an unrelated mailbox (`GET /users/ceo@contoso.com/messages`); expect 403.

### SharePoint and OneDrive

Planned.

- **A SharePoint site:** grant `Sites.Selected` (application) and consent. That grants **nothing** until a site owner
  or admin adds the app to a specific site, with Graph
  `POST /sites/{site-id}/permissions` and a body naming the app and `"roles": ["read"]`. For a single document library,
  `Lists.SelectedOperations.Selected` with a grant on that list is narrower still; a grant below site level breaks
  permission inheritance on that item.
- **A user's OneDrive:** a `PER_USER` connection with delegated `Files.Read` and `offline_access` (add
  `Files.ReadWrite` only for agent tools that write), or delegated `Files.SelectedOperations.Selected` to limit tools
  to files explicitly granted.
- SharePoint deprioritises undecorated traffic; EDDI sends a decorated `User-Agent`.

**Negative test:** read a site that was not granted; expect 403.

## National clouds

Microsoft's government and China clouds use different hosts for every service on this page: the token endpoint
(`login.microsoftonline.us` for GCC High and DoD, `login.chinacloudapi.cn` for 21Vianet), Graph, Exchange and the Bot
Connector service URLs. EDDI's Teams integration currently pins the commercial token endpoint
(`login.microsoftonline.com`), so it serves the commercial cloud and GCC, which signs in there too. GCC's Bot Connector
service URL differs from the commercial one, though: put the `serviceUrl` origin your activities carry into the
connection's `baseUrlAllowlist` (Microsoft lists them per cloud on its
[proactive messages](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/conversations/send-proactive-messages)
page; a send to an origin not on the list fails with "may not be sent to"). Ask before deploying into GCC High, DoD or
China; it is an open item of the Microsoft 365 plan.

## Users from Entra: map the object id, never the email

When Keycloak brokers Entra sign-in, identify Microsoft users by their **object id (`oid`)**. Entra's email and UPN
can change and are not guaranteed unique; the object id is. Teams users are already named by it
(`teams:<tenantId>:<objectId>`), and planned features (permission-aware retrieval) will match by it.

In Keycloak:

1. **Realm settings → User profile → Create attribute** `entraObjectId` (admin-editable only). Since Keycloak 24 the
   declarative user profile drops attributes it does not know, so without this step the importer below stores nothing.
2. **Identity providers → (your Entra provider) → Mappers → Add mapper**: type *Attribute Importer*, claim `oid`,
   user attribute `entraObjectId`, sync mode *force*.
3. **Clients → (the EDDI client) → Client scopes → dedicated scope → Add mapper → By configuration → User Attribute**:
   user attribute `entraObjectId`, token claim name `oid`, added to the access token.

This is groundwork: today nothing in EDDI reads the `oid` claim yet, so a Keycloak login and the same person in Teams
are still different EDDI users (the Keycloak `sub` and `teams:<tenantId>:<objectId>`).
