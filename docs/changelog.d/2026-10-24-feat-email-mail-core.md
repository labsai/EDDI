## ✨ feat(mail): mail core, socket guard and bare OAuth tokens — email Phase 0 + 0b (2026-10-24)

**Repo:** EDDI (`feat/email-mail-core`)

The first slice of [the email plan](../../planning/email-channel-plan.md) (Phase 0 and 0b): a pure library under
`engine/mail` that later phases (notifications, trigger mode, conversation mode) and the mail RAG source
([`rag-ingestion-source-types-plan.md`](../../planning/rag-ingestion-source-types-plan.md) §8) build on. No channel,
no transport, no conversation code — nothing here is reachable from an agent yet.

### What changed and why

- **Angus Mail** (`org.eclipse.angus:angus-mail` 2.0.5, `jakarta.mail:jakarta.mail-api` 2.1.5 — the versions the
  Quarkus BOM manages, so one copy on the classpath; EPL-2.0 / GPL-2.0-with-CPE / EDL-1.0) for MIME grammar.
- **`MimeMessageParser`**: raw RFC 5322 bytes → `InboundMail`. Every limit (`MailLimits`: raw bytes, header bytes,
  nesting depth, part count, attachments, per-attachment bytes, output characters) is enforced before the work it
  bounds — multipart delimiters are counted in the raw bytes before Jakarta Mail materialises a part, so a few KB
  declaring thousands of parts is refused, not allocated, and a base64-encoded multipart (which would hide its
  delimiters) is skipped. `text/plain` preferred, HTML converted as fallback. Broken and unknown charsets, RFC 2047
  words (split multi-byte, unknown charset), unparseable dates and invalid addresses become `MailAnomaly` values,
  never exceptions. A message without a Message-ID gets `sha256:<hex of raw bytes>`, flagged `syntheticMessageId`.
  Executables (by name, type and magic bytes) never carry content; archives are flagged, never expanded;
  `message/rfc822` is never walked into. `multipart/report` DSNs are parsed into a `DeliveryReport` (recipient,
  action, status, original Message-ID) for later bounce suppression.
- **`AddressNormalizer`** (lower-case, IDN→ASCII, no dot/`+tag` folding, no domain literals or numeric TLDs),
  **`QuoteStripper`** (trailing `>` blocks with en/de/fr/es/it/nl attributions; Outlook header blocks in en/de/fr;
  Gmail `gmail_quote`, Apple/Thunderbird `blockquote type=cite` + `moz-cite-prefix`, Outlook web markers —
  interleaved inline replies, forwards and quote-only messages are kept whole), **`AutoReplyDetector`** (RFC 3834,
  `X-Autoreply`/`X-Autorespond`, `Precedence`, `List-*`, null/daemon return paths, reports),
  **`PlainTextMailRenderer`** (`text/plain` UTF-8 `format=flowed`, threading headers with a capped References chain,
  `Re:` without stacking, `Auto-Submitted`, `X-Auto-Response-Suppress: All`, random `<eddi.<uuid>@domain>` ids, and
  a refusal of any header value containing CR/LF or another control character).
- **`HiddenContentFilter`**, run by `HtmlToMarkdownConverter` for **every** caller: element-level
  (`display:none`, opacity ~0, `mso-hide`, zero-size + overflow hidden, off-screen, clip, `scale(0)`, `hidden`,
  comments, `display:none` rules in `<style>` outside `@media`) and text-level (inherited `visibility:hidden`,
  ≤1px fonts, transparent text, text the colour of its stated background). Web/file ingestion and
  `WebScraperTool` benefit with no change to visible output; only the new `convertMail` assumes a white page, so
  white text with no stated background is dropped in mail but kept on web pages (whose backgrounds come from
  stylesheets). `convertMail` also skips the `main`/`article` scoping and the `<title>`.
- **`SafeSocketConnector`** (`engine/net`): the socket-level SSRF guard the RAG plan §3.10 proposed. DNS names only
  (every IP-literal spelling refused), IMAP 993/143-STARTTLS and SMTP 465/587-STARTTLS only, optional
  `eddi.email.allowed-hosts`, resolved once with every address checked by `UrlValidationUtils.isPrivateAddress`,
  pinned to the first address (`PinnedSocket` ignores a library's second lookup), TLS always verified, timeouts.
  `angusProperties(endpoint)` wires Angus Mail to it with `socketFactory.fallback=false`; a loopback smoke test
  proves Angus connects through it, requires STARTTLS before any credential, and does not fall back.
- **`ConnectionResolver.resolveAccessToken`**: the bare access token for `XOAUTH2`, OAuth connections only
  (`STATIC`, `BASIC`, `CALLER_SUPPLIED` → `UNSUPPORTED_PLACEMENT`), with the allowlist, `PER_USER`/`VERIFIED` rules
  and the shared token supplier (refresh, caching) unchanged.

### For the next phases

- `InboundMail.authentication()` is `NOT_EVALUATED` until the DKIM verifier attaches a verdict with
  `withAuthentication`; only `ALIGNED_PASS` authenticates. The verifier must also treat the `MULTIPLE_FROM` and
  `DUPLICATE_SINGLETON_HEADER` anomalies as unauthenticated. Raw bytes are not kept in `InboundMail`; the verifier
  gets them from the transport.
- `baseUrlAllowlist` holds http(s) origins only, so `resolveAccessToken` refuses an `imaps://` target today (fails
  closed). The IMAP phase must teach the connection model (and the Manager's mirror in
  `connection-validation.ts`) mail origins.
- Private-address mail servers (on-premises Exchange) are refused, with no override yet.

**Files:** [`engine/mail/`](../../src/main/java/ai/labs/eddi/engine/mail/),
[`SafeSocketConnector.java`](../../src/main/java/ai/labs/eddi/engine/net/SafeSocketConnector.java),
[`HiddenContentFilter.java`](../../src/main/java/ai/labs/eddi/modules/ingestion/HiddenContentFilter.java),
[`ConnectionResolver.java`](../../src/main/java/ai/labs/eddi/connections/ConnectionResolver.java),
[`security.md`](../security.md#mail-sockets--safesocketconnector),
[`configuration-reference.md`](../configuration-reference.md#email), [`rag.md`](../rag.md), fixtures under
[`src/test/resources/mail/`](../../src/test/resources/mail/).

```decision-log
| 2026-10-24 | The hidden-content pass runs for every HtmlToMarkdownConverter caller; only mail assumes a white background | Injection hidden in web pages and uploaded HTML reaches RAG exactly as in mail, and the element- and text-level rules only remove what a reader cannot see | Opt-in per caller (leaves RAG exposed); assuming white everywhere (deletes the text of dark-themed pages styled by stylesheets) |
| 2026-10-24 | A forward (Fwd:/FW:/WG:/TR: subject, or a forward separator) is never quote-stripped | Outlook writes the same From/Sent block for forwards as for replies, and a forwarded invoice is the content of a trigger-mode mail | Stripping by header block alone (deleted the forwarded mail) |
```
