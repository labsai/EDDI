# **Production-Grade Secrets Management Architecture for Conversational AI Orchestration**

## **1\. Architecture Decision Record: Ephemeral Runtime Secret Resolution**

### **1.1 Context and Threat Landscape**

The aggregation of third-party capabilities—ranging from vast language models and vector databases to external tool application programming interfaces (APIs)—requires the secure, continuous management of highly privileged credentials. The Enhanced Dialog Driven Interface (EDDI) currently orchestrates agent behaviors via versioned JSON configurations persisted in MongoDB or PostgreSQL. Historically, these configurations have contained plaintext API keys and authorization headers directly within the document structure. This architectural paradigm presents a critical infrastructural vulnerability. Storing plaintext credentials within persistent storage violates the principle of least privilege and dramatically expands the attack surface, exposing the system to catastrophic credential leakage through multiple egress vectors, including configuration exports, log files, conversation memory snapshots, and multi-agent delegation boundaries.

The severity of this threat model is perfectly illustrated by recent industry incidents, such as the .npmrc injection attack targeting a multi-agent AI product.1 In that real-world breach, an attacker exploited infrastructure trust boundaries by injecting a payload into a shared filesystem. The compromised application subsequently read the injected configuration, exposing master API credentials that lacked IP restrictions, session scoping, and sandbox binding.1 The core deduction from this incident is that the underlying infrastructure, not the artificial intelligence model itself, must enforce cryptographic boundaries. As the EDDI platform evolves to support Model Context Protocol (MCP) clients, Directed Acyclic Graph (DAG) task pipelines, and complex multi-agent orchestration, the operational attack surface expands exponentially. Every programmatic delegation creates a potential exfiltration pathway if credentials are not tightly scoped to the specific execution sandbox.

### **1.2 Architectural Decision**

To neutralize these systemic vulnerabilities, the architecture mandates a complete transition from plaintext credential storage to an ephemeral runtime secret resolution paradigm. All plaintext credentials within the JSON configurations must be systematically replaced with opaque vault references following a standardized syntax. The actual cryptographic secret values will never touch the primary operational database, will never be serialized into export payloads, and will be actively filtered from all observability and logging streams.

Credentials will only exist in their plaintext form within the volatile memory of the Java Virtual Machine (JVM) at the precise microsecond of HTTP client invocation or tool execution. To achieve this state of strict ephemerality, the architecture introduces a pluggable Service Provider Interface (SPI), defined as the ISecretProvider. This interface is backed by Quarkus-native SmallRye Config capabilities, ensuring seamless compatibility with agenth enterprise cloud-native secret managers and localized, envelope-encrypted storage for self-hosted deployments.

## **2\. Trust Boundary Enforcement and Threat Model**

The architecture establishes a centralized execution sandbox. Within this zone, the EDDI orchestration engine interacts with the ISecretProvider SPI to resolve credentials. The boundaries of this sandbox are absolute. Peripheral components—such as the persistent MongoDB or PostgreSQL storage, the NATS JetStream message broker, and external Model Context Protocol (MCP) servers—reside entirely outside this boundary. Consequently, data flowing across these boundaries consists exclusively of opaque references or securely scoped, short-lived tokens, ensuring that plaintext secrets remain strictly confined to the localized execution sandbox.

### **2.1 Storage and Persistence Isolation**

The primary database operates strictly outside the credential trust boundary. When the system persists a versioned configuration document, it writes solely the reference identifier. If the database is compromised via SQL injection, NoSQL injection, or improper access controls, the adversary obtains only useless reference strings. Similarly, the asynchronous event-driven architecture relies on NATS JetStream for processing conversations. When the central orchestrator delegates a branch of a DAG pipeline to a worker node via NATS, the message payload contains only the conversation context and the opaque vault reference strings. The actual secret resolution occurs exclusively on the consumer side, within the isolated JVM memory space of the worker node executing the task. This late-binding strategy guarantees that persistent NATS streams, which may be archived or monitored, contain zero sensitive cryptographic material.

### **2.2 Agent-Scoped Namespace Segregation**

To prevent a maliciously crafted or misconfigured agent from accessing credentials belonging to another entity, the system enforces strict namespace isolation. The execution lifecycle of a task possesses the contextual awareness of the currently executing agent uniform resource identifier (URI). This URI, combined with the tenant identifier, forms a mandatory cryptographic prefix for all secret lookups. When a task requests a generic secret, the resolution engine automatically expands this request to an absolute path, effectively isolating the lookup to a specific tenant and agent hierarchy. The secret provider restricts access exclusively to keys matching the executing thread's contextual namespace, effectively neutralizing cross-tenant credential harvesting.

## **3\. Phase 1: Secret Storage Backend Options**

The implementation of the ISecretProvider SPI must accommodate the highly diverse deployment models of the EDDI platform, scaling from local developer environments to highly regulated enterprise cloud infrastructures.

### **3.1 Pluggable Backend Architecture**

The Quarkus ecosystem provides robust, native extensions for industry-standard secrets management solutions. The quarkus-vault extension facilitates native integration with HashiCorp Vault.2 This backend is superior for complex, multi-cloud deployments, offering dynamic secret generation, transit encryption, and highly configurable lease-based expiry.3

For organizations deeply integrated into specific cloud ecosystems, the quarkus-amazon-services-secretsmanager and quarkus-azure-key-vault extensions provide seamless enterprise-grade integration.2 AWS Secrets Manager excels in native AWS deployments, offering automated rotation capabilities via Lambda functions and deep integration with AWS Key Management Service (KMS) and Identity and Access Management (IAM).5 Azure Key Vault provides comparable security, featuring Hardware Security Module (HSM) backing validated to FIPS 140-3 Level 3 standards, alongside native Entra ID integration.7

The architecture utilizes a layered ISecretProvider factory pattern. The application conditionally loads the appropriate provider implementation during the Quarkus CDI (Contexts and Dependency Injection) bootstrap phase, based on the active deployment profile. This abstraction shields the core orchestration engine from the underlying storage mechanism, allowing EDDI to issue generic retrieval requests without requiring awareness of whether the secret resides in HashiCorp Vault or AWS Secrets Manager.

### **3.2 The Default Self-Hosted Store: Envelope Encryption**

For open-source users deploying EDDI via simple container orchestration without access to external key management systems, a highly secure internal storage mechanism is mandatory. The DatabaseSecretProvider stores secrets within a dedicated, isolated table in the existing MongoDB or PostgreSQL databases, secured entirely via Envelope Encryption.

Envelope encryption employs a dual-key hierarchy to protect data while minimizing the exposure of root cryptographic material.8 The system relies on a Master Key, functioning as the Key Encryption Key (KEK), which is supplied exclusively via an environment variable. The Master Key is never persisted to the database. Upon tenant initialization, the provider generates a highly secure Data Encryption Key (DEK) specifically for that tenant. The tenant DEK is then encrypted using the KEK and stored securely in the database.

When a new secret is created, the system utilizes the tenant's decrypted DEK to encrypt the actual secret value. The algorithmic standard for this operation is the 256-bit Advanced Encryption Standard in Galois/Counter Mode (AES-256-GCM).8 AES-256-GCM provides agenth data confidentiality and authenticity, ensuring that the ciphertext has not been tampered with.9 The implementation requires a 32-byte key and a randomly generated 12-byte Initialization Vector (IV), producing a 16-byte authentication tag appended to the ciphertext.10 The cryptographic operation leverages the Java Cipher class initialized with GCMParameterSpec.11 This hierarchical envelope model ensures cryptographic isolation between tenants and allows for the seamless rotation of the Master Key without requiring the computationally expensive re-encryption of every individual secret residing in the database.12

### **3.3 Storage Backend Comparison**

| Storage Backend             | Operational Complexity             | Enterprise Security            | Self-Hosted Viability | Key Rotation Support     | Native Quarkus Support   |
| :-------------------------- | :--------------------------------- | :----------------------------- | :-------------------- | :----------------------- | :----------------------- |
| **HashiCorp Vault**         | High (Requires HA architecture)    | Outstanding (Transit, Dynamic) | Moderate              | Automated                | Yes (quarkus-vault)      |
| **AWS Secrets Manager**     | Low (Managed Service)              | High (KMS Integration)         | No                    | Automated via Lambda     | Yes (quarkus-amazon)     |
| **Azure Key Vault**         | Low (Managed Service)              | High (HSM, Entra ID)           | No                    | Automated                | Yes (quarkus-azure)      |
| **SmallRye \+ K8s Secrets** | Low (Native Kubernetes)            | Moderate (Base64 default)      | Yes (If running K8s)  | Manual / External        | Yes (Built-in)           |
| **Database Envelope Store** | Lowest (Zero extra infrastructure) | Moderate (Requires KEK safety) | Outstanding           | Supported (DEK rotation) | No (Custom SPI required) |

## **4\. Phase 2: Reference Syntax and Resolution Pipeline**

The mechanism by which the orchestration engine identifies and dynamically replaces vault references within its configuration models requires rigorous parsing and highly secure execution timing.

### **4.1 Syntax Design and Internal Interception**

The syntax for secret references must be easily identifiable by regular expressions for export sanitization, yet robust enough to support dynamic variable injection. The architecture adopts the syntax ${eddivault:namespace/key}. SmallRye Config provides a built-in SecretKeysHandler mechanism that natively supports custom expressions.13 By registering a custom SecretKeysHandlerFactory via the Java ServiceLoader mechanism, applications can typically intercept requests for custom configuration schemas.13

However, SmallRye Config enforces a strict limitation prohibiting the mixing of secret expressions with standard property expressions within the same configuration value.13 Furthermore, EDDI dynamically evaluates deeply nested JSON configurations at runtime, rather than relying exclusively on static application.properties files loaded during application bootstrap. Consequently, relying exclusively on SmallRye's interceptor chain is insufficient for runtime JSON evaluation. The resolution logic must be built directly into the EDDI execution pipeline.

### **4.2 Resolution Timing within the Orchestration Pipeline**

The orchestration engine loads configuration documents from the database, passes them through the Thymeleaf templating engine for variable interpolation, and finally constructs the executable task via the ILanguageModelBuilder. The precise moment of secret resolution dictates the absolute security posture of the entire pipeline.

Resolving the secret immediately upon loading the configuration from the database introduces a critical vulnerability. The resolved plaintext secret would be injected directly into the Thymeleaf evaluation context. If a agent's configuration is poorly designed, or if an adversary manages to manipulate a prompt to echo template variables, the templating engine could inadvertently log or return the plaintext API key within a conversational response.

The secure architecture demands deferring resolution until the final possible operation. The vault references pass through the Thymeleaf rendering engine entirely untouched. Only after template interpolation is complete, and the configuration parameters map is passed to the ILanguageModelBuilder.build(Map\<String, String\> parameters) function, does the internal SecretResolver parse the map. It identifies strings matching the specific ${eddivault:...} pattern and retrieves the plaintext values from the configured ISecretProvider. This delayed-resolution approach guarantees that the secret is instantiated in memory only within the localized, ephemeral scope of the builder, directly prior to the execution of the external HTTP call.

### **4.4 Ephemeral Caching Strategy**

Retrieving secrets from an external network vault, or executing AES-256-GCM decryption for every single API call, introduces unacceptable latency agenttlenecks in a high-throughput conversational system. A caching layer is necessary, but it must be strictly governed to maintain ephemerality. The architecture implements a Caffeine-based, in-memory cache localized exclusively to the SecretResolver. Resolved secrets are cached with a strict Time-To-Live (TTL) of exactly five minutes. This duration minimizes external API calls while ensuring that revoked or rotated credentials propagate through the system rapidly, enforcing the security principle that secrets must die alongside the sandbox session.

## **5\. Data Model Specification**

To fully implement the ISecretProvider architecture, specific domain entities and interfaces are required within the Java layer.

### **5.1 The Service Provider Interface**

The ISecretProvider defines the core contract for all backend implementations. It mandates methods for creating, reading, updating, and deleting secrets, alongside specialized methods for rotation and health checks.

- String resolve(SecretReference reference): Fetches the plaintext value for runtime execution.
- SecretMetadata getMetadata(SecretReference reference): Retrieves non-sensitive data about the secret, such as rotation schedules and access timestamps, for the Manager UI.
- void store(SecretReference reference, String plaintext): Persists a new secret to the backend.
- void delete(SecretReference reference): Completely removes the secret from the backend.

### **5.2 Domain Entities**

The SecretReference is an immutable value object representing the parsed ${eddivault:tenantId/agentId/keyName} string. It encapsulates the routing logic, ensuring the provider knows exactly which namespace to query. The SecretMetadata entity operates as a secondary data transfer object, containing the creation timestamp, the last accessed timestamp, the owning tenant ID, and a cryptographic checksum of the value to verify integrity without exposing the plaintext.

## **6\. Phase 3: Multi-Agent and MCP Credential Isolation**

As EDDI scales into a comprehensive multi-agent orchestration platform involving the Model Context Protocol (MCP) and dynamic inter-agent delegation, cryptographic boundaries must be rigorously established to prevent lateral credential movement and privilege escalation.

### **6.1 MCP Credential Delegation Patterns**

The integration of Model Context Protocol clients introduces complex authorization challenges. When EDDI, acting as an MCP client, connects to an external tool server to execute a function, it must authenticate itself. The MCP specification explicitly forbids token passthrough—the dangerous practice of an MCP server indiscriminately forwarding authentication tokens through intermediaries.15 If an attacker compromises an intermediary system, they can capture these valid tokens and impersonate legitimate users indefinitely.15

To comply with the official MCP authorization specification, EDDI implements the OAuth 2.1 framework for client authorization.16 EDDI initiates an OAuth flow, utilizing the On-Behalf-Of (OBO) pattern or device authorization flows, depending on the specific user interaction model.18 The long-lived credentials required to negotiate this OAuth exchange (the Client ID and Client Secret) are stored within the EDDI vault as ephemeral references. Once EDDI obtains a scoped JWT access token from the authorization server, that highly production token is injected into the HTTP Authorization header strictly for the duration of the MCP tool execution. The external MCP server validates the token directly against the identity provider.15 Under no circumstances does EDDI transmit its internal infrastructure API keys to an external MCP server, successfully neutralizing the confused deputy problem inherent in multi-agent tool chains.

### **6.2 Sandbox-Scoped Ephemeral Credentials for DAGs**

Phase 9 of the EDDI roadmap introduces Directed Acyclic Graph (DAG) pipelines, requiring concurrent asynchronous task execution. When parallel task branches execute concurrently, they must not share a monolithic credential context. The architecture implements credential views—opaque, single-use handles that resolve to the real secret only within the execution thread of a specific ILifecycleTask.execute() call. Once the task completes, the handle is cryptographically invalidated, preventing any secondary thread or delayed asynchronous process from accessing the secret material.

## **7\. Phase 4: Export Sanitization and Data Egress**

Every pathway through which data exits the EDDI platform must be fortified with automated, uncompromising sanitization mechanisms to prevent the accidental leakage of resolved credentials to external systems or observability dashboards.

### **7.1 Configuration Backup and ZIP Scrubbing**

The internal RestExportService.exportAgent() endpoint generates downloadable ZIP archives of agent configurations for backup and migration purposes. If a legacy configuration retains a plaintext key, exporting it blindly propagates the vulnerability. Relying on simple, flat regular expressions to scrub serialized JSON strings is historically fragile and highly prone to payload splitting or obfuscation evasion tactics.

The export pipeline utilizes an Abstract Syntax Tree (AST) based scrubbing mechanism. During the serialization process, a customized Jackson object mapper parses the entire configuration graph node by node. It evaluates field names against known sensitive heuristics (e.g., apiKey, password, token, authorization, credential) and evaluates the content of the field values using high-entropy detection algorithms.20 Industry standard methodology, such as that utilized by GitGuardian, identifies strings with a Shannon entropy score greater than 3.5 and matching specific regular expressions (e.g., \[a-zA-Z0-9\_.+/\~$-\]{14,1022}) as highly probable cryptographic secrets.20 When the AST parser encounters a definitive match based on these mathematical properties, it completely removes the plaintext value from the tree and replaces it with a definitive tombstone string: ${eddivault:REDACTED}. This AST-driven approach ensures that secrets embedded deeply within complex nested arrays or obscure JSON properties are reliably neutralized before the ZIP archive is ever constructed.

### **7.2 Log Entropy Filtering via JBoss Logging**

Logging frameworks represent one of the most pervasive vectors for credential exposure, as evidenced by major industry common vulnerabilities and exposures (CVEs) where observability pipelines inadvertently ingested raw HTTP headers containing bearer tokens. Quarkus utilizes the JBoss LogManager backend for all internal and application-level logging routing.22 To intercept all outbound telemetry safely, EDDI implements a custom extension of the java.util.logging.ConsoleHandler interface.23

This specialized CustomConsoleHandler overrides the core publish(LogRecord record) method.23 Before delegating the message to the standard console output or the internal BoundedLogStore ring buffer, the handler executes a highly optimized regular expression pattern matcher against the message body. It is designed to identify standard HTTP Authorization headers (e.g., Bearer \[A-Za-z0-9-\_=\]+\\.\[A-Za-z0-9-\_=\]+\\.?\[A-Za-z0-9-\_.+/=\]\*) and structural API key patterns characteristic of major providers (e.g., sk-\[a-zA-Z0-9\]{20,}). Matched substrings are aggressively and irreversibly masked with a \<REDACTED\> tag.23 To mitigate performance degradation on high-throughput log streams, the regular expression engine utilizes statically compiled, pre-computed Pattern objects and fast-fail prefix logic to immediately bypass messages lacking sensitive structural indicators.

### **7.3 Conversation Memory Sanitization**

The IConversationMemory object comprehensively tracks the state of the large language model interaction turn by turn. If an external API responds with a verbose error message that carelessly echoes the provided API key back to the client, this key could inadvertently be serialized into the MongoDB conversation history. To mitigate this specific vector, a post-processing interceptor hooks directly into the ConversationStep.storeData() execution lifecycle. This interceptor applies the exact same high-entropy Shannon analysis utilized in the export scrubber, ensuring that memory snapshots persisted to the database are categorically devoid of cryptographic material.

### **7.4 Security Audit Checklist for Egress Vectors**

| Egress Vector             | Sanitization Mechanism                               | Enforcement Location                        |
| :------------------------ | :--------------------------------------------------- | :------------------------------------------ |
| **ZIP Export Archives**   | Jackson AST heuristic and Shannon entropy parsing    | RestExportService serialization phase       |
| **Console / File Logs**   | Pre-compiled regex pattern matching and masking      | CustomConsoleHandler.publish() override     |
| **Internal Ring Buffer**  | Inherits masking from the custom JBoss handler       | BoundedLogStore ingestion point             |
| **Memory Snapshots**      | Post-processing entropy validation before DB write   | ConversationStep.storeData() interceptor    |
| **NATS Message Payloads** | Late-binding resolution; only references transmitted | Orchestrator delegation payload constructor |
| **External MCP Servers**  | OAuth 2.1 scoped JWTs; prohibition of passthrough    | MCP Client initialization handshake         |

## **8\. Phase 5: Migration Strategy**

Transitioning active, production enterprise deployments from legacy plaintext configurations to the new vault reference architecture necessitates a rigorous, zero-downtime migration runbook.

### **8.1 Automated Discovery and Heuristics**

The first stage of the migration runbook involves auditing the existing database comprehensively to quantify the exact exposure surface. A secure, read-only migration script scans all MongoDB and PostgreSQL configuration documents. The script applies the aforementioned heuristic field-name matching and Shannon entropy scoring to flag every plaintext secret currently residing in the persistence layer. Crucially, these findings are not exported; rather, they populate a specialized, internal migration telemetry table used to power the Manager UI's health dashboard.

### **8.2 Phased Zero-Downtime Rollout**

The migration follows a strictly backward-compatible, four-phase rollout designed to prevent service interruption:

1. **Phase 1: Dual Support (Current State).** The ILanguageModelBuilder is updated to process agenth legacy plaintext strings and the new ${eddivault:...} syntax seamlessly. Existing agent executions continue entirely uninterrupted while the new codebase is deployed.
2. **Phase 2: Warning and Auditing.** The Manager UI actively queries the migration telemetry table. Any agent configuration identified as containing plaintext secrets triggers a persistent, high-visibility security warning within the user interface, prompting the administrator to take immediate remedial action.
3. **Phase 3: Automated Bulk Migration.** Administrators execute a verified one-click "Migrate to Vault" function in the UI. The backend dynamically provisions new AES-GCM encrypted entries in the ISecretProvider, generates the corresponding vault reference strings, updates the JSON configurations in memory, and persists the sanitized documents back to the database. Old configuration versions are preserved for integrity but are marked with a restrictive contains_sensitive_data flag. This flag blocks their exposure via standard API read operations unless elevated, break-glass audit privileges are explicitly invoked.
4. **Phase 4: Strict Enforcement Mode.** Once the telemetry dashboard confirms 100% migration across all tenants and workspaces, system administrators manually enable "Strict Mode." The configuration persistence layer implements hard validation logic that actively rejects any subsequent API PUT or POST requests containing plaintext in known sensitive JSON fields, permanently preventing architectural regression.

### **8.3 Handling Agent Father Templates**

EDDI utilizes a meta-agent known as "Agent Father" to programmatically generate other agent configurations using Thymeleaf templates. Historically, these templates passed actual API keys as raw template variables. During the migration, the Agent Father configuration is refactored to generate templates that inject vault references (${eddivault:new-agent-key}) rather than plaintext. The provisioning script orchestrating the Agent Father automatically calls the ISecretProvider to securely store the user-provided key before the new agent's configuration is fully rendered and saved.

## **9\. Phase 6: Manager UI for Secrets Management**

The EDDI Manager UI requires dedicated, highly secure components to administrate the new cryptographic infrastructure while maintaining an exceptional, frictionless developer experience.

### **9.1 Secrets Command Center**

A new specialized routing view, built using React 19 and Tailwind v4, provides comprehensive CRUD (Create, Read, Update, Delete) capabilities interfacing with the ISecretProvider REST API. Administrators can effortlessly browse secrets segmented by tenant and agent namespaces. The UI surfaces critical operational metadata, including precise creation timestamps, last-accessed metrics, and an interactive "Reverse Lookup" graph mapping each secret to the specific agent configurations that depend upon it. When a mandatory secret rotation is required, updating the value within this command center instantaneously invalidates the 5-minute Caffeine cache in the backend, ensuring all actively executing agents immediately utilize the updated credential without requiring a disruptive infrastructure restart.

### **9.2 Configuration Editor Integration**

Within the standard JSON configuration forms (e.g., editing a specific langchain.json task), the user experience dynamically adapts. Fields designated as sensitive by the backend schema automatically transform from standard text inputs into intelligent autocomplete dropdowns. These dropdowns query the ISecretProvider and present a list of available vault references specifically scoped for that agent's namespace. A distinct visual padlock indicator denotes that the field is cryptographically secured. To aid in complex debugging scenarios, a highly guarded "Reveal Secret" capability is implemented, requiring a secondary biometric or Multi-Factor Authentication (MFA) prompt before triggering a secure backend API request to fetch and briefly display the plaintext value.

## **10\. Phase 7: Competitive Analysis and Industry Standards**

The implementation of this ephemeral runtime secret resolution architecture positions EDDI significantly ahead of incumbent orchestration platforms in agenth fundamental security posture and strict regulatory compliance.

### **10.1 Architecture Comparison with Competitors**

An analysis of competing automation and orchestration platforms reveals pervasive architectural flaws regarding secret management. Langflow relies heavily on standard environment variables for global settings but utilizes the Python Fernet library to encrypt user-defined global variables stored directly within its internal database.25 While an improvement over raw plaintext, this implementation couples the encryption architecture directly to the application logic, complicating key rotation and external auditing.

Similarly, the automation platform n8n utilizes a master encryption key (N8N_ENCRYPTION_KEY) to secure credentials within its database.26 However, n8n's architecture severely lacks native multi-tenant secret boundaries. Consequently, a sandbox escape vulnerability (such as the deeply critical CVE-2025-68613) provides an attacker with complete access to the database and the unified decryption key, leading inevitably to a total credential compromise across all workflows.27 Flowise implements local AES-256 encryption but requires complex manual configuration to integrate effectively with enterprise solutions like AWS Secrets Manager for production deployments.28

EDDI's ISecretProvider architecture solves these issues by decoupling the storage mechanism entirely. By enforcing strictly ephemeral runtime resolution, applying aggressive JBoss log entropy filtering, and defining concrete MCP trust boundaries using OAuth 2.1, EDDI successfully nullifies the lateral movement and exfiltration vulnerabilities that plague competing tools.

### **10.2 Alignment with OWASP Top 10 for LLMs 2025**

The proposed architecture provides comprehensive, defense-in-depth mitigation against the most critical vulnerabilities outlined in the OWASP Top 10 for LLM Applications (2025 iteration).30

The ephemeral secret resolution methodology directly neutralizes the threat of **LLM02: Sensitive Information Disclosure**.30 By cryptographically ensuring that API keys are never injected into the LLM context window and are aggressively stripped from conversation memory via AST scrubbing, EDDI physically prevents models from accidentally disclosing operational credentials during natural language generation. Furthermore, the strict namespace isolation and utilization of OAuth 2.1 scoped tokens for all MCP interactions directly address **LLM06: Excessive Agency**.30 Tools and child agents operate strictly under the principle of least privilege, possessing only the exact, short-lived tokens necessary to execute their narrowly defined functions, thereby eliminating the risk of autonomous agents undertaking unauthorized actions.

### **10.3 European Union AI Act Compliance**

The European Union AI Act imposes stringent, legally binding obligations on AI system providers and deployers, demanding rigorous risk management, extensive documentation, and technical robustness.32

EDDI's architecture directly supports compliance with **Article 17 (Quality Management System)** by providing cryptographic assurance that internal infrastructure cannot be compromised by user-defined configurations or prompt injection attacks.33 Compliance with **Article 19 (Logging of Activity)** is significantly enhanced through the deployment of the JBoss log filter; institutions can maintain comprehensive six-month traceability logs without violating GDPR or data residency requirements by inadvertently archiving plaintext personal data or credentials.33 Finally, the overarching regulatory mandate for **Cybersecurity Protection (Article 15 & Article 55\)** is unequivocally satisfied through the implementation of envelope encryption, secure KEK/DEK rotation, and the absolute elimination of static, persistently stored credentials.33 This architecture provides auditors with verifiable, indisputable proof of data governance and infrastructural security.36

## **11\. REST API Specification**

To support the Manager UI and programmatic administration of the cryptographic infrastructure, EDDI exposes a dedicated suite of REST endpoints. These endpoints interface directly with the ISecretProvider SPI.

| Endpoint                                              | Method | Description                                                                   | Security Posture                                              |
| :---------------------------------------------------- | :----- | :---------------------------------------------------------------------------- | :------------------------------------------------------------ |
| /api/v1/secrets/{tenantId}/{agentId}                  | GET    | Retrieves a list of SecretMetadata objects for a specific namespace.          | Requires Admin Role. Plaintext values are **never** returned. |
| /api/v1/secrets/{tenantId}/{agentId}                  | POST   | Creates a new secret. Payload contains the plaintext value.                   | Requires Admin Role. Payload must be transmitted over TLS.    |
| /api/v1/secrets/{tenantId}/{agentId}/{keyName}        | PUT    | Updates an existing secret (Rotation). Triggers immediate cache invalidation. | Requires Admin Role.                                          |
| /api/v1/secrets/{tenantId}/{agentId}/{keyName}        | DELETE | Cryptographically deletes the secret from the backend provider.               | Requires Admin Role.                                          |
| /api/v1/secrets/reveal/{tenantId}/{agentId}/{keyName} | GET    | Retrieves the plaintext value for debugging purposes.                         | Requires SuperAdmin Role \+ Secondary MFA Validation.         |

## **12\. Implementation Estimate**

The comprehensive refactoring of the EDDI core to support the ephemeral secret architecture requires a highly coordinated engineering effort across backend API design, database migration, and frontend UX development. The estimated effort is roughly 160 story points, executable across four distinct engineering sprints.

- **Sprint 1: Core SPI and Providers (40 Points):** Implementation of the fundamental ISecretProvider interfaces, the DatabaseSecretProvider (utilizing AES-256-GCM envelope encryption), and the integration of HashiCorp, AWS, and Azure Quarkus extensions.
- **Sprint 2: Resolution and Pipeline (30 Points):** Integration of Caffeine caching mechanisms, SmallRye SecretKeysHandler bridging, and the critical ILanguageModelBuilder interception and delayed-resolution logic.
- **Sprint 3: Egress Fortification (40 Points):** Development of the Jackson AST JSON scrubber for export sanitization, the CustomConsoleHandler for JBoss logging with regex pre-compilation, and the implementation of NATS JetStream late-binding payload refinement.
- **Sprint 4: Migration and UI (50 Points):** Construction of the heuristic database scanner, the execution of the zero-downtime migration scripts, and the deployment of the React 19/Tailwind v4 Manager UI Command Center.

#### **Works cited**

1. Compare AWS Secrets Manager vs. Azure Key Vault \- G2, accessed March 16, 2026, [https://www.g2.com/compare/aws-secrets-manager-vs-azure-key-vault](https://www.g2.com/compare/aws-secrets-manager-vs-azure-key-vault)
2. Using a Credentials Provider \- Quarkus, accessed March 16, 2026, [https://quarkus.io/guides/credentials-provider](https://quarkus.io/guides/credentials-provider)
3. Vault vs AWS Secrets vs Azure Key Vault 2025 Guide \- sanj.dev, accessed March 16, 2026, [https://sanj.dev/post/hashicorp-vault-aws-secrets-azure-key-vault-comparison](https://sanj.dev/post/hashicorp-vault-aws-secrets-azure-key-vault-comparison)
4. All extensions \- Quarkus, accessed March 16, 2026, [https://quarkus.io/extensions/](https://quarkus.io/extensions/)
5. Top Secrets Management Tools in 2026 \- Keeper Security, accessed March 16, 2026, [https://www.keepersecurity.com/blog/2025/11/12/top-secrets-management-tools-in-2026/](https://www.keepersecurity.com/blog/2025/11/12/top-secrets-management-tools-in-2026/)
6. Three Degrees of Cloud Secret Management | by Shreya Maheshwar \- Medium, accessed March 16, 2026, [https://medium.com/@random.droid/cloud-secrets-management-4b3790ce7d7f](https://medium.com/@random.droid/cloud-secrets-management-4b3790ce7d7f)
7. Best Secrets Management Tools for 2026 | Cycode, accessed March 16, 2026, [https://cycode.com/blog/best-secrets-management-tools/](https://cycode.com/blog/best-secrets-management-tools/)
8. Envelope encryption | Cloud Key Management Service \- Google Cloud Documentation, accessed March 16, 2026, [https://docs.cloud.google.com/kms/docs/envelope-encryption](https://docs.cloud.google.com/kms/docs/envelope-encryption)
9. Implementing Local AES-GCM Encryption and Decryption in Java | by John Vazquez, accessed March 16, 2026, [https://medium.com/@johnvazna/implementing-local-aes-gcm-encryption-and-decryption-in-java-ac1dacaaa409](https://medium.com/@johnvazna/implementing-local-aes-gcm-encryption-and-decryption-in-java-ac1dacaaa409)
10. Java AES encryption and decryption \- Mkyong.com, accessed March 16, 2026, [https://mkyong.com/java/java-aes-encryption-and-decryption/](https://mkyong.com/java/java-aes-encryption-and-decryption/)
11. Cross-Platform AES-GCM-256 Encryption & Decryption using JAVA to encrypt and NODE to decrypt \- gists · GitHub, accessed March 16, 2026, [https://gist.github.com/ea7d7ff473e0a2ddcf76007348418ccd](https://gist.github.com/ea7d7ff473e0a2ddcf76007348418ccd)
12. Protecting Sensitive Data Using Envelope Encryption | by İbrahim Gündüz | Medium, accessed March 16, 2026, [https://ibrahimgunduz34.medium.com/protecting-sensitive-data-using-envelope-encryption-95cef1623e64](https://ibrahimgunduz34.medium.com/protecting-sensitive-data-using-envelope-encryption-95cef1623e64)
13. Secret Keys \- SmallRye Config, accessed March 16, 2026, [https://smallrye.io/smallrye-config/Main/config/secret-keys/](https://smallrye.io/smallrye-config/Main/config/secret-keys/)
14. Secret Keys \- SmallRye Config, accessed March 16, 2026, [https://smallrye.io/smallrye-config/2.13.3/config/secret-keys/](https://smallrye.io/smallrye-config/2.13.3/config/secret-keys/)
15. MCP Authentication and Authorization Patterns \- Aembit, accessed March 16, 2026, [https://aembit.io/blog/mcp-authentication-and-authorization-patterns/](https://aembit.io/blog/mcp-authentication-and-authorization-patterns/)
16. Understanding Authorization in MCP \- Model Context Protocol, accessed March 16, 2026, [https://modelcontextprotocol.io/docs/tutorials/security/authorization](https://modelcontextprotocol.io/docs/tutorials/security/authorization)
17. Authorization \- What is the Model Context Protocol (MCP)?, accessed March 16, 2026, [https://modelcontextprotocol.io/specification/2025-03-26/basic/authorization](https://modelcontextprotocol.io/specification/2025-03-26/basic/authorization)
18. Securing MCP Tools with Azure AD On-Behalf-Of (OBO) | by Saima Khan \- Medium, accessed March 16, 2026, [https://medium.com/@khansaima/securing-mcp-tools-with-azure-ad-on-behalf-of-obo-29b1ada1e505](https://medium.com/@khansaima/securing-mcp-tools-with-azure-ad-on-behalf-of-obo-29b1ada1e505)
19. Introduction to MCP authentication \- WorkOS, accessed March 16, 2026, [https://workos.com/blog/introduction-to-mcp-authentication](https://workos.com/blog/introduction-to-mcp-authentication)
20. Generic high entropy secret | GitGuardian documentation, accessed March 16, 2026, [https://docs.gitguardian.com/secrets-detection/secrets-detection-engine/detectors/generics/generic_high_entropy_secret](https://docs.gitguardian.com/secrets-detection/secrets-detection-engine/detectors/generics/generic_high_entropy_secret)
21. Logging configuration \- Quarkus, accessed March 16, 2026, [https://quarkus.io/guides/logging](https://quarkus.io/guides/logging)
22. Configuring logging with Quarkus \- Red Hat Documentation, accessed March 16, 2026, [https://docs.redhat.com/de/documentation/red_hat_build_of_quarkus/2.13/html-single/configuring_logging_with_quarkus/index](https://docs.redhat.com/de/documentation/red_hat_build_of_quarkus/2.13/html-single/configuring_logging_with_quarkus/index)
23. Replacing sensitive data in logs in a Quarkus application \- Stack Overflow, accessed March 16, 2026, [https://stackoverflow.com/questions/70578234/replacing-sensitive-data-in-logs-in-a-quarkus-application](https://stackoverflow.com/questions/70578234/replacing-sensitive-data-in-logs-in-a-quarkus-application)
24. Cannot change log message via logging filter · Issue \#27844 · quarkusio/quarkus \- GitHub, accessed March 16, 2026, [https://github.com/quarkusio/quarkus/issues/27844](https://github.com/quarkusio/quarkus/issues/27844)
25. API keys and authentication | Langflow Documentation, accessed March 16, 2026, [https://docs.langflow.org/api-keys-and-authentication](https://docs.langflow.org/api-keys-and-authentication)
26. n8n Security Best Practices: Protect Your Data and Workflows | Soraia, accessed March 16, 2026, [https://www.soraia.io/blog/n8n-security-best-practices-protect-your-data-and-workflows](https://www.soraia.io/blog/n8n-security-best-practices-protect-your-data-and-workflows)
27. n8n, best tool ever, or disaster waiting to happen? | OCD Tech, LLC, accessed March 16, 2026, [https://ocd-tech.com/blog-posts/n8n-best-tool-ever-or-disaster-waiting-to-happen](https://ocd-tech.com/blog-posts/n8n-best-tool-ever-or-disaster-waiting-to-happen)
28. Running in Production | FlowiseAI \- Flowise Docs, accessed March 16, 2026, [https://docs.flowiseai.com/configuration/running-in-production](https://docs.flowiseai.com/configuration/running-in-production)
29. Environment Variables | FlowiseAI \- Flowise Docs, accessed March 16, 2026, [https://docs.flowiseai.com/configuration/environment-variables](https://docs.flowiseai.com/configuration/environment-variables)
30. OWASP Top 10 for LLMs 2025: Key Risks and Mitigation Strategies \- Invicti, accessed March 16, 2026, [https://www.invicti.com/blog/web-security/owasp-top-10-risks-llm-security-2025](https://www.invicti.com/blog/web-security/owasp-top-10-risks-llm-security-2025)
31. OWASP Top 10 for LLM Applications 2025, accessed March 16, 2026, [https://owasp.org/www-project-top-10-for-large-language-model-applications/assets/PDF/OWASP-Top-10-for-LLMs-v2025.pdf](https://owasp.org/www-project-top-10-for-large-language-model-applications/assets/PDF/OWASP-Top-10-for-LLMs-v2025.pdf)
32. White Papers 2024 Understanding the EU AI Act \- ISACA, accessed March 16, 2026, [https://www.isaca.org/resources/white-papers/2024/understanding-the-eu-ai-act](https://www.isaca.org/resources/white-papers/2024/understanding-the-eu-ai-act)
33. AI Act | Shaping Europe's digital future \- European Union, accessed March 16, 2026, [https://digital-strategy.ec.europa.eu/en/policies/regulatory-framework-ai](https://digital-strategy.ec.europa.eu/en/policies/regulatory-framework-ai)
34. The EU AI Act: What are the obligations for providers? \- DataGuard, accessed March 16, 2026, [https://www.dataguard.com/blog/the-eu-ai-act-and-obligations-for-providers/](https://www.dataguard.com/blog/the-eu-ai-act-and-obligations-for-providers/)
35. EU AI Act Compliance Checker | EU Artificial Intelligence Act, accessed March 16, 2026, [https://artificialintelligenceact.eu/assessment/eu-ai-act-compliance-checker/](https://artificialintelligenceact.eu/assessment/eu-ai-act-compliance-checker/)
36. From innovation to regulation: How internal audit must respond to the EU AI Act, accessed March 16, 2026, [https://www.wolterskluwer.com/en/expert-insights/innovation-regulation-how-internal-audit-must-respond-eu-ai-act](https://www.wolterskluwer.com/en/expert-insights/innovation-regulation-how-internal-audit-must-respond-eu-ai-act)
