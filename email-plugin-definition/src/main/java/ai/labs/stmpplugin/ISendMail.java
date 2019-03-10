package ai.labs.stmpplugin;

import java.util.List;

public interface ISendMail {
    void sendMail(List<String> addressee, String subject, String body);
}
