package ai.labs.eddi.modules.templating.impl.dialects.uuid;

public class UUIDWrapper {
    public String generateUUID() {
        return java.util.UUID.randomUUID().toString();
    }
}
