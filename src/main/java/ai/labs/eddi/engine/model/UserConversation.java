package ai.labs.eddi.engine.model;


public class UserConversation {
    private String intent;
    private String userId;
    private Deployment.Environment environment;
    private String botId;
    private String conversationId;

    public UserConversation() {
    }

    public UserConversation(String intent, String userId, Deployment.Environment environment, String botId, String conversationId) {
        this.intent = intent;
        this.userId = userId;
        this.environment = environment;
        this.botId = botId;
        this.conversationId = conversationId;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Deployment.Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Deployment.Environment environment) {
        this.environment = environment;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
