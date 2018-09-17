package ai.labs.memory;

public class Property {
    private String name;
    private String value;
    private ValidUntil validUntil;

    public enum ValidUntil {
        conversationEnd,
        always
    }
}
