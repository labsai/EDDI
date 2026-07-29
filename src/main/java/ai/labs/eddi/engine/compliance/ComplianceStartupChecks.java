/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compliance;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Startup checks that warn operators about compliance-relevant configuration
 * gaps. These are advisory warnings, not hard blocks — EDDI runs fine without
 * them, but regulated deployments (HIPAA, EU AI Act) require attention.
 * <p>
 * Suppress individual warnings via configuration properties.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class ComplianceStartupChecks {

    private static final Logger LOGGER = Logger.getLogger("ai.labs.eddi.COMPLIANCE");

    private final String sslCertFile;
    private final boolean dbEncryptionAcknowledged;
    private final String vaultMasterKey;
    private final boolean auditEnabled;
    private final boolean auditSigningRequired;

    public ComplianceStartupChecks(
            @ConfigProperty(name = "quarkus.http.ssl.certificate.file") Optional<String> sslCertFile,
            @ConfigProperty(name = "eddi.compliance.database-encryption-acknowledged",
                            defaultValue = "false") boolean dbEncryptionAcknowledged,
            @ConfigProperty(name = "eddi.vault.master-key") Optional<String> vaultMasterKey,
            @ConfigProperty(name = "eddi.audit.enabled", defaultValue = "true") boolean auditEnabled,
            @ConfigProperty(name = "eddi.compliance.audit-signing-required",
                            defaultValue = "false") boolean auditSigningRequired) {
        this.sslCertFile = sslCertFile.orElse("");
        this.dbEncryptionAcknowledged = dbEncryptionAcknowledged;
        this.vaultMasterKey = vaultMasterKey.orElse("");
        this.auditEnabled = auditEnabled;
        this.auditSigningRequired = auditSigningRequired;
    }

    void onStartup(@Observes StartupEvent event) {
        if (event != null) {
            LOGGER.debug("Compliance startup checks running.");
        }
        checkTls();
        checkDatabaseEncryption();
        checkAuditSigning();
    }

    /**
     * Surface an unsigned audit ledger.
     * <p>
     * {@code eddi.vault.master-key} ships empty, so out of the box
     * {@code AuditLedgerService} derives no HMAC key and writes every entry
     * unsigned — while the documentation presents the ledger as evidence-grade and
     * the EU AI Act Art. 12/19 obligations it is sold against assume tamper
     * evidence. Nothing warned about that until now.
     * <p>
     * Advisory by default (an unsigned ledger is still a useful log). Deployments
     * that actually rely on the ledger as evidence set
     * {@code eddi.compliance.audit-signing-required=true} and get a hard startup
     * failure instead of a warning they will scroll past.
     */
    private void checkAuditSigning() {
        if (!auditEnabled || !vaultMasterKey.isBlank()) {
            return;
        }

        if (auditSigningRequired) {
            throw new IllegalStateException("COMPLIANCE: eddi.compliance.audit-signing-required=true but no vault master key is configured. "
                    + "Audit ledger entries would be written WITHOUT an HMAC integrity signature and tampering would be undetectable. "
                    + "Set EDDI_VAULT_MASTER_KEY, or disable the requirement.");
        }

        LOGGER.warn("""

                +------------------------------------------------------------------+
                |  COMPLIANCE: Audit ledger is UNSIGNED                            |
                +------------------------------------------------------------------+
                |                                                                  |
                |  No vault master key is configured, so audit entries are written |
                |  without an HMAC integrity signature. Tampering with the ledger  |
                |  — editing or deleting entries — cannot be detected, and the     |
                |  /auditstore/verify endpoints can prove nothing.                 |
                |                                                                  |
                |  EU AI Act Arts. 12/19 traceability assumes a tamper-evident     |
                |  record. To enable signing:                                      |
                |    EDDI_VAULT_MASTER_KEY=<your-32-char-passphrase>               |
                |                                                                  |
                |  To make this a hard startup failure instead of a warning:       |
                |    eddi.compliance.audit-signing-required=true                   |
                |                                                                  |
                |  To suppress entirely, disable the ledger:                       |
                |    eddi.audit.enabled=false                                      |
                +------------------------------------------------------------------+

                """);
    }

    private void checkTls() {
        if (sslCertFile == null || sslCertFile.isBlank()) {
            LOGGER.warn("""

                    +------------------------------------------------------------------+
                    |  COMPLIANCE: No TLS certificate configured                       |
                    +------------------------------------------------------------------+
                    |                                                                  |
                    |  HIPAA (§164.312(e)) and EU AI Act deployments require           |
                    |  encryption in transit for all data containing PII / PHI.        |
                    |                                                                  |
                    |  If TLS is terminated at a reverse proxy (nginx, Traefik, etc.), |
                    |  this warning is safe to ignore.                                 |
                    |                                                                  |
                    |  To suppress, configure TLS directly:                            |
                    |    quarkus.http.ssl.certificate.file=/path/to/cert.pem           |
                    |    quarkus.http.ssl.certificate.key-file=/path/to/key.pem        |
                    |                                                                  |
                    |  See: https://docs.labs.ai/hipaa-compliance                      |
                    +------------------------------------------------------------------+

                    """);
        }
    }

    private void checkDatabaseEncryption() {
        if (!dbEncryptionAcknowledged) {
            LOGGER.warn("""

                    +------------------------------------------------------------------+
                    |  COMPLIANCE: Database encryption status unknown                  |
                    +------------------------------------------------------------------+
                    |                                                                  |
                    |  Conversation memories and user data are stored in the database. |
                    |  HIPAA (§164.312(a)(2)(iv)) requires encryption at rest for PHI. |
                    |                                                                  |
                    |  Ensure your database has encryption enabled:                     |
                    |    MongoDB:    WiredTiger Encryption at Rest (Enterprise)        |
                    |    PostgreSQL: pgcrypto, LUKS, or cloud-managed encryption      |
                    |    Cloud:      AWS EBS / Azure Disk / GCP PD encryption          |
                    |                                                                  |
                    |  Once confirmed, suppress this warning:                           |
                    |    eddi.compliance.database-encryption-acknowledged=true          |
                    |                                                                  |
                    |  See: https://docs.labs.ai/hipaa-compliance                      |
                    +------------------------------------------------------------------+

                    """);
        }
    }
}
