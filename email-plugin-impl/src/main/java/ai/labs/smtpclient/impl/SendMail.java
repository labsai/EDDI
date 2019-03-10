package ai.labs.smtpclient.impl;

import ai.labs.stmpplugin.ISendMail;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.List;
import java.util.Properties;

public class SendMail implements ISendMail {

    //todo handover smtp details here
    public SendMail() {
    }

    //todo send email to with message and subject
    void sendMail(Session session, String toEmail, String subject, String body) {
        try {

            MimeMessage msg = new MimeMessage(session);
            msg.addHeader("Content-type", "text/html");
            msg.addHeader("format", "flowed");
            msg.addHeader("Content-Transfer-Encoding", "8bit");
            msg.setFrom(new InternetAddress("no_reply@test.com", "test"));
            msg.setReplyTo(InternetAddress.parse("no_reply@test.com", false));
            msg.setSubject(subject, "UTF-8");
            msg.setText(body, "UTF-8");
            msg.setSentDate(new Date());
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
            Transport.send(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void sendMail(List<String> addressee, String subject, String body) {
        //sendmail
    }

    public static void main(String[] args) {
        System.out.println("SimpleEmail Start");
        String smtpHostServer = "smtp.example.com";
        String emailId = "test@example.com";

        Properties props = System.getProperties();
        props.put("mail.smtp.host", smtpHostServer);
        Session session = Session.getInstance(props, null);
        //SendMail.sendMail(session, emailId, "Test mail", "Test mail");
    }
}
