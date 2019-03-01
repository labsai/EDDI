package ai.labs.smtpclient.impl;

import java.util.Properties;
import javax.mail.Session;

public class EmailClient {
    public static void main (String [] args) {
        System.out.println("SimpleEmail Start");
        String smtpHostServer = "smtp.example.com";
        String emailId = "test@example.com";

        Properties props = System.getProperties();
        props.put("mail.smtp.host", smtpHostServer);
        Session session = Session.getInstance(props, null);
        EmailUtil.sendMail(session, emailId, "Test mail", "Test mail");
    }
}
