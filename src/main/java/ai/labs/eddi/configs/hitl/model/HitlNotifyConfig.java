/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import java.util.List;

/**
 * Who hears about this agent's (or group's) pauses, and how
 * ({@code AgentConfiguration.HitlConfig.notify}). Agent-level, because the
 * agent decides who cares about its pauses whichever channel the conversation
 * came from (email plan §16).
 * <p>
 * Absent = no notifications, exactly as before the field existed. A
 * notification confers nothing: it links to the Manager's approvals page, where
 * EDDI's own authorization decides who may approve.
 *
 * @since 6.6.0
 */
public class HitlNotifyConfig {

    /** Email notifications, sent through the deployment's system mailer. */
    private Email email;

    /**
     * A Slack approval card in a Slack channel integration's approval channel.
     *
     * @since 6.8.0
     */
    private Slack slack;

    public Slack getSlack() {
        return slack;
    }

    public void setSlack(Slack slack) {
        this.slack = slack;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    /**
     * {@code hitlConfig.notify.email}: when a conversation of this agent pauses for
     * approval, mail each recipient once per pause.
     * <p>
     * Needs {@code eddi.mail.smtp.*} configured; without a system mailer the
     * setting is saved but nothing is sent.
     */
    public static class Email {

        /**
         * Addresses that receive the notification — plain {@code local@domain}, ASCII,
         * at most {@code HitlConfigValidation.MAX_NOTIFY_RECIPIENTS}, no duplicates.
         * Required and non-empty when {@code email} is present.
         */
        private List<String> recipients;

        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }
    }

    /**
     * {@code hitlConfig.notify.slack}: when a conversation of this agent (or a
     * discussion of this group) pauses for approval, the named Slack channel
     * integration posts its approval card — the same card a pause started from
     * Slack gets — into that integration's {@code hitlApprovalChannel}, whoever or
     * whatever started the conversation: a schedule, a team cadence, a webhook,
     * REST or MCP.
     * <p>
     * The integration decides everything else: the channel (its
     * {@code hitlApprovalChannel}; without one nothing is posted), who may press
     * the buttons (its {@code hitlApproverUserIds}; without them the card has
     * none), its workspace binding ({@code teamId}), its bot token and whether the
     * approval context is shown ({@code hitlApprovalContext}). The agent only
     * chooses which integration.
     */
    public static class Slack {

        /**
         * The {@code name} of a Slack channel integration. Required when {@code slack}
         * is present; must not contain {@code |}, which approval buttons cannot carry.
         */
        private String integration;

        public String getIntegration() {
            return integration;
        }

        public void setIntegration(String integration) {
            this.integration = integration;
        }
    }
}
