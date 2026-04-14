# EU AI Act Compliance Guide

> **The EU AI Act** is the world's first comprehensive AI regulation. It
> applies to any AI system used within the EU, regardless of where the
> provider is based. EDDI deployers must classify their agents by risk
> level and meet corresponding obligations.

This guide maps EDDI's built-in features to EU AI Act requirements and
helps deployers determine their compliance obligations.

---

## Risk Classification

The EU AI Act classifies AI systems into four risk tiers. The deployer
(not EDDI as infrastructure) determines the risk level based on the
**use case**, not the technology.

### High-Risk AI Systems (Annex III)

Your EDDI agents are **high-risk** if used for:

| Domain | Examples |
|---|---|
| **Healthcare** | Patient triage, symptom assessment, treatment recommendations |
| **Employment** | Resume screening, interview assessment, performance evaluation |
| **Credit & Finance** | Credit scoring, loan eligibility, fraud detection |
| **Education** | Student assessment, admission decisions |
| **Law Enforcement** | Suspect profiling, crime prediction |
| **Critical Infrastructure** | Energy management, water treatment decisions |

**Obligations**: Full compliance with Articles 9–15 (risk management,
data governance, technical documentation, transparency, human oversight,
accuracy/robustness).

### Limited-Risk AI Systems

Your EDDI agents are **limited-risk** if they interact with humans but
don't fall into high-risk categories:

| Examples |
|---|
| Customer service chatbots |
| Product recommendation agents |
| FAQ / help desk bots |

**Obligations**: Transparency only — users must be informed they are
interacting with an AI system (Article 52).

### Minimal-Risk AI Systems

| Examples |
|---|
| Entertainment chatbots |
| Internal testing agents |

**Obligations**: None specific, but general principles apply.

---

## EDDI Feature Mapping

### Article 9 — Risk Management System

| Requirement | EDDI Feature | Status |
|---|---|---|
| Identify and analyze known/foreseeable risks | Behavior rules with guardrails | ✅ Available |
| Estimate and evaluate risks | Cost tracking, token budgets | ✅ Available |
| Adopt risk management measures | Rate limiting, tool caching, budget caps | ✅ Available |
| Test risk management measures | Integration test suite, Testcontainers | ✅ Available |

**Deployer action**: Document your risk assessment per agent in your internal
risk management system.

### Articles 11–12 — Technical Documentation & Record-Keeping

| Requirement | EDDI Feature | Status |
|---|---|---|
| General description of the AI system | [architecture.md](architecture.md) | ✅ Available |
| Detailed description of system elements | Agent configuration (JSON) | ✅ Available |
| Information about training data | N/A — EDDI uses pre-trained models | ℹ️ Provider responsibility |
| Capabilities and limitations | Agent config + system prompt | ✅ Available |
| Automatic logging / record-keeping | Immutable audit ledger | ✅ Available |

**Deployer action**: Maintain technical documentation that references EDDI's
architecture docs and your agent configuration.

### Article 13 — Transparency & Information

| Requirement | EDDI Feature | Status |
|---|---|---|
| Inform users they are interacting with AI | Deployer responsibility | ⚠️ Deployer |
| Explain system capabilities and limitations | System prompt + agent description | ✅ Available |
| Provide contact information for deployer | Deployer responsibility | ⚠️ Deployer |

**Deployer action**: Display a clear notice that users are interacting with
an AI system. Include this in your application's UI or terms of service.

### Article 14 — Human Oversight

| Requirement | EDDI Feature | Status |
|---|---|---|
| Human ability to understand AI outputs | Audit ledger (full prompt + response trail) | ✅ Available |
| Human ability to override AI decisions | Behavior rules (action routing) | ✅ Available |
| Human ability to stop the AI system | Agent undeploy, conversation end | ✅ Available |
| Human-in-the-loop for high-risk decisions | HITL framework (planned Phase 9b) | ⚠️ Planned |

**Deployer action for high-risk agents**: Configure behavior rules that
require human approval for consequential decisions. Use the `managed_agent`
pattern to route high-stakes outputs through a human review queue.

### Articles 17/19 — Quality Management & Logging

| Requirement | EDDI Feature | Status |
|---|---|---|
| Immutable decision traceability | HMAC-signed audit ledger | ✅ Available |
| What data was read by each task | Audit entry `input` field | ✅ Available |
| What data was produced | Audit entry `output` field | ✅ Available |
| LLM prompts and responses | Audit entry `llmDetail` field | ✅ Available |
| Tool invocations and results | Audit entry `toolCalls` field | ✅ Available |
| Timing and cost | Audit entry `durationMs` + `cost` fields | ✅ Available |
| Tamper detection | HMAC-SHA256 integrity hash on every entry | ✅ Available |

This is EDDI's strongest compliance area. The audit ledger was specifically
designed for EU AI Act compliance.

---

## Deployer Checklist

### All Deployments

- [ ] **Risk classification**: Determine the risk level of each agent
- [ ] **Transparency notice**: Inform users they are interacting with AI
- [ ] **Audit ledger**: Ensure `eddi.audit.enabled=true` (default)
- [ ] **Vault master key**: Set `EDDI_VAULT_MASTER_KEY` for HMAC audit signing

### High-Risk Deployments

All of the above, plus:

- [ ] **Technical documentation**: Maintain documentation per Art. 11
- [ ] **Risk assessment**: Document per-agent risk analysis
- [ ] **Human oversight**: Configure behavior rules for human review of
      high-stakes decisions
- [ ] **Data governance**: Document training data provenance (this is the
      LLM provider's responsibility — ensure your provider complies)
- [ ] **Accuracy testing**: Regularly evaluate agent output quality
- [ ] **Incident reporting**: Report serious incidents to the relevant
      national authority (Art. 62)

---

## See Also

- [audit-ledger.md](audit-ledger.md) — Immutable decision trail (Art. 17/19)
- [security.md](security.md) — Security architecture
- [behavior-rules.md](behavior-rules.md) — Agent guardrails configuration
- [hipaa-compliance.md](hipaa-compliance.md) — HIPAA compliance guide
- [gdpr-compliance.md](gdpr-compliance.md) — GDPR/CCPA compliance
