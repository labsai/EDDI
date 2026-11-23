/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailSelfTestResult;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.SmimeCertificateCheck;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** The sender-list audit; field-injected for the same reason. */
    @Inject
    Instance<EmailSenderListAudit> senderListAudit;
    /** S/MIME signing (v3 phase C2); field-injected like {@link #suppressions}. */
    @Inject
    Instance<EmailSmimeSigning> smimeSigning;

    /** The sending-health self-test (v3 plan §6.5). */
    @Inject
    Instance<EmailSelfTest> selfTest;

    /**
     * The ledger retention: held mail cannot wait longer than its row lives
     * ({@code emailControl.review.expiry}, plan §7.1).
     */
    @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d")
    Duration ledgerRetention = Duration.ofDays(30);

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
        EmailChannelSettings settings = EmailChannelSettings.from(configuration);
        List<String> errors = new ArrayList<>();
        try {
            connector.resolve(settings.host(), settings.port(), SafeSocketConnector.Protocol.IMAP);
        } catch (SocketPolicyException e) {
            errors.add("platformConfig.host: " + e.getMessage());
        }
        // Conversation mode replies over SMTP; a trigger-mode integration with an
        // smtpHost uses it for the self-test. Either way the relay and the login to it
        // are checked here.
        boolean replies = settings.replies() || settings.hasSmtp();
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
        validateSmime(settings, errors);
        validateReviewExpiry(configuration, errors);
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
        // Email plan v3 §5.3, best effort: an agent a mail can reach must not hold a
        // tool that changes channel integrations or decides approvals. The runtime
        // refusal is the boundary; this tells the author at save time.
        List<String> guarded = toolsInspector.targetsHoldingGuardedTools(configuration.getTargets());
        if (!guarded.isEmpty()) {
            errors.add("A mail-started turn may never change channel integrations or their senders, nor decide approvals, and these "
                    + "targets give their agent such a tool: " + String.join("; ", guarded) + ". Remove the tool from that agent, or "
                    + "point the target at an agent without it.");
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join(" ", errors));
        }
    }

    /**
     * Records a change of the integration's sender lists in the audit ledger
     * ({@link EmailSenderListAudit}). Never throws.
     */
    public void auditSenderListChange(String integrationId, Integer previousVersion, Integer newVersion, List<String> added,
                                      List<String> removed, String reason, boolean allowlistEmptied) {
        if (senderListAudit != null && senderListAudit.isResolvable()) {
            senderListAudit.get().changed(integrationId, previousVersion, newVersion, added, removed, reason, allowlistEmptied);
        }
    }

    /**
     * A pattern made only of wildcards ({@code *}, {@code **}) matches every tool
     * name. A prefix such as {@code eddi*} does not: it is restricted, so it is not
     * reported as universal.
     */
    static boolean isUniversalPattern(String pattern) {
        return !pattern.isEmpty() && pattern.chars().allMatch(c -> c == '*');
    }

    /**
     * Names of the gated targets whose {@code readOnlyTools} hold a pattern that
     * matches every tool — {@code *} and the like — which leaves the gate nothing
     * to gate.
     */
    static List<String> targetsWithEverythingReadOnly(List<ChannelTarget> targets) {
        List<String> names = new ArrayList<>();
        if (targets == null) {
            return names;
        }
        for (ChannelTarget target : targets) {
            if (target == null || target.getType() == ChannelTarget.TargetType.GROUP || !target.effectiveApprovalPolicy().gatesTools()
                    || target.getReadOnlyTools() == null) {
                continue;
            }
            for (String pattern : target.getReadOnlyTools()) {
                if (pattern != null && isUniversalPattern(pattern.trim())) {
                    names.add(target.getName());
                    break;
                }
            }
        }
        return names;
    }

    /**
     * {@code emailControl.smime} against the deployment: the named connection
     * exists, is {@code SMIME_SIGNING} and may sign for the SMTP relay; under
     * {@code sign: required} its certificate must also be usable for the mailbox
     * address (SAN, key usage, validity, chain) — otherwise the save is refused,
     * because every outgoing mail would be held. Under {@code sign: true} a
     * certificate problem is a warning ({@link #warnings}).
     */
    void validateSmime(EmailChannelSettings settings, List<String> errors) {
        String connection = settings.smimeConnection();
        if (connection == null || settings.smimeSign() == EmailControl.SignMode.FALSE) {
            return;
        }
        if (settings.smtpOrigin() == null) {
            errors.add("emailControl.smime.connection needs platformConfig.smtpHost: signing applies to mail sent through the "
                    + "integration's SMTP relay.");
            return;
        }
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            return; // already reported for platformConfig.connection
        }
        try {
            connectionResolver.get().requireSmimeSigningTarget("${connection:" + connection + "}", URI.create(settings.smtpOrigin()));
        } catch (ConnectionException e) {
            errors.add("emailControl.smime.connection: " + e.getMessage() + " An SMIME_SIGNING connection's baseUrlAllowlist must contain "
                    + settings.smtpOrigin() + ".");
            return;
        } catch (IllegalArgumentException e) {
            errors.add("emailControl.smime.connection: " + e.getMessage());
            return;
        }
        if (settings.smimeSign() != EmailControl.SignMode.REQUIRED) {
            return;
        }
        SmimeOutcome outcome = smimeOutcome(settings);
        if (outcome.unavailable() != null) {
            errors.add("emailControl.smime.sign is 'required', but the signing certificate cannot be used: " + outcome.unavailable() + ".");
        } else if (outcome.result() != null && !outcome.result().usable()) {
            errors.add("emailControl.smime.sign is 'required', but " + outcome.result().describe());
        }
    }

    /**
     * {@code emailControl.review.expiry} at most the ledger retention minus one
     * day: a held row must outlive its review.
     */
    void validateReviewExpiry(ChannelIntegrationConfiguration configuration, List<String> errors) {
        var control = configuration.getEmailControl();
        if (control == null || control.getReview() == null || control.getReview().getExpiry() == null) {
            return;
        }
        Duration max = ledgerRetention == null ? Duration.ofDays(29) : ledgerRetention.minusDays(1);
        if (EmailChannelSettings.from(configuration).reviewExpiry().compareTo(max) > 0) {
            errors.add("emailControl.review.expiry must be at most the ledger retention (eddi.email.ledger-retention) minus one day: "
                    + max + ".");
        }
    }

    /** A certificate check, or why there is no certificate to check. */
    record SmimeOutcome(SmimeCertificateCheck.Result result, String unavailable) {
    }

    /** The certificate check of {@code settings}, never throwing. */
    SmimeOutcome smimeOutcome(EmailChannelSettings settings) {
        if (smimeSigning == null || !smimeSigning.isResolvable()) {
            return new SmimeOutcome(null, "S/MIME signing is not available on this node");
        }
        try {
            return new SmimeOutcome(smimeSigning.get().check(settings), null);
        } catch (EmailSmimeSigning.Unavailable e) {
            return new SmimeOutcome(null, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.debugf("S/MIME certificate check failed: %s", e.getMessage());
            return new SmimeOutcome(null, "the certificate could not be checked (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The S/MIME warning codes of {@code settings}. */
    List<String> smimeWarnings(EmailChannelSettings settings) {
        List<String> warnings = new ArrayList<>();
        if (settings.smimeConnection() == null || settings.smimeSign() == EmailControl.SignMode.FALSE || !settings.hasSmtp()) {
            return warnings;
        }
        SmimeOutcome outcome = smimeOutcome(settings);
        if (outcome.unavailable() != null || (outcome.result() != null && !outcome.result().usable())) {
            warnings.add(EmailChannelSettings.WARNING_SMIME_CERTIFICATE_UNUSABLE);
            return warnings;
        }
        if (outcome.result() != null) {
            if (outcome.result().expiresSoon()) {
                warnings.add(EmailChannelSettings.WARNING_SMIME_CERTIFICATE_EXPIRING);
            }
            if (outcome.result().chain().untrusted()) {
                warnings.add(EmailChannelSettings.WARNING_SMIME_UNTRUSTED_CHAIN);
            }
        }
        return warnings;
    }

    /**
     * Starts the self-test of a saved integration.
     *
     * @throws EmailSelfTest.Refused
     *             no SMTP relay, or the hourly limit
     */
    public EmailSelfTestResult startSelfTest(String integrationId, ChannelIntegrationConfiguration configuration) {
        return requireSelfTest().start(integrationId, EmailChannelSettings.from(configuration));
    }

    /** The state of a self-test of {@code integrationId}. */
    public Optional<EmailSelfTestResult> readSelfTest(String integrationId, String token) {
        return requireSelfTest().read(integrationId, token);
    }

    private EmailSelfTest requireSelfTest() {
        if (selfTest == null || !selfTest.isResolvable()) {
            throw new ServiceUnavailableException("The email self-test is not available on this node.");
        }
        return selfTest.get();
    }

    /** Stable warning codes for a saved email integration (never a refusal). */
    public List<String> warnings(ChannelIntegrationConfiguration configuration) {
        EmailChannelSettings settings = EmailChannelSettings.from(configuration);
        List<String> warnings = new ArrayList<>(EmailChannelSettings.warnings(settings));
        warnings.addAll(smimeWarnings(settings));
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
        // Email plan v3 §8.1: a target that can act but has no email approval gate,
        // and a gated target whose rule-driven calls a mail turn will refuse.
        List<String> noPolicy = toolsInspector.targetsActingWithoutApprovalPolicy(configuration.getTargets());
        if (!noPolicy.isEmpty()) {
            warnings.add(EmailChannelSettings.WARNING_APPROVAL_POLICY_NONE);
        }
        List<String> everything = targetsWithEverythingReadOnly(configuration.getTargets());
        if (!everything.isEmpty()) {
            warnings.add(EmailChannelSettings.WARNING_READ_ONLY_EVERYTHING);
            LOGGER.warnf("Email integration '%s': target(s) %s have an approvalPolicy, but a readOnlyTools pattern matches every tool, so no tool "
                    + "call of a mail turn waits for approval. Name the read-only tools instead.", sanitize(configuration.getName()),
                    sanitize(String.join(", ", everything)));
        }
        List<String> ruleCalls = toolsInspector.targetsWithRefusedRuleCalls(configuration.getTargets());
        if (!ruleCalls.isEmpty()) {
            warnings.add(EmailChannelSettings.WARNING_RULE_CALLS_REFUSED);
            LOGGER.infof("Email integration '%s': target(s) %s have an approvalPolicy and rule-driven httpcalls/mcpcalls; a mail turn refuses "
                    + "those calls unless readOnlyTools names them.", sanitize(configuration.getName()), sanitize(String.join(", ", ruleCalls)));
        }
        return warnings;
    }

    /**
     * Brings the poll schedule in line with the saved configuration. Never throws.
     */
    public void afterSave(String integrationId, ChannelIntegrationConfiguration configuration) {
        directory.invalidate();
        try {
            scheduler.sync(integrationId, EmailChannelSettings.from(configuration));
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
