# Business Associate Agreement (BAA) Template

> **Disclaimer**: This is a starter template, not legal advice. Deployers
> should have this reviewed by healthcare compliance counsel before
> execution. Adjust terms based on your specific deployment scenario.

---

## BUSINESS ASSOCIATE AGREEMENT

This Business Associate Agreement ("BAA") is entered into by and between:

**Covered Entity**: [YOUR ORGANIZATION NAME] ("Covered Entity")

**Business Associate**: [SERVICE PROVIDER / EDDI OPERATOR] ("Business
Associate")

Effective Date: [DATE]

---

### 1. Definitions

**Protected Health Information (PHI)**: Individually identifiable health
information transmitted or maintained in any form, as defined in 45 CFR
§160.103.

**Electronic Protected Health Information (ePHI)**: PHI transmitted or
maintained in electronic media, as defined in 45 CFR §160.103.

**EDDI Platform**: The E.D.D.I. (Enhanced Dialog Driven Interface)
multi-agent orchestration middleware used to process conversations that
may contain PHI.

---

### 2. Obligations of Business Associate

Business Associate agrees to:

**(a) Safeguards**: Implement administrative, physical, and technical
safeguards that reasonably and appropriately protect the confidentiality,
integrity, and availability of ePHI, including but not limited to:

- Encryption at rest for all databases storing ePHI (MongoDB WiredTiger
  Encryption at Rest, PostgreSQL TDE, or equivalent)
- Encryption in transit via TLS 1.2+ for all EDDI endpoints
- Access control via Keycloak OIDC with role-based permissions
- Automatic session timeout (15-minute idle maximum)
- AES-256-GCM envelope encryption for API keys and credentials
  (EDDI Secrets Vault)

**(b) Audit Trail**: Maintain an immutable audit ledger (HMAC-SHA256
signed) of all AI pipeline operations, tool invocations, and data access
events, retained for the period required by HIPAA (minimum 6 years).

**(c) Reporting**: Report to Covered Entity any use or disclosure of PHI
not provided for by this BAA, and any Security Incident or Breach of
Unsecured PHI, without unreasonable delay and no later than 60 days after
discovery.

**(d) Subcontractors**: Ensure that any subcontractors (including LLM
providers) that create, receive, maintain, or transmit PHI on behalf of
Business Associate agree to the same restrictions and conditions. Business
Associate shall maintain BAAs with the following subcontractors:

- [LLM PROVIDER NAME] — for AI inference processing
- [HOSTING PROVIDER NAME] — for infrastructure hosting
- [LIST ADDITIONAL SUBCONTRACTORS]

**(e) Access to PHI**: Make PHI available to Covered Entity or the
individual as required by 45 CFR §164.524, using the EDDI data export
endpoint (`GET /admin/gdpr/{userId}/export`).

**(f) Amendment**: Make PHI available for amendment as required by 45 CFR
§164.526.

**(g) Accounting of Disclosures**: Maintain and make available information
required for an accounting of disclosures as required by 45 CFR §164.528.
The EDDI audit ledger provides this capability.

**(h) Government Access**: Make internal practices, books, and records
relating to the use and disclosure of PHI available to the Secretary of
HHS for determining compliance.

**(i) Minimum Necessary**: Use PHI only as the minimum necessary to
accomplish the intended purpose of the use or disclosure. Configure EDDI's
conversation history windowing and context selection to limit PHI in LLM
context windows.

---

### 3. Permitted Uses and Disclosures

Business Associate may use or disclose PHI only as permitted or required
by this BAA or as required by law. Specifically, Business Associate may:

- Process PHI through EDDI's conversation pipeline for the purpose of
  providing AI-assisted services to Covered Entity's users
- Store PHI in encrypted databases for the duration of the configured
  retention period
- Transmit PHI to LLM providers listed in Section 2(d) for AI inference,
  subject to those providers' BAAs

---

### 4. LLM Provider Requirements

Business Associate acknowledges that EDDI transmits conversation content
(which may contain PHI) to configured LLM providers. Business Associate
shall:

- Use only LLM providers that have executed a BAA
  (e.g., Azure OpenAI, AWS Bedrock, Google Vertex AI, OpenAI API)
- OR use self-hosted models (Ollama, jlama) to eliminate external PHI
  transfer entirely
- Document all LLM providers in use and their BAA status

---

### 5. Term and Termination

**(a) Term**: This BAA shall be effective as of the Effective Date and
shall terminate when all PHI is destroyed or returned to Covered Entity,
or if this is not feasible, protections are extended to such PHI.

**(b) Termination for Cause**: Covered Entity may terminate this BAA if
Covered Entity determines that Business Associate has violated a material
term of this BAA.

**(c) Effect of Termination**: Upon termination, Business Associate shall
return or destroy all PHI. If return or destruction is not feasible,
Business Associate shall extend the protections of this BAA to the PHI
and limit further uses and disclosures. EDDI's GDPR erasure endpoint
(`DELETE /admin/gdpr/{userId}`) provides programmatic data destruction.

---

### 6. Miscellaneous

**(a) Regulatory References**: The terms of this BAA shall be construed
in light of any applicable interpretation of HIPAA by HHS.

**(b) Amendment**: This BAA may be amended only by written agreement of
both parties.

**(c) Survival**: The obligations of Business Associate under Section 5(c)
shall survive the termination of this BAA.

---

### Signatures

**Covered Entity**

Name: \_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_

Title: \_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_

Date: \_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_

**Business Associate**

Name: \_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_

Title: \_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_

Date: \_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_\_
