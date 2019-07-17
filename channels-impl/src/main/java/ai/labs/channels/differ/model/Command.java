package ai.labs.channels.differ.model;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Date;

@NoArgsConstructor
@Getter
public class Command implements ICommand {
    protected String id;
    protected Date sentAt;

    Command(String id) {
        this.id = id;
    }

    @Override
    public void setSentAt(Date sentAt) {
        this.sentAt = sentAt;
    }
}
