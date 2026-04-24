/*
 * Copyright (c) 2016-2026 EDDI contributors
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

    public ComplianceStartupChecks(
            @ConfigProperty(name = "quarkus.http.ssl.certificate.file") Optional<String> sslCertFile,
            @ConfigProperty(name = "eddi.compliance.database-encryption-acknowledged",
                            defaultValue = "false") boolean dbEncryptionAcknowledged) {
        this.sslCertFile = sslCertFile.orElse("");
        this.dbEncryptionAcknowledged = dbEncryptionAcknowledged;
    }

    void onStartup(@Observes StartupEvent event) {
        if (event != null) {
            LOGGER.debug("Compliance startup checks running.");
        }
        checkTls();
        checkDatabaseEncryption();
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
