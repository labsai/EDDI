/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.mail.SmimeTestCertificates;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The daily S/MIME certificate check (v3 plan §6.3). */
class EmailSmimeCertificateMonitorTest {

    static final String ID = "a1b2c3d4e5f60718293a4b5c";

    @SuppressWarnings("unchecked")
    static EmailSmimeCertificateMonitor monitor(SmimeTestCertificates.Leaf leaf, EmailOwnerNotifier notifier, SimpleMeterRegistry registry)
            throws Exception {
        ConnectionResolver resolver = mock(ConnectionResolver.class);
        String pem = SmimeTestCertificates.pem(SmimeTestCertificates.issue(leaf));
        when(resolver.resolveSmimeSigning(eq("${connection:cert}"), any())).thenReturn(new ConnectionResolver.SmimeMaterial("cert", pem, null));
        Instance<ConnectionResolver> resolvers = mock(Instance.class);
        when(resolvers.isResolvable()).thenReturn(true);
        when(resolvers.get()).thenReturn(resolver);
        var configuration = EmailInboundProcessorTest.integration(Map.of("smtpHost", "smtp.contoso.com"));
        configuration.setEmailControl(new EmailControl(new EmailControl.Smime(null, "cert")));
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        when(directory.scan()).thenReturn(List.of(new EmailIntegration(ID, configuration, EmailChannelSettings.from(configuration))));
        return new EmailSmimeCertificateMonitor(directory, new EmailSmimeSigning(resolvers), notifier, registry);
    }

    @Test
    @DisplayName("an expiry within 30 days tells the owners and sets the days-left gauge")
    void expiring() throws Exception {
        EmailOwnerNotifier notifier = mock(EmailOwnerNotifier.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var monitor = monitor(SmimeTestCertificates.Leaf.valid("invoices@contoso.com").expiringIn(Duration.ofDays(12).plusHours(1)), notifier,
                registry);

        assertEquals(1, monitor.check(Instant.now()));

        verify(notifier).notifyOnce(eq(ID), any(), any(), eq(EmailOwnerNotifier.Reason.CERTIFICATE_EXPIRING), any());
        assertEquals(12.0, registry.get("eddi.email.smime.certificate.days_left").tag("integration", ID).gauge().value());
    }

    @Test
    @DisplayName("a certificate that can no longer sign tells the owners it is unavailable")
    void unusable() throws Exception {
        EmailOwnerNotifier notifier = mock(EmailOwnerNotifier.class);
        var monitor = monitor(SmimeTestCertificates.Leaf.valid("ceo@contoso.com"), notifier, new SimpleMeterRegistry());
        monitor.check(Instant.now());
        verify(notifier).notifyOnce(eq(ID), any(), any(), eq(EmailOwnerNotifier.Reason.SMIME_UNAVAILABLE), any());
        verify(notifier, never()).notifyOnce(eq(ID), any(), any(), eq(EmailOwnerNotifier.Reason.CERTIFICATE_EXPIRING), any());
    }

    @Test
    @DisplayName("a connection that disappears withdraws the days-left gauge instead of leaving the last value")
    @SuppressWarnings("unchecked")
    void aVanishedConnectionClearsTheGauge() throws Exception {
        ConnectionResolver resolver = mock(ConnectionResolver.class);
        String pem = SmimeTestCertificates.pem(SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid("invoices@contoso.com")));
        when(resolver.resolveSmimeSigning(eq("${connection:cert}"), any())).thenReturn(new ConnectionResolver.SmimeMaterial("cert", pem, null))
                .thenThrow(new ConnectionException(ConnectionException.Reason.NOT_FOUND, "connection 'cert' not found"));
        Instance<ConnectionResolver> resolvers = mock(Instance.class);
        when(resolvers.isResolvable()).thenReturn(true);
        when(resolvers.get()).thenReturn(resolver);
        var configuration = EmailInboundProcessorTest.integration(Map.of("smtpHost", "smtp.contoso.com"));
        configuration.setEmailControl(new EmailControl(new EmailControl.Smime(null, "cert")));
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        when(directory.scan()).thenReturn(List.of(new EmailIntegration(ID, configuration, EmailChannelSettings.from(configuration))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmailOwnerNotifier notifier = mock(EmailOwnerNotifier.class);
        var monitor = new EmailSmimeCertificateMonitor(directory, new EmailSmimeSigning(resolvers), notifier, registry);

        monitor.check(Instant.now());
        assertNotNull(registry.find("eddi.email.smime.certificate.days_left").tag("integration", ID).gauge());

        monitor.check(Instant.now());
        assertNull(registry.find("eddi.email.smime.certificate.days_left").tag("integration", ID).gauge());
        verify(notifier).notifyOnce(eq(ID), any(), any(), eq(EmailOwnerNotifier.Reason.SMIME_UNAVAILABLE), any());
    }

    @Test
    @DisplayName("a healthy certificate tells nobody")
    void healthy() throws Exception {
        EmailOwnerNotifier notifier = mock(EmailOwnerNotifier.class);
        monitor(SmimeTestCertificates.Leaf.valid("invoices@contoso.com"), notifier, new SimpleMeterRegistry()).check(Instant.now());
        verify(notifier, never()).notifyOnce(any(), any(), any(), any(), any());
    }
}
