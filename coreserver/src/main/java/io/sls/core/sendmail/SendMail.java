package io.sls.core.sendmail;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;

/**
 * @author ginccc
 */
@Slf4j
public class SendMail {

    public static class Options {
        public Options(String host, String port, String auth, String from, String password) {
            this.host = host;
            this.port = port;
            this.auth = auth;
            this.from = from;
            this.password = password;
        }

        String host;
        String port;
        String auth;
        String from;
        String password;
    }

    private Options options;


    public SendMail(final Options options) {
        this.options = options;
    }

    public void send(String[] recipients, String replyTo, String subject, String message) throws EmailException {
        HtmlEmail email = new HtmlEmail();
        email.setHostName(options.host);
        email.setSslSmtpPort(options.port);
        email.setTLS(true);
        email.setSSL(true);
        email.setAuthentication(options.from, options.password);
        for (String recipient : recipients) {
            email.addTo(recipient);
        }
        email.setFrom(options.from, "EDDI", "UTF-8");
        if (replyTo != null) {
            email.addReplyTo(replyTo);
        } else {
            email.addReplyTo(options.from);
        }
        email.setSubject(subject);
        email.setMsg(message);
        email.send();
        log.info("An email as been sent!");
    }
}
