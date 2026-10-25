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
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
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
     * The checks that need the deployment: the IMAP host passes the author socket
     * policy (and resolves), the connection exists and may log in to exactly that
     * origin, and no other email integration polls the same address.
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
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            errors.add("Connections are not available in this deployment, so platformConfig.connection cannot be checked.");
        } else {
            try {
                connectionResolver.get().requireMailLoginTarget("${connection:" + settings.connection() + "}", URI.create(settings.imapOrigin()));
            } catch (ConnectionException e) {
                errors.add("platformConfig.connection: " + e.getMessage() + " The connection's baseUrlAllowlist must contain "
                        + settings.imapOrigin() + ".");
            } catch (IllegalArgumentException e) {
                errors.add("platformConfig.connection: " + e.getMessage());
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

    /** Stops polling a deleted integration. Never throws. */
    public void afterDelete(String integrationId) {
        directory.invalidate();
        scheduler.remove(integrationId);
    }
}
