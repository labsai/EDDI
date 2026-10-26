/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * What the channel store needs to know about an email integration beyond its
 * own document (email plan §10): the checks against the deployment, the
 * save-time warnings, and keeping the poll schedule in step with the saved
 * configuration.
 */
@ApplicationScoped
public class EmailChannelSupport {

    private static final Logger LOGGER = Logger.getLogger(EmailChannelSupport.class);

    private final SafeSocketConnector connector;
    private final Instance<ConnectionResolver> connectionResolver;
    private final EmailIntegrationDirectory directory;
    private final EmailPollScheduler scheduler;
    private final EmailAgentToolsInspector toolsInspector;

    /**
     * Conversation mode's suppression list. Field-injected so the constructor stays
     * the one the tests use.
     */
    @Inject
    Instance<IEmailSuppressionStore> suppressions;

    @Inject
    public EmailChannelSupport(SafeSocketConnector connector, Instance<ConnectionResolver> connectionResolver, EmailIntegrationDirectory directory,
            EmailPollScheduler scheduler, EmailAgentToolsInspector toolsInspector) {
        this.connector = connector;
        this.connectionResolver = connectionResolver;
        this.directory = directory;
        this.scheduler = scheduler;
        this.toolsInspector = toolsInspector;
    }

    /**
     * The checks that need the deployment: the IMAP host (and, in conversation
     * mode, the SMTP host) passes the author socket policy and resolves, the
     * connection exists and may log in to exactly those origins, and no other email
     * integration polls the same address.
     *
     * @param excludeId
     *            the integration being updated (null on create)
     * @throws BadRequestException
     *             naming every rule that failed
     */
    public void validateAgainstDeployment(ChannelIntegrationConfiguration configuration, String excludeId) {
        EmailChannelSettings settings = EmailChannelSettings.from(configuration.getPlatformConfig());
        List<String> errors = new ArrayList<>();
        try {
            connector.resolve(settings.host(), settings.port(), SafeSocketConnector.Protocol.IMAP);
        } catch (SocketPolicyException e) {
            errors.add("platformConfig.host: " + e.getMessage());
        }
        boolean replies = settings.replies();
        if (replies) {
            // The reply relay is the author's choice exactly as the IMAP host is: the
            // author policy, never the operator one the system mailer uses.
            try {
                connector.resolve(settings.smtpHost(), settings.smtpPort(), SafeSocketConnector.Protocol.SMTP);
            } catch (SocketPolicyException e) {
                errors.add("platformConfig.smtpHost: " + e.getMessage());
            }
        }
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            errors.add("Connections are not available in this deployment, so platformConfig.connection cannot be checked.");
        } else {
            List<String> origins = replies ? List.of(settings.imapOrigin(), settings.smtpOrigin()) : List.of(settings.imapOrigin());
            for (String origin : origins) {
                try {
                    connectionResolver.get().requireMailLoginTarget("${connection:" + settings.connection() + "}", URI.create(origin));
                } catch (ConnectionException e) {
                    errors.add("platformConfig.connection: " + e.getMessage() + " The connection's baseUrlAllowlist must contain " + origin + ".");
                } catch (IllegalArgumentException e) {
                    errors.add("platformConfig.connection: " + e.getMessage());
                }
            }
        }
        List<EmailIntegration> others;
        try {
            others = directory.scan();
        } catch (RuntimeException e) {
            // Fail closed: two integrations polling one mailbox would each run every mail.
            LOGGER.warn("Could not list email integrations to check address uniqueness", e);
            throw new ServiceUnavailableException("Could not verify that the mailbox address is unique. Please retry.");
        }
        for (EmailIntegration other : others) {
            if (!other.id().equals(excludeId) && settings.address() != null && settings.address().equals(other.settings().address())) {
                // Not naming the other integration: no enumeration oracle.
                errors.add("Another email integration already polls the address in platformConfig.address. A mailbox address is unique "
                        + "per deployment, or every mail would be processed twice.");
                break;
            }
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join(" ", errors));
        }
    }

    /** Stable warning codes for a saved email integration (never a refusal). */
    public List<String> warnings(ChannelIntegrationConfiguration configuration) {
        EmailChannelSettings settings = EmailChannelSettings.from(configuration.getPlatformConfig());
        List<String> warnings = new ArrayList<>(EmailChannelSettings.warnings(settings));
        if (warnings.contains(EmailChannelSettings.WARNING_OPEN_TRIGGER)) {
            LOGGER.warnf("Email integration '%s' accepts unauthenticated mail from any sender: anyone on the internet can make its agent "
                    + "run. Set a senderAllowlist, or requireAuthentication: true.", sanitize(configuration.getName()));
        }
        List<String> ungated = toolsInspector.targetsWithUngatedTools(configuration.getTargets());
        if (!ungated.isEmpty()) {
            warnings.add(EmailChannelSettings.WARNING_TOOLS_WITHOUT_APPROVALS);
            LOGGER.warnf("Email integration '%s': target(s) %s run an agent with tools but no toolApprovals. Mail is untrusted input; "
                    + "consider hitlConfig.toolApprovals for tools that change things.", sanitize(configuration.getName()),
                    sanitize(String.join(", ", ungated)));
        }
        return warnings;
    }

    /**
     * Brings the poll schedule in line with the saved configuration. Never throws.
     */
    public void afterSave(String integrationId, ChannelIntegrationConfiguration configuration) {
        directory.invalidate();
        try {
            scheduler.sync(integrationId, EmailChannelSettings.from(configuration.getPlatformConfig()));
        } catch (Exception e) {
            // The integration is stored; the sweep re-creates a missing schedule.
            LOGGER.errorf(e, "Email integration %s: its poll schedule could not be written; the inbound sweep will retry",
                    sanitize(integrationId));
        }
    }

    /**
     * Stops polling a deleted integration and drops its suppression list. Never
     * throws.
     */
    public void afterDelete(String integrationId) {
        directory.invalidate();
        scheduler.remove(integrationId);
        if (suppressions != null && suppressions.isResolvable()) {
            try {
                suppressions.get().removeAll(integrationId);
            } catch (RuntimeException e) {
                LOGGER.warnf("Email integration %s: its suppression list could not be removed: %s", sanitize(integrationId), e.getMessage());
            }
        }
    }

    /**
     * The integration's suppressed addresses, newest first (hashes, no addresses).
     */
    public List<IEmailSuppressionStore.Suppression> suppressions(String integrationId, int limit) {
        return requireSuppressions().list(integrationId, Math.max(1, Math.min(limit, MAX_LISTED_SUPPRESSIONS)));
    }

    /**
     * Clears the suppression of {@code address} for {@code integrationId}, so
     * replies to it are sent again.
     *
     * @return whether there was an entry
     * @throws BadRequestException
     *             when {@code address} is not a plain email address
     */
    public boolean clearSuppression(String integrationId, String address) {
        String normalized = address == null ? null : AddressNormalizer.tryNormalize(address).orElse(null);
        if (normalized == null) {
            throw new BadRequestException("'address' must be a plain email address.");
        }
        boolean removed = requireSuppressions().remove(integrationId, normalized);
        LOGGER.infof("Email integration %s: suppression of %s %s by an administrator", sanitize(integrationId),
                IEmailSuppressionStore.hash(normalized), removed ? "cleared" : "not found");
        return removed;
    }

    private IEmailSuppressionStore requireSuppressions() {
        if (suppressions == null || !suppressions.isResolvable()) {
            throw new ServiceUnavailableException("The email suppression store is not available on this node.");
        }
        return suppressions.get();
    }

    /** Entries one listing returns at most. */
    static final int MAX_LISTED_SUPPRESSIONS = 500;
}
