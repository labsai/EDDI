package ai.labs.eddi.configs.migration.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@NoArgsConstructor
public class MigrationLog {
    private String name;
    private boolean finished;
    private Date timestamp;

    public MigrationLog(String name) {
        this.name = name;
        finished = true;
        timestamp = new Date(System.currentTimeMillis());
    }
}
