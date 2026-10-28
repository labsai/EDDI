## ✨ feat(mail): EDDI verifies DKIM and DMARC alignment itself — email sender authentication (2026-10-25)

**Repo:** EDDI (`feat/email-dkim`, on top of `feat/email-hitl-notifications` and `feat/email-mail-core`)

The sender-authentication slice of [the email plan](../../planning/email-channel-plan.md) (§8, the DKIM/DMARC part of
§21), built ahead of Phase 2 so the IMAP poller can call it. No channel, transport or poller is part of this change.

### The facade Phase 2 calls

- **`SenderAuthenticator.authenticate(byte[] raw, InboundMail parsed)`** (`engine/mail`, `@ApplicationScoped`) →
  `MailAuthentication`: verdict `ALIGNED_PASS` · `PASS_UNALIGNED` · `FAIL` · `NONE`, the signing domain, a fixed-text
  reason (safe to log), a `temporary` flag (a DNS failure or the lookup budget kept a check from running — retrying
  later may authenticate), and one `DkimResult` per evaluated signature (domain, selector, algorithm, `PASS` · `FAIL` ·
  `TEMPERROR` · `TESTING`, reason). The caller passes the bytes it received and the parser's `InboundMail` of those
  bytes (a size mismatch is refused), then attaches the verdict with `InboundMail.withAuthentication`. Never throws.
- **Only an aligned pass authenticates**, and never when the message has anything but exactly one `From` field with
  one mailbox (counted on the raw bytes and from the parser's `MULTIPLE_FROM`/`MISSING_FROM`) or a duplicated
  singleton header (`DUPLICATE_SINGLETON_HEADER`): an unsigned second `From` or `Subject` added above a validly signed
  one is not hashed, so the signature still verifies, while clients show the forged one.
- `MailAuthentication` gains `reason`, `temporary` and `signatures` (the two-argument constructor stays).
- **Judged as of delivery:** `authenticate(raw, parsed, Instant deliveredAt, fallback)` evaluates `x=` and the
  future-`t=` check at the delivery instant — the transport passes the mailbox server's `INTERNALDATE`, the receiving
  server's own clock, never a sender header — so a short `x=` does not expire while the mail waits for the poller or
  the cost budget. The instant is clamped to `[now − eddi.email.dkim.max-delivery-age, now]` (new property, default
  `7d`): a later value is now, an older one is the cap boundary, so a broken or forged value can neither revive an
  ancient signature nor postdate one. Without it, signatures are judged at the current time, as before.
  `DkimVerifier.verify(raw, budget, evaluatedAt)` takes the instant unclamped.
- **Provider fallback**: `authenticate(raw, parsed, SignedProviderVerdict)`, an interface for a verdict delivered under
  a signature the transport checked (SES over SNS). Consulted only when the local check could not run (no raw bytes,
  or every key lookup failed temporarily); never over a local fail, none or pass; alignment is still EDDI's. No
  provider is implemented.

### DKIM (`DkimVerifier`)

- RFC 6376 on the raw bytes: `simple`/`relaxed` header and body canonicalisation in all four combinations,
  `rsa-sha256` (1024–8192-bit keys, exponent ≤ 64 bits; SPKI or bare PKCS#1 `p=`) and `ed25519-sha256` (RFC 8463,
  the JDK's EdDSA). Refused as `FAIL`: `rsa-sha1` (RFC 8301) and other algorithms, `l=` (body-extension attack), a
  signature not covering `From`, `i=` outside `d=`, expired `x=`, `x=` before `t=`, `t=` in the future (5 min skew),
  revoked (`p=` empty) or missing keys, more than one key record, `k=`/`h=`/`s=` that do not admit the signature,
  `t=s` with `i=` ≠ `d=`, malformed or duplicated tags. A `t=y` key gives `TESTING`, not a pass.
- Bottom-up header-instance selection with over-signing semantics (a name in `h=` more often than present binds the
  absence); the signature being verified is never one of its own `h=` instances. Bare-LF input is converted to CRLF.
- Bounds: 5 signatures evaluated (rest counted), 256 KB header block, 100 `h=` names, 8 KB signature field, one body
  hash per canonicalisation, the body checked before the key is fetched (a tampered mail spends no lookup).

### DMARC alignment (`DmarcAlignment`) and the Public Suffix List

- RFC 7489 §3.1.1: relaxed (same organisational domain) or strict (equal) per the `From` domain's `_dmarc` `adkim=`,
  falling back to the organisational domain's record (§6.6.3); no record = relaxed; several `v=DMARC1` records = no
  DMARC; a temporary lookup failure assumes strict and is flagged. `p=` is not applied. **SPF is not evaluated**
  (EDDI never sees the connecting IP).
- **`PublicSuffixList`**: the upstream file bundled unmodified as `src/main/resources/mail/public_suffix_list.dat`
  (MPL-2.0, new `licenses/MPL-2.0.txt`; commit `8437e5262f13`, 2026-10-22), ICANN and private sections, wildcard and
  exception rules, IDN rules in ASCII. `public_suffix_list.properties` records commit and SHA-256;
  `PublicSuffixListTest` fails when the file is missing, small, or differs from that hash. Refresh with the new
  `scripts/update-public-suffix-list.py`. The `.dat` is `-text` in `.gitattributes` so its hash is stable.

### DNS (`MailDnsResolver`, `DnsjavaTxtSource`)

- **dnsjava 3.6.5** (BSD-3-Clause; runtime dependency slf4j-api only, BOM-managed; CVE-2024-25638 and the KeyTrap
  advisories fixed in 3.6.0 and confined to the DNSSEC validator, which is not used). Its jar registers a
  `java.net.spi.InetAddressResolverProvider` that stays inert unless `org.dnsjava.spi.enable=true` — documented in
  `pom.xml` and `security.md`, with a test that fails if the switch's constant disappears.
- New properties `eddi.email.dns.servers` (empty = system resolvers), `.timeout` (5 s, one retry),
  `.cache-max-entries` (10000), `.cache-max-ttl` (1 h; record TTLs and the SOA minimum for negative answers honoured,
  temporary failures not cached), `.max-lookups-per-message` (10; cache hits free). Records over 4096 characters or
  answers over 10 records are refused; multi-string TXT records are joined. No DNSSEC requirement. The `TxtSource`
  seam keeps every test offline.

### Metrics, docs

- `eddi.email.auth{result}` and `eddi.email.dns.lookups{result,cached}`: two panels in the Full Metrics dashboard's
  Integrations row (k8s copy re-synced), described in [`metrics.md`](../metrics.md).
- [`security.md` → Mail Sender Authentication](../security.md#mail-sender-authentication--dkim-and-dmarc): the rules,
  what an aligned pass proves and does not (the domain, not the person; forwarded and list mail; no ARC; no SPF), the
  PSL refresh and the dnsjava resolver switch. [`configuration-reference.md` → Email](../configuration-reference.md#email)
  lists the six properties. [`compliance-data-flow.md` → Other outbound recipients](../compliance-data-flow.md#other-outbound-recipients):
  the DNS resolver learns the signing domains, selectors and `From` domains of received mail.

### Tests

- `DkimVerifierTest` (60): the RFC 8463 Appendix A message and key records stored as fixtures
  (`src/test/resources/mail/dkim/`, byte exact) — both signatures pass, also with LF endings; body, header, second-`From`
  tampering and re-folding. The RFC 6376 §3.4.6 canonicalisation example, signed over the canonical text the RFC
  prints. Self-signed RSA-2048 and Ed25519 messages (`DkimTestSigner`, a regex-based signer written independently of
  the verifier) in all canonicalisations, with folded headers, trailing whitespace, 8-bit text, empty and header-only
  bodies; every refusal above; DNS outcomes; bounds.
- `SenderAuthenticatorTest` (31): verdicts, DMARC through the facade (subdomain relaxed/strict, org-domain fallback,
  `d=co.uk`, DMARC tempfail), header injection (second `From`, two mailboxes, no `From`, second `Subject`, mismatched
  bytes, and each of the two From guards on its own against a parser that disagrees), judging as of delivery
  (expired since delivery passes; a delivery instant older than the cap or in the future is clamped), provider fallback, 300 random inputs, and a hostile message (5000 header lines, 50 signatures × 100 `h=`
  names, 10 MB body) within a 5 s budget.
- `DmarcAlignmentTest` (9), `PublicSuffixListTest` (24), `MailDnsResolverTest` (13; three run dnsjava against a
  loopback UDP server built in the test).
- Every verdict path was mutation-checked: the check reverted, a test failed, the check restored.

### For Phase 2

- Call `authenticate` with the transport's bytes right after parsing; keep `temporary` in mind (leave the message
  for the next poll rather than treating a DNS outage as an unauthenticated sender).
- Pass the mailbox server's `INTERNALDATE` as `deliveredAt`; without it, `x=` is judged at processing time.
- `eddi.email.auth` carries only `result`. Prometheus requires one tag set per meter name, so per-integration tags
  mean moving the counter to the channel layer rather than adding tags here.
- No real-world signed mail (Gmail, Outlook.com, Exchange Online, a list) is in the corpus yet (§21); add it with
  its DNS records when a test mailbox is available.

```decision-log
| 2026-10-25 | A validly signed message with a second From, or a duplicated Subject/Date/Message-ID/In-Reply-To/References, is FAIL, not ALIGNED_PASS | Bottom-up selection hashes only one instance; the other is unsigned attacker text that clients may display | Trusting the signature alone; downgrading to PASS_UNALIGNED (would hide that the message is malformed) |
| 2026-10-25 | DKIM l= is refused as FAIL rather than honoured | It lets anyone append unsigned text that EDDI would read as the sender's | Honouring l= and ignoring the extra body (EDDI's parser would still read it) |
| 2026-10-25 | Signatures under a t=y key make a message NONE, not FAIL | RFC 6376 §3.6.1: testing-mode mail must be treated as unsigned | FAIL (would misreport a sender who is merely testing) |
| 2026-10-25 | A temporary DMARC lookup failure assumes strict alignment and flags the result temporary | Strict still authenticates an exact-domain signature, and never over-authenticates a subdomain one | Relaxed (could authenticate what the domain's policy forbids); FAIL (punishes the common exact-domain case for a DNS blip) |
| 2026-10-25 | dnsjava, not JNDI's DNS provider | JNDI gives no TTLs, no rcode, no control of the server per lookup and no TCP fallback control; dnsjava is BSD-3-Clause with one runtime dependency | JNDI (com.sun.jndi.dns); a hand-written DNS client |
```
