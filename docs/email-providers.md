# Email Providers

> Which mailbox providers the [email integration](email-integration.md) works with, and what each one needs. Every
> provider fact on this page links to the provider's own documentation, checked on **2026-11-23**. Providers change
> these settings without notice: check the linked page before you rely on a value. Anything EDDI could not confirm from
> a primary source, or has not tested against a real mailbox, is marked **unverified**.

**Contents:** [At a glance](#at-a-glance) · [What EDDI does, whatever the provider](#what-eddi-does-whatever-the-provider) ·
[Microsoft 365 / Exchange Online](#microsoft-365--exchange-online) · [Google Workspace](#google-workspace) ·
[Personal Gmail](#personal-gmail) · [Outlook.com](#outlookcom) · [Any other IMAP host](#any-other-imap-host) ·
[DKIM signed headers](#dkim-signed-headers) · [Not yet verified](#not-yet-verified) · [Sources](#sources)

---

## At a glance

| Provider | Status in EDDI | Connection | IMAP (`host`, `port`) | SMTP (`smtpHost`, `smtpPort`) |
| --- | --- | --- | --- | --- |
| **Microsoft 365 dedicated, licensed mailbox** | Supported — the documented path | `OAUTH2_CLIENT_CREDENTIALS` | `outlook.office365.com`, `993` [[M8]](#sources) | `smtp.office365.com`, `587` [[M2]](#sources) |
| **Microsoft 365 shared mailbox** | **Unverified** — see [Shared mailboxes](#shared-mailboxes) | `OAUTH2_CLIENT_CREDENTIALS` | as above | as above, but see the licence note |
| **Google Workspace** | Dedicated mailbox over OAuth (`MAILBOX` connection), **not live-tested**; app password not blocked but **not live-tested** | `OAUTH2_AUTHORIZATION_CODE` bound `MAILBOX`, or `BASIC` (app password) | `imap.gmail.com`, `993` [[G1]](#sources) | `smtp.gmail.com`, `465` or `587` [[G1]](#sources) |
| **Personal Gmail** | Best effort: app password, **not live-tested** | `BASIC` (app password) | `imap.gmail.com`, `993` [[G1]](#sources) | `smtp.gmail.com`, `465` or `587` [[G1]](#sources) |
| **Outlook.com** (personal Microsoft account) | **Not supported** | — | — | — |
| **Other IMAP hosts** | Supported, if they offer password login over TLS | `BASIC` | the hoster's | the hoster's |

Workspace through Google OAuth (an administrator links a dedicated mailbox once) is the `MAILBOX` connection in
[Email Integration → Google Workspace](email-integration.md#google-workspace-a-dedicated-mailbox-linked-once-by-an-administrator).
Domain-wide delegation is not offered.

## What EDDI does, whatever the provider

These are properties of EDDI's code on `main`, not of any provider:

- **Login.** A `BASIC` connection logs in with its own username and password, offering `PLAIN` and `LOGIN`. Any OAuth
  connection logs in with SASL `XOAUTH2`, the integration's `address` as the user and the access token as the secret —
  the format both Microsoft [[M3]](#sources) and Google [[G2]](#sources) document. The same connection is used for IMAP
  and SMTP (`ImapMailTransport`).
- **No per-person grants.** A mailbox poll runs as no verified user, so a `PER_USER` connection is refused for a mailbox
  at save time (`ConnectionResolver`). An `OAUTH2_AUTHORIZATION_CODE` connection reaches a mailbox only bound `MAILBOX`:
  an administrator links each dedicated mailbox once, and the grant is spent only by the email channel's login
  ([Email Integration](email-integration.md#google-workspace-a-dedicated-mailbox-linked-once-by-an-administrator)). A
  person's own mailbox (personal Gmail, Outlook.com) has no OAuth path.
- **Ports.** IMAP `993` (TLS) or `143` (STARTTLS, required); SMTP `587` (STARTTLS, required) or `465` (TLS). A plaintext
  login is never attempted.
- **Marking processed mail.** `processedAction: flag` sets the keyword `$EddiProcessed`. A folder whose
  `PERMANENTFLAGS` accept neither `\*` nor the keyword gets no keyword: polling goes on, one WARN is logged per
  integration and `eddi_email_poll_marked_total{result="nothing"}` counts it. `move:<folder>` needs the server's IMAP
  `MOVE`; without it, the mail is only flagged, and only if the folder accepts the keyword. Where the server has
  no `MOVE` and the folder takes no keyword, the mail is neither moved nor flagged: it stays unmarked and EDDI relies on
  its cursor and ledger, so for `move:<folder>` pick a host that supports `MOVE`. `label:<name>` is refused for the IMAP transport.
- **DKIM is the sending provider's job.** EDDI verifies inbound DKIM itself and never signs outbound mail. A reply is
  only as trustworthy to its recipient as the mailbox provider's DKIM signing for the domain of `address`.

## Microsoft 365 / Exchange Online

Setup is in [Email Integration → Exchange Online](email-integration.md#exchange-online-oauth-client-credentials). The
provider facts behind it:

| Topic | Fact | Source |
| --- | --- | --- |
| IMAP | `outlook.office365.com`, port 993, SSL/TLS | [[M8]](#sources) |
| SMTP | `smtp.office365.com`, port **587** (recommended) or 25, TLS 1.2 or 1.3. Microsoft: a client that "recommends or defaults to TCP port 465" does not support the required TLS — use `smtpPort: 587` (the default) | [[M2]](#sources) |
| App-only permissions | Office 365 Exchange Online → Application permissions: `IMAP.AccessAsApp` for reading, **`SMTP.SendAsApp`** for conversation-mode replies; tenant admin consent (M3 states this for POP/IMAP and gives the same consent scope for SMTP) | [[M3]](#sources) |
| Token | Client credentials grant with scope `https://outlook.office365.com/.default`; the token is used for IMAP and SMTP | [[M3]](#sources) |
| Mailbox scope | `New-ServicePrincipal` registers the app in Exchange; `Add-MailboxPermission … -AccessRights FullAccess` grants it one mailbox. Microsoft adds: sending *as* another mailbox with client credentials needs `Add-RecipientPermission` (SendAs) | [[M3]](#sources) |
| SMTP AUTH switch | Disabled for organisations created after January 2020 [[M2]](#sources); enable it for the one mailbox with `Set-CASMailbox -Identity <address> -SmtpClientAuthenticationDisabled $false` (the mailbox setting overrides the organisation's) [[M4]](#sources). **With Entra ID security defaults on, SMTP AUTH is disabled**; Microsoft says to turn security defaults off to use it [[M4]](#sources) | [[M2]](#sources), [[M4]](#sources) |
| Licence | Client SMTP submission needs "a licensed Microsoft 365 or Office 365 mailbox" | [[M2]](#sources) |
| Sending limits | Per mailbox: 10,000 recipients per day, 30 messages per minute, up to 1,000 recipients per message (customisable). Per tenant: an external-recipient limit that depends on the licence count (5,000 a day for a trial tenant); mail from an `*.onmicrosoft.com` domain is limited to 100 external recipients per organisation per day | [[M5]](#sources) |
| DKIM, custom domain | "Currently, no DKIM signing occurs for outbound mail from custom domains" until an admin publishes the two selector CNAMEs and enables signing; `*.onmicrosoft.com` mail is signed automatically | [[M6]](#sources) |
| DKIM, inside the tenant | **The signature is omitted** when sender and recipient are in the same domain, or in different domains of the same organisation; such mail carries `dkim=none (message not signed)` | [[M6]](#sources) |

**What the last row means for EDDI.** A colleague in the same tenant who writes to the integration's mailbox sends an
unsigned mail. EDDI's verdict is `none`, so with `requireAuthentication: true` (the default, and forced in
conversation mode) it is refused as `unauthenticated:none` and never answered. In trigger mode,
`requireAuthentication: false` runs it — under a memory-free anonymous identity, and with the `From` unproven. So, on
Microsoft's statement (EDDI has not yet confirmed it against a live tenant), an integration meant for internal
senders on Microsoft 365 does not work as a conversation-mode integration today; mail
from outside the tenant (a customer, a supplier) is accepted only when its sender provides an aligned DKIM signature (a
sender on a Microsoft 365 custom domain whose administrator has not enabled DKIM is refused too, as `unauthenticated:none`
or `unauthenticated:pass-unaligned` when only an `onmicrosoft.com` signature is present). Whether a
recipient-side signal can safely stand in for the missing signature is open; EDDI does not trust Exchange's own
`Authentication-Results` header (`SignedProviderVerdict`: it carries no authserv-id).

### Shared mailboxes

A shared mailbox needs no licence up to 50 GB [[M5]](#sources), so it is the natural home for a process address — but
it is **not a confirmed fit**:

- Microsoft's OAuth guide grants an app "specific mailboxes" with `Add-MailboxPermission` [[M3]](#sources), and its
  section on shared mailboxes describes a token obtained *on behalf of a user*, with the shared mailbox's address in
  the `XOAUTH2` user field. It does not state that an **app-only** token can open a shared mailbox. EDDI has not tested
  it. **Unverified.**
- Client SMTP submission needs a **licensed** mailbox [[M2]](#sources), which an unlicensed shared mailbox is not. So
  conversation-mode replies from an unlicensed shared mailbox are, on Microsoft's own wording, not supported.
  **Unverified** in practice.

Until a live test settles both, use a dedicated, licensed mailbox (never a person's own) for conversation mode.

## Google Workspace

| Topic | Fact | Source |
| --- | --- | --- |
| IMAP | `imap.gmail.com`, port 993, implicit TLS | [[G1]](#sources) |
| SMTP | `smtp.gmail.com`, port 465 (implicit TLS) or 587 (STARTTLS) | [[G1]](#sources) |
| OAuth scope | `https://mail.google.com/` for IMAP, POP and SMTP [[G2]](#sources); Google classes it as **restricted** [[G3]](#sources) | [[G2]](#sources), [[G3]](#sources) |
| Password login | Since 14 March 2025, IMAP, SMTP and POP "no longer work with legacy passwords"; app passwords are the remaining exception Google names | [[G4]](#sources) |
| App passwords | Need 2-Step Verification. Google lists signing in with "a work, school, or another organization account" among the reasons the option may not appear. Whether a Workspace admin can enable or block them is **unverified** | [[G5]](#sources) |
| IMAP switch | The admin turns IMAP on or off under *Apps → Google Workspace → Gmail → End User Access*, for everyone or per organisational unit | [[G6]](#sources) |
| Folders | "Gmail treats labels as folders for the purposes of IMAP" | [[G7]](#sources) |
| Sending limits | Per user: 2,000 messages a day (500 for a trial), 100 recipients per message sent over SMTP by IMAP or POP clients, 10,000 recipients a day, 3,000 external recipients a day. "Sending limits can change without notice" | [[G8]](#sources) |
| DKIM | The admin generates a key (2048 or 1024 bit), publishes it as a TXT record and then clicks **Start authentication** | [[G9]](#sources) |

**DKIM not started.** EDDI's own test of a Workspace domain on 2026-11-18 found a published key but signing never
started: mail was signed by a `*.gappssmtp.com` domain instead of the domain of `From`, so EDDI's verdict was
`pass-unaligned`, refused as `unauthenticated:pass-unaligned`. Google's current DKIM page does not describe that
fallback signature, so this is an observation, not a documented default. Check that the Admin console shows
"Authenticating email with DKIM" [[G9]](#sources) for the sending domain.

**In EDDI today:** an app password over `BASIC` is not blocked by any code path, but it is **not live-tested**, and the
admin controls both IMAP and, possibly, app passwords. The OAuth path for a dedicated Workspace mailbox is the
[`MAILBOX` connection](email-integration.md#google-workspace-a-dedicated-mailbox-linked-once-by-an-administrator),
not yet verified against a real tenant. Gmail and Drive as **tools on each user's own account**
are a separate, working recipe: [Google Workspace](google-workspace.md).

## Personal Gmail

Same hosts, ports, scope and password rule as Workspace [[G1]](#sources), [[G4]](#sources). Differences:

- App passwords need 2-Step Verification; Google lists security-keys-only 2-Step Verification and Advanced Protection
  among the reasons the option may not appear [[G5]](#sources).
- Limits: 500 messages a day, 100 recipients per message sent over SMTP by IMAP clients [[G1]](#sources); Gmail's
  general page names 500 recipients per mail and 500 mails a day [[G10]](#sources).
- Google OAuth for a personal account would be a person's consent — an `OAUTH2_AUTHORIZATION_CODE` grant, which EDDI
  files `PER_USER` and refuses for a mailbox. Personal Gmail stays "app password, best effort", **not live-tested**.

## Outlook.com

Basic authentication is "no longer available to access any Outlook account" since 16 September 2024 [[M7]](#sources); Outlook.com IMAP (`outlook.office365.com:993`) and SMTP
(`smtp-mail.outlook.com:587`) require OAuth 2.0 [[M1]](#sources). That OAuth is a person's consent, which EDDI refuses
for a mailbox (above), and client credentials need an Entra tenant, which a personal account does not have.
**Not supported**, and not planned.

## Any other IMAP host

A hoster that still offers password login over TLS works with a `BASIC` connection
([setup](email-integration.md#any-other-imap-host-app-password)). Check three things in the hoster's documentation,
because they decide how the integration behaves:

1. Whether its IMAP folders accept custom keywords (`PERMANENTFLAGS`) — if not, use `processedAction: move:<folder>`.
2. Whether it supports IMAP `MOVE` — if not, `move:` falls back to the keyword.
3. Whether it DKIM-signs submitted mail for your domain, aligned with the `From` — required for replies to be trusted.

## DKIM signed headers

Which headers a provider covers with its DKIM signature (the `h=` tag) is the signer's choice: RFC 6376 lists `To` and
`Cc`, `Subject`, `Reply-To`, `In-Reply-To` and `References` only as "common examples" of fields to sign
[[R1]](#sources). On `main`, an aligned DKIM pass authenticates the sender, and EDDI does not look at which headers the
signature covered. That changes with [PR #1394](https://github.com/labsai/EDDI/pull/1394) (open), which binds a pass to
the signed headers: there, a `To`, `Cc` or `Subject` that the signature does not cover is not used for routing or
addressing.

| Provider | Signs `To` / `Cc`? | Source |
| --- | --- | --- |
| Microsoft 365 | **Unverified.** Microsoft's illustrative `DKIM-Signature` lists `h=From:Date:Subject:Message-ID:Content-Type:MIME-Version:X-MS-Exchange-SenderADCheck` — no `To`, no `Cc` — but it is an example, not a documented default | [[M6]](#sources) |
| Google Workspace | **Unverified** — Google documents no `h=` list | — |
| Personal Gmail | **Unverified** — as above | — |

The way to settle this is to capture real mail — a plain mail, a reply-all with `Cc` and a forward from each provider —
and read its `DKIM-Signature`.

## Not yet verified

Nobody has run EDDI against a real mailbox of these providers for this page. The open points:

| # | Question | Why it matters |
| --- | --- | --- |
| 1 | Does Gmail accept the keyword `$EddiProcessed` (`PERMANENTFLAGS`)? What does `move:EDDI/Processed` do to the INBOX label? Does a reply sent over SMTP appear twice in *Sent*? | The right `processedAction` for Gmail and Workspace |
| 2 | Does an app password work for a Workspace account, and can the admin block it? | Whether the `BASIC` path is usable for Workspace at all |
| 3 | Can an **app-only** token open an Exchange Online shared mailbox over IMAP, and submit SMTP for it? | Whether the unlicensed shared mailbox is a valid recommendation ([Shared mailboxes](#shared-mailboxes)) |
| 4 | Is `Add-RecipientPermission` needed when the app sends as the very mailbox it was granted? | The Exchange setup steps |
| 5 | Which headers Microsoft 365, Workspace and Gmail sign in practice | Routing and addressing once #1394 lands |
| 6 | Whether personal Gmail signs with `d=gmail.com` | Whether personal Gmail senders reach `aligned-pass` |
| 7 | Whether `*.gappssmtp.com` is still Google's signature before Workspace DKIM is started | The advice above |

**DNS on Windows.** Without `eddi.email.dns.servers`, EDDI's DNS client uses every resolver the operating system
reports. On a Windows development machine (2026-11-18) that included unreachable adapters' resolvers, and every DKIM
lookup failed temporarily, leaving mail unmarked for a later poll. On Windows, name the DNS servers explicitly
([Operations](email-integration.md#operations)).

## Sources

All checked on 2026-11-23.

| Ref | Page |
| --- | --- |
| M1 | Microsoft Support — [POP, IMAP, and SMTP settings for Outlook.com](https://support.microsoft.com/en-us/office/pop-imap-and-smtp-settings-for-outlook-com-d088b986-291d-42b8-9564-9c414e2aa040) |
| M2 | Microsoft Learn — [How to set up a multifunction device or application to send email using Microsoft 365](https://learn.microsoft.com/en-us/exchange/mail-flow-best-practices/how-to-set-up-a-multifunction-device-or-application-to-send-email-using-microsoft-365-or-office-365) |
| M3 | Microsoft Learn — [Authenticate an IMAP, POP or SMTP connection using OAuth](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth) |
| M4 | Microsoft Learn — [Enable or disable SMTP AUTH in Exchange Online](https://learn.microsoft.com/en-us/exchange/clients-and-mobile-in-exchange-online/authenticated-client-smtp-submission) |
| M5 | Microsoft Learn — [Exchange Online limits](https://learn.microsoft.com/en-us/office365/servicedescriptions/exchange-online-service-description/exchange-online-limits) |
| M6 | Microsoft Learn — [How to use DKIM for email in your custom domain](https://learn.microsoft.com/en-us/defender-office-365/email-authentication-dkim-configure) |
| M7 | Microsoft Support — [Modern Authentication methods now needed to continue syncing Outlook email in non-Microsoft email apps](https://support.microsoft.com/en-us/topic/c5d65390-9676-4763-b41f-d7986499a90d) |
| M8 | Microsoft Support — [POP and IMAP email settings for Outlook](https://support.microsoft.com/en-us/office/pop-and-imap-email-settings-for-outlook-8361e398-8af4-4e97-b147-6c6c4ac95353), the page [M3] links to as the Office 365 IMAP settings (its SMTP row names the Outlook.com server; for Microsoft 365 see M2) |
| G1 | Google for Developers — [IMAP, POP, and SMTP](https://developers.google.com/workspace/gmail/imap/imap-smtp) |
| G2 | Google for Developers — [OAuth 2.0 Mechanism (XOAUTH2)](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol) |
| G3 | Google for Developers — [Choose Gmail API scopes](https://developers.google.com/workspace/gmail/api/auth/scopes) |
| G4 | Google Workspace Help — [Transition from less secure apps to OAuth](https://knowledge.workspace.google.com/admin/sync/transition-from-less-secure-apps-to-oauth) |
| G5 | Google Account Help — [Sign in with app passwords](https://support.google.com/accounts/answer/185833) |
| G6 | Google Workspace Help — [Turn POP and IMAP on or off for users](https://knowledge.workspace.google.com/admin/sync/turn-pop-and-imap-on-or-off-for-users) |
| G7 | Google for Developers — [IMAP Extensions](https://developers.google.com/workspace/gmail/imap/imap-extensions) |
| G8 | Google Workspace Help — [Gmail sending limits in Google Workspace](https://knowledge.workspace.google.com/admin/gmail/gmail-sending-limits-in-google-workspace) |
| G9 | Google Workspace Help — [Set up DKIM](https://knowledge.workspace.google.com/admin/security/set-up-dkim) |
| G10 | Gmail Help — [Gmail sending limits](https://support.google.com/mail/answer/22839) |
| R1 | IETF — [RFC 6376 §5.4.1, Recommended Signature Content](https://www.rfc-editor.org/rfc/rfc6376#section-5.4.1) |
