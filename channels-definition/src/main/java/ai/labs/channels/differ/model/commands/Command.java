package ai.labs.channels.differ.model.commands;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString
@EqualsAndHashCode
public abstract class Command implements Serializable {
    protected AuthContext authContext;
    protected String commandId;
    protected String commandName;
    protected Date createdAt;

    Command(AuthContext authContext, String commandName) {
        this.authContext = authContext;
        this.commandId = String.valueOf(UUID.randomUUID());
        this.commandName = commandName;
    }

    Command(AuthContext authContext, String commandName, Date createdAt) {
        this(authContext, commandName);
        this.createdAt = createdAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthContext implements Serializable {
        private String userId;
    }
}
