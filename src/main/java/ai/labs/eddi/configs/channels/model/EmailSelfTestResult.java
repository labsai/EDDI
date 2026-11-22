/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The answer of {@code POST /channelstore/channels/{id}/email-self-test} and
 * {@code GET …/email-self-test/{token}} (email channel v3 plan §6.5): where the
 * sending-health self-test stands and, once the test mail came back, what it
 * showed, in plain words.
 *
 * @param status
 *            {@code waiting} (sent, not back yet — the next poll picks it up),
 *            {@code done}, {@code expired} (not back within 15 minutes) or
 *            {@code send_failed}
 * @param token
 *            the single-use token; only in the answer that started the test
 * @param expiresAt
 *            when the test closes (ISO-8601), or null
 * @param signed
 *            whether the test mail left S/MIME-signed
 * @param checks
 *            the raw outcome codes, once {@code done}
 * @param findings
 *            what the codes mean and what to do, once {@code done}; the reason,
 *            when {@code send_failed}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmailSelfTestResult(String status, String token, String expiresAt, Boolean signed, Checks checks, List<Finding> findings) {

    public static final String WAITING = "waiting";
    public static final String DONE = "done";
    public static final String EXPIRED = "expired";
    public static final String SEND_FAILED = "send_failed";

    /**
     * The codes the returned mail produced.
     *
     * @param dkim
     *            the authentication verdict: {@code aligned-pass},
     *            {@code pass-unaligned}, {@code fail}, {@code none}
     * @param signingDomain
     *            the {@code d=} of the passing DKIM signature, or null
     * @param aligned
     *            whether that domain aligns with the mailbox domain (DMARC)
     * @param dnsTemporary
     *            whether a DNS lookup failed, so the verdict may be incomplete
     * @param smime
     *            {@code verified}, {@code not_signed}, {@code invalid} or
     *            {@code wrong_signer} (verifies, but the certificate names another
     *            address)
     * @param smimeExpected
     *            whether the settings asked for a signature
     * @param dmarc
     *            {@code present}, {@code absent} or {@code unknown}
     * @param dmarcDomain
     *            where the DMARC record was found, or null
     * @param microsoft365
     *            whether the mailbox is on Microsoft 365, which does not DKIM-sign
     *            mail inside one organisation
     * @param domain
     *            the mailbox domain
     */
    public record Checks(String dkim, String signingDomain, boolean aligned, boolean dnsTemporary, String smime, boolean smimeExpected,
            String dmarc, String dmarcDomain, boolean microsoft365, String domain) {
    }

    /**
     * One finding.
     *
     * @param code
     *            stable, for translation: {@code dkim.aligned},
     *            {@code dkim.unaligned}, {@code dkim.missing},
     *            {@code dkim.inconclusive}, {@code dkim.failed},
     *            {@code dkim.dns-temporary}, {@code smime.verified},
     *            {@code smime.missing}, {@code smime.off}, {@code smime.invalid},
     *            {@code smime.wrong-signer}, {@code dmarc.present},
     *            {@code dmarc.absent}, {@code dmarc.unknown}, {@code send.failed}
     * @param level
     *            {@code ok}, {@code warning}, {@code problem} or
     *            {@code inconclusive}
     * @param text
     *            the finding in plain English
     */
    public record Finding(String code, String level, String text) {
    }

    /** A test just started: its token, shown this once. */
    public static EmailSelfTestResult open(String token, Instant expiresAt, boolean signed) {
        return new EmailSelfTestResult(WAITING, token, expiresAt.toString(), signed, null, null);
    }

    public static EmailSelfTestResult waiting(Instant expiresAt, boolean signed) {
        return new EmailSelfTestResult(WAITING, null, expiresAt.toString(), signed, null, null);
    }

    public static EmailSelfTestResult expired(Instant expiresAt, boolean signed) {
        return new EmailSelfTestResult(EXPIRED, null, expiresAt.toString(), signed, null,
                List.of(new Finding("test.expired", "problem", "The test mail did not come back within 15 minutes. Check that the mailbox "
                        + "is being polled (the integration is saved and its poll runs), and that the relay accepted the mail.")));
    }

    public static EmailSelfTestResult sendFailed(String token, String category, String reason) {
        return new EmailSelfTestResult(SEND_FAILED, token, null, false, null,
                List.of(new Finding("send.failed", "problem", "The test mail could not be sent (" + category + "): " + reason)));
    }

    /** The test came back: its checks and what they mean. */
    public static EmailSelfTestResult done(Instant expiresAt, boolean signed, Checks checks) {
        return new EmailSelfTestResult(DONE, null, expiresAt.toString(), signed, checks, findings(checks));
    }

    /** The plain-language reading of {@code checks}. */
    public static List<Finding> findings(Checks c) {
        List<Finding> findings = new ArrayList<>();
        String domain = c.domain() == null ? "your domain" : c.domain();
        if (c.dnsTemporary() && !"aligned-pass".equals(c.dkim())) {
            findings.add(new Finding("dkim.dns-temporary", "inconclusive", "A DNS lookup failed while checking the signature, so the DKIM "
                    + "result is incomplete. Run the test again; if it keeps happening, check eddi.email.dns.servers."));
        } else {
            switch (c.dkim()) {
                case "aligned-pass" -> findings.add(new Finding("dkim.aligned", "ok", "Your provider DKIM-signs mail from this address as "
                        + c.signingDomain() + ", which aligns with " + domain + ". Recipients can verify that the mail is yours."));
                case "pass-unaligned" -> findings.add(new Finding("dkim.unaligned", "problem", "Your provider signs as " + c.signingDomain()
                        + ", not " + domain + ": DMARC does not count that signature for " + domain + ", so EDDI and other receivers "
                        + "treat the mail as unauthenticated. Start DKIM authentication for " + domain + " in your provider's admin console "
                        + "(Google Admin: Apps > Google Workspace > Gmail > Authenticate email; Microsoft 365: Defender > Email "
                        + "authentication > DKIM)."));
                case "none" -> {
                    if (c.microsoft365()) {
                        findings.add(new Finding("dkim.inconclusive", "inconclusive", "The mail arrived without a DKIM signature. Microsoft "
                                + "365 does not DKIM-sign mail that stays inside one organisation, so this test cannot tell whether mail "
                                + "to outside recipients is signed. Send a test from outside, or check DKIM for " + domain
                                + " in Microsoft Defender > Email authentication > DKIM."));
                    } else {
                        findings.add(new Finding("dkim.missing", "problem", "The mail arrived without a DKIM signature. Turn on DKIM signing "
                                + "for " + domain + " at your mail provider."));
                    }
                }
                default -> findings.add(new Finding("dkim.failed", "problem", "The mail carried a DKIM signature that did not verify. "
                        + "Something between your relay and the mailbox changed the mail, or the published DKIM key does not match."));
            }
        }
        switch (c.smime()) {
            case "verified" -> findings.add(new Finding("smime.verified", "ok", "The S/MIME signature verified: recipients' mail clients "
                    + "show the message as signed by this mailbox."));
            case "invalid" -> findings.add(new Finding("smime.invalid", "problem", "The mail was S/MIME-signed, but the signature did not "
                    + "verify: something changed the mail on the way. Check for a gateway that rewrites or adds footers to outgoing mail."));
            case "wrong_signer" -> findings.add(new Finding("smime.wrong-signer", "problem", "The S/MIME signature verified, but its "
                    + "certificate is issued to another address. Use a certificate issued to this mailbox."));
            default -> findings.add(c.smimeExpected()
                    ? new Finding("smime.missing", "problem", "Signing is configured, but the mail arrived unsigned. Check the "
                            + "SMIME_SIGNING connection named in emailControl.smime.connection.")
                    : new Finding("smime.off", "warning", "The mail is not S/MIME-signed: no signing connection is configured "
                            + "(emailControl.smime), or signing is switched off."));
        }
        switch (c.dmarc()) {
            case "present" -> findings.add(new Finding("dmarc.present", "ok", "A DMARC record is published"
                    + (c.dmarcDomain() == null ? "" : " for " + c.dmarcDomain()) + "."));
            case "absent" -> findings.add(new Finding("dmarc.absent", "warning", "No DMARC record is published for " + domain
                    + ". This is advice only: a DMARC record (_dmarc." + domain + ") tells receivers how to treat mail that fails "
                    + "authentication."));
            default -> findings.add(new Finding("dmarc.unknown", "inconclusive", "Whether " + domain + " publishes DMARC could not be "
                    + "checked (a DNS lookup failed)."));
        }
        return findings;
    }
}
