# EDDI Privacy & Data Processing

> **EDDI is a data processor.** Organizations deploying EDDI act as the
> data controller and are responsible for obtaining user consent, maintaining
> data processing agreements (DPAs), and ensuring lawful basis for processing.

## What Data EDDI Stores

| Data Category | Storage | Contains PII? | Retention |
|---|---|---|---|
| **Conversation Memory** | MongoDB / PostgreSQL | Yes (userId, chat content) | 365 days default (configurable) |
| **User Memory** | MongoDB / PostgreSQL | Yes (userId, structured facts) | Until explicitly deleted |
| **Audit Ledger** | MongoDB / PostgreSQL | Yes (userId) | Indefinite (EU AI Act) |
| **Database Logs** | MongoDB / PostgreSQL | Yes (userId) | Configurable |
| **Managed Conversations** | MongoDB / PostgreSQL | Yes (userId, intent) | Until explicitly deleted |

## Data Subject Rights

### Right to Erasure (Art. 17)

EDDI provides a unified erasure endpoint:

```
DELETE /admin/gdpr/{userId}
```

This cascades across all stores:
1. **User memories** — permanently deleted
2. **Conversation snapshots** — permanently deleted
3. **Managed conversation mappings** — permanently deleted
4. **Database logs** — userId pseudonymized (SHA-256 hash)
5. **Audit ledger** — userId pseudonymized (SHA-256 hash)

**Why pseudonymize instead of delete?** The audit ledger and logs are retained
under GDPR Art. 17(3)(e) — compliance with EU AI Act Articles 17/19 which
require immutable decision traceability for AI systems. User identifiers are
replaced with irreversible hashes, making re-identification impossible without
the original identifier.

### Right of Access / Portability (Art. 15/20)

```
GET /admin/gdpr/{userId}/export
```

Returns all user data as JSON: memories, conversations (with chat history),
and managed conversation mappings.

### MCP Tools

For AI-orchestrated compliance workflows:
- `delete_user_data` — full cascade erasure (requires `confirmation="CONFIRM"`)
- `export_user_data` — complete user data bundle

## Security Measures

- **Encryption at rest**: AES-256-GCM via Secrets Vault for API keys and credentials
- **Immutable audit trail**: HMAC-signed ledger entries for tamper detection
- **Secret redaction**: `SecretRedactionFilter` scrubs API keys, tokens, and vault
  references from audit entries before persistence
- **RBAC**: All GDPR endpoints require `eddi-admin` role
- **Input validation**: URL validation, regex injection prevention, path traversal
  protection
- **PII-safe logging**: GDPR operations log SHA-256 pseudonyms, never raw user IDs

## Third-Party Data Transfers (GDPR Art. 44-49)

EDDI sends conversation content to configured LLM providers during AI
processing. **Every conversation turn constitutes a data transfer to the
selected provider.** The specific provider is configured per agent by the
deployer.

### Supported LLM Providers and Data Locations

| Provider | Data Location | Self-Hosted? | DPA Available |
|---|---|---|---|
| **Ollama** | Your infrastructure | ✅ Yes | N/A (local) |
| **jlama** | Your infrastructure | ✅ Yes | N/A (local) |
| **Anthropic** | US/EU (varies) | ❌ No | [anthropic.com/policies](https://www.anthropic.com/policies) |
| **OpenAI** | US | ❌ No | [openai.com/policies](https://openai.com/policies) |
| **Google Gemini** | US/EU (varies) | ❌ No | [cloud.google.com/terms](https://cloud.google.com/terms) |
| **Mistral** | EU (France) | ❌ No | [mistral.ai/terms](https://mistral.ai/terms/) |
| **Azure OpenAI** | Your Azure region | Partially | Via Azure Enterprise Agreement |
| **AWS Bedrock** | Your AWS region | Partially | Via AWS Enterprise Agreement |
| **Oracle GenAI** | Your OCI region | Partially | Via Oracle Cloud Agreement |
| **Hugging Face** | Varies by model host | Partially | [huggingface.co/terms](https://huggingface.co/terms-of-service) |

### Deployer Responsibilities for LLM Data Transfers

As the data controller, you **must**:

1. **Select providers**: Choose LLM providers that meet your data residency
   and compliance requirements
2. **Establish DPAs**: Sign Data Processing Agreements with each cloud LLM
   provider you configure in EDDI
3. **Document transfers**: Record all LLM providers in your Article 30
   processing register
4. **Inform users**: Disclose in your privacy policy that conversations are
   processed by third-party AI providers
5. **Assess adequacy**: For non-EU providers, ensure adequate safeguards
   (Standard Contractual Clauses, adequacy decisions, or binding corporate
   rules) per GDPR Art. 46
6. **Consider self-hosting**: For maximum data sovereignty, use Ollama or
   jlama with self-hosted models — zero external data transfers

### What Data is Sent to LLM Providers

| Data Type | Sent? | Notes |
|---|---|---|
| User messages | ✅ Yes | The current turn's input |
| Conversation history | ✅ Yes | Recent conversation context (windowed) |
| System prompt | ✅ Yes | Agent instructions (configured by deployer) |
| User ID | ❌ No | Not included in LLM requests |
| API keys | ❌ No | Only the provider's own key for authentication |

EDDI does **not** send user IDs, metadata, or data from other conversations
to LLM providers. Only the conversation context relevant to the current agent
interaction is transmitted.

## Consent (GDPR Art. 6/7)

EDDI does **not** manage user consent. As a data processor, consent is the
controller's responsibility.

### Legal Basis

EDDI's data processing activities and their typical legal bases:

| Processing Activity | Suggested Legal Basis | Notes |
|---|---|---|
| Conversation processing | Art. 6(1)(a) Consent or Art. 6(1)(b) Contract | Controller determines |
| Persistent user memory | Art. 6(1)(a) Consent | Users should be informed |
| Audit ledger retention | Art. 6(1)(c) Legal obligation | EU AI Act Art. 17/19 |
| System logging | Art. 6(1)(f) Legitimate interest | Operational necessity |

### Controller Obligations

Deployers should:

1. Obtain appropriate consent before enabling conversational AI
2. Inform users about data processing via their privacy policy
3. Provide clear opt-out mechanisms in their application
4. Consider disabling `enableMemoryTools` unless users are explicitly informed
   that their interactions are remembered across sessions
5. Document the legal basis for each processing activity in your Article 30
   register

## CCPA Compliance

### Do Not Sell (§1798.120)

EDDI does **not sell personal information** and has no mechanism to do so.
EDDI is middleware infrastructure — it processes data on behalf of the
deployer (controller) and does not share, sell, or monetize user data with
any third party for commercial purposes.

If your deployment scenario involves sharing user data with third parties
(e.g., analytics providers), this is the controller's responsibility to
manage and disclose.

### Right to Know (§1798.100)

The GDPR export endpoint (`GET /admin/gdpr/{userId}/export`) satisfies the
CCPA "right to know" requirement by providing all personal information
collected about a consumer in a structured, machine-readable format.

### Right to Delete (§1798.105)

The GDPR erasure endpoint (`DELETE /admin/gdpr/{userId}`) satisfies the
CCPA "right to delete" requirement.

## Data Retention

| Category | Default | Configuration |
|---|---|---|
| Ended conversations | 365 days | `eddi.conversations.deleteEndedConversationsOnceOlderThanDays` |
| Idle conversations | 90 days | `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` |
| User memories | No auto-delete | Use GDPR API or MCP tools |
| Audit ledger | No auto-delete | Retained for EU AI Act compliance |

Set retention to `-1` to disable automatic cleanup.

## International Privacy Regulations

EDDI is open-source middleware deployed by organizations worldwide in
regulated environments. This section maps each major privacy framework to
EDDI's technical capabilities and identifies the organizational measures
deployers add on top.

> **How to read this section**: Privacy regulations have two layers —
> **technical safeguards** (encryption, access control, audit, erasure)
> and **organizational measures** (consent processes, officer appointments,
> breach notification procedures). EDDI implements the technical layer.
> Your organization implements the organizational layer. Together, they
> form a complete compliance posture.
>
> - ✅ **Built-in** — EDDI handles this out of the box
> - 🏢 **Your org** — Organizational measure, outside the scope of middleware
> - ✅ + 🏢 — EDDI provides the technical foundation; your org completes it

---

### PIPEDA — Canada

Canada's **Personal Information Protection and Electronic Documents Act**
(2000, amended 2023) governs the collection, use, and disclosure of personal
information in the course of commercial activity. It follows the 10 Fair
Information Principles.

| PIPEDA Principle | EDDI Technical Capability | Status |
|---|---|---|
| **Accountability** | Immutable HMAC-signed audit ledger traces all operations | ✅ + 🏢 |
| **Identifying Purposes** | Agent configuration documents AI processing purpose | ✅ + 🏢 |
| **Consent** | Deployer integrates consent capture in their application layer | 🏢 Your org |
| **Limiting Collection** | Token-aware windowing limits data sent to LLMs; configurable retention auto-deletes old conversations | ✅ Built-in |
| **Limiting Use/Disclosure** | Data used only for configured agent interactions; audit trail logs every LLM invocation (model name, prompt, response) | ✅ Built-in |
| **Accuracy** | Conversation state is timestamped and versioned; user memories updatable via `PUT /usermemorystore/memories` | ✅ Built-in |
| **Safeguards** | AES-256-GCM envelope encryption (Secrets Vault), HMAC-SHA256 audit integrity, Keycloak OIDC, role-based access control | ✅ Built-in |
| **Openness** | Full source code is open (Apache 2.0); PRIVACY.md and documentation are public | ✅ Built-in |
| **Individual Access** | `GET /admin/gdpr/{userId}/export` — returns all memories, conversations, and managed conversation mappings as JSON | ✅ Built-in |
| **Challenging Compliance** | `DELETE /admin/gdpr/{userId}` — cascade deletion across all 5 data stores; audit trail pseudonymized (not deleted) | ✅ Built-in |

**Deployer checklist**:

| Responsibility | Details |
|---|---|
| Consent capture | Integrate consent flow in your frontend before enabling EDDI chat. PIPEDA: implied consent for non-sensitive data, express consent for sensitive data (health, financial) |
| Bilingual notices | Provide English/French privacy notices for Canadian consumers |
| Breach reporting | Report breaches with "real risk of significant harm" to the **Office of the Privacy Commissioner** (OPC) and affected individuals. Maintain breach records for 24 months. Use EDDI's `docs/incident-response.md` as your runbook template |

---

### LGPD — Brazil

Brazil's **Lei Geral de Proteção de Dados** (2018, effective 2020) closely
mirrors GDPR and grants data subjects (titulares) extensive rights over
their personal data.

| LGPD Right | EDDI Technical Capability | Status |
|---|---|---|
| Confirmation of processing (Art. 18, I) | Documented in PRIVACY.md; audit trail records all operations | ✅ Built-in |
| Access to data (Art. 18, II) | `GET /admin/gdpr/{userId}/export` — full JSON bundle | ✅ Built-in |
| Correction of inaccurate data (Art. 18, III) | `PUT /usermemorystore/memories` — upserts individual memory entries | ✅ Built-in |
| Anonymization/blocking/deletion (Art. 18, IV) | `DELETE /admin/gdpr/{userId}` — cascade deletion + SHA-256 pseudonymization of audit trail | ✅ Built-in |
| Data portability (Art. 18, V) | JSON export includes all data; machine-readable format | ✅ Built-in |
| Deletion of unnecessary data (Art. 18, VI) | Configurable retention (`eddi.conversations.deleteEndedConversationsOnceOlderThanDays`) + idle conversation auto-end | ✅ Built-in |
| Information about sharing (Art. 18, VII) | LLM provider data flows documented; audit trail records model name per invocation | ✅ Built-in |
| Consent revocation (Art. 18, IX) | `POST /{conversationId}/endConversation` + `DELETE /admin/gdpr/{userId}` provide the technical mechanism; consent state tracking is your application layer | ✅ + 🏢 |

**Deployer checklist**:

| Responsibility | Details |
|---|---|
| Legal basis | Establish a legal basis for processing (consent, legitimate interest, contract performance) before deploying EDDI agents |
| DPO appointment | Appoint a **Data Protection Officer** (Encarregado) — mandatory for all controllers |
| DPIA | Conduct and document Data Protection Impact Assessments for AI processing. EDDI's audit ledger provides the data inputs for your DPIA |
| Breach reporting | Report security incidents to the **ANPD** within a "reasonable time" (ANPD recommends 2 business days) |
| Cross-border transfers | Ensure LLM providers meet LGPD transfer requirements (adequacy decisions, standard contractual clauses, or binding corporate rules) |
| Portuguese notices | Provide privacy notices in **Portuguese** |

---

### APPI — Japan

Japan's **Act on the Protection of Personal Information** (2003, significantly
amended 2022) is one of Asia's most mature data protection laws. Japan holds
an EU adequacy decision, facilitating cross-border data flows between the
two regions.

| APPI Obligation | EDDI Technical Capability | Status |
|---|---|---|
| Purpose of use specification (Art. 17) | Agent configuration documents processing purpose via system prompts and behavior rules | ✅ + 🏢 |
| Accurate and up-to-date data (Art. 19) | Conversation state is timestamped and versioned; user memories are updatable via REST API | ✅ Built-in |
| Security control measures (Art. 23) | AES-256-GCM vault encryption, HMAC-SHA256 audit integrity, Keycloak OIDC, RBAC, SSRF protection, sandboxed evaluation | ✅ Built-in |
| Supervision of employees (Art. 24) | Keycloak OIDC + role-based access (eddi-admin, eddi-editor, eddi-viewer) | ✅ Built-in |
| Supervision of contractors (Art. 25) | LLM provider data flows documented in PRIVACY.md; audit trail records which model/provider processed each turn | ✅ Built-in |
| Disclosure to data subjects (Art. 33) | `GET /admin/gdpr/{userId}/export` — full data bundle | ✅ Built-in |
| Correction and deletion (Art. 34-35) | `PUT /usermemorystore/memories` for correction; `DELETE /admin/gdpr/{userId}` for deletion | ✅ Built-in |
| Breach notification (Art. 26) | Incident response runbook (`docs/incident-response.md`) | ✅ Built-in |
| Cross-border transfer (Art. 28) | EDDI documents provider data flows; deployer verifies recipient country protections and obtains consent where required | ✅ + 🏢 |
| Pseudonymized information (2022 amendment) | GDPR erasure uses SHA-256 pseudonymization — satisfies APPI's pseudonymized information category | ✅ Built-in |

**Deployer checklist**:

| Responsibility | Details |
|---|---|
| Purpose documentation | Document AI processing purposes in your privacy notice; EDDI's agent config captures intent, but your privacy policy communicates it to users |
| Cross-border verification | Verify LLM provider countries meet APPI equivalence or obtain individual consent. Japan→EU: covered by adequacy decision. Other countries: require contractual safeguards |
| PPC registration | Register with the **Personal Information Protection Commission** (PPC) if processing data at scale |
| Breach reporting | Notify PPC and affected individuals **promptly** (report within 3-5 days in practice, full report within 30 days) |

---

### POPIA — South Africa

South Africa's **Protection of Personal Information Act** (2013, effective
2021) establishes 8 data processing conditions closely aligned with EU
standards. Enforced by the Information Regulator.

| POPIA Condition | EDDI Technical Capability | Status |
|---|---|---|
| Accountability (Condition 1) | HMAC-signed audit ledger, documented data flows, open-source code | ✅ Built-in |
| Processing limitation (Condition 2) | Token-aware windowing, configurable retention, idle conversation auto-end | ✅ Built-in |
| Purpose specification (Condition 3) | Agent configuration documents purpose; deployer communicates to users | ✅ + 🏢 |
| Further processing limitation (Condition 4) | Data used only for configured agent interactions; audit trail provides accountability | ✅ Built-in |
| Information quality (Condition 5) | Timestamped, versioned state; user memories updatable | ✅ Built-in |
| Openness (Condition 6) | Public PRIVACY.md + Apache 2.0 open source | ✅ Built-in |
| Security safeguards (Condition 7) | AES-256-GCM, HMAC, Keycloak OIDC, RBAC, SSRF protection | ✅ Built-in |
| Data subject participation (Condition 8) | Export endpoint + cascade deletion endpoint | ✅ Built-in |

**Deployer checklist**:

| Responsibility | Details |
|---|---|
| Information Regulator registration | Register with the **Information Regulator** before processing personal information |
| Information Officer | Appoint an **Information Officer** — mandatory for all responsible parties |
| Special personal information | POPIA requires prior authorization from the Information Regulator for processing special personal information (health, biometric, children's data). Assess whether your EDDI agents process such data |
| Breach notification | Notify the Information Regulator and data subjects "as soon as reasonably possible" |
| Language requirements | Provide privacy notices in at least one of South Africa's **11 official languages** relevant to your user base |
| Cross-border transfers | Only to countries with adequate protection or with appropriate safeguards (binding corporate rules, consent) |

---

### PDPA — Southeast Asia

The **Personal Data Protection Act** applies in multiple Southeast Asian
jurisdictions. Singapore's PDPA (2012, major amendments 2021) and
Thailand's PDPA (2019, effective 2022) are the most mature.

#### Singapore PDPA

| Singapore PDPA Obligation | EDDI Technical Capability | Status |
|---|---|---|
| Consent (Part 4) | Deployer integrates consent capture in their application layer | 🏢 Your org |
| Purpose limitation (Part 4) | Agent configuration documents purpose; deployer communicates to users | ✅ + 🏢 |
| Access obligation (Part 5) | `GET /admin/gdpr/{userId}/export` — full data bundle | ✅ Built-in |
| Correction obligation (Part 5) | `PUT /usermemorystore/memories` — upserts individual entries | ✅ Built-in |
| Accuracy obligation (Part 4) | Timestamped, versioned conversation state | ✅ Built-in |
| Protection obligation (Part 5) | AES-256-GCM, HMAC, Keycloak OIDC, RBAC | ✅ Built-in |
| Retention limitation (Part 5) | Configurable auto-cleanup + configurable idle conversation timeout | ✅ Built-in |
| Transfer limitation (Part 5) | Provider data flows documented; deployer verifies transfer safeguards | ✅ + 🏢 |
| Data breach notification (Part 6A) | Incident response runbook template | ✅ Built-in |

**Deployer checklist (Singapore)**:

| Responsibility | Details |
|---|---|
| Consent capture | Singapore PDPA has a deemed consent framework for business improvement, but explicit consent is required for most AI processing |
| DPO appointment | Appoint a **Data Protection Officer** (DPO) — mandatory |
| Breach notification | Notify the **PDPC** within **3 calendar days** of assessing a notifiable breach; notify affected individuals as soon as practicable |
| DPIA for AI processing | Mandatory Data Protection Impact Assessment for high-risk AI processing. EDDI's audit ledger provides the technical evidence for your DPIA |

#### Thailand PDPA

Thailand's PDPA is structurally modeled on GDPR. EDDI's GDPR infrastructure
covers all technical requirements. Key deployer responsibilities:

**Deployer checklist (Thailand)**:

| Responsibility | Details |
|---|---|
| DPO appointment | Appoint a DPO if processing sensitive data or performing large-scale monitoring |
| Breach notification | Notify the **PDPC** (Personal Data Protection Committee) within **72 hours** of discovery |
| Cross-border transfers | Require adequacy, appropriate safeguards, or consent |
| Thai-language notices | Provide privacy notices in **Thai** |
| Consent for sensitive data | Explicit consent required for sensitive personal data (health, biometrics, etc.) |

---

### Other Jurisdictions

For jurisdictions not listed above, EDDI's data protection infrastructure
generally meets international standards. Key regions and their primary
regulations:

| Region | Regulation | Key Notes |
|---|---|---|
| **Australia** | Privacy Act 1988 + APPs | 13 Australian Privacy Principles; notifiable data breach scheme; OAIC oversight |
| **South Korea** | PIPA (Personal Information Protection Act) | Strict consent requirements; mandatory DPO; 72-hour breach notification |
| **India** | DPDPA (Digital Personal Data Protection Act, 2023) | Consent-based framework; "significant data fiduciary" category; cross-border restrictions to blocked countries only |
| **EU/EEA** | GDPR | See [gdpr-compliance.md](docs/gdpr-compliance.md) |
| **USA (state)** | CCPA/CPRA (CA), VCDPA (VA), CPA (CO), etc. | See CCPA section above |
| **UK** | UK GDPR + Data Protection Act 2018 | Substantially mirrors EU GDPR; ICO oversight |

For all jurisdictions: deployers should consult local counsel to confirm
organizational obligations specific to their region.

## Contact

For privacy-related inquiries about the EDDI platform, contact the
project maintainers at [github.com/labsai/EDDI](https://github.com/labsai/EDDI).
