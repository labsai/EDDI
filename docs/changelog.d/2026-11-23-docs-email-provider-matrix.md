## 📝 docs(email): provider matrix with primary sources; Gmail promises removed; Exchange SMTP and DKIM corrected (2026-11-23)

**Repo:** EDDI (`docs/email-provider-matrix`)

Phase 1 of the email channel v2 plan (doc corrections and provider verification), docs only.

### What changed and why

- **New page [`email-providers.md`](../email-providers.md)**, listed in [`SUMMARY.md`](../SUMMARY.md): one section per
  provider the plan names (Microsoft 365 user and shared mailbox, Google Workspace, personal Gmail, Outlook.com, other
  IMAP hosts) with hosts, ports, OAuth permissions and scopes, app-password rules, sending limits and DKIM behaviour.
  Every value cites the provider's own page (Microsoft Learn/Support, Google for Developers/Workspace Help, RFC 6376),
  checked on 2026-11-23. What no primary source settles, or nobody has tested live, is marked unverified and collected
  in a "Not yet verified" table: Gmail keyword and `MOVE` behaviour, app-only access to an Exchange shared mailbox,
  each provider's DKIM `h=` list, Workspace app passwords, `*.gappssmtp.com` fallback signing.
- **[`email-integration.md`](../email-integration.md)**:
  - Gmail section (defect F1): no longer promises an admin-linked `SERVICE` grant or domain-wide delegation. It now
    says personal Gmail is app password, best effort; a dedicated Workspace mailbox grant is in PR #1476; there will
    be no domain-wide delegation.
  - Exchange Online: `SMTP.SendAsApp` added for conversation mode; SMTP on port 587 only, because Microsoft says a
    client defaulting to 465 lacks the required TLS (the page offered `smtps://smtp.office365.com`); Entra ID security
    defaults switch SMTP AUTH off.
  - Microsoft 365 omits the DKIM signature between senders of the same organisation, so a colleague's mail arrives
    unsigned and is refused `unauthenticated:none` under `requireAuthentication`. Noted in the setup and next to the
    conversation-mode example, whose `*@contoso.com` allowlist entry otherwise suggests colleagues are answered.
  - "Most hosters sign once DKIM is enabled" replaced by the sourced fact that neither Microsoft 365 nor Workspace
    signs with a custom domain until an admin turns it on.
  - Shared mailbox not recommended yet: Microsoft requires a licensed mailbox for SMTP submission and does not state
    app-only IMAP access to a shared mailbox.
  - Windows: set `eddi.email.dns.servers` (v3 plan §14 item 10).

### Not done

The plan's live verification matrix (§5.2, rows V1–V4 against real mailboxes, raw `.eml` fixtures with their `h=`
lists) was not run; its questions are the page's "Not yet verified" table. The amendments' `decision-log` rows are in
the plan's own fragment, not repeated here. The v2 plan is not on `main` yet, so the docs do not link to it.
