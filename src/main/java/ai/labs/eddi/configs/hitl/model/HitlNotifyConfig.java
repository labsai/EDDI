/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl.model;

import java.util.List;

/**
 * Who hears about this agent's pauses, and how
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
}
