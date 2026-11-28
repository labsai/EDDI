/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.engine.mail.SmimeCertificateCheck;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The daily check of every email integration's S/MIME signing certificate
 * (email channel v3 plan §6.3): days left as the gauge
 * {@code eddi_email_smime_certificate_days_left{integration}}; an expiry within
 * 30 days tells the owners once a day ({@code CERTIFICATE_EXPIRING}), and a
 * certificate that can no longer sign — expired, mismatched, unreadable, gone —
 * does too ({@code SMIME_UNAVAILABLE}), since under {@code sign: required} mail
 * is now held and otherwise it goes out unsigned.
 * <p>
 * Runs on every node; the once-a-day notice claim keeps the owners from hearing
 * it twice.
 */
@ApplicationScoped
public class EmailSmimeCertificateMonitor {

    private static final Logger LOGGER = Logger.getLogger(EmailSmimeCertificateMonitor.class);

    private final EmailIntegrationDirectory directory;
    private final EmailSmimeSigning signing;
    private final EmailOwnerNotifier ownerNotifier;
    private final EmailMetrics metrics;

    @Inject
    public EmailSmimeCertificateMonitor(EmailIntegrationDirectory directory, EmailSmimeSigning signing, EmailOwnerNotifier ownerNotifier,
            MeterRegistry meterRegistry) {
        this.directory = directory;
        this.signing = signing;
        this.ownerNotifier = ownerNotifier;
        this.metrics = new EmailMetrics(meterRegistry);
    }

    @Scheduled(every = "${eddi.email.smime.check-interval:24h}", delayed = "2m", identity = "email-smime-certificate-check",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduled() {
        check(Instant.now());
    }

    /**
     * Checks every integration that names a signing connection.
     *
     * @return how many certificates were checked
     */
    int check(Instant now) {
        int checked = 0;
        Iterable<EmailIntegration> integrations;
        try {
            integrations = directory.scan();
        } catch (RuntimeException e) {
            LOGGER.warnf("S/MIME certificate check: the email integrations could not be listed: %s", e.getMessage());
            return 0;
        }
        for (EmailIntegration integration : integrations) {
            EmailChannelSettings settings = integration.settings();
            if (settings.smimeConnection() == null || settings.smimeSign() == EmailControl.SignMode.FALSE) {
                continue;
            }
            checked++;
            String name = integration.configuration().getName();
            try {
                SmimeCertificateCheck.Result result = signing.check(settings);
                metrics.certificateDaysLeft(integration.id(), result.daysLeft());
                if (!result.usable()) {
                    LOGGER.warnf("Email integration %s: %s", sanitize(integration.id()), result.describe());
                    ownerNotifier.notifyOnce(integration.id(), name, settings, EmailOwnerNotifier.Reason.SMIME_UNAVAILABLE, now);
                } else if (result.expiresSoon()) {
                    LOGGER.warnf("Email integration %s: its S/MIME certificate expires in %d day(s)", sanitize(integration.id()),
                            result.daysLeft());
                    ownerNotifier.notifyOnce(integration.id(), name, settings, EmailOwnerNotifier.Reason.CERTIFICATE_EXPIRING, now);
                }
            } catch (EmailSmimeSigning.Unavailable e) {
                metrics.clearCertificateDaysLeft(integration.id());
                LOGGER.warnf("Email integration %s: its S/MIME certificate cannot be checked: %s", sanitize(integration.id()), e.getMessage());
                ownerNotifier.notifyOnce(integration.id(), name, settings, EmailOwnerNotifier.Reason.SMIME_UNAVAILABLE, now);
            } catch (RuntimeException e) {
                LOGGER.warnf("Email integration %s: the S/MIME certificate check failed: %s", sanitize(integration.id()), e.getMessage());
            }
        }
        return checked;
    }
}
