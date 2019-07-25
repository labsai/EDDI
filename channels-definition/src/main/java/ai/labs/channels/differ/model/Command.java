package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public abstract class Command implements Serializable {
    protected AuthContext authContext;
    protected String commandId;
    protected String commandName;
    protected Date createdAt;

    public Command(AuthContext authContext, String commandId, String commandName) {
        this.authContext = authContext;
        this.commandId = commandId;
        this.commandName = commandName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthContext implements Serializable {
        private String userId;
    }
}
